package io.github.yesidrobayo.shopledger.ui.payable

import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObservePaymentChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.PaymentChoices
import io.github.yesidrobayo.shopledger.domain.usecase.payable.ObservePayablesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payable.ObserveSupplierStatementUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayablesLoad
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierStatement
import io.github.yesidrobayo.shopledger.domain.usecase.payable.VoidSupplierPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.VoidSupplierPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Lo que las cuentas por pagar le piden al dominio. Es una interfaz para que las pruebas de los
 * ViewModel den respuestas fijas sin una base.
 */
interface PayableOperations {
    /** La lista: cuánto se debe, qué vence y qué está vencido, por proveedor. */
    fun payables(): Flow<PayablesLoad>

    /** El extracto de un proveedor, o `null` si no existe. */
    fun statement(supplierId: Long): Flow<SupplierStatement?>

    /** El calendario del negocio, para saber qué día es hoy y qué está vencido. */
    fun calendar(): Flow<BusinessCalendarResult>

    /** Las cuentas de dinero de las que puede salir un pago, y si la persona las lleva. */
    fun paymentChoices(): Flow<PaymentChoices>

    suspend fun pay(request: SupplierPaymentRequest): PaySupplierResult

    suspend fun voidPayment(entryId: Long): VoidSupplierPaymentResult

    fun now(): Instant
}

/** Las operaciones sobre los casos de uso de cuentas por pagar y de dinero. */
class PayableUseCases(
    private val observePayables: ObservePayablesUseCase,
    private val observeStatement: ObserveSupplierStatementUseCase,
    private val calendarSource: BusinessCalendarSource,
    private val choices: ObservePaymentChoicesUseCase,
    private val paySupplier: PaySupplierUseCase,
    private val voidSupplierPayment: VoidSupplierPaymentUseCase,
    private val clockNow: () -> Instant,
) : PayableOperations {
    override fun payables(): Flow<PayablesLoad> = observePayables()

    override fun statement(supplierId: Long): Flow<SupplierStatement?> =
        observeStatement(supplierId)

    override fun calendar(): Flow<BusinessCalendarResult> = calendarSource.observe()

    override fun paymentChoices(): Flow<PaymentChoices> = choices()

    override suspend fun pay(request: SupplierPaymentRequest): PaySupplierResult =
        paySupplier(request)

    override suspend fun voidPayment(entryId: Long): VoidSupplierPaymentResult =
        voidSupplierPayment(entryId)

    override fun now(): Instant = clockNow()

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): PayableUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val calendar = BusinessCalendarSource(ports.settings)
            return PayableUseCases(
                observePayables = ObservePayablesUseCase(ports.payables, calendar, clock),
                observeStatement = ObserveSupplierStatementUseCase(
                    ports.suppliers,
                    ports.supplierLedger,
                ),
                calendarSource = calendar,
                choices = ObservePaymentChoicesUseCase(
                    ports.moduleUsage,
                    ports.paymentMethods,
                    ports.moneyAccounts,
                ),
                paySupplier = PaySupplierUseCase(
                    transactions = ports.transactions,
                    settings = ports.settings,
                    suppliers = ports.suppliers,
                    ledger = ports.supplierLedger,
                    clock = clock,
                    accounts = ports.moneyAccounts,
                    moneyLedger = ports.moneyLedger,
                ),
                voidSupplierPayment = VoidSupplierPaymentUseCase(
                    transactions = ports.transactions,
                    settings = ports.settings,
                    ledger = ports.supplierLedger,
                    clock = clock,
                    accounts = ports.moneyAccounts,
                    moneyLedger = ports.moneyLedger,
                ),
                clockNow = clock::now,
            )
        }
    }
}
