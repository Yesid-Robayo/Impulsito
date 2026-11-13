package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * El libro de los clientes: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una fila, y no debe haberla en ningún DAO: el saldo del cliente
 * es la suma de sus filas, y una fila cambiada lo dejaría mal sin que nada lo delatara. La
 * inserción no usa `REPLACE`, que borraría la fila que choca y escribiría otra en su lugar.
 *
 * Todo sale en el orden del libro, por `id`. [insert] solo se llama dentro de la transacción del
 * caso de uso, la misma en que el cliente queda con el `balance_after` de la fila.
 */
@Dao
internal interface CustomerLedgerEntryDao {
    @Query("SELECT * FROM customer_ledger_entries WHERE customer_id = :customerId ORDER BY id")
    fun observeByCustomer(customerId: Long): Flow<List<CustomerLedgerEntryEntity>>

    @Query("SELECT * FROM customer_ledger_entries WHERE id = :id")
    suspend fun findById(id: Long): CustomerLedgerEntryEntity?

    /** Las filas de un cliente en el orden del libro: lo que da su extracto y su saldo. */
    @Query("SELECT * FROM customer_ledger_entries WHERE customer_id = :customerId ORDER BY id")
    suspend fun findByCustomer(customerId: Long): List<CustomerLedgerEntryEntity>

    /**
     * El saldo que dejó la última fila del cliente, o `null` si no tiene ninguna: la base del
     * `balance_after` de la fila siguiente.
     */
    @Query(
        "SELECT balance_after FROM customer_ledger_entries WHERE customer_id = :customerId " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun lastBalanceAfter(customerId: Long): Long?

    /**
     * Los cargos a crédito de una venta: lo que su anulación compensa. Un compensatorio no lleva la
     * venta, solo la fila que anula, así que no sale aquí: lo dice [isCompensated].
     */
    @Query("SELECT * FROM customer_ledger_entries WHERE sale_id = :saleId ORDER BY id")
    suspend fun findBySale(saleId: Long): List<CustomerLedgerEntryEntity>

    /**
     * El saldo a favor de una devolución de venta: lo que su anulación compensa. Solo se lee al
     * anularla, poco frecuente: por eso la columna no lleva índice propio.
     */
    @Query(
        "SELECT * FROM customer_ledger_entries WHERE sale_return_id = :saleReturnId ORDER BY id",
    )
    suspend fun findBySaleReturn(saleReturnId: Long): List<CustomerLedgerEntryEntity>

    /** Si alguna fila ya anula a [entryId]: una fila se anula una sola vez. */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM customer_ledger_entries WHERE reverses_entry_id = :entryId)",
    )
    suspend fun isCompensated(entryId: Long): Boolean

    /** Agrega la fila y devuelve su id, que fija su lugar en el orden del libro. */
    @Insert
    suspend fun insert(entry: CustomerLedgerEntryEntity): Long
}
