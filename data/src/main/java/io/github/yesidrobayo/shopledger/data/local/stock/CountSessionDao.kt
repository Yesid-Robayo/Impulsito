package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `count_sessions` y de sus líneas. Las escrituras solo se llaman dentro de
 * la transacción del caso de uso. Aplicar una sesión escribe en la misma transacción los ajustes
 * del libro y el cierre.
 *
 * Mientras la sesión está abierta se agregan líneas y se anota lo contado. Al cerrarla, ni la
 * sesión ni sus líneas cambian más: cada escritura lo pide en su condición, y las guardas lo
 * imponen. Sin `REPLACE`: un `INSERT OR REPLACE` borraría la sesión que choca, y con ella sus
 * líneas en cascada.
 */
@Dao
internal interface CountSessionDao {
    /** Las sesiones abiertas, de la más vieja a la más nueva. */
    @Query("SELECT * FROM count_sessions WHERE status = 'OPEN' ORDER BY id")
    fun observeOpen(): Flow<List<CountSessionEntity>>

    @Query("SELECT * FROM count_sessions WHERE id = :id")
    suspend fun findById(id: Long): CountSessionEntity?

    /** Las sesiones terminadas, aplicadas o canceladas, de la más nueva a la más antigua. */
    @Query("SELECT * FROM count_sessions WHERE status <> 'OPEN' ORDER BY id DESC")
    fun observeFinished(): Flow<List<CountSessionEntity>>

    /** La sesión abierta de una ubicación, si hay una. */
    @Query(
        "SELECT * FROM count_sessions WHERE location_id = :locationId AND status = 'OPEN' " +
            "ORDER BY id LIMIT 1",
    )
    suspend fun findOpenAt(locationId: Long): CountSessionEntity?

    /**
     * Los productos de la sesión con filas del libro en su ubicación registradas después de
     * abrirla, sin contar los ajustes de la propia sesión.
     */
    @Query(
        "SELECT DISTINCT m.product_id FROM stock_movements m " +
            "JOIN count_sessions c ON c.id = :sessionId " +
            "WHERE m.location_id = c.location_id AND m.created_at > c.started_at " +
            "AND m.count_session_id IS NOT :sessionId " +
            "AND m.product_id IN " +
            "(SELECT product_id FROM count_session_lines WHERE count_session_id = :sessionId)",
    )
    suspend fun productsMovedSinceOpen(sessionId: Long): List<Long>

    /** Las líneas de una sesión, en el orden en que se agregaron. */
    @Query("SELECT * FROM count_session_lines WHERE count_session_id = :sessionId ORDER BY id")
    fun observeLines(sessionId: Long): Flow<List<CountSessionLineEntity>>

    @Query("SELECT * FROM count_session_lines WHERE count_session_id = :sessionId ORDER BY id")
    suspend fun findLines(sessionId: Long): List<CountSessionLineEntity>

    /** Devuelve el id nuevo. La sesión nace abierta y sin cierre. */
    @Insert
    suspend fun insert(session: CountSessionEntity): Long

    /** Devuelve el id nuevo. Falla si la sesión ya no está abierta. */
    @Insert
    suspend fun insertLine(line: CountSessionLineEntity): Long

    /**
     * Anota lo contado en una línea, o lo borra con `null`. Falla si la sesión ya no está abierta.
     * Devuelve las filas cambiadas.
     */
    @Query("UPDATE count_session_lines SET counted_milli = :countedMilli WHERE id = :id")
    suspend fun setCounted(id: Long, countedMilli: Long?): Int

    /** Cambia la nota de una sesión abierta. Devuelve 0 si no existe o ya se cerró. */
    @Query("UPDATE count_sessions SET note = :note WHERE id = :id AND status = 'OPEN'")
    suspend fun updateNote(id: Long, note: String?): Int

    /**
     * Cierra una sesión abierta como [status], `APPLIED` o `CANCELLED`, con [finishedAt]. Devuelve
     * 0 si no existe o ya estaba cerrada: una sesión se cierra una sola vez.
     */
    @Query(
        "UPDATE count_sessions SET status = :status, finished_at = :finishedAt " +
            "WHERE id = :id AND status = 'OPEN'",
    )
    suspend fun finish(id: Long, status: String, finishedAt: Long): Int

    /**
     * Elimina una sesión sin ajustes en el libro, con sus líneas en cascada. Con ajustes, su llave
     * `RESTRICT` hace fallar la sentencia y no borra nada. Devuelve las filas borradas.
     */
    @Query("DELETE FROM count_sessions WHERE id = :id")
    suspend fun delete(id: Long): Int
}
