package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.WorkAccess
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Lo que corre el trabajo de respaldo: llegar a la base sin riesgo y, si se llega, una ejecución
 * del motor. El trabajo no sabe nada más.
 */
internal interface BackupEngine {
    /** Corre una ejecución. Solo lanza la cancelación, o una falla que nadie previó. */
    suspend fun run(control: TransferControl): BackupRun

    /**
     * Deja a la vista que la última ejecución terminó por una falla que nadie previó, si la base ya
     * está abierta. Nunca lanza: si tampoco se puede escribir, no queda nada.
     */
    suspend fun recordUnexpected()
}

/**
 * El motor sobre la base de la app.
 *
 * Antes de abrir la base mira si hay una restauración en curso: la base puede estar a punto de
 * cambiar entera, y no se abre ni se copia. Después pasa por la compuerta de los trabajos, que
 * nunca migra: si la base debe migrar, o no abre sin riesgo, la ejecución se reintenta sin haber
 * abierto Room.
 *
 * @param restoreMarkers da las marcas de la restauración. Pedirlas toca el disco: se piden en
 *   [ioContext].
 * @param openForWork abre la base para un trabajo, con la compuerta del arranque.
 * @param runnerFor arma el motor sobre la base ya abierta.
 */
internal class DatabaseBackupEngine(
    private val restoreMarkers: () -> RestoreMarkers,
    private val openForWork: suspend () -> WorkAccess,
    private val runnerFor: (DataPorts) -> BackupRunner,
    private val ioContext: CoroutineContext,
) : BackupEngine {
    @Volatile private var opened: DataPorts? = null

    override suspend fun run(control: TransferControl): BackupRun {
        if (withContext(ioContext) { restoreMarkers().present() }) {
            return BackupRun.Retry(RetryReason.RESTORE_IN_PROGRESS)
        }
        return when (val access = openForWork()) {
            is DataAccess.Ready -> {
                opened = access.ports
                runnerFor(access.ports).run(control)
            }

            WorkAccess.MigrationPending -> BackupRun.Retry(RetryReason.MIGRATION_PENDING)

            is DataAccess.Rescue -> BackupRun.Retry(RetryReason.DATABASE_UNAVAILABLE)
        }
    }

    override suspend fun recordUnexpected() {
        val ports = opened ?: return
        try {
            ports.backupState.recordFailure(BackupFailureCode.UNEXPECTED.name)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // La falla ya quedó en el registro con su clase: esta no agrega nada.
        }
    }
}
