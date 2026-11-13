package io.github.yesidrobayo.shopledger.ui.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AdjustCustomerBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AdjustCustomerBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AnonymizeCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AnonymizeCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ArchiveCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerStatement
import io.github.yesidrobayo.shopledger.domain.usecase.customer.EditCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObserveCustomerDetailSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObserveCustomerStatementUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObservePaymentChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.PaymentChoices
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ReactivateCustomerUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ReadCustomerSalesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ReminderTemplateUseCases
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveReminderTemplateResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.VoidCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.VoidCustomerPaymentUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lo que el detalle de un cliente le pide al dominio. Es una interfaz para que las pruebas del
 * ViewModel den respuestas fijas sin una base.
 */
interface CustomerDetailOperations {
    /** El extracto del cliente, o `null` si no existe. */
    fun statement(customerId: Long): Flow<CustomerStatement?>

    /** El calendario del negocio, para saber qué día es hoy y qué está vencido. */
    fun calendar(): Flow<BusinessCalendarResult>

    /** Si el programa de puntos está activo: solo entonces se ven los puntos del cliente. */
    fun loyaltyEnabled(): Flow<Boolean>

    fun paymentChoices(): Flow<PaymentChoices>

    /** El nombre del negocio, que encabeza el extracto, o `null` si no se escribió. */
    fun businessName(): Flow<String?>

    /** Una marca que cambia con cada venta nueva o anulada: las ventas del cliente se releen. */
    fun salesChanges(): Flow<String>

    suspend fun salesPage(
        customerId: Long,
        after: SaleHistoryCursor?,
        limit: Int,
    ): List<SaleHistoryRow>

    /** Guarda lo editado de [customerId]; con [allowSameName] ya se vio el aviso de nombre repetido. */
    suspend fun saveCustomer(
        customerId: Long?,
        form: CustomerForm,
        allowSameName: Boolean,
    ): SaveCustomerResult

    suspend fun recordPayment(request: CustomerPaymentRequest): RecordCustomerPaymentResult

    /** La plantilla del recordatorio que la persona escribió en [languageTag], o `null`. */
    suspend fun reminderTemplate(languageTag: String): String?

    suspend fun saveReminderTemplate(
        languageTag: String,
        template: String,
    ): SaveReminderTemplateResult

    /** Vuelve al texto de los recursos; `false` si no pudo escribir. */
    suspend fun resetReminderTemplate(languageTag: String): Boolean

    suspend fun voidPayment(customerId: Long, entryId: Long): VoidCustomerPaymentResult

    suspend fun adjust(customerId: Long, amount: Money, note: String): AdjustCustomerBalanceResult

    suspend fun archive(customerId: Long): CustomerLifecycleResult

    suspend fun reactivate(customerId: Long): CustomerLifecycleResult

    suspend fun anonymize(customerId: Long): AnonymizeCustomerResult

    /**
     * Pide una copia nueva ahora, para que los datos borrados al anonimizar dejen de estar en la
     * copia más reciente. Las anteriores salen solas por retención.
     */
    suspend fun updateCopies()

    fun now(): Instant
}

/** Las operaciones del detalle sobre los casos de uso de clientes, abonos y cuentas. */
class CustomerDetailUseCases(
    private val observeStatement: ObserveCustomerStatementUseCase,
    private val calendarSource: BusinessCalendarSource,
    private val detailSettings: ObserveCustomerDetailSettingsUseCase,
    private val choices: ObservePaymentChoicesUseCase,
    private val sales: ReadCustomerSalesUseCase,
    private val edit: EditCustomerUseCase,
    private val reminders: ReminderTemplateUseCases,
    private val record: RecordCustomerPaymentUseCase,
    private val void: VoidCustomerPaymentUseCase,
    private val adjustBalance: AdjustCustomerBalanceUseCase,
    private val archiveUseCase: ArchiveCustomerUseCase,
    private val reactivateUseCase: ReactivateCustomerUseCase,
    private val anonymizeUseCase: AnonymizeCustomerUseCase,
    private val clockNow: () -> Instant,
    private val requestBackup: suspend () -> Unit,
) : CustomerDetailOperations {
    override fun statement(customerId: Long): Flow<CustomerStatement?> =
        observeStatement(customerId)

    override fun calendar(): Flow<BusinessCalendarResult> = calendarSource.observe()

    override fun loyaltyEnabled(): Flow<Boolean> =
        detailSettings().map { it.loyaltyEnabled }.distinctUntilChanged()

    override fun paymentChoices(): Flow<PaymentChoices> = choices()

    override fun businessName(): Flow<String?> =
        detailSettings().map { it.businessName }.distinctUntilChanged()

    override fun salesChanges(): Flow<String> = sales.changes()

    override suspend fun salesPage(
        customerId: Long,
        after: SaleHistoryCursor?,
        limit: Int,
    ): List<SaleHistoryRow> = sales.page(customerId, after, limit)

    override suspend fun saveCustomer(
        customerId: Long?,
        form: CustomerForm,
        allowSameName: Boolean,
    ): SaveCustomerResult = if (customerId == null) {
        // El detalle solo edita: un cliente nuevo nace en la lista.
        SaveCustomerResult.NotFound
    } else {
        edit(customerId, form, allowSameName)
    }

    override suspend fun recordPayment(request: CustomerPaymentRequest) = record(request)

    override suspend fun reminderTemplate(languageTag: String): String? =
        reminders.read(languageTag)

    override suspend fun saveReminderTemplate(languageTag: String, template: String) =
        reminders.save(languageTag, template)

    override suspend fun resetReminderTemplate(languageTag: String): Boolean =
        reminders.reset(languageTag)

    override suspend fun voidPayment(customerId: Long, entryId: Long) = void(customerId, entryId)

    override suspend fun adjust(customerId: Long, amount: Money, note: String) =
        adjustBalance(customerId, amount, note)

    override suspend fun archive(customerId: Long) = archiveUseCase(customerId)

    override suspend fun reactivate(customerId: Long) = reactivateUseCase(customerId)

    override suspend fun anonymize(customerId: Long) = anonymizeUseCase(customerId)

    override suspend fun updateCopies() = requestBackup()

    override fun now(): Instant = clockNow()

    companion object {
        /** Arma las operaciones con los puertos de la base abierta. */
        fun of(dependencies: ScreenDependencies): CustomerDetailUseCases {
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val tx = ports.transactions
            val money = MoneyLedgerWriter(ports.moneyAccounts, ports.moneyLedger)
            return CustomerDetailUseCases(
                observeStatement = ObserveCustomerStatementUseCase(
                    ports.customers,
                    ports.customerLedger,
                ),
                calendarSource = BusinessCalendarSource(ports.settings),
                detailSettings = ObserveCustomerDetailSettingsUseCase(ports.settings),
                choices = ObservePaymentChoicesUseCase(
                    ports.moduleUsage,
                    ports.paymentMethods,
                    ports.moneyAccounts,
                ),
                sales = ReadCustomerSalesUseCase(ports.customerSales, ports.saleHistory),
                edit = EditCustomerUseCase(tx, ports.customers, clock),
                reminders = ReminderTemplateUseCases(tx, ports.messageTemplates, clock),
                record = RecordCustomerPaymentUseCase(
                    transactions = tx,
                    settings = ports.settings,
                    customers = ports.customers,
                    ledger = ports.customerLedger,
                    accounts = ports.moneyAccounts,
                    money = money,
                    clock = clock,
                ),
                void = VoidCustomerPaymentUseCase(
                    transactions = tx,
                    settings = ports.settings,
                    ledger = ports.customerLedger,
                    movements = ports.moneyLedger,
                    money = money,
                    clock = clock,
                ),
                adjustBalance = AdjustCustomerBalanceUseCase(
                    transactions = tx,
                    settings = ports.settings,
                    customers = ports.customers,
                    ledger = ports.customerLedger,
                    clock = clock,
                ),
                archiveUseCase = ArchiveCustomerUseCase(tx, ports.customers, clock),
                reactivateUseCase = ReactivateCustomerUseCase(tx, ports.customers, clock),
                anonymizeUseCase = AnonymizeCustomerUseCase(tx, ports.customers, clock),
                clockNow = clock::now,
                requestBackup = { dependencies.container.data.backupScheduler.backupNow() },
            )
        }
    }
}
