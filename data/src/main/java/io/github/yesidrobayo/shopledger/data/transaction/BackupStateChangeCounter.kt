package io.github.yesidrobayo.shopledger.data.transaction

import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException

/**
 * Cuenta el cambio en la fila del estado del respaldo: sube `change_seq` en uno y, si era el
 * primero sin copia, guarda su instante. El respaldo compara ese contador con el de la última copia
 * verificada para saber que hay algo por subir.
 *
 * Si la fila falta, la sentencia no cambia nada, y el cambio quedaría guardado sin marca: ninguna
 * copia lo pediría. Entonces lanza, y la escritura entera se deshace. Es una regla de la base rota,
 * no algo que la persona pueda arreglar reintentando.
 */
internal class BackupStateChangeCounter(private val backupState: BackupStateDao) : ChangeCounter {
    override suspend fun markChanged(nowEpochMillis: Long) {
        if (backupState.markChanged(nowEpochMillis) != 1) {
            throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)
        }
    }
}
