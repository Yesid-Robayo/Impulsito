package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.BackupEvent
import io.github.yesidrobayo.shopledger.data.backup.BackupRun
import io.github.yesidrobayo.shopledger.data.backup.report
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * El trabajo en segundo plano que sube una copia de la base. Corre una ejecución del motor y la
 * traduce a lo que el sistema hace después:
 *
 * - **Terminar** (`success`), con una copia comprobada o sin nada que subir.
 * - **Reintentar más tarde** (`retry`), con lo que puede pasar solo: sin red, un servicio ocupado,
 *   una restauración en curso, una migración que espera a la app o el sistema que pidió parar. El
 *   sistema espera cada vez más entre intentos.
 * - **Detenerse** (`failure`), con lo que espera a la persona o a un cambio nuevo: acceso perdido,
 *   un conflicto o un fallo a la vista.
 *
 * **Ninguna excepción sale con su mensaje.** El mensaje de una conexión o de la base puede traer la
 * dirección del servicio, la de un documento o datos de una fila, y lo que sale de aquí lo registra
 * la biblioteca de trabajos, fuera de la app. Por eso todo corre dentro de [EventLog.contain]: una
 * falla queda en el registro solo con su clase, y el trabajo se detiene. Solo la cancelación sigue
 * su camino.
 *
 * Nunca pasa a primer plano ni pide ser urgente: un trabajo así puede correr como servicio en primer
 * plano, que la app no usa. Por eso cada ejecución sale sola antes del tope del sistema, con
 * [BACKUP_TIME_BUDGET].
 *
 * @param engine el motor de respaldo, con la base y el destino de la app.
 * @param metering dice si la red activa es medida.
 */
internal class BackupWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: BackupEngine,
    private val log: EventLog,
    private val metering: () -> Boolean,
    private val budget: Duration = BACKUP_TIME_BUDGET,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val control = WorkerControl(TimeSource.Monotonic.markNow()) { isStopped }
        return when (
            val contained = log.contain(BackupEvent.WORKER_FAILED) {
                engine.run(control)
            }
        ) {
            is Contained.Done -> {
                log.report(contained.value)
                resultOf(contained.value)
            }

            is Contained.Failed -> {
                engine.recordUnexpected()
                Result.failure()
            }
        }
    }

    /** Lo que el destino revisa mientras transfiere, desde este trabajo. */
    private inner class WorkerControl(
        private val started: TimeSource.Monotonic.ValueTimeMark,
        private val stopRequested: () -> Boolean,
    ) : TransferControl {
        override fun isStopped(): Boolean = stopRequested()

        override fun remainingMillis(): Long? =
            (budget - started.elapsedNow()).inWholeMilliseconds.coerceAtLeast(0)

        // Cortar una subida porque la red pasó a medida es de la compuerta de red, que llega con la
        // programación de los trabajos.
        override fun meteredStop(): Boolean = false

        override fun isMetered(): Boolean = metering()
    }

    companion object {
        /**
         * Lo más que dura una ejecución: el sistema detiene un trabajo a los 10 minutos, y uno que
         * agota su tiempo con frecuencia puede llevar a la app a un grupo con menos ejecuciones.
         */
        val BACKUP_TIME_BUDGET: Duration = 8.minutes

        /** Lo que hace el sistema después de una ejecución. */
        internal fun resultOf(run: BackupRun): Result = when (run) {
            is BackupRun.Verified -> Result.success()
            is BackupRun.Skipped -> Result.success()
            is BackupRun.Retry -> Result.retry()
            is BackupRun.Stopped -> Result.failure()
        }
    }
}
