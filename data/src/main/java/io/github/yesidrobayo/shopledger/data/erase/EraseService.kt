package io.github.yesidrobayo.shopledger.data.erase

import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseFacts
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.RemoteEraseResult
import io.github.yesidrobayo.shopledger.domain.port.EraseMyDataPort
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.IOException
import kotlinx.coroutines.sync.Mutex

/**
 * `Borrar mis datos` con las piezas de la capa de datos: el registro durable de la fase, el
 * borrado en el destino y el de este teléfono.
 *
 * Cada borrado corre con los trabajos del respaldo cancelados y su cerrojo tomado: ninguna copia
 * sube al destino mientras se vacía, ni lee la base mientras se borra. Si la nube quedó vacía, el
 * cerrojo sigue tomado hasta borrar este teléfono, para que ninguna copia nueva suba entre los dos
 * pasos. Lo de este teléfono corre además con la base cerrada, en [exclusive], sin que nada la
 * vuelva a abrir hasta terminar.
 *
 * @param state el estado del respaldo de la base abierta, o `null` si no se lee.
 * @param restoreInProgress si hay una restauración preparándose o reemplazando la base.
 * @param hasNetwork si hay una red que llegue a internet.
 * @param stopWork cancela los trabajos del respaldo y espera a que salga el que corre.
 * @param exclusive cierra la base y corre lo que recibe sin dejar que nada la abra.
 */
internal class EraseService(
    private val journal: EraseJournalStore,
    private val state: suspend () -> BackupStateEntity?,
    private val restoreInProgress: suspend () -> Boolean,
    private val hasNetwork: () -> Boolean,
    private val remote: RemoteEraser,
    private val local: LocalEraser,
    private val mutex: Mutex,
    private val stopWork: suspend () -> Unit,
    private val exclusive: suspend (suspend () -> Boolean) -> Boolean,
    private val log: EventLog,
) : EraseMyDataPort {
    override suspend fun facts(): EraseFacts =
        EraseFacts(eraseDestinationOf(state()), hasNetwork(), restoreInProgress())

    override suspend fun pending(): ErasePhase? = journal.read()

    override suspend fun save(phase: ErasePhase) = journal.write(phase)

    override suspend fun deleteRemote(): RemoteEraseResult {
        stopWork()
        if (!mutex.holdsLock(owner)) mutex.lock(owner)
        val result =
            try {
                remote.erase()
            } catch (failure: IOException) {
                log.warn(EraseEvent.REMOTE_FAILED, failure)
                RemoteEraseResult.UNREACHABLE
            } catch (failure: Throwable) {
                mutex.unlock(owner)
                throw failure
            }
        // Con la nube vacía, el cerrojo sigue tomado hasta borrar lo local.
        if (result != RemoteEraseResult.DELETED) mutex.unlock(owner)
        log.info(EraseEvent.REMOTE_FINISHED, LogField.state("resultado", result))
        return result
    }

    override suspend fun deleteLocal(): Boolean {
        stopWork()
        if (!mutex.holdsLock(owner)) mutex.lock(owner)
        try {
            return exclusive { local.erase() }
        } finally {
            mutex.unlock(owner)
        }
    }

    /** Quién tiene el cerrojo del respaldo durante un borrado: este servicio, entre sus pasos. */
    private val owner = Any()
}
