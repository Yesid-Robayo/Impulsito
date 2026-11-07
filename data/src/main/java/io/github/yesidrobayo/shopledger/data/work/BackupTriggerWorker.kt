package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.yesidrobayo.shopledger.data.backup.BackupEvent
import io.github.yesidrobayo.shopledger.domain.port.Contained
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.contain

/**
 * La espera que junta los cambios seguidos: un temporizador que, al vencer, solo encola la subida.
 * Cada cambio lo reemplaza y vuelve a empezar el reloj; reemplazar la subida en su lugar cortaría
 * una que ya corre.
 *
 * Si la base todavía no se puede leer, como con una migración que espera a la app, se reintenta
 * más tarde: el cambio sigue contado y no se pierde. Ninguna excepción sale con su mensaje.
 *
 * @param elapsed lo que se programa al vencer. Devuelve `false` si la base no se pudo leer.
 */
internal class BackupTriggerWorker(
    context: Context,
    params: WorkerParameters,
    private val elapsed: suspend () -> Boolean,
    private val log: EventLog,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (val done = log.contain(BackupEvent.SCHEDULING_FAILED) { elapsed() }) {
            is Contained.Done -> if (done.value) Result.success() else Result.retry()

            // El cambio sigue contado: el arranque siguiente o el próximo cambio lo programan.
            is Contained.Failed -> Result.failure()
        }
}
