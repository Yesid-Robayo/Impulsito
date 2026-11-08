package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.yesidrobayo.shopledger.data.backup.HeadDeepCheck
import io.github.yesidrobayo.shopledger.data.backup.HeadProbe
import io.github.yesidrobayo.shopledger.data.backup.ProbeEvent
import io.github.yesidrobayo.shopledger.data.backup.ProbeRun
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * El trabajo que contrasta el estado con el destino, en sus tres formas: la sonda semanal, la
 * sonda única al abrir o reconectar, y la comprobación completa mensual. Cuál corre lo dice su
 * etiqueta, que es su nombre único.
 *
 * - **Terminar** (`success`), también tras una falla: la siguiente es la programada.
 * - **Reintentar más tarde** (`retry`), con lo pasajero: sin red, la base que todavía no se puede
 *   usar o una restauración a medias.
 *
 * Ninguna excepción sale con su mensaje, y nunca pasa a primer plano. Cada ejecución tiene el mismo
 * tope de tiempo que una subida.
 *
 * @param metering dice si la red activa es medida.
 */
internal class HeadProbeWorker(
    context: Context,
    params: WorkerParameters,
    private val probe: HeadProbe,
    private val deepCheck: HeadDeepCheck,
    private val log: EventLog,
    private val metering: () -> Boolean,
    private val budget: Duration = BackupWorker.BACKUP_TIME_BUDGET,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val control = ProbeControl(TimeSource.Monotonic.markNow()) { isStopped }
        val deep = BackupWorkNames.HEAD_DEEP_CHECK in tags
        return when (
            val done = log.contain(ProbeEvent.WORKER_FAILED) {
                if (deep) deepCheck.run(control) else probe.run(control)
            }
        ) {
            is Contained.Done ->
                when (done.value) {
                    ProbeRun.DONE -> Result.success()
                    ProbeRun.RETRY -> Result.retry()
                }

            is Contained.Failed -> Result.success()
        }
    }

    /** Lo que el destino revisa mientras transfiere, desde este trabajo. */
    private inner class ProbeControl(
        private val started: TimeSource.Monotonic.ValueTimeMark,
        private val stopRequested: () -> Boolean,
    ) : TransferControl {
        override fun isStopped(): Boolean = stopRequested()

        override fun remainingMillis(): Long? =
            (budget - started.elapsedNow()).inWholeMilliseconds.coerceAtLeast(0)

        // Listar gasta poco, y la comprobación completa ya pide una red sin datos móviles.
        override fun meteredStop(): Boolean = false

        override fun isMetered(): Boolean = metering()
    }
}
