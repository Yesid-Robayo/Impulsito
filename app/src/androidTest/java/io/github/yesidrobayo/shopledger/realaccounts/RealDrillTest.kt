package io.github.yesidrobayo.shopledger.realaccounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.BackupNowRequest
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.backup.DrillOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.FullVerifyOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.realaccounts.RealAccounts.Kind
import io.github.yesidrobayo.shopledger.realaccounts.RealAccounts.report
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los pasos del simulacro de respaldo y restauración que se pueden hacer en un solo teléfono, con
 * el destino real que conectó la persona y el motor de la app. El guion del PC los llama uno por
 * uno, en orden, porque entre algunos cambia la red o la app debe cerrarse y volver a abrir:
 *
 * - [step01RecordControl] anota los valores de control.
 * - [step02BackupNow] es `Respaldar ahora`, o `Guardar en {provider}` con la nube del teléfono,
 *   con la app abierta: mide cuánto tarda y cuánto pesa.
 * - [step03OfflineSales] registra tres ventas sin red; el guion cierra la app y devuelve la red.
 * - [step04UploadedWhileClosed] comprueba que la copia subió sola, sin abrir la app.
 * - [step06PrepareRestore] pide restaurar la copia más reciente, hasta "falta reiniciar".
 * - [step08VerifyRestore] abre la app de nuevo, lo que termina el reemplazo, y compara.
 * - [step21DestinationLooksRight] describe lo que quedó en el destino.
 * - [inAppDrill] corre `Probar restauración` y la lectura completa de la cabeza.
 *
 * Los valores de control son cifras del negocio inventado: se guardan en la carpeta privada de la
 * app, y al registro solo va si coinciden. Con `-e label volume` las líneas se marcan como de la
 * base grande.
 */
@RunWith(AndroidJUnit4::class)
class RealDrillTest {
    private val label =
        Kind(if (RealAccounts.argument("label", "") == "volume") "volume" else "base")

    @Test
    fun step01RecordControl() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        val ports = RealAccounts.ports()
        File(RealAccounts.privateDir(), CONTROL_FILE).writeText(RealAccounts.controlValues(ports))
        report("control", "label" to label, "saved" to true)
    }

    @Test
    fun step02BackupNow() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        val ports = RealAccounts.ports()
        if (RealAccounts.argument("sale", "true") == "true") {
            RealAccounts.registerInventedSale(ports)
        }
        val started = System.currentTimeMillis()
        val waitMs = RealAccounts.argument("waitMinutes", "15").toLong() * MINUTE_MS
        // Con la app a la vista, como la persona: con la nube del teléfono solo se escribe así.
        val (request, done) =
            RealAccounts.withAppInSight {
                val request = RealAccounts.app.container.data.backupScheduler.backupNow()
                val done =
                    request == BackupNowRequest.QUEUED &&
                        RealAccounts.awaitCondition(waitMs) { copyChecked() }
                request to done
            }
        val elapsed = System.currentTimeMillis() - started
        val status = RealAccounts.visibleStatus()
        val listed = RealAccounts.app.container.data.backupDestination.listCopies()
        val newest = (listed as? BackupCopyList.Listed)?.copies?.firstOrNull()
        report(
            "backupNow",
            "label" to label,
            "request" to request,
            "done" to done,
            "ms" to elapsed,
            "status" to status.status,
            "bytes" to newest?.sizeBytes,
            "generation" to newest?.generation,
        )
        assertTrue("La copia no quedó comprobada a tiempo", done)
        assertNoAllSavedWithSlots(status.status)
    }

    @Test
    fun step03OfflineSales() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        val ports = RealAccounts.ports()
        val before = RealAccounts.visibleStatus().pendingChanges
        repeat(OFFLINE_SALES) { RealAccounts.registerInventedSale(ports) }
        val status = RealAccounts.visibleStatus()
        report(
            "offlineSales",
            "network" to RealAccounts.app.container.data.backupDestination.hasNetwork(),
            "pendingBefore" to before,
            "pendingAfter" to status.pendingChanges,
            "status" to status.status,
        )
        assertEquals(before + OFFLINE_SALES, status.pendingChanges)
        // La app se cierra al terminar la instrumentación: el guion devuelve la red sin abrirla.
    }

    @Test
    fun step04UploadedWhileClosed() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        val facts = RealAccounts.ports().backupStatus.current()
        val status = RealAccounts.visibleStatus()
        report(
            "uploadedWhileClosed",
            "status" to status.status,
            "pending" to status.pendingChanges,
            "failure" to facts.failureCode,
        )
        assertEquals(facts.changeSeq, facts.verifiedSeq)
        assertNoAllSavedWithSlots(status.status)
    }

    @Test
    fun step06PrepareRestore() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        val ports = RealAccounts.ports()
        val facts = ports.backupStatus.current()
        assertEquals("Hay cambios sin copia", facts.changeSeq, facts.verifiedSeq)
        File(RealAccounts.privateDir(), CONTROL_FILE).writeText(RealAccounts.controlValues(ports))
        File(RealAccounts.privateDir(), LANGUAGE_COPY).writeBytes(languageBytes())
        val data = RealAccounts.app.container.data
        val list = data.backupDestination.listCopies()
        val newest = (list as? BackupCopyList.Listed)?.copies?.firstOrNull { it.restorable }
        if (newest == null) fail("No hay una copia que restaurar: ${list::class.simpleName}")
        val started = System.currentTimeMillis()
        var progress = data.backupRestore.restoreCopy(newest!!.key)
        var answers = 0
        while (answers < MAX_ANSWERS) {
            val answer = answerFor(progress) ?: break
            progress = data.backupRestore.answer(answer)
            answers++
        }
        report(
            "prepareRestore",
            "label" to label,
            "progress" to Kind(progress::class.java.simpleName),
            "answers" to answers,
            "bytes" to newest.sizeBytes,
            "ms" to System.currentTimeMillis() - started,
        )
        assertTrue(progress is RestoreProgress.ReadyToRestart)
    }

    @Test
    fun step08VerifyRestore() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        val started = System.currentTimeMillis()
        // Abrir la base en primer plano termina el reemplazo que dejó el paso anterior.
        val ports = RealAccounts.ports()
        val opened = System.currentTimeMillis() - started
        val expected = File(RealAccounts.privateDir(), CONTROL_FILE).readText()
        val same = RealAccounts.controlValues(ports) == expected
        val before = File(RealAccounts.privateDir(), LANGUAGE_COPY).readBytes()
        val sameLanguage = languageBytes().contentEquals(before)
        val status = RealAccounts.visibleStatus()
        report(
            "verifyRestore",
            "label" to label,
            "controlIdentical" to same,
            "languageUnchanged" to sameLanguage,
            "openMs" to opened,
            "status" to status.status,
        )
        assertTrue("Los valores de control no coinciden", same)
        assertTrue("La restauración cambió el idioma de la interfaz", sameLanguage)
        assertNoAllSavedWithSlots(status.status)
    }

    @Test
    fun step21DestinationLooksRight() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        val kind = RealAccounts.connectedKind()
        val list = RealAccounts.app.container.data.backupDestination.listCopies()
        val copies = (list as? BackupCopyList.Listed)?.copies.orEmpty()
        report(
            "destination",
            "kind" to kind,
            "list" to Kind(list::class.java.simpleName),
            "copies" to copies.size,
            "emptyFiles" to copies.count { it.sizeBytes <= 0 },
            "pinned" to copies.count { it.pinned },
            "withDifferences" to copies.count { it.withDifferences },
            "restorable" to copies.count { it.restorable },
            "fromThisPhone" to copies.count { it.origin == CopyOrigin.THIS_PHONE },
            "missingNewer" to (list as? BackupCopyList.Listed)?.missingNewer,
        )
        assertTrue(list is BackupCopyList.Listed)
        assertEquals(0, copies.count { it.sizeBytes <= 0 })
        if (kind == BackupDestinationKind.DOCUMENT_SLOTS) assertEquals(SLOTS, copies.size)
    }

    @Test
    fun inAppDrill() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        val data = RealAccounts.app.container.data
        val drillStarted = System.currentTimeMillis()
        val drill = data.restoreDrill.runDrill()
        val drillMs = System.currentTimeMillis() - drillStarted
        val verifyStarted = System.currentTimeMillis()
        val verify = data.fullVerify.runFullVerify()
        report(
            "inAppDrill",
            "label" to label,
            "drill" to drill,
            "drillMs" to drillMs,
            "fullVerify" to verify,
            "fullVerifyMs" to System.currentTimeMillis() - verifyStarted,
        )
        assertEquals(DrillOutcome.PASSED, drill)
        if (RealAccounts.connectedKind() == BackupDestinationKind.WEBDAV) {
            assertEquals(FullVerifyOutcome.MATCHED, verify)
        }
    }

    /** Ninguna subida espera ni corre, y todos los cambios tienen su copia comprobada. */
    private suspend fun copyChecked(): Boolean {
        val facts = RealAccounts.ports().backupStatus.current()
        val idle = RealAccounts.uploadWork().none { (_, info) -> !info.state.isFinished }
        return idle && facts.verifiedSeq == facts.changeSeq
    }

    /** Con la nube del teléfono, la app nunca dice que todo está guardado. */
    private suspend fun assertNoAllSavedWithSlots(status: BackupStatus) {
        if (RealAccounts.connectedKind() == BackupDestinationKind.DOCUMENT_SLOTS) {
            assertNotEquals(BackupStatus.UP_TO_DATE, status)
        }
    }

    /**
     * La respuesta a una pregunta de la restauración, o nula si no hay que contestar. Solo se
     * contesta lo que la persona contestaría en este teléfono de prueba: descartar la copia de
     * seguridad más vieja. Lo demás detiene el paso.
     */
    private fun answerFor(progress: RestoreProgress): RestoreAnswer? = when (progress) {
        is RestoreProgress.AskDiscardSafetyCopy -> {
            val oldest = progress.copies.withIndex()
                .minByOrNull { it.value.createdAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
            RestoreAnswer.DiscardSafetyCopy(oldest?.index ?: 0)
        }

        else -> null
    }

    private fun languageBytes(): ByteArray {
        val file = File(RealAccounts.context.noBackupFilesDir, LANGUAGE_FILE)
        return if (file.isFile) file.readBytes() else ByteArray(0)
    }

    private companion object {
        const val CONTROL_FILE = "control.txt"
        const val LANGUAGE_COPY = "ui-language.before"
        const val LANGUAGE_FILE = "ui-language"
        const val OFFLINE_SALES = 3
        const val MAX_ANSWERS = 3
        const val SLOTS = 3
        const val MINUTE_MS = 60_000L
    }
}
