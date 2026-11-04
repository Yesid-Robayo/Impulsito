package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * El libro de puntos: agregar y leer, nada más. Igual que el libro del cliente, ninguna función
 * edita ni borra una fila, y la inserción no usa `REPLACE`.
 *
 * Todo sale en el orden del libro, por `id`. [insert] solo se llama dentro de la transacción del
 * caso de uso, la misma en que el cliente queda con el `balance_after` de la fila.
 */
@Dao
internal interface LoyaltyLedgerEntryDao {
    @Query("SELECT * FROM loyalty_ledger_entries WHERE customer_id = :customerId ORDER BY id")
    fun observeByCustomer(customerId: Long): Flow<List<LoyaltyLedgerEntryEntity>>

    @Query("SELECT * FROM loyalty_ledger_entries WHERE id = :id")
    suspend fun findById(id: Long): LoyaltyLedgerEntryEntity?

    /** Las filas de un cliente en el orden del libro. */
    @Query("SELECT * FROM loyalty_ledger_entries WHERE customer_id = :customerId ORDER BY id")
    suspend fun findByCustomer(customerId: Long): List<LoyaltyLedgerEntryEntity>

    /**
     * El saldo de puntos que dejó la última fila del cliente, o `null` si no tiene ninguna: la base
     * del `balance_after` de la fila siguiente.
     */
    @Query(
        "SELECT balance_after FROM loyalty_ledger_entries WHERE customer_id = :customerId " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun lastBalanceAfter(customerId: Long): Long?

    /**
     * Lo que una venta ganó y canjeó: lo que su anulación revierte. Un compensatorio no lleva la
     * venta, solo la fila que anula, así que no sale aquí: lo dice [isCompensated].
     */
    @Query("SELECT * FROM loyalty_ledger_entries WHERE sale_id = :saleId ORDER BY id")
    suspend fun findBySale(saleId: Long): List<LoyaltyLedgerEntryEntity>

    /** Si alguna fila ya anula a [entryId]: una fila se anula una sola vez. */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM loyalty_ledger_entries WHERE reverses_entry_id = :entryId)",
    )
    suspend fun isCompensated(entryId: Long): Boolean

    /** Agrega la fila y devuelve su id, que fija su lugar en el orden del libro. */
    @Insert
    suspend fun insert(entry: LoyaltyLedgerEntryEntity): Long
}
