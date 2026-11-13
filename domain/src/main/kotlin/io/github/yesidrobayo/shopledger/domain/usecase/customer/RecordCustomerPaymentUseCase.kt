package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntryDraft
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerDueRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerPaymentIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerPaymentRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.PaymentAllocation
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import kotlin.time.Instant

/**
 * Lo que la persona registra al recibir un abono de [customerId].
 *
 * - [amount] es lo que el cliente entrega, en positivo y en la moneda del negocio.
 * - [moneyAccountId] es la cuenta donde entra el dinero, o `null` si la persona no usa cuentas: el
 *   abono queda solo en el libro del cliente.
 * - [date] es la fecha del abono, o `null` para hoy. Puede ser pasada, nunca futura.
 * - [note] es libre, hasta 200 caracteres.
 */
data class CustomerPaymentRequest(
    val customerId: Long,
    val amount: Money,
    val moneyAccountId: Long?,
    val date: BusinessDate?,
    val note: String?,
)

/** Cómo termina registrar un abono. Salvo [Recorded], no se escribió nada. */
sealed interface RecordCustomerPaymentResult {
    /**
     * El abono quedó en el libro con el id [entryId], y el cliente debe [balanceAfter]: negativo es
     * saldo a favor. [movementId] es su entrada en la cuenta de dinero, si se eligió una.
     * [allocation] dice qué cargos cubrió, del más antiguo al más nuevo, y cuánto quedó a favor.
     */
    data class Recorded(
        val entryId: Long,
        val movementId: Long?,
        val balanceAfter: Money,
        val allocation: PaymentAllocation,
    ) : RecordCustomerPaymentResult

    /** El cliente ya no existe. */
    data object CustomerNotFound : RecordCustomerPaymentResult

    /** El monto no sirve como abono: en cero, negativo o por encima del tope. */
    data class InvalidAmount(val issue: CustomerPaymentIssue) : RecordCustomerPaymentResult

    /** La fila no entra en el libro, por [issue]: una nota muy larga o un saldo que desborda. */
    data class Invalid(val issue: CustomerEntryIssue) : RecordCustomerPaymentResult

    /** La fecha elegida es posterior a hoy en la zona del negocio. */
    data object FutureDate : RecordCustomerPaymentResult

    /** La cuenta de dinero elegida ya no existe. */
    data object AccountNotFound : RecordCustomerPaymentResult

    /** La cuenta elegida está archivada: no recibe dinero nuevo. La persona elige otra. */
    data object AccountArchived : RecordCustomerPaymentResult

    /**
     * La cuenta elegida lleva otra moneda que el negocio. El abono va en la moneda del negocio, y
     * mezclar las dos unidades daría un saldo sin sentido: la persona elige una cuenta del negocio.
     */
    data object AccountInOtherCurrency : RecordCustomerPaymentResult

    /** El saldo de la cuenta no cabría en un entero de 64 bits con este abono. */
    data object AccountOverflow : RecordCustomerPaymentResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId], así que no hay cómo fecharlo. */
    data class UnknownTimeZone(val storedZoneId: String) : RecordCustomerPaymentResult

    /** La moneda sigue sin confirmar: ningún monto se guarda antes. Lo escrito se conserva. */
    data object CurrencyNotConfirmed : RecordCustomerPaymentResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : RecordCustomerPaymentResult
}

/**
 * Registra lo que un cliente paga después, o lo que deja por adelantado, en una sola transacción:
 * un `PAYMENT` en su libro, que baja su saldo, y, si la persona eligió una cuenta, un
 * `CUSTOMER_PAYMENT` que sube el saldo de esa cuenta.
 *
 * - **Nada se edita.** Los cargos quedan como estaban: el abono es una fila más, y lo que cubre de
 *   cada cargo es una lectura, del más antiguo al más nuevo.
 * - **Un abono mayor que la deuda se acepta.** Lo que sobra queda a favor del cliente, sin códigos
 *   ni tarjetas, y la próxima venta a crédito lo consume. Sirve igual para un anticipo.
 * - **La cuenta es opcional.** Sin cuenta no se escribe ningún movimiento de dinero, y quien no usa
 *   cuentas no ve ningún paso más.
 * - **Un cliente archivado o anonimizado también abona**, porque su deuda sigue contando.
 * - **La fecha puede ser pasada**, nunca futura: hoy lleva el instante actual, y un día anterior, las
 *   12:00 de ese día en la zona del negocio. El orden del libro sigue siendo el de llegada.
 * - **El primer abono bloquea la moneda**, en su misma transacción, como cualquier registro con
 *   dinero.
 */
class RecordCustomerPaymentUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val customers: CustomerRepository,
    private val ledger: CustomerLedgerRepository,
    private val accounts: MoneyAccountRepository,
    private val money: MoneyLedgerWriter,
    private val clock: Clock,
) {
    private val writer = CustomerLedgerWriter(ledger)
    private val currencyLock = CurrencyLock(settings)

    suspend operator fun invoke(request: CustomerPaymentRequest): RecordCustomerPaymentResult =
        storageSafe(RecordCustomerPaymentResult::StorageFailed) {
            transactions.write { record(request) }
        }

    private suspend fun record(
        request: CustomerPaymentRequest,
    ): TxResult<RecordCustomerPaymentResult> {
        CustomerPaymentRules.check(request.amount)?.let { issue ->
            return reject(RecordCustomerPaymentResult.InvalidAmount(issue))
        }
        if (customers.findById(request.customerId) == null) {
            return reject(RecordCustomerPaymentResult.CustomerNotFound)
        }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return reject(RecordCustomerPaymentResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val time = recordTime(calendar, request.date, now)
            ?: return reject(RecordCustomerPaymentResult.FutureDate)

        // Justo antes de escribir: el primer registro con dinero bloquea la moneda en esta misma
        // transacción, y si el abono se deshace, el bloqueo también.
        val currency = when (val lock = currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> lock.currency

            CurrencyLockResult.Unconfirmed ->
                return reject(RecordCustomerPaymentResult.CurrencyNotConfirmed)
        }
        request.moneyAccountId?.let { accountId ->
            accountIssue(accountId, currency)?.let { return reject(it) }
        }

        // El reparto se lee antes de agregar el abono: son los cargos que estaban pendientes.
        val dues = CustomerDueRules.duesOf(ledger.findByCustomer(request.customerId))
        val draft = CustomerEntryDraft(
            customerId = request.customerId,
            type = CustomerLedgerEntryType.PAYMENT,
            amount = -request.amount,
            source = CustomerEntrySource.None,
            dueDate = null,
            note = request.note,
        )
        val appended = when (val outcome = writer.append(draft, time)) {
            is CustomerEntryOutcome.Appended -> outcome

            is CustomerEntryOutcome.Rejected ->
                return reject(RecordCustomerPaymentResult.Invalid(outcome.issue))

            // Un abono no compensa ninguna fila: estos tres no pueden salir de agregarlo.
            CustomerEntryOutcome.EntryNotFound,
            CustomerEntryOutcome.AlreadyCompensated,
            CustomerEntryOutcome.NotReversible,
            -> error("Un abono no compensa ninguna fila")
        }
        val movementId = request.moneyAccountId?.let { accountId ->
            val movement = MoneyMovementDraft(
                accountId = accountId,
                type = MoneyMovementType.CUSTOMER_PAYMENT,
                amount = request.amount,
                origin = MoneyOrigin.CustomerPayment(appended.entryId),
            )
            when (val result = money.append(movement, time, now)) {
                is MoneyAppendResult.Appended -> result.movementId

                // Se comprobó la cuenta en esta misma transacción: no puede haber desaparecido.
                MoneyAppendResult.AccountNotFound ->
                    return reject(RecordCustomerPaymentResult.AccountNotFound)

                MoneyAppendResult.Overflow ->
                    return reject(RecordCustomerPaymentResult.AccountOverflow)
            }
        }
        return TxResult.Commit(
            RecordCustomerPaymentResult.Recorded(
                entryId = appended.entryId,
                movementId = movementId,
                balanceAfter = appended.balanceAfter,
                allocation = CustomerPaymentRules.allocate(dues, request.amount),
            ),
        )
    }

    /**
     * Por qué la cuenta [accountId] no puede recibir el abono, o `null` si puede: tiene que existir,
     * estar vigente y llevar la moneda del negocio, con sus mismos decimales.
     */
    private suspend fun accountIssue(
        accountId: Long,
        currency: BusinessCurrency,
    ): RecordCustomerPaymentResult? {
        val account = accounts.findById(accountId)
            ?: return RecordCustomerPaymentResult.AccountNotFound
        return when {
            account.isArchived -> RecordCustomerPaymentResult.AccountArchived

            account.currency != AccountCurrency.of(currency) ->
                RecordCustomerPaymentResult.AccountInOtherCurrency

            else -> null
        }
    }

    /**
     * El instante y la fecha del abono: ahora, si la fecha es hoy o no se eligió; las 12:00 de ese
     * día, si es pasada; o `null` si es futura.
     */
    private fun recordTime(
        calendar: BusinessCalendar,
        chosen: BusinessDate?,
        now: Instant,
    ): RecordTime? {
        val today = calendar.stamp(now)
        return when {
            chosen == null || chosen == today.businessDate -> today
            chosen > today.businessDate -> null
            else -> calendar.stampBackdated(chosen)
        }
    }

    private fun reject(result: RecordCustomerPaymentResult): TxResult<RecordCustomerPaymentResult> =
        TxResult.Rollback(result)
}
