package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.NewLoyaltyLedgerEntry
import kotlinx.coroutines.flow.Flow

/**
 * El libro de puntos de los clientes: agregar y leer, nada más. Ninguna función edita ni borra una
 * fila: un error se compensa con otra.
 *
 * Todo sale en el orden del libro, por id. [append] solo se llama dentro de
 * [TransactionRunner.write], y deja al cliente con el saldo de puntos de la fila en la misma
 * transacción.
 */
interface LoyaltyLedgerRepository {
    /** Las filas de [customerId] en el orden del libro. Vuelve a emitir con cada fila nueva. */
    fun observeByCustomer(customerId: Long): Flow<List<LoyaltyLedgerEntry>>

    suspend fun findById(id: Long): LoyaltyLedgerEntry?

    /** El saldo de puntos que dejó la última fila de [customerId], o `null` si no tiene ninguna. */
    suspend fun lastBalanceAfter(customerId: Long): Long?

    /** Lo que una venta ganó y canjeó, en el orden del libro. Su anulación no la lleva. */
    suspend fun findBySale(saleId: Long): List<LoyaltyLedgerEntry>

    /** Si alguna fila ya compensa a [entryId]. */
    suspend fun isCompensated(entryId: Long): Boolean

    /** Agrega la fila, deja al cliente con su saldo de puntos, y devuelve el id de la fila. */
    suspend fun append(entry: NewLoyaltyLedgerEntry): Long
}
