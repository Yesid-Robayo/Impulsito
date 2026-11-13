package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAppendResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyLedgerWriter
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import kotlin.time.Instant

/**
 * Lo que la persona pide registrar como pago a un proveedor: [amount] en unidad menor, mayor que
 * cero, en la fecha de negocio [date], o `null` para hoy. [note] es opcional. [moneyAccountId] es la
 * cuenta de la que salió el dinero, o `null` si la persona no usa cuentas.
 */
data class SupplierPaymentRequest(
    val supplierId: Long,
    val amount: Money,
    val date: BusinessDate? = null,
    val note: String? = null,
    val moneyAccountId: Long? = null,
)

/**
 * Registra un pago a un proveedor, en una sola transacción: un `PAYMENT` en su libro, con el monto
 * en negativo, que baja lo que se le debe, y, si la persona eligió una cuenta, un `SUPPLIER_PAYMENT`
 * que baja esa cuenta y apunta al pago. Sin cuenta no se escribe nada en el libro de dinero.
 *
 * - **La compra no se edita.** Lo pagado al confirmarla no cambia: el pago vive solo en el libro.
 * - **Se puede pagar de más.** El saldo queda negativo, que es crédito con el proveedor, y la
 *   pantalla lo dice antes de confirmar.
 * - **La fecha puede ser pasada**, nunca futura. La fila toma su lugar en el orden del libro al
 *   registrarse, como cualquier otra.
 * - **Un proveedor archivado** con saldo se puede pagar: archivar no borra la deuda.
 * - **La cuenta va en la moneda del negocio**, y vigente. El pago está en la moneda del libro, y
 *   escribirlo en una cuenta de otra moneda mezclaría unidades.
 * - **La moneda se bloquea con el primer pago**, si todavía no lo estaba, en la misma transacción.
 *
 * Si cualquier paso falla, no queda nada: un pago a medias dejaría el saldo diciendo algo que no
 * pasó.
 */
class PaySupplierUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val suppliers: SupplierRepository,
    private val ledger: SupplierLedgerRepository,
    private val clock: Clock,
    private val accounts: MoneyAccountRepository? = null,
    private val moneyLedger: MoneyLedgerRepository? = null,
) {
    private val currencyLock = CurrencyLock(settings)

    suspend operator fun invoke(request: SupplierPaymentRequest): PaySupplierResult = try {
        transactions.write { pay(request) }
    } catch (failure: StorageFailureException) {
        PaySupplierResult.StorageFailed(failure.failure)
    }

    private suspend fun pay(request: SupplierPaymentRequest): TxResult<PaySupplierResult> {
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
        shapeRejection(request, note)?.let { return TxResult.Rollback(it) }
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(PaySupplierResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val time = recordTime(calendar, request.date, now)
            ?: return TxResult.Rollback(PaySupplierResult.FutureDate)
        suppliers.findById(request.supplierId)
            ?: return TxResult.Rollback(PaySupplierResult.SupplierNotFound)
        val currency = when (val lock = currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> AccountCurrency.of(lock.currency)

            CurrencyLockResult.Unconfirmed ->
                return TxResult.Rollback(PaySupplierResult.CurrencyNotConfirmed)
        }
        request.moneyAccountId?.let { id ->
            accountRejection(id, currency)?.let { return TxResult.Rollback(it) }
        }
        val amount = -request.amount
        val entryId = ledger.appendChained(request.supplierId, amount) { balanceAfter ->
            NewSupplierLedgerEntry(
                supplierId = request.supplierId,
                type = SupplierLedgerEntryType.PAYMENT,
                amount = amount,
                balanceAfter = balanceAfter,
                note = note,
                occurredAt = time.occurredAt,
                businessDate = time.businessDate,
                createdAt = now,
            )
        } ?: return TxResult.Rollback(PaySupplierResult.BalanceTooLarge)
        request.moneyAccountId?.let { accountId ->
            val draft = MoneyMovementDraft(
                accountId = accountId,
                type = MoneyMovementType.SUPPLIER_PAYMENT,
                amount = amount,
                origin = MoneyOrigin.SupplierPayment(entryId),
            )
            when (writer().append(draft, time, now)) {
                is MoneyAppendResult.Appended -> Unit

                MoneyAppendResult.AccountNotFound ->
                    return TxResult.Rollback(PaySupplierResult.MoneyAccountNotFound)

                MoneyAppendResult.Overflow ->
                    return TxResult.Rollback(PaySupplierResult.AccountBalanceTooLarge)
            }
        }
        return TxResult.Commit(PaySupplierResult.Paid(entryId))
    }

    /** Por qué no se puede pagar desde la cuenta [id], o `null` si se puede. */
    private suspend fun accountRejection(id: Long, currency: AccountCurrency): PaySupplierResult? {
        val repository = requireNotNull(accounts) { "Pagar desde una cuenta pide las cuentas" }
        val found = repository.findById(id) ?: return PaySupplierResult.MoneyAccountNotFound
        return when {
            found.isArchived -> PaySupplierResult.MoneyAccountArchived
            found.currency != currency -> PaySupplierResult.MoneyAccountOtherCurrency
            else -> null
        }
    }

    private fun writer() = MoneyLedgerWriter(
        requireNotNull(accounts) { "Pagar desde una cuenta pide las cuentas" },
        requireNotNull(moneyLedger) { "Pagar desde una cuenta pide el libro de dinero" },
    )

    /** Lo que se rechaza sin leer la base: la forma del pago. */
    private fun shapeRejection(request: SupplierPaymentRequest, note: String?): PaySupplierResult? =
        when {
            request.amount <= Money.ZERO -> PaySupplierResult.AmountNotPositive
            !Limit.DOCUMENT_TOTAL.allows(request.amount.minor) -> PaySupplierResult.AmountTooLarge
            (note?.length ?: 0) > MAX_NOTE_LENGTH -> PaySupplierResult.NoteTooLong
            else -> null
        }

    /** Ahora, si la fecha es hoy o no se eligió; las 12:00 de ese día, si es pasada; o `null`. */
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

    private companion object {
        /** Largo máximo de la nota de una fila del libro. */
        const val MAX_NOTE_LENGTH = 200
    }
}

/**
 * Resultado de [PaySupplierUseCase]. Salvo [Paid], no se escribió nada: lo que la persona escribió
 * sigue en la hoja, intacto.
 */
sealed interface PaySupplierResult {
    /** El pago quedó como la fila [entryId] del libro del proveedor. */
    data class Paid(val entryId: Long) : PaySupplierResult

    /** El monto es cero o negativo. */
    data object AmountNotPositive : PaySupplierResult

    /** El monto pasa del máximo de un documento. */
    data object AmountTooLarge : PaySupplierResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : PaySupplierResult

    /** La fecha elegida es futura. */
    data object FutureDate : PaySupplierResult

    /** El proveedor ya no existe. */
    data object SupplierNotFound : PaySupplierResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar el pago. */
    data class UnknownTimeZone(val storedZoneId: String) : PaySupplierResult

    /** La moneda del negocio sigue sin confirmar: sin ella, un monto no tiene unidad. */
    data object CurrencyNotConfirmed : PaySupplierResult

    /** El saldo que dejaría no cabe en un monto. Solo sale con cifras absurdas. */
    data object BalanceTooLarge : PaySupplierResult

    /** La cuenta de dinero elegida ya no existe. */
    data object MoneyAccountNotFound : PaySupplierResult

    /** La cuenta de dinero elegida está archivada: no recibe movimientos nuevos. */
    data object MoneyAccountArchived : PaySupplierResult

    /** La cuenta de dinero elegida tiene otra moneda que la del negocio. */
    data object MoneyAccountOtherCurrency : PaySupplierResult

    /** El saldo de la cuenta de dinero no cabría en un monto. Solo sale con cifras absurdas. */
    data object AccountBalanceTooLarge : PaySupplierResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : PaySupplierResult
}
