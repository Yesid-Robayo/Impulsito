package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.NewCustomerLedgerEntry
import kotlinx.coroutines.flow.Flow

/**
 * El libro de los clientes: agregar y leer, nada más. Ninguna función edita ni borra una fila: un
 * error se compensa con otra.
 *
 * Todo sale en el orden del libro, por id. [append] solo se llama dentro de
 * [TransactionRunner.write], y deja al cliente con el saldo de la fila en la misma transacción.
 */
interface CustomerLedgerRepository {
    /** Las filas de [customerId] en el orden del libro. Vuelve a emitir con cada fila nueva. */
    fun observeByCustomer(customerId: Long): Flow<List<CustomerLedgerEntry>>

    suspend fun findByCustomer(customerId: Long): List<CustomerLedgerEntry>

    suspend fun findById(id: Long): CustomerLedgerEntry?

    /** El saldo que dejó la última fila de [customerId], o `null` si no tiene ninguna. */
    suspend fun lastBalanceAfter(customerId: Long): Money?

    /** Los cargos a crédito de una venta, en el orden del libro. Su anulación no la lleva. */
    suspend fun findBySale(saleId: Long): List<CustomerLedgerEntry>

    /**
     * El saldo a favor que dejó la devolución [saleReturnId], en el orden del libro. Su anulación
     * no la lleva.
     */
    suspend fun findBySaleReturn(saleReturnId: Long): List<CustomerLedgerEntry>

    /** Si alguna fila ya compensa a [entryId]. */
    suspend fun isCompensated(entryId: Long): Boolean

    /** Agrega la fila, deja al cliente con su saldo después, y devuelve el id de la fila. */
    suspend fun append(entry: NewCustomerLedgerEntry): Long
}
