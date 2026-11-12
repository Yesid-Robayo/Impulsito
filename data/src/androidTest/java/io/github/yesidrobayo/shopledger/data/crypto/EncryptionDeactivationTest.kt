package io.github.yesidrobayo.shopledger.data.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.DeactivationOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Apagar el cifrado con Keystore real y carpetas y alias propios: queda apagado con el instante y la
 * clave que se apagó, el sobre se conserva y sigue abriendo, reiniciar no lo vuelve a encender, y la
 * muerte del proceso en cada paso, también dos seguidas, lo deja activo o apagado, nunca a medias.
 * También sin código en este teléfono. El código es inventado.
 */
@RunWith(AndroidJUnit4::class)
class EncryptionDeactivationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "encryption-deactivation-test")
    private val recovery = File(root, RecoveryKeyStore.DIRECTORY)
    private val aliases = "test-deactivation-a" to "test-deactivation-b"
    private var now = 1_793_491_200_000L
    private var recorded = 0
    private var forced = 0

    private fun custody() = RecoveryCustody(
        RecoveryKeyStore({ recovery }, Dispatchers.IO, aliases),
        RecoveryStateStore({ recovery }, Dispatchers.IO),
    ) { now }

    private fun activation() = EncryptionActivation(
        custody = custody(),
        backupMutex = Mutex(),
        discardPendingPlainUpload = {},
        forceUpload = {},
        nowUtcMillis = { now },
        ioContext = Dispatchers.IO,
    )

    private fun deactivation(faults: (DeactivationStep) -> Unit = {}) = EncryptionDeactivation(
        custody = custody(),
        backupMutex = Mutex(),
        recordChanged = { recorded++ },
        forceUpload = { forced++ },
        nowUtcMillis = { now },
        faults = faults,
    )

    /** Activa con un código nuevo y devuelve su clave. */
    private suspend fun activated(): Pair<RecoveryCode, String> {
        val activation = activation()
        val code = activation.newCode()
        activation.activate(code)
        return code to BackupKey.from(code).keyIdHex
    }

    @Before
    fun cleanFiles() {
        root.deleteRecursively()
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }

    @After
    fun cleanUp() = cleanFiles()

    @Test
    fun turningOffKeepsTheEnvelopeAndStaysOffAfterARestart() = runBlocking {
        val (code, keyId) = activated()
        now += 1000

        assertEquals(DeactivationResult.Deactivated, deactivation().deactivate())

        val custody = custody()
        val state = custody.state.read()
        assertFalse(state.enabled)
        assertEquals(now, state.offConfirmedAtUtcMillis)
        assertEquals(keyId, state.disabledKeyIdHex)
        // Las copias cifradas que queden se siguen abriendo sin pedir el código.
        assertEquals(code, (custody.keys.open() as RecoveryKeyRead.Available).code)
        // Reiniciar no lo vuelve a encender.
        assertFalse(custody().alignAtStartup().enabled)
        assertEquals(1, recorded)
        assertEquals(1, forced)
    }

    @Test
    fun withoutACodeOnThisPhoneItStillTurnsOff() = runBlocking {
        assertEquals(DeactivationResult.Deactivated, deactivation().deactivate())

        val state = custody().alignAtStartup()
        assertFalse(state.enabled)
        assertEquals(now, state.offConfirmedAtUtcMillis)
        assertNull(state.disabledKeyIdHex)
        assertTrue(custody().keys.open() is RecoveryKeyRead.Absent)
    }

    @Test
    fun withoutACodeAndWithEncryptedCopiesTheScreenSeesItStoppedUntilTurnedOff() = runBlocking {
        val service =
            EncryptionService(
                custody = custody(),
                activation = activation(),
                deactivation = deactivation(),
                keyUnavailableFailure = { true },
            )
        assertEquals(EncryptionStatus.NoCodeHere, service.status())

        assertEquals(DeactivationOutcome.DEACTIVATED, service.deactivate())

        assertEquals(EncryptionStatus.Off(wasOn = true), service.status())
    }

    @Test
    fun turningOnAgainWithANewCodeClearsTheConfirmation() = runBlocking {
        val (_, first) = activated()
        deactivation().deactivate()
        now += 1000

        val (second, secondKey) = activated()

        val custody = custody()
        val state = custody.alignAtStartup()
        assertTrue(state.enabled)
        assertEquals(secondKey, state.keyIdHex)
        assertNull(state.offConfirmedAtUtcMillis)
        assertEquals(first, state.disabledKeyIdHex)
        assertEquals(second, (custody.keys.open() as RecoveryKeyRead.Available).code)
    }

    @Test
    fun aDeathAtEveryStepLeavesItOnOrOffButNeverHalfway() = runBlocking {
        for (step in DeactivationStep.entries) {
            cleanFiles()
            val (code, keyId) = activated()
            try {
                deactivation { if (it == step) throw ProcessDeath() }.deactivate()
            } catch (_: ProcessDeath) {
                // Así termina un proceso muerto.
            }
            assertOnOrOff(step.name, code, keyId)
        }
    }

    @Test
    fun twoDeathsInARowStillEndOnOrOffAndATurnOffThenFinishes() = runBlocking {
        for (first in DeactivationStep.entries) {
            for (second in DeactivationStep.entries) {
                cleanFiles()
                val (code, keyId) = activated()
                listOf(first, second).forEach { step ->
                    try {
                        deactivation { if (it == step) throw ProcessDeath() }.deactivate()
                    } catch (_: ProcessDeath) {
                        Unit
                    }
                }
                assertOnOrOff("$first,$second", code, keyId)

                assertEquals(DeactivationResult.Deactivated, deactivation().deactivate())
                val state = custody().alignAtStartup()
                assertFalse("$first,$second", state.enabled)
                assertEquals("$first,$second", keyId, state.disabledKeyIdHex)
            }
        }
    }

    /**
     * Tras arrancar de nuevo, el sobre de [code] sigue abriendo, y el cifrado está activo con su
     * clave, o apagado a sabiendas con esa clave como la apagada.
     */
    private suspend fun assertOnOrOff(case: String, code: RecoveryCode, keyId: String) {
        val custody = custody()
        val state = custody.alignAtStartup()
        assertEquals(case, code, (custody.keys.open() as RecoveryKeyRead.Available).code)
        if (state.enabled) {
            assertEquals(case, keyId, state.keyIdHex)
            assertNull(case, state.offConfirmedAtUtcMillis)
        } else {
            assertEquals(case, keyId, state.disabledKeyIdHex)
            assertTrue(case, state.offConfirmedAtUtcMillis != null)
        }
    }

    private class ProcessDeath : RuntimeException()
}
