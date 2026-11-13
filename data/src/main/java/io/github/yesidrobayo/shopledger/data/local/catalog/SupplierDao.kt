package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `suppliers`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * La edición no toca `balance`: es la suma del libro del proveedor, y solo lo mueve [setBalance],
 * en la transacción que agrega la fila de ese libro. Las listas van por nombre normalizado y
 * después por `id`, porque un archivado puede repetir el nombre de otro proveedor.
 */
@Dao
internal interface SupplierDao {
    @Query("SELECT * FROM suppliers WHERE archived_at IS NULL ORDER BY normalized_name, id")
    fun observeActive(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers WHERE archived_at IS NOT NULL ORDER BY normalized_name, id")
    fun observeArchived(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers WHERE id = :id")
    suspend fun findById(id: Long): SupplierEntity?

    /** Los proveedores con saldo distinto de cero, también archivados, por nombre y luego `id`. */
    @Query("SELECT * FROM suppliers WHERE balance <> 0 ORDER BY normalized_name, id")
    fun observeWithBalance(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers WHERE id = :id")
    fun observeById(id: Long): Flow<SupplierEntity?>

    /**
     * Cuánto se le compró y si tiene historia, en una sola lectura. Cuenta todas sus compras, pero
     * solo suma las vigentes: una anulada ya no es dinero gastado.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM purchases WHERE supplier_id = :id) AS purchases_count, " +
            "(SELECT COALESCE(SUM(total), 0) FROM purchases " +
            "WHERE supplier_id = :id AND status = 'ACTIVE') AS active_total, " +
            "(EXISTS (SELECT 1 FROM purchases WHERE supplier_id = :id) " +
            "OR EXISTS (SELECT 1 FROM purchase_returns WHERE supplier_id = :id) " +
            "OR EXISTS (SELECT 1 FROM supplier_ledger_entries WHERE supplier_id = :id)) " +
            "AS has_history",
    )
    fun observeTotals(id: Long): Flow<SupplierTotalsRow>

    /** Sus compras, también las anuladas, de la más nueva a la más antigua y luego por `id`. */
    @Query(
        "SELECT id, status, total, invoice_reference, occurred_at, business_date FROM purchases " +
            "WHERE supplier_id = :id ORDER BY occurred_at DESC, id DESC",
    )
    fun observePurchases(id: Long): Flow<List<SupplierPurchaseRow>>

    /** Los productos vigentes que lo tienen como habitual, por nombre. */
    @Query(
        "SELECT id, name FROM products WHERE default_supplier_id = :id AND archived_at IS NULL " +
            "ORDER BY normalized_name, id",
    )
    fun observeUsualProducts(id: Long): Flow<List<SupplierProductRow>>

    /** Cuántos productos vigentes tiene cada proveedor como habitual. */
    @Query(
        "SELECT default_supplier_id AS supplier_id, COUNT(*) AS products FROM products " +
            "WHERE default_supplier_id IS NOT NULL AND archived_at IS NULL " +
            "GROUP BY default_supplier_id ORDER BY default_supplier_id",
    )
    fun observeUsualProductCounts(): Flow<List<SupplierProductCountRow>>

    /** El proveedor sin archivar con ese nombre: un archivado no reserva el suyo. */
    @Query(
        "SELECT * FROM suppliers WHERE normalized_name = :normalizedName AND archived_at IS NULL " +
            "ORDER BY id LIMIT 1",
    )
    suspend fun findActiveByNormalizedName(normalizedName: String): SupplierEntity?

    /**
     * Si el proveedor tiene historia: alguna compra, devolución a proveedor o fila de su libro de
     * cuenta, aunque esté anulada. Con historia ya no se borra: solo se archiva.
     */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM purchases WHERE supplier_id = :id) " +
            "OR EXISTS (SELECT 1 FROM purchase_returns WHERE supplier_id = :id) " +
            "OR EXISTS (SELECT 1 FROM supplier_ledger_entries WHERE supplier_id = :id)",
    )
    suspend fun hasHistory(id: Long): Boolean

    /** Devuelve el id nuevo. El saldo inicial lo pone el repositorio, en cero. */
    @Insert
    suspend fun insert(supplier: SupplierEntity): Long

    /** Cambia lo que la persona escribe. No toca el saldo, el archivado ni `created_at`. */
    @Query(
        "UPDATE suppliers SET name = :name, normalized_name = :normalizedName, phone = :phone, " +
            "email = :email, notes = :notes, payment_terms_days = :paymentTermsDays, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String,
        normalizedName: String,
        phone: String?,
        email: String?,
        notes: String?,
        paymentTermsDays: Long?,
        updatedAt: Long,
    ): Int

    /** Lo archiva con un instante, o lo reactiva con `null`. */
    @Query("UPDATE suppliers SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Lo que se le debe tras una fila nueva de su libro, su `balance_after`. Solo la llama la
     * escritura del libro del proveedor, en la misma transacción que agrega la fila.
     */
    @Query("UPDATE suppliers SET balance = :balance, updated_at = :updatedAt WHERE id = :id")
    suspend fun setBalance(id: Long, balance: Long, updatedAt: Long): Int

    /**
     * Elimina un proveedor sin historia. Su llave foránea deja sin proveedor habitual a sus
     * productos en la misma sentencia. Devuelve las filas borradas.
     */
    @Query("DELETE FROM suppliers WHERE id = :id")
    suspend fun delete(id: Long): Int
}
