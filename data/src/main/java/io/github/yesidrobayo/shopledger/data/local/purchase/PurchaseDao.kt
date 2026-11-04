package io.github.yesidrobayo.shopledger.data.local.purchase

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `purchases`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Una compra confirmada no se edita ni se borra: la única escritura después de insertarla es la
 * anulación, que toca solo el estado y sus tres columnas. Sin `REPLACE`: un `INSERT OR REPLACE`
 * borraría la compra que choca sin pasar por la guarda de borrado.
 *
 * `ACTIVE` y `VOIDED` son los nombres guardados de `DocumentStatus`, que no cambian nunca.
 */
@Dao
internal interface PurchaseDao {
    /**
     * Las compras con fecha de negocio entre [startDate] y [endDate], las dos incluidas, de la más
     * nueva a la más antigua. En la misma fecha, la registrada después va primero.
     *
     * Las fechas son texto `YYYY-MM-DD`, cuyo orden es el de las fechas. El filtro por los dos
     * estados no descarta nada: deja que SQLite busque por el índice `(status, business_date)` en
     * vez de recorrer la tabla cada vez que la lista vuelve a emitir.
     */
    @Query(
        "SELECT * FROM purchases WHERE status IN ('ACTIVE', 'VOIDED') " +
            "AND business_date BETWEEN :startDate AND :endDate " +
            "ORDER BY business_date DESC, id DESC",
    )
    fun observeIn(startDate: String, endDate: String): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchases WHERE id = :id")
    fun observeById(id: Long): Flow<PurchaseEntity?>

    @Query("SELECT * FROM purchases WHERE id = :id")
    suspend fun findById(id: Long): PurchaseEntity?

    /** Devuelve el id nuevo. El repositorio la guarda activa y sin datos de anulación. */
    @Insert
    suspend fun insert(purchase: PurchaseEntity): Long

    /**
     * Anula una compra activa: el estado pasa a `VOIDED` con el instante, el motivo y la nota.
     * Devuelve 0 si no existe o ya estaba anulada, porque un documento se anula una sola vez, y no
     * toca ninguna otra columna.
     */
    @Query(
        "UPDATE purchases SET status = 'VOIDED', voided_at = :voidedAt, " +
            "void_reason = :voidReason, void_note = :voidNote " +
            "WHERE id = :id AND status = 'ACTIVE'",
    )
    suspend fun markVoided(id: Long, voidedAt: Long, voidReason: String, voidNote: String?): Int
}
