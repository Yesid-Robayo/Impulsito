package io.github.yesidrobayo.shopledger.data.local.returns

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `purchase_returns`. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso.
 *
 * Una devolución confirmada no se edita ni se borra: la única escritura después de insertarla es la
 * anulación, que toca solo el estado y sus tres columnas. Sin `REPLACE`: un `INSERT OR REPLACE`
 * borraría la devolución que choca sin pasar por la guarda de borrado.
 */
@Dao
internal interface PurchaseReturnDao {
    /** Las devoluciones al proveedor, activas y anuladas, en el orden en que se registraron. */
    @Query("SELECT * FROM purchase_returns WHERE supplier_id = :supplierId ORDER BY id")
    fun observeBySupplier(supplierId: Long): Flow<List<PurchaseReturnEntity>>

    @Query("SELECT * FROM purchase_returns WHERE id = :id")
    suspend fun findById(id: Long): PurchaseReturnEntity?

    /** Devuelve el id nuevo. El repositorio la guarda activa y sin datos de anulación. */
    @Insert
    suspend fun insert(purchaseReturn: PurchaseReturnEntity): Long

    /**
     * Anula una devolución activa. Devuelve 0 si no existe o ya estaba anulada, porque un documento
     * se anula una sola vez, y no toca ninguna otra columna.
     */
    @Query(
        "UPDATE purchase_returns SET status = 'VOIDED', voided_at = :voidedAt, " +
            "void_reason = :voidReason, void_note = :voidNote " +
            "WHERE id = :id AND status = 'ACTIVE'",
    )
    suspend fun markVoided(id: Long, voidedAt: Long, voidReason: String, voidNote: String?): Int
}
