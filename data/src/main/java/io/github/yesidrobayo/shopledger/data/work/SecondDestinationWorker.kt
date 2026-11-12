package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.yesidrobayo.shopledger.data.backup.SecondDestinationEvent
import io.github.yesidrobayo.shopledger.data.backup.SecondDestinationJob
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain

/**
 * El trabajo del segundo destino: una copia en la carpeta local, sin red y con cualquier destino o
 * ninguno. Se encola con `KEEP`, nunca con `REPLACE`: reemplazar uno que escribe borraría el
 * temporal de otro.
 *
 * Reintenta si la base todavía no se puede usar; termina en cualquier otro caso, también tras una
 * falla, que queda en el registro solo con su clase. Un problema de la carpeta queda anotado aparte
 * y nunca toca el estado del respaldo.
 */
internal class SecondDestinationWorker(
    context: Context,
    params: WorkerParameters,
    private val job: SecondDestinationJob,
    private val log: EventLog,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (val done = log.contain(SecondDestinationEvent.WORKER_FAILED) { job.run() }) {
            is Contained.Done ->
                when (done.value) {
                    SecondDestinationJob.JobResult.DONE -> Result.success()
                    SecondDestinationJob.JobResult.RETRY -> Result.retry()
                }

            is Contained.Failed -> Result.success()
        }
}
