package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerDueRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerDues
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * La cuenta de un cliente: sus filas del libro, de la más reciente a la más antigua como las
 * muestra el extracto, y lo que tiene pendiente por vencimiento. El saldo de cada fila es el que
 * guardó al escribirse, sin recalcular nada.
 */
data class CustomerStatement(
    val customer: Customer,
    val entries: List<CustomerLedgerEntry>,
    val dues: CustomerDues,
)

/**
 * El extracto de un cliente. Emite `null` si el cliente no existe, y vuelve a emitir con cada fila
 * nueva de su libro o cambio de sus datos.
 */
class ObserveCustomerStatementUseCase(
    private val customers: CustomerRepository,
    private val ledger: CustomerLedgerRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(customerId: Long): Flow<CustomerStatement?> =
        customers.observeById(customerId).flatMapLatest { customer ->
            if (customer == null) {
                flowOf(null)
            } else {
                ledger.observeByCustomer(customerId).map { rows ->
                    CustomerStatement(customer, rows.reversed(), CustomerDueRules.duesOf(rows))
                }
            }
        }.distinctUntilChanged()
}
