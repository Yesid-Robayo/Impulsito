package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.CustomerSalesSource
import io.github.yesidrobayo.shopledger.domain.port.ModuleUsageSource
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.SaleHistorySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Con qué se puede registrar un abono: los métodos de pago vigentes, sin los de cobro a crédito, y
 * las cuentas de dinero vigentes. [accountsInUse] dice si la persona lleva cuentas: sin ellas, la
 * hoja del abono no pregunta método ni cuenta, y el abono queda solo en el libro del cliente.
 */
data class PaymentChoices(
    val accountsInUse: Boolean = false,
    val methods: List<PaymentMethodRecord> = emptyList(),
    val accounts: List<MoneyAccount> = emptyList(),
)

/** Los métodos y las cuentas con que se registra un abono, de nuevo con cada cambio. */
class ObservePaymentChoicesUseCase(
    private val usage: ModuleUsageSource,
    private val methods: PaymentMethodRepository,
    private val accounts: MoneyAccountRepository,
) {
    operator fun invoke(): Flow<PaymentChoices> = combine(
        usage.observe().map { BusinessTool.MONEY_ACCOUNTS in it }.distinctUntilChanged(),
        methods.observe(ArchiveFilter.ACTIVE),
        accounts.observe(ArchiveFilter.ACTIVE),
    ) { inUse, methodList, accountList ->
        PaymentChoices(
            accountsInUse = inUse,
            // Un abono no puede quedar a crédito: sería deber lo que se acaba de pagar.
            methods = methodList.filter { it.details.kind != PaymentMethodKind.ON_ACCOUNT },
            accounts = accountList,
        )
    }.distinctUntilChanged()
}

/**
 * Lo que el detalle de un cliente lee de los ajustes: si el programa de puntos está activo y el
 * nombre del negocio, que encabeza el extracto.
 */
data class CustomerDetailSettings(val loyaltyEnabled: Boolean, val businessName: String?)

/** Los ajustes que usa el detalle de un cliente, de nuevo cuando cambian. */
class ObserveCustomerDetailSettingsUseCase(private val settings: AppSettingsRepository) {
    operator fun invoke(): Flow<CustomerDetailSettings> = settings.observe()
        .map { CustomerDetailSettings(it.loyalty.enabled, it.profile.businessName) }
        .distinctUntilChanged()
}

/** Las ventas de un cliente por tramos, y la marca que dice cuándo releerlas. */
class ReadCustomerSalesUseCase(
    private val sales: CustomerSalesSource,
    private val history: SaleHistorySource,
) {
    suspend fun page(
        customerId: Long,
        after: SaleHistoryCursor?,
        limit: Int,
    ): List<SaleHistoryRow> = sales.page(customerId, after, limit)

    /** Cambia con cada venta nueva o anulada. */
    fun changes(): Flow<String> = history.observeChanges()
}
