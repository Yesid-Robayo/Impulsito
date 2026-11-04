package io.github.yesidrobayo.shopledger.data.local.payable

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * El libro de los proveedores: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una fila, y no debe haberla en ningún DAO: lo que se le debe a
 * cada proveedor es la suma de sus filas, y una fila cambiada lo dejaría mal sin que nada lo
 * delatara. La inserción no usa `REPLACE`, que borraría la fila que choca.
 *
 * Todo sale en el orden del libro, por `id`. [insert] solo se llama dentro de la transacción del
 * caso de uso, la misma en que el proveedor queda con el `balance_after` de la fila.
 */
@Dao
internal interface SupplierLedgerEntryDao {
    @Query("SELECT * FROM supplier_ledger_entries WHERE supplier_id = :supplierId ORDER BY id")
    fun observeBySupplier(supplierId: Long): Flow<List<SupplierLedgerEntryEntity>>

    @Query("SELECT * FROM supplier_ledger_entries WHERE id = :id")
    suspend fun findById(id: Long): SupplierLedgerEntryEntity?

    /** Las filas de un proveedor en el orden del libro: lo que da su extracto y su saldo. */
    @Query("SELECT * FROM supplier_ledger_entries WHERE supplier_id = :supplierId ORDER BY id")
    suspend fun findBySupplier(supplierId: Long): List<SupplierLedgerEntryEntity>

    /**
     * Lo que dejó la última fila del proveedor, o `null` si no tiene ninguna: la base del
     * `balance_after` de la fila siguiente.
     */
    @Query(
        "SELECT balance_after FROM supplier_ledger_entries WHERE supplier_id = :supplierId " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun lastBalanceAfter(supplierId: Long): Long?

    /**
     * La parte no pagada de una compra: lo que su anulación compensa. Un compensatorio no lleva la
     * compra, solo la fila que anula, así que no sale aquí: lo dice [isCompensated].
     */
    @Query("SELECT * FROM supplier_ledger_entries WHERE purchase_id = :purchaseId ORDER BY id")
    suspend fun findByPurchase(purchaseId: Long): List<SupplierLedgerEntryEntity>

    /** Si alguna fila ya anula a [entryId]: una fila se anula una sola vez. */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM supplier_ledger_entries WHERE reverses_entry_id = :entryId)",
    )
    suspend fun isCompensated(entryId: Long): Boolean

    /** Agrega la fila y devuelve su id, que fija su lugar en el orden del libro. */
    @Insert
    suspend fun insert(entry: SupplierLedgerEntryEntity): Long
}
