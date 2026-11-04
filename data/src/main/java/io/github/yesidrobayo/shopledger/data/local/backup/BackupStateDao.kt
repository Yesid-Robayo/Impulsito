package io.github.yesidrobayo.shopledger.data.local.backup

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de la única fila de `backup_state`.
 *
 * No hay inserción ni borrado: la fila nace con la base y dura lo que ella. Las escrituras del
 * motor de respaldo llegan con él, cada una con un `UPDATE` de solo sus columnas.
 */
@Dao
internal interface BackupStateDao {
    /** El estado del respaldo, de nuevo cada vez que cambia. Nulo solo si falta la fila. */
    @Query("SELECT * FROM backup_state WHERE id = 1")
    fun observe(): Flow<BackupStateEntity?>

    @Query("SELECT * FROM backup_state WHERE id = 1")
    suspend fun get(): BackupStateEntity?

    /**
     * Cuenta un cambio de negocio sin respaldar: sube `change_seq` en uno y, si no había cambios
     * pendientes, deja [now] como el primero sin copia. Si ya había uno, lo conserva: la antigüedad
     * se mide desde el cambio más viejo sin copia.
     *
     * Va dentro de la misma transacción que escribe los datos, al final, solo si el caso de uso
     * confirma: así un cambio y su marca se confirman juntos o no ocurren. La misma sentencia
     * fuerza una subida cuando el respaldo lo necesita.
     *
     * [now] es el reloj de pared, en milisegundos UTC. Devuelve las filas cambiadas: 1, o 0 si
     * falta la fila. Con 0 la transacción no debe confirmarse, porque el cambio quedaría sin marca.
     */
    @Query(
        "UPDATE backup_state SET change_seq = change_seq + 1, " +
            "first_unsynced_at = COALESCE(first_unsynced_at, :now) WHERE id = 1",
    )
    suspend fun markChanged(now: Long): Int
}
