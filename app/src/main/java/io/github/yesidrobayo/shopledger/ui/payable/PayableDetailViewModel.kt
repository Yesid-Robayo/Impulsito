package io.github.yesidrobayo.shopledger.ui.payable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierStatement
import io.github.yesidrobayo.shopledger.domain.usecase.payable.VoidSupplierPaymentResult
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Los símbolos y decimales con que se leen los montos que escribe la persona. */
data class PayableNumberFormat(val moneySymbols: NumberSymbols, val currencyDecimals: Int)

/**
 * El detalle de un proveedor en cuentas por pagar: lo que se le debe, su vencimiento, su libro y
 * los pagos.
 *
 * Nada se edita en el libro. Un pago o su anulación son filas nuevas, cada una en su propia
 * transacción del dominio, y el libro se vuelve a leer solo al confirmarse. El saldo de cada fila
 * es el que guardó al escribirse. Una hoja que falla conserva lo escrito y dice el motivo dentro.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayableDetailViewModel(
    private val supplierId: Long,
    private val operations: PayableOperations,
    private val numbers: PayableNumberFormat,
) : ViewModel() {
    private val state = MutableStateFlow(PayableDetailUiState())
    val uiState: StateFlow<PayableDetailUiState> = state.asStateFlow()

    private val attempts = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            attempts.flatMapLatest {
                combine(operations.calendar(), operations.statement(supplierId)) { calendar, read ->
                    Read.Shown(calendar, read)
                }.catch<Read> { emit(Read.Failed) }
            }.collect(::show)
        }
        viewModelScope.launch {
            operations.paymentChoices().catch {}.collect { choices ->
                state.update { it.copy(choices = choices) }
            }
        }
    }

    fun onAction(action: PayableDetailAction) {
        when (action) {
            PayableDetailAction.Retry -> attempts.update { it + 1 }

            PayableDetailAction.OpenPayment -> openPayment()

            is PayableDetailAction.PaymentAmount -> editPayment {
                it.copy(amountText = action.text)
            }

            is PayableDetailAction.PaymentAccount -> editPayment {
                it.copy(accountId = action.accountId, notice = null)
            }

            is PayableDetailAction.PaymentDate -> editPayment { it.copy(date = action.date) }

            is PayableDetailAction.PaymentNote -> editPayment { it.copy(note = action.text) }

            PayableDetailAction.SavePayment -> savePayment()

            PayableDetailAction.DismissSheet -> state.update {
                if (it.payment?.working == true) it else it.copy(payment = null)
            }

            is PayableDetailAction.AskVoidPayment -> state.update {
                it.copy(voidingEntryId = action.entryId)
            }

            PayableDetailAction.ConfirmVoid -> confirmVoid()

            PayableDetailAction.DismissDialog -> state.update { it.copy(voidingEntryId = null) }

            PayableDetailAction.MessageShown -> state.update { it.copy(message = null) }

            // Las resuelve la pantalla con la navegación.
            PayableDetailAction.Back, PayableDetailAction.OpenSupplier,
            is PayableDetailAction.OpenPurchase, PayableDetailAction.ChooseZone,
            PayableDetailAction.ConfirmCurrency,
            -> Unit
        }
    }

    private fun show(read: Read) {
        var calendar: BusinessCalendar? = null
        val content = when (read) {
            Read.Failed -> PayableDetailContent.ReadError

            is Read.Shown -> {
                calendar = when (val result = read.calendar) {
                    is BusinessCalendarResult.Ready -> result.calendar
                    is BusinessCalendarResult.UnknownZone -> null
                }
                val statement = read.statement
                val ready = calendar
                when {
                    statement == null -> PayableDetailContent.Missing

                    ready == null -> PayableDetailContent.UnknownZone

                    else -> payableDetailView(statement, ready.businessDateOf(operations.now()))
                        ?.let { PayableDetailContent.Ready(it) }
                        ?: PayableDetailContent.ReadError
                }
            }
        }
        state.update {
            it.copy(
                content = content,
                today = calendar?.businessDateOf(operations.now()) ?: it.today,
                weekStartDay = calendar?.weekStartDay ?: it.weekStartDay,
                payment = it.payment?.let { sheet -> withPreview(sheet, content) },
            )
        }
    }

    private fun view(): PayableDetailView? =
        (state.value.content as? PayableDetailContent.Ready)?.view

    // ------------------------------------------------------------------ pago

    private fun openPayment() {
        val view = view() ?: return
        // La sugerencia es la deuda: lo más común es saldarla. Con saldo a favor o en cero, nada.
        val balance = view.supplier.balance
        val amountText = if (balance > Money.ZERO) {
            fixedPointInputText(balance.minor, numbers.currencyDecimals, numbers.moneySymbols)
        } else {
            ""
        }
        state.update {
            it.copy(
                payment = withPreview(SupplierPaymentSheet(amountText = amountText), it.content),
            )
        }
    }

    private fun editPayment(change: (SupplierPaymentSheet) -> SupplierPaymentSheet) {
        state.update { current ->
            val sheet = current.payment ?: return@update current
            if (sheet.working) return@update current
            current.copy(payment = withPreview(change(sheet), current.content))
        }
    }

    /** La hoja con lo que dejaría el monto escrito, si se puede leer. */
    private fun withPreview(
        sheet: SupplierPaymentSheet,
        content: PayableDetailContent,
    ): SupplierPaymentSheet {
        val balance = (content as? PayableDetailContent.Ready)?.view?.supplier?.balance
        val amount = (parseAmount(sheet.amountText) as? NumberInput.Value)?.value
        return sheet.copy(preview = previewOf(balance, amount))
    }

    private fun savePayment() {
        val current = state.value
        val sheet = current.payment ?: return
        if (sheet.working) return
        val amount = parseAmount(sheet.amountText)
        val errors = buildSet {
            amountError(amount)?.let(::add)
            if (sheet.note.trim().length > NOTE_MAX) add(PayableFormError.NOTE_TOO_LONG)
            val today = current.today
            if (today != null && sheet.date != null && sheet.date > today) {
                add(PayableFormError.DATE_FUTURE)
            }
        }
        if (errors.isNotEmpty() || amount !is NumberInput.Value) {
            setSheet { it.copy(errors = errors) }
            return
        }
        setSheet { it.copy(errors = emptySet(), notice = null, working = true) }
        val request = SupplierPaymentRequest(
            supplierId = supplierId,
            amount = amount.value,
            date = sheet.date,
            note = sheet.note.trim().ifEmpty { null },
            // Sin cuentas en uso el pago queda solo en el libro del proveedor.
            moneyAccountId = sheet.accountId.takeIf { current.choices.accountsInUse },
        )
        viewModelScope.launch {
            when (val result = operations.pay(request)) {
                is PaySupplierResult.Paid -> state.update {
                    it.copy(payment = null, message = PayableDetailMessage.PaymentRecorded)
                }

                // Ya no existe: el detalle lo muestra, y la hoja no tiene a quién pagar.
                PaySupplierResult.SupplierNotFound -> state.update { it.copy(payment = null) }

                PaySupplierResult.AmountNotPositive ->
                    failed(errors = setOf(PayableFormError.AMOUNT_ZERO))

                PaySupplierResult.AmountTooLarge, PaySupplierResult.BalanceTooLarge ->
                    failed(errors = setOf(PayableFormError.AMOUNT_TOO_HIGH))

                PaySupplierResult.NoteTooLong ->
                    failed(errors = setOf(PayableFormError.NOTE_TOO_LONG))

                PaySupplierResult.FutureDate ->
                    failed(errors = setOf(PayableFormError.DATE_FUTURE))

                PaySupplierResult.MoneyAccountNotFound, PaySupplierResult.MoneyAccountArchived,
                PaySupplierResult.MoneyAccountOtherCurrency,
                PaySupplierResult.AccountBalanceTooLarge,
                -> failed(notice = PayableFormNotice.AccountUnavailable)

                is PaySupplierResult.UnknownTimeZone ->
                    failed(notice = PayableFormNotice.UnknownZone)

                PaySupplierResult.CurrencyNotConfirmed ->
                    failed(notice = PayableFormNotice.CurrencyUnconfirmed)

                is PaySupplierResult.StorageFailed ->
                    failed(notice = PayableFormNotice.SaveFailed(result.failure.isOutOfSpace))
            }
        }
    }

    private fun setSheet(change: (SupplierPaymentSheet) -> SupplierPaymentSheet) {
        state.update { current ->
            current.payment?.let { current.copy(payment = change(it)) }
                ?: current
        }
    }

    private fun failed(
        errors: Set<PayableFormError> = emptySet(),
        notice: PayableFormNotice? = null,
    ) = setSheet { it.copy(errors = errors, notice = notice, working = false) }

    // ------------------------------------------------------------------ anular

    private fun confirmVoid() {
        val entryId = state.value.voidingEntryId ?: return
        state.update { it.copy(voidingEntryId = null) }
        viewModelScope.launch {
            val message = when (val result = operations.voidPayment(entryId)) {
                VoidSupplierPaymentResult.Voided -> PayableDetailMessage.PaymentVoided

                // Ya estaba anulado, o la fila cambió: el libro ya muestra cómo quedó.
                VoidSupplierPaymentResult.PaymentNotFound, VoidSupplierPaymentResult.NotAPayment,
                VoidSupplierPaymentResult.AlreadyVoided,
                -> null

                is VoidSupplierPaymentResult.StorageFailed ->
                    PayableDetailMessage.Failed(result.failure.isOutOfSpace)

                is VoidSupplierPaymentResult.UnknownTimeZone,
                VoidSupplierPaymentResult.BalanceTooLarge,
                VoidSupplierPaymentResult.AccountBalanceTooLarge,
                -> PayableDetailMessage.Failed(outOfSpace = false)
            }
            message?.let { shown -> state.update { it.copy(message = shown) } }
        }
    }

    // ------------------------------------------------------------------ montos

    private fun parseAmount(text: String): NumberInput<Money> = NumberInputParser.money(
        text,
        numbers.moneySymbols,
        numbers.currencyDecimals,
        Limit.DOCUMENT_TOTAL,
    )

    private fun amountError(amount: NumberInput<Money>): PayableFormError? = when (amount) {
        is NumberInput.Value -> PayableFormError.AMOUNT_ZERO.takeIf { amount.value <= Money.ZERO }
        NumberInput.Blank -> PayableFormError.AMOUNT_ZERO
        NumberInput.Malformed -> PayableFormError.AMOUNT_NOT_A_NUMBER
        is NumberInput.TooManyDecimals -> PayableFormError.AMOUNT_DECIMALS
        is NumberInput.TooLarge -> PayableFormError.AMOUNT_TOO_HIGH
    }

    private sealed interface Read {
        data class Shown(val calendar: BusinessCalendarResult, val statement: SupplierStatement?) :
            Read

        data object Failed : Read
    }

    private companion object {
        /** Largo máximo de la nota de un pago: el del libro del proveedor. */
        const val NOTE_MAX = 200
    }
}

/**
 * Lo que dejaría pagar [amount] sobre [balance]: lo que queda debiendo, o el saldo a favor si se
 * paga de más. `null` sin saldo, sin monto, con un monto en cero o si la resta no cabe.
 */
internal fun previewOf(balance: Money?, amount: Money?): PayablePreview? {
    if (balance == null || amount == null || amount <= Money.ZERO) return null
    val after = try {
        balance - amount
    } catch (_: ArithmeticException) {
        return null
    }
    return if (after <
        Money.ZERO
    ) {
        PayablePreview.CreditLeft(-after)
    } else {
        PayablePreview.Remaining(after)
    }
}
