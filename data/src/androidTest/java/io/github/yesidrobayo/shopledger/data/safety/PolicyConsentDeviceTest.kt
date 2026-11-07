package io.github.yesidrobayo.shopledger.data.safety

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.backup.BackupRun
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.backup.SkipReason
import io.github.yesidrobayo.shopledger.data.backup.TestControl
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyVersions
import io.github.yesidrobayo.shopledger.domain.port.PolicyAcceptance
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ObserveBackupStatusUseCase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La compuerta de la política en el teléfono: `consents.json` en su carpeta, escrito con temporal y
 * renombrado, lo que pasa con un archivo que no se entiende o que no se puede escribir, y que sin la
 * versión vigente ninguna copia sale a la nube mientras el negocio sigue igual. Una versión mayor
 * vuelve a pausar.
 */
@RunWith(AndroidJUnit4::class)
class PolicyConsentDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "policy-consent-test")
    private val consentFile = File(directory, PolicyConsentFile.FILE_NAME)
    private val log = MemoryLog()
    private val file = PolicyConsentFile({ directory }, Dispatchers.IO)
    private var acceptedCalls = 0
    private val fixture = BackupFixture(context, "policy-consent-backup-test")

    private fun gate(current: PolicyVersions = CURRENT) =
        PolicyConsentGate(file, current, log, onAccepted = { acceptedCalls++ })

    @Before
    fun clean() {
        directory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
        fixture.close()
    }

    @Test
    fun withoutAFileNothingWasEverAcceptedAndAcceptingWritesBothVersions() = runBlocking {
        val gate = gate()
        assertEquals(PolicyConsentStatus.NEVER_ACCEPTED, gate.status())
        assertFalse(gate.allowsUploads())

        assertEquals(PolicyAcceptance.SAVED, gate.acceptCurrent())

        assertEquals(PolicyConsentStatus.ACCEPTED, gate.status())
        assertTrue(gate.allowsUploads())
        assertEquals(1, acceptedCalls)
        val saved = JSONObject(consentFile.readText())
        assertEquals(2, saved.getInt(PolicyConsentFile.PRIVACY_POLICY_VERSION))
        assertEquals(3, saved.getInt(PolicyConsentFile.TERMS_OF_USE_VERSION))
        // Solo las versiones: ni fecha ni nada de la persona.
        assertEquals(2, saved.length())
        // El temporal ya se renombró: nunca quedan dos versiones.
        assertEquals(listOf(PolicyConsentFile.FILE_NAME), directory.list()?.toList())
        assertEquals(listOf("Aceptación de la política guardada"), log.lines)
    }

    @Test
    fun aNewerPolicyOrNewerTermsPauseAgainUntilAccepted() = runBlocking {
        gate().acceptCurrent()

        for (newer in listOf(PolicyVersions(3, 3), PolicyVersions(2, 4))) {
            val next = gate(newer)
            assertEquals("$newer", PolicyConsentStatus.OUTDATED, next.status())
            assertFalse("$newer", next.allowsUploads())
        }

        val next = gate(PolicyVersions(3, 4))
        next.acceptCurrent()
        assertEquals(PolicyConsentStatus.ACCEPTED, next.status())
        // Volver a una compilación anterior no vuelve a pausar lo que ya se aceptó.
        assertEquals(PolicyConsentStatus.ACCEPTED, gate().status())
    }

    @Test
    fun aFileThatDoesNotSayClearlyWhatWasAcceptedAsksAgain() = runBlocking {
        val unclear =
            listOf(
                "",
                "no es json",
                "[2, 3]",
                """{"privacy_policy_version": 2}""",
                """{"privacy_policy_version": "2", "terms_of_use_version": 3}""",
                """{"privacy_policy_version": 2.5, "terms_of_use_version": 3}""",
                """{"privacy_policy_version": 0, "terms_of_use_version": 3}""",
                """{"privacy_policy_version": 2, "terms_of_use_version": 99999999999}""",
                """{"privacy_policy_version": 2, "terms_of_use_version": null}""",
                """{"privacy_policy_version": 2, "terms_of_use_version": 3, "x": "${
                    "a".repeat(17 * 1024)
                }"}""",
            )
        for (text in unclear) {
            directory.mkdirs()
            consentFile.writeText(text)
            assertEquals(text.take(40), ConsentLoad.Unreadable, file.load())
            assertEquals(text.take(40), PolicyConsentStatus.OUTDATED, gate().status())
        }

        // Una carpeta con ese nombre tampoco es una aceptación.
        consentFile.delete()
        consentFile.mkdirs()
        assertEquals(ConsentLoad.Unreadable, file.load())
    }

    @Test
    fun acceptingKeepsTheOtherMarksOfTheFileAndReplacesAnUnreadableOne() = runBlocking {
        directory.mkdirs()
        consentFile.writeText(
            """{"privacy_policy_version": 1, "terms_of_use_version": 1, "other_mark": true}""",
        )

        gate().acceptCurrent()

        val kept = JSONObject(consentFile.readText())
        assertTrue(kept.getBoolean("other_mark"))
        assertEquals(ConsentLoad.Accepted(CURRENT), file.load())

        consentFile.writeText("{roto")
        gate().acceptCurrent()
        assertEquals(ConsentLoad.Accepted(CURRENT), file.load())
    }

    @Test
    fun aPhoneThatCannotWriteTheFileLeavesCopiesPausedAndSaysNothingElse(): Unit = runBlocking {
        // La carpeta es un archivo: no se puede crear nada adentro.
        directory.parentFile?.mkdirs()
        directory.writeText("")
        val gate = gate()

        assertEquals(PolicyAcceptance.NOT_SAVED, gate.acceptCurrent())

        assertEquals(0, acceptedCalls)
        assertFalse(gate.allowsUploads())
        assertEquals(
            listOf(
                "No se pudo guardar la aceptación de la política: error=" +
                    IOException::class.java.name,
            ),
            log.lines,
        )
        directory.delete()
    }

    @Test
    fun withoutThePolicyAcceptedNoCopyLeavesEvenWhenThePersonAsksAndSalesStillWork() = runBlocking {
        fixture.open()
        fixture.connect()
        // Vender no depende de la política.
        fixture.addProduct("Pan de prueba")
        val before = fixture.state()
        val destination = FakeBackupDestination()

        for (control in listOf(TestControl(), TestControl(backupNow = true))) {
            val run = fixture.runner(destination, policyAccepted = { false }).run(control)
            assertEquals(BackupRun.Skipped(SkipReason.POLICY_PENDING), run)
        }

        assertEquals(emptyList<Operation>(), destination.calls)
        // No cuenta como un intento, y nada cambió en el estado ni en la carpeta.
        assertEquals(before, fixture.state())
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun theAppShowsCopiesPausedUntilAcceptedAndAgainWhenAVersionGoesUp() = runBlocking {
        fixture.open()
        fixture.connect()
        fixture.addProduct("Pan de prueba")
        val status = ObserveBackupStatusUseCase(fixture.ports.backupStatus, fixture.clock)
        assertEquals(BackupStatus.POLICY_PENDING, status.current().status)
        assertEquals(PolicyConsentStatus.NEVER_ACCEPTED, fixture.policyConsent.status())

        // Aceptar vuelve a calcular el estado sin esperar a otra venta.
        val next = async {
            withTimeout(10_000) { status().first { it.status != BackupStatus.POLICY_PENDING } }
        }
        assertEquals(BackupStatus.POLICY_PENDING, status().first().status)
        assertEquals(PolicyAcceptance.SAVED, fixture.policyConsent.acceptCurrent())
        assertEquals(BackupStatus.PENDING, next.await().status)

        // Una compilación nueva con otra versión de la política vuelve a pausar.
        val published = PolicyVersions.CURRENT
        fixture.currentPolicy = published.copy(privacyPolicy = published.privacyPolicy + 1)
        val reopened = fixture.reopen() as DataAccess.Ready
        val again = ObserveBackupStatusUseCase(reopened.ports.backupStatus, fixture.clock)
        assertEquals(BackupStatus.POLICY_PENDING, again.current().status)
        assertEquals(PolicyConsentStatus.OUTDATED, fixture.policyConsent.status())
    }

    private companion object {
        val CURRENT = PolicyVersions(privacyPolicy = 2, termsOfUse = 3)
    }
}
