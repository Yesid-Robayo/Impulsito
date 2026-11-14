package io.github.yesidrobayo.shopledger.data.media

import io.github.yesidrobayo.shopledger.data.local.catalog.MediaAssetDao
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.info
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.withContext

/**
 * La limpieza de las fotos que nada usa, en el mantenimiento diario y bajo el cerrojo del respaldo.
 *
 * 1. **Marca** con `orphaned_at` las imágenes que ningún producto, método de pago, comprobante ni
 *    logo usa. El caso de uso que cambia una foto ya marca la anterior; esto alcanza lo demás, como
 *    un producto borrado o una copia vieja restaurada.
 * 2. **A los [RETENTION] de la marca,** vuelve a mirar que nada la use y borra la fila. Si algo
 *    volvió a usarla, le quita la marca. Un comprobante nunca llega aquí: lo cuenta como uso, y su
 *    llave `RESTRICT` impediría igual borrar la fila.
 * 3. **Anota la foto para borrarla del servicio,** antes de borrar el archivo local: `media-sync` la
 *    borra en su siguiente ejecución. Recién entonces borra el archivo del teléfono.
 *
 * Lo escrito en la base cuenta como un cambio, como toda escritura: confirmar sin contar es solo
 * para lo que una regla declara así. Sin nada que marcar ni borrar, no escribe ni cuenta nada.
 *
 * Una copia vieja restaurada puede nombrar una foto ya borrada: se ve `Foto no disponible`, y se
 * acepta. Solo registra conteos.
 */
internal class MediaCleanup(
    private val rows: MediaAssetDao,
    private val transactions: TransactionRunner,
    private val store: suspend () -> MediaSyncStore,
    private val mediaDirectory: () -> File,
    private val ioContext: CoroutineContext,
    private val log: EventLog,
) {
    /** Corre la limpieza con el instante [now]. Un reloj atrasado no borra nada antes de tiempo. */
    suspend fun run(now: Instant) {
        val marked =
            transactions.write {
                val unreferenced = rows.findUnreferencedInUse()
                unreferenced.forEach { rows.setOrphanedAt(it.id, now.toEpochMilliseconds()) }
                if (unreferenced.isEmpty()) {
                    TxResult.Rollback(0)
                } else {
                    TxResult.Commit(unreferenced.size)
                }
            }
        val cutoff = (now - RETENTION).toEpochMilliseconds()
        val expired = rows.findOrphanedBefore(cutoff)
        if (expired.isNotEmpty()) {
            // Primero se anota para el servicio: un corte después nunca deja una foto sin borrar.
            val states = store()
            states.saveDeletions(states.deletions() + expired.map { it.sha256 })
        }
        val deleted =
            if (expired.isEmpty()) {
                emptyList()
            } else {
                transactions.write {
                    val gone = ArrayList<String>()
                    for (row in expired) {
                        if (rows.isReferenced(row.id)) {
                            rows.setOrphanedAt(row.id, null)
                        } else if (rows.delete(row.id) == 1) {
                            gone += row.sha256
                        }
                    }
                    TxResult.Commit(gone)
                }
            }
        val kept = expired.map { it.sha256 }.toSet() - deleted.toSet()
        if (kept.isNotEmpty()) {
            val states = store()
            states.saveDeletions(states.deletions() - kept)
        }
        withContext(ioContext) {
            val directory = mediaDirectory()
            deleted.forEach { File(directory, StoredImage.fileNameOf(it)).delete() }
        }
        if (deleted.isNotEmpty()) {
            val states = store()
            states.saveUnavailable(states.unavailable() - deleted.toSet())
        }
        log.info(
            MediaCleanupEvent.CLEANED,
            LogField.count("marcadas", marked.toLong()),
            LogField.count("borradas", deleted.size.toLong()),
        )
    }

    companion object {
        /** Cuánto espera una imagen marcada antes de borrarse. */
        val RETENTION = 30.days
    }
}

/** Los eventos de la limpieza de fotos. Solo llevan conteos. */
internal enum class MediaCleanupEvent(override val text: String) : LogEvent {
    CLEANED("Limpieza de fotos sin uso"),
    FAILED("Falla de la limpieza de fotos"),
}
