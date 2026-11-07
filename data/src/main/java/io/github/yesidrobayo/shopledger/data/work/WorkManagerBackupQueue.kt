package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.await
import androidx.work.workDataOf
import io.github.yesidrobayo.shopledger.data.backup.BackupWorkQueue
import io.github.yesidrobayo.shopledger.data.backup.RunKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Los trabajos del respaldo en la biblioteca de trabajos del sistema, que los guarda y los corre
 * aunque la app esté cerrada.
 *
 * Cada trabajo lleva su nombre único también como etiqueta: así el trabajo de subida sabe cuál de
 * los dos es, y cancela al otro al terminar con una copia comprobada. Ninguno pasa a primer plano
 * ni pide ser urgente.
 *
 * Si la biblioteca todavía no está lista en este proceso, no hay nada que programar.
 */
internal class WorkManagerBackupQueue(private val context: Context) : BackupWorkQueue {
    override suspend fun restartDebounce(delay: Duration) {
        val request =
            OneTimeWorkRequestBuilder<BackupTriggerWorker>()
                .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .addTag(BackupWorkNames.DEBOUNCE)
                .build()
        enqueue(BackupWorkNames.DEBOUNCE, ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun keepUnmetered() {
        enqueue(
            BackupWorkNames.UPLOAD_UNMETERED,
            ExistingWorkPolicy.KEEP,
            upload(BackupWorkNames.UPLOAD_UNMETERED, NetworkType.UNMETERED, Duration.ZERO, null),
        )
    }

    override suspend fun keepUpload(delay: Duration, needsNetwork: Boolean) {
        enqueue(
            BackupWorkNames.UPLOAD,
            ExistingWorkPolicy.KEEP,
            upload(BackupWorkNames.UPLOAD, networkFor(needsNetwork), delay, null),
        )
    }

    override suspend fun replaceUpload(needsNetwork: Boolean, kind: RunKind) {
        enqueue(
            BackupWorkNames.UPLOAD,
            ExistingWorkPolicy.REPLACE,
            upload(BackupWorkNames.UPLOAD, networkFor(needsNetwork), Duration.ZERO, kind),
        )
    }

    override suspend fun cancel(name: String) {
        manager()?.cancelUniqueWork(name)?.done()
    }

    override suspend fun uploadWork(): BackupUploadWork {
        val manager = manager() ?: return BackupUploadWork.NONE
        return uploadWorkOf(states(manager, BackupWorkNames.UPLOADS))
    }

    override suspend fun awaitNoneRunning(names: List<String>) {
        val manager = manager() ?: return
        // Un trabajo cancelado deja de figurar corriendo en cuanto la biblioteca registra la
        // cancelación. El tope solo evita colgar la restauración si la biblioteca no respondiera:
        // el cerrojo del respaldo ya impide que una ejecución corra mientras tanto.
        withTimeoutOrNull(AWAIT_LIMIT) {
            manager
                .getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(names))
                .first { infos -> infos.none { it.state == WorkInfo.State.RUNNING } }
        }
    }

    private suspend fun enqueue(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        manager()?.enqueueUniqueWork(name, policy, request)?.done()
    }

    private fun upload(
        name: String,
        network: NetworkType,
        delay: Duration,
        kind: RunKind?,
    ): OneTimeWorkRequest {
        val builder =
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .addTag(name)
        when (kind) {
            RunKind.BACKUP_NOW ->
                builder.setInputData(workDataOf(BackupWorker.KEY_BACKUP_NOW to true))

            RunKind.PAST_INTERVAL ->
                builder.setInputData(workDataOf(BackupWorker.KEY_PAST_INTERVAL to true))

            RunKind.AUTOMATIC, null -> Unit
        }
        return builder.build()
    }

    private fun networkFor(needsNetwork: Boolean): NetworkType =
        if (needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED

    private fun manager(): WorkManager? = try {
        WorkManager.getInstance(context)
    } catch (_: IllegalStateException) {
        null
    }

    private suspend fun states(manager: WorkManager, names: List<String>): List<WorkInfo.State> =
        manager.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(names)).first().map {
            it.state
        }

    /** Espera a que la biblioteca registre la operación: lo siguiente va después. */
    private suspend fun Operation.done() {
        await()
    }

    private companion object {
        val AWAIT_LIMIT = 2.minutes
    }
}
