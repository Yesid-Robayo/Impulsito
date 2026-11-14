package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.media.MediaSync
import io.github.yesidrobayo.shopledger.data.media.MediaSyncEvent
import io.github.yesidrobayo.shopledger.data.media.MediaSyncRun
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * El trabajo `media-sync`: sube las fotos que faltan en el servicio WebDAV, aparte de la copia de
 * la base y cediéndole siempre el turno.
 *
 * - **Terminar** (`success`), también tras una falla: lo encola otra vez el respaldo.
 * - **Reintentar más tarde** (`retry`), con lo pasajero: sin red, la base que todavía no se puede
 *   usar o una restauración a medias.
 *
 * Ninguna excepción sale con su mensaje, y nunca pasa a primer plano. Cada ejecución tiene el mismo
 * tope de tiempo que una subida: lo que no alcanzó sigue en la próxima, desde `media-sync.txt`.
 *
 * @param metering dice si la red activa es medida.
 */
internal class MediaSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val sync: MediaSync,
    private val log: EventLog,
    private val metering: () -> Boolean,
    private val budget: Duration = BackupWorker.BACKUP_TIME_BUDGET,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val control = MediaControl(TimeSource.Monotonic.markNow()) { isStopped }
        return when (val done = log.contain(MediaSyncEvent.WORKER_FAILED) { sync.run(control) }) {
            is Contained.Done ->
                when (done.value) {
                    MediaSyncRun.DONE -> Result.success()
                    MediaSyncRun.RETRY -> Result.retry()
                }

            is Contained.Failed -> Result.success()
        }
    }

    /** Lo que el canal revisa mientras transfiere, desde este trabajo. */
    private inner class MediaControl(
        private val started: TimeSource.Monotonic.ValueTimeMark,
        private val stopRequested: () -> Boolean,
    ) : TransferControl {
        override fun isStopped(): Boolean =
            stopRequested() || (budget - started.elapsedNow()).isNegative()

        override fun remainingMillis(): Long? =
            (budget - started.elapsedNow()).inWholeMilliseconds.coerceAtLeast(0)

        // La red la decide el trabajo antes de empezar: con datos móviles solo si se permite.
        override fun meteredStop(): Boolean = false

        override fun isMetered(): Boolean = metering()
    }
}
