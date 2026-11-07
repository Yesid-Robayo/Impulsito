package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.yesidrobayo.shopledger.data.backup.DailyMaintenance
import io.github.yesidrobayo.shopledger.data.backup.MaintenanceEvent
import io.github.yesidrobayo.shopledger.data.backup.MaintenanceRun
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain

/**
 * El trabajo periódico, una vez al día, que corre el mantenimiento del respaldo. No pide red ni
 * ninguna otra condición, y nunca pasa a primer plano.
 *
 * - **Terminar** (`success`), también tras una falla: el mantenimiento de mañana lo intenta de
 *   nuevo, y un trabajo periódico sigue programado sea cual sea su resultado.
 * - **Reintentar más tarde** (`retry`), si la base todavía no se puede usar, como con una migración
 *   que espera a la app o una restauración a medias.
 *
 * **Ninguna excepción sale con su mensaje:** todo corre dentro de [EventLog.contain], y una falla
 * queda en el registro solo con su clase.
 *
 * @param maintenance el mantenimiento, con la base y el respaldo de la app.
 */
internal class DailyMaintenanceWorker(
    context: Context,
    params: WorkerParameters,
    private val maintenance: DailyMaintenance,
    private val log: EventLog,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (val done = log.contain(MaintenanceEvent.WORKER_FAILED) { maintenance.run() }) {
            is Contained.Done ->
                when (done.value) {
                    MaintenanceRun.DONE -> Result.success()
                    MaintenanceRun.RETRY -> Result.retry()
                }

            is Contained.Failed -> Result.success()
        }
}
