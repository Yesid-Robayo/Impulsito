package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.await
import androidx.work.workDataOf
import io.github.yesidrobayo.shopledger.data.backup.BackupWorkQueue
import io.github.yesidrobayo.shopledger.data.backup.RunKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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

    override suspend fun keepDailyMaintenance() {
        // Sin restricciones: no usa la red, y debe correr también sin cargador ni Wi-Fi.
        val request =
            PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(
                MAINTENANCE_PERIOD.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            ).addTag(BackupWorkNames.DAILY_MAINTENANCE)
                .build()
        manager()
            ?.enqueueUniquePeriodicWork(
                BackupWorkNames.DAILY_MAINTENANCE,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )?.done()
    }

    override suspend fun keepHeadChecks(needsNetwork: Boolean) =
        scheduleHeadChecks(needsNetwork, ExistingPeriodicWorkPolicy.KEEP)

    // Actualizar conserva cuándo toca la siguiente, y cambia la red que pide.
    override suspend fun replaceHeadChecks(needsNetwork: Boolean) =
        scheduleHeadChecks(needsNetwork, ExistingPeriodicWorkPolicy.UPDATE)

    private suspend fun scheduleHeadChecks(
        needsNetwork: Boolean,
        probePolicy: ExistingPeriodicWorkPolicy,
    ) {
        val manager = manager() ?: return
        val probe =
            PeriodicWorkRequestBuilder<HeadProbeWorker>(
                HEAD_PROBE_PERIOD.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            ).setConstraints(
                Constraints.Builder().setRequiredNetworkType(networkFor(needsNetwork)).build(),
            )
                .addTag(BackupWorkNames.HEAD_PROBE)
                .build()
        manager
            .enqueueUniquePeriodicWork(
                BackupWorkNames.HEAD_PROBE,
                probePolicy,
                probe,
            ).done()
        val deep =
            PeriodicWorkRequestBuilder<HeadProbeWorker>(
                HEAD_DEEP_CHECK_PERIOD.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            ).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
            )
                .addTag(BackupWorkNames.HEAD_DEEP_CHECK)
                .build()
        manager
            .enqueueUniquePeriodicWork(
                BackupWorkNames.HEAD_DEEP_CHECK,
                ExistingPeriodicWorkPolicy.KEEP,
                deep,
            ).done()
    }

    override suspend fun keepHeadProbeOnce(needsNetwork: Boolean) {
        val request =
            OneTimeWorkRequestBuilder<HeadProbeWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(networkFor(needsNetwork)).build(),
                )
                .addTag(BackupWorkNames.HEAD_PROBE_ONCE)
                .build()
        enqueue(BackupWorkNames.HEAD_PROBE_ONCE, ExistingWorkPolicy.KEEP, request)
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

        /** Cada cuánto corre el mantenimiento diario. */
        val MAINTENANCE_PERIOD = 24.hours

        /** Cada cuánto corre la sonda de la cabeza. */
        val HEAD_PROBE_PERIOD = BackupTimings.HEAD_PROBE

        /** Cada cuánto corre la comprobación completa de la cabeza. */
        val HEAD_DEEP_CHECK_PERIOD = BackupTimings.HEAD_DEEP_CHECK
    }
}
