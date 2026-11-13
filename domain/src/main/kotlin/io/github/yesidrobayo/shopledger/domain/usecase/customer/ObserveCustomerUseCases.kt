package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Los clientes vigentes y los archivados, cada grupo en el orden de su nombre, y lo que hay por
 * cobrar.
 *
 * [receivable] suma los saldos positivos de todos, también de los archivados y los anonimizados:
 * archivar o anonimizar no perdona una deuda. Un saldo a favor no la resta, porque es de otra
 * persona. Es `null` solo si la suma desborda, con cifras absurdas.
 */
data class CustomersOverview(
    val active: List<Customer>,
    val archived: List<Customer>,
    val receivable: Money?,
)

/** La lista de clientes. Vuelve a emitir con cada cambio confirmado de clientes o de sus saldos. */
class ObserveCustomersUseCase(private val customers: CustomerRepository) {
    operator fun invoke(): Flow<CustomersOverview> = combine(
        customers.observe(ArchiveFilter.ACTIVE),
        customers.observe(ArchiveFilter.ARCHIVED),
    ) { active, archived ->
        CustomersOverview(
            active = active,
            archived = archived,
            receivable = (active + archived)
                .map { it.balance }
                .filter { it > Money.ZERO }
                .sumOrNull(),
        )
    }.distinctUntilChanged()
}

/** Un cliente, o `null` si no existe, por ejemplo porque se acaba de eliminar. */
class ObserveCustomerUseCase(private val customers: CustomerRepository) {
    operator fun invoke(customerId: Long): Flow<Customer?> =
        customers.observeById(customerId).distinctUntilChanged()
}
