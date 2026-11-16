package io.github.yesidrobayo.shopledger.realaccounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.realaccounts.RealAccounts.report
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un paso de la corrida comprimida de varios días, que conduce el guion del PC con `adb`.
 *
 * [tick] primero describe cómo terminó la ejecución anterior del trabajo de respaldo de
 * producción, y después hace lo que hace la persona: registra una venta inventada y deja la app.
 * Con WebDAV, la subida se encola como cuando la app pasa a segundo plano. Con la nube del
 * teléfono, como cuando se abre tras un día sin copia: así la corrida no espera la hora entera
 * entre escrituras que exige la app con ranuras, y el trabajo que corre es el mismo. La
 * instrumentación termina, el proceso de la app muere, y el trabajo corre después, con la app
 * cerrada, cuando el sistema le da turno según el *bucket* y Doze que fijó el guion.
 *
 * [probe] solo describe la última ejecución, al final de la corrida.
 *
 * Nada de esto toca la credencial: el trabajo la abre como siempre. El registro lleva solo
 * estados, conteos y horas en milisegundos.
 */
@RunWith(AndroidJUnit4::class)
class RealRunTickTest {
    private val runId = RealAccounts.argument("runId", "0").toLongOrNull() ?: 0L

    @Test
    fun tick() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        describePrevious()
        val ports = RealAccounts.ports()
        val kind = ports.backupStatus.current().destinationKind
        assertNotNull("No hay destino conectado", kind)
        val debounceBefore = RealAccounts.debounceWork().map { it.id }.toSet()
        RealAccounts.registerInventedSale(ports)
        // El aviso del cambio corre aparte, tras confirmar la venta: se espera a que encole la
        // espera, para que no llegue después de encolar la subida y la vuelva a empezar.
        val debounced = RealAccounts.awaitCondition(SHORT_WAIT_MS) {
            RealAccounts.debounceWork().any {
                it.id !in debounceBefore && it.state == WorkInfo.State.ENQUEUED
            }
        }
        val scheduler = RealAccounts.app.container.data.backupScheduler
        when (kind) {
            BackupDestinationKind.WEBDAV -> scheduler.onAppBackgrounded()
            BackupDestinationKind.DOCUMENT_SLOTS ->
                scheduler.onAppOpened(AppOpenUpload.PAST_INTERVAL)
            null -> Unit
        }
        val queued = RealAccounts.awaitCondition(SHORT_WAIT_MS) {
            RealAccounts.uploadWork().any { (_, info) -> !info.state.isFinished }
        }
        val waiting = RealAccounts.uploadWork().filter { (_, info) -> !info.state.isFinished }
        val due = waiting.map { (_, info) -> info.nextScheduleTimeMillis }.minOrNull()
        val network = waiting.firstOrNull()?.let { it.second.constraints.requiredNetworkType }
        report(
            "tick",
            "runId" to runId,
            "kind" to kind,
            "debounced" to debounced,
            "queued" to queued,
            "uploadsWaiting" to waiting.size,
            "dueAtMs" to due,
            "requiredNetwork" to network,
            "bucket" to RealAccounts.standbyBucket(),
            "atMs" to System.currentTimeMillis(),
        )
        assertTrue("La subida no quedó encolada", queued)
    }

    @Test
    fun probe() = runBlocking<Unit> {
        RealAccounts.requireRealAccounts()
        describePrevious()
    }

    /**
     * Cómo terminó la última ejecución: el trabajo terminado que no se canceló, con sus intentos
     * y `stopReason`, y el estado guardado y visible del respaldo. Con ranuras, el estado nunca
     * puede decir que todo está guardado.
     */
    private suspend fun describePrevious() {
        val work = RealAccounts.uploadWork()
        val finished = work.map { it.second }
            .filter { it.state.isFinished && it.state != WorkInfo.State.CANCELLED }
        val last = finished.maxByOrNull { it.runAttemptCount }
        val facts = RealAccounts.ports().backupStatus.current()
        val status = RealAccounts.visibleStatus()
        report(
            "previous",
            "runId" to runId,
            "kind" to facts.destinationKind,
            "work" to last?.state,
            "attempts" to last?.runAttemptCount,
            "stopReason" to last?.stopReason,
            "cancelled" to work.count { it.second.state == WorkInfo.State.CANCELLED },
            "unfinished" to work.count { !it.second.state.isFinished },
            "status" to status.status,
            "pending" to status.pendingChanges,
            "changeSeq" to facts.changeSeq,
            "verifiedSeq" to facts.verifiedSeq,
            "failure" to facts.failureCode,
            "link" to facts.linkState,
            "lastUploadMs" to facts.lastUploadFinishedAt?.toEpochMilliseconds(),
            "atMs" to System.currentTimeMillis(),
        )
        if (facts.destinationKind == BackupDestinationKind.DOCUMENT_SLOTS) {
            assertNotEquals(BackupStatus.UP_TO_DATE, status.status)
        }
    }

    private companion object {
        const val SHORT_WAIT_MS = 15_000L
    }
}
