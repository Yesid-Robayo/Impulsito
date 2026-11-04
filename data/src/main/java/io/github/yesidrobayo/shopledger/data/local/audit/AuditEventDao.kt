package io.github.yesidrobayo.shopledger.data.local.audit

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Cuántos eventos conserva el registro de auditoría. Por encima, la poda borra los más viejos; en
 * el tope o por debajo, la guarda de la tabla no deja borrar ninguno.
 */
internal const val AUDIT_EVENTS_MAX_ROWS = 5_000

/**
 * El registro de auditoría: agregar, podar sobre el tope y leer, nada más.
 *
 * **Se escribe fuera de las transacciones de los casos de uso.** Un evento no es un cambio del
 * negocio: no sube `change_seq`, no programa una copia y, si falla, la operación que lo causó sigue
 * en pie. Por eso quien registra llama a [insert] y a [pruneOldest] directamente, después de la
 * operación y nunca dentro de `TransactionRunner.write`, que cuenta cada confirmación como un
 * cambio sin respaldar: así el evento no se cuenta, y su falla no deshace la operación. Cada
 * llamada corre en su propia transacción corta de Room.
 *
 * No hay función que edite una fila: la guarda lo rechaza. La inserción no usa `REPLACE`, que
 * borraría el evento que choca. Lo único que se borra es lo que pasa del tope, siempre desde el más
 * viejo, por `id`.
 */
@Dao
internal interface AuditEventDao {
    /** Los [limit] eventos más nuevos, primero el último, de nuevo cada vez que cambian. */
    @Query("SELECT * FROM audit_events ORDER BY id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<AuditEventEntity>>

    /** Agrega el evento y devuelve su id. */
    @Insert
    suspend fun insert(event: AuditEventEntity): Long

    /**
     * Borra los eventos más viejos que pasan de [AUDIT_EVENTS_MAX_ROWS], y devuelve cuántos borró.
     * En el tope o por debajo no borra nada. Borra justo los que sobran: uno más y la guarda
     * abortaría la sentencia entera.
     */
    @Query(
        "DELETE FROM audit_events WHERE id IN (SELECT id FROM audit_events ORDER BY id " +
            "LIMIT max((SELECT COUNT(*) FROM audit_events) - $AUDIT_EVENTS_MAX_ROWS, 0))",
    )
    suspend fun pruneOldest(): Int
}
