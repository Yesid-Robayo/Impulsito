package io.github.yesidrobayo.shopledger.ui.customer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerEntryIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerLedgerRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerPaymentIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerPaymentRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.ReminderRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AdjustCustomerBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AnonymizeCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerStatement
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveReminderTemplateResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.VoidCustomerPaymentResult
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Los símbolos y decimales con que se leen los montos que escribe la persona. */
data class CustomerNumberFormat(val moneySymbols: NumberSymbols, val currencyDecimals: Int)

/**
 * El detalle de un cliente: su saldo, lo vencido, su extracto y sus ventas, y lo que se hace con su
 * cuenta.
 *
 * Nada se edita en el libro. Un abono, un ajuste o la anulación de un abono son filas nuevas, cada
 * una en su propia transacción del dominio, y el extracto se vuelve a leer solo al confirmarse. El
 * saldo que muestra cada fila es el que guardó al escribirse.
 *
 * Una hoja que falla conserva lo escrito y muestra el motivo dentro de la hoja. Anonimizar llega
 * aquí ya verificado por la pantalla, si el bloqueo está activo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerDetailViewModel(
    private val customerId: Long,
    private val operations: CustomerDetailOperations,
    private val numbers: CustomerNumberFormat,
    private val reminderTexts: ReminderTexts = ReminderTexts("", ""),
    priceLists: () -> Flow<List<PriceList>> = { flowOf(emptyList()) },
) : ViewModel() {
    /** Las listas de precios vigentes, que la hoja ofrece si hay más de una. */
    private val activeLists = priceLists().catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val state = MutableStateFlow(CustomerDetailUiState())
    val uiState: StateFlow<CustomerDetailUiState> = state.asStateFlow()

    private val attempts = MutableStateFlow(0)
    private var calendar: BusinessCalendar? = null
    private var businessName: String? = null

    /** La hoja de editar al cliente, la misma de la lista. */
    val editor = CustomerEditorModel(
        scope = viewModelScope,
        save = operations::saveCustomer,
        onSaved = { showMessage(CustomerDetailMessage.Saved) },
        priceLists = { activeLists.value },
    )

    init {
        viewModelScope.launch {
            attempts.flatMapLatest {
                combine(
                    operations.calendar(),
                    operations.statement(customerId),
                    operations.loyaltyEnabled(),
                ) { calendarResult, statement, loyalty ->
                    Read.Shown(calendarResult, statement, loyalty)
                }
                    .catch<Read> { emit(Read.Failed) }
            }.collect(::show)
        }
        viewModelScope.launch {
            operations.paymentChoices().catch {}.collect { choices ->
                state.update { it.copy(choices = choices) }
            }
        }
        viewModelScope.launch {
            operations.businessName().catch {}.collect { businessName = it }
        }
        viewModelScope.launch {
            // Una venta nueva o anulada cambia la lista: se vuelve a leer si ya se abrió.
            operations.salesChanges().drop(1).catch {}.collect {
                if (state.value.sales.started) reloadSales()
            }
        }
    }

    fun onAction(action: CustomerDetailAction) {
        when (action) {
            CustomerDetailAction.Retry -> attempts.update { it + 1 }

            CustomerDetailAction.Edit -> view()?.let { editor.openEdit(it.customer) }

            is CustomerDetailAction.SelectTab -> selectTab(action.tab)

            CustomerDetailAction.LoadMoreSales -> loadSales()

            CustomerDetailAction.ShareStatement -> shareStatement()

            is CustomerDetailAction.StatementShared -> state.update {
                it.copy(
                    statement = null,
                    message = CustomerDetailMessage.StatementFailed.takeUnless { action.ok },
                )
            }

            CustomerDetailAction.OpenPayment -> openPayment()

            is CustomerDetailAction.PaymentAmount -> editPayment {
                it.copy(amountText = action.text)
            }

            is CustomerDetailAction.ChooseMethod -> editPayment {
                it.copy(methodId = action.methodId, notice = null)
            }

            is CustomerDetailAction.PaymentAccount -> editPayment {
                it.copy(accountId = action.accountId, notice = null)
            }

            is CustomerDetailAction.PaymentDate -> editPayment { it.copy(date = action.date) }

            is CustomerDetailAction.PaymentNote -> editPayment { it.copy(note = action.text) }

            CustomerDetailAction.SavePayment -> savePayment()

            CustomerDetailAction.DismissSheet -> state.update {
                if (it.payment?.working == true || it.adjust?.working == true) {
                    it
                } else {
                    it.copy(payment = null, adjust = null, reminder = null)
                }
            }

            is CustomerDetailAction.AskVoidPayment -> state.update {
                it.copy(dialog = CustomerDetailDialog.VoidPayment(action.entryId))
            }

            CustomerDetailAction.OpenAdjust -> state.update { it.copy(adjust = AdjustSheet()) }

            CustomerDetailAction.OpenReminder -> openReminder()

            is CustomerDetailAction.ReminderText -> editReminder {
                it.copy(template = action.text, error = null)
            }

            is CustomerDetailAction.ReminderDetails -> editReminder {
                it.copy(attachDetails = action.attach)
            }

            CustomerDetailAction.ResetReminder -> resetReminder()

            is CustomerDetailAction.SendReminder -> sendReminder(action.sms)

            is CustomerDetailAction.ReminderSent -> state.update {
                it.copy(
                    outgoing = null,
                    reminder = if (action.ok) null else it.reminder,
                    message = CustomerDetailMessage.ReminderNoApp.takeUnless { action.ok },
                )
            }

            is CustomerDetailAction.AdjustAmount -> editAdjust { it.copy(amountText = action.text) }

            is CustomerDetailAction.AdjustNote -> editAdjust { it.copy(note = action.text) }

            CustomerDetailAction.SaveAdjust -> saveAdjust()

            CustomerDetailAction.AskArchive -> view()?.let { view ->
                state.update {
                    it.copy(
                        dialog = CustomerDetailDialog.Archive(
                            view.customer.contact?.name.orEmpty(),
                            view.customer.balance,
                        ),
                    )
                }
            }

            CustomerDetailAction.Reactivate -> lifecycle(CustomerDetailMessage.Reactivated) {
                operations.reactivate(customerId)
            }

            CustomerDetailAction.AskAnonymize -> view()?.let { view ->
                if (!view.customer.isAnonymized) {
                    state.update {
                        it.copy(
                            dialog = CustomerDetailDialog.Anonymize(
                                view.customer.contact?.name.orEmpty(),
                                view.customer.balance,
                            ),
                        )
                    }
                }
            }

            CustomerDetailAction.ConfirmDialog -> confirmDialog()

            CustomerDetailAction.DismissDialog -> state.update { it.copy(dialog = null) }

            CustomerDetailAction.MessageShown -> state.update { it.copy(message = null) }

            CustomerDetailAction.UpdateCopies -> viewModelScope.launch { operations.updateCopies() }

            // Las resuelve la pantalla con la navegación o con otra app.
            CustomerDetailAction.Back,
            is CustomerDetailAction.OpenSale, is CustomerDetailAction.Dial,
            CustomerDetailAction.ChooseZone, CustomerDetailAction.ConfirmCurrency,
            CustomerDetailAction.SellToCustomer,
            -> Unit
        }
    }

    private fun show(read: Read) {
        val content = when (read) {
            Read.Failed -> CustomerDetailContent.ReadError

            is Read.Shown -> {
                val ready = when (val result = read.calendar) {
                    is BusinessCalendarResult.Ready -> result.calendar
                    is BusinessCalendarResult.UnknownZone -> null
                }
                calendar = ready
                val statement: CustomerStatement? = read.statement
                when {
                    statement == null -> CustomerDetailContent.Missing

                    ready == null -> CustomerDetailContent.UnknownZone

                    else -> CustomerDetailContent.Ready(
                        detailView(statement, today(ready), read.loyalty),
                    )
                }
            }
        }
        val ready = calendar
        state.update {
            it.copy(
                content = content,
                today = ready?.let(::today),
                weekStartDay = ready?.weekStartDay,
                zone = ready?.zone,
                payment = it.payment?.let { sheet -> withPreview(sheet, content) },
            )
        }
    }

    private fun today(calendar: BusinessCalendar) = calendar.businessDateOf(operations.now())

    private fun view(): CustomerDetailView? =
        (state.value.content as? CustomerDetailContent.Ready)?.view

    // ------------------------------------------------------------------ ventas

    private fun selectTab(tab: CustomerDetailTab) {
        state.update { it.copy(tab = tab) }
        if (tab == CustomerDetailTab.SALES && !state.value.sales.started) loadSales()
    }

    private fun reloadSales() {
        state.update { it.copy(sales = CustomerSales()) }
        loadSales()
    }

    private fun loadSales() {
        val sales = state.value.sales
        if (sales.loading || sales.complete) return
        state.update {
            it.copy(sales = sales.copy(loading = true, failed = false, started = true))
        }
        viewModelScope.launch {
            val next = try {
                operations.salesPage(customerId, sales.rows.lastOrNull()?.cursor, SALES_PAGE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Una lectura que falla no cierra la pantalla: el segmento ofrece reintentar.
                null
            }
            state.update { current ->
                val shown = current.sales
                current.copy(
                    sales = if (next == null) {
                        shown.copy(loading = false, failed = true)
                    } else {
                        shown.copy(
                            rows = shown.rows + next,
                            loading = false,
                            complete = next.size < SALES_PAGE,
                        )
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ extracto

    private fun shareStatement() {
        val view = view() ?: return
        val today = calendar?.let(::today) ?: return
        state.update { it.copy(statement = statementDocument(view, businessName, today)) }
    }

    // ------------------------------------------------------------------ abono

    private fun openPayment() {
        val view = view() ?: return
        val choices = state.value.choices
        // La sugerencia es la deuda: lo más común es saldarla. Con saldo a favor o en cero, nada.
        val balance = view.customer.balance
        val amountText = if (balance > Money.ZERO) {
            fixedPointInputText(balance.minor, numbers.currencyDecimals, numbers.moneySymbols)
        } else {
            ""
        }
        val method = choices.methods.firstOrNull().takeIf { choices.accountsInUse }
        val sheet = PaymentSheet(amountText = amountText, methodId = method?.id)
        state.update { it.copy(payment = withPreview(sheet, it.content)) }
    }

    private fun editPayment(change: (PaymentSheet) -> PaymentSheet) {
        state.update { current ->
            val sheet = current.payment ?: return@update current
            if (sheet.working) return@update current
            current.copy(payment = withPreview(change(sheet), current.content))
        }
    }

    /** La hoja con el saldo que dejaría el monto escrito, si se puede leer. */
    private fun withPreview(sheet: PaymentSheet, content: CustomerDetailContent): PaymentSheet {
        val balance = (content as? CustomerDetailContent.Ready)?.view?.customer?.balance
        val amount = parseAmount(sheet.amountText) as? NumberInput.Value
        val preview = if (balance != null && amount != null && amount.value > Money.ZERO) {
            CustomerPaymentRules.preview(balance, amount.value)
        } else {
            null
        }
        return sheet.copy(preview = preview)
    }

    /** La cuenta donde entra el abono: la del método, o la elegida; sin cuentas en uso, ninguna. */
    private fun accountFor(sheet: PaymentSheet): Long? {
        val choices = state.value.choices
        if (!choices.accountsInUse) return null
        val method = choices.methods.firstOrNull { it.id == sheet.methodId }
        return method?.details?.moneyAccountId ?: sheet.accountId
    }

    private fun savePayment() {
        val current = state.value
        val sheet = current.payment ?: return
        if (sheet.working) return
        val amount = parseAmount(sheet.amountText)
        val errors = buildSet {
            amountError(amount, positiveOnly = true)?.let(::add)
            if (sheet.note.trim().length > CustomerLedgerRules.NOTE_MAX) {
                add(CustomerFormError.NOTE_TOO_LONG)
            }
            val today = current.today
            if (today != null && sheet.date != null && sheet.date > today) {
                add(CustomerFormError.DATE_FUTURE)
            }
        }
        if (errors.isNotEmpty() || amount !is NumberInput.Value) {
            editSheetStatus { it.copy(errors = errors) }
            return
        }
        editSheetStatus { it.copy(errors = emptySet(), notice = null, working = true) }
        val request = CustomerPaymentRequest(
            customerId = customerId,
            amount = amount.value,
            moneyAccountId = accountFor(sheet),
            date = sheet.date,
            note = sheet.note.trim().ifEmpty { null },
        )
        viewModelScope.launch {
            when (val result = operations.recordPayment(request)) {
                is RecordCustomerPaymentResult.Recorded -> state.update {
                    it.copy(payment = null, message = CustomerDetailMessage.PaymentRecorded)
                }

                // Ya no existe: el detalle lo muestra, y la hoja no tiene a quién abonar.
                RecordCustomerPaymentResult.CustomerNotFound -> state.update {
                    it.copy(payment = null)
                }

                is RecordCustomerPaymentResult.InvalidAmount -> paymentFailed(
                    errors = setOf(
                        when (result.issue) {
                            CustomerPaymentIssue.AMOUNT_NOT_POSITIVE ->
                                CustomerFormError.AMOUNT_ZERO

                            CustomerPaymentIssue.AMOUNT_TOO_LARGE ->
                                CustomerFormError.AMOUNT_TOO_HIGH
                        },
                    ),
                )

                is RecordCustomerPaymentResult.Invalid -> paymentFailed(
                    errors = entryErrors(result.issue),
                )

                RecordCustomerPaymentResult.FutureDate ->
                    paymentFailed(errors = setOf(CustomerFormError.DATE_FUTURE))

                RecordCustomerPaymentResult.AccountNotFound,
                RecordCustomerPaymentResult.AccountArchived,
                RecordCustomerPaymentResult.AccountInOtherCurrency,
                RecordCustomerPaymentResult.AccountOverflow,
                -> paymentFailed(notice = CustomerFormNotice.AccountUnavailable)

                is RecordCustomerPaymentResult.UnknownTimeZone ->
                    paymentFailed(notice = CustomerFormNotice.UnknownZone)

                RecordCustomerPaymentResult.CurrencyNotConfirmed ->
                    paymentFailed(notice = CustomerFormNotice.CurrencyUnconfirmed)

                is RecordCustomerPaymentResult.StorageFailed ->
                    paymentFailed(
                        notice = CustomerFormNotice.SaveFailed(result.failure.isOutOfSpace),
                    )
            }
        }
    }

    private fun editSheetStatus(change: (PaymentSheet) -> PaymentSheet) {
        state.update { current ->
            current.payment?.let { current.copy(payment = change(it)) }
                ?: current
        }
    }

    private fun paymentFailed(
        errors: Set<CustomerFormError> = emptySet(),
        notice: CustomerFormNotice? = null,
    ) = editSheetStatus { it.copy(errors = errors, notice = notice, working = false) }

    // ------------------------------------------------------------------ recordatorio

    /** La plantilla guardada en este idioma, o `null` si vale la de los recursos. */
    private var savedReminder: String? = null

    private fun openReminder() {
        val view = view() ?: return
        if (view.customer.balance <= Money.ZERO || view.customer.isAnonymized) return
        // Los datos de cobro son las instrucciones del primer método que las tiene.
        val details = state.value.choices.methods
            .firstNotNullOfOrNull { it.details.instructionsText?.takeIf(String::isNotBlank) }
        viewModelScope.launch {
            val saved = try {
                operations.reminderTemplate(reminderTexts.languageTag)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Sin la plantilla guardada vale la de los recursos: el recordatorio no se pierde.
                null
            }
            savedReminder = saved
            state.update {
                it.copy(
                    reminder = ReminderSheet(
                        template = saved ?: reminderTexts.defaultTemplate,
                        details = details,
                    ),
                )
            }
        }
    }

    private fun editReminder(change: (ReminderSheet) -> ReminderSheet) {
        state.update { current ->
            current.reminder?.let { current.copy(reminder = change(it)) } ?: current
        }
    }

    private fun resetReminder() {
        editReminder { it.copy(template = reminderTexts.defaultTemplate, error = null) }
        if (savedReminder == null) return
        viewModelScope.launch {
            if (operations.resetReminderTemplate(reminderTexts.languageTag)) savedReminder = null
        }
    }

    /**
     * Guarda la plantilla si cambió, y deja el recordatorio listo para que la pantalla lo entregue a
     * otra app. Un fallo al guardar no impide enviar: el mensaje es de ahora, la plantilla es para
     * la próxima vez.
     */
    private fun sendReminder(sms: Boolean) {
        val sheet = state.value.reminder ?: return
        val view = view() ?: return
        ReminderRules.check(sheet.template)?.let { issue ->
            editReminder { it.copy(error = issue) }
            return
        }
        val phone = view.customer.contact?.phone?.takeIf { sms }
        if (sms && phone == null) return
        viewModelScope.launch {
            val current = savedReminder ?: reminderTexts.defaultTemplate
            if (sheet.template != current) {
                val ok = if (sheet.template == reminderTexts.defaultTemplate) {
                    operations.resetReminderTemplate(reminderTexts.languageTag)
                } else {
                    operations.saveReminderTemplate(reminderTexts.languageTag, sheet.template) ==
                        SaveReminderTemplateResult.Saved
                }
                if (ok) {
                    savedReminder =
                        sheet.template.takeUnless { it == reminderTexts.defaultTemplate }
                }
            }
            state.update {
                it.copy(
                    outgoing = OutgoingReminder(
                        template = sheet.template,
                        details = sheet.details?.takeIf { sheet.attachDetails },
                        businessName = businessName,
                        phone = phone,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ ajuste

    private fun editAdjust(change: (AdjustSheet) -> AdjustSheet) {
        state.update { current ->
            val sheet = current.adjust ?: return@update current
            if (sheet.working) current else current.copy(adjust = change(sheet))
        }
    }

    private fun saveAdjust() {
        val sheet = state.value.adjust ?: return
        if (sheet.working) return
        val amount = parseAmount(sheet.amountText)
        val note = sheet.note.trim()
        val errors = buildSet {
            amountError(amount, positiveOnly = false)?.let(::add)
            if (note.isEmpty()) add(CustomerFormError.NOTE_REQUIRED)
            if (note.length > CustomerLedgerRules.NOTE_MAX) add(CustomerFormError.NOTE_TOO_LONG)
        }
        if (errors.isNotEmpty() || amount !is NumberInput.Value) {
            setAdjust { it.copy(errors = errors) }
            return
        }
        setAdjust { it.copy(errors = emptySet(), notice = null, working = true) }
        viewModelScope.launch {
            when (val result = operations.adjust(customerId, amount.value, note)) {
                is AdjustCustomerBalanceResult.Recorded -> state.update {
                    it.copy(adjust = null, message = CustomerDetailMessage.Adjusted)
                }

                AdjustCustomerBalanceResult.CustomerNotFound -> state.update {
                    it.copy(adjust = null)
                }

                is AdjustCustomerBalanceResult.Invalid -> adjustFailed(
                    errors = entryErrors(result.issue),
                )

                is AdjustCustomerBalanceResult.UnknownTimeZone ->
                    adjustFailed(notice = CustomerFormNotice.UnknownZone)

                AdjustCustomerBalanceResult.CurrencyNotConfirmed ->
                    adjustFailed(notice = CustomerFormNotice.CurrencyUnconfirmed)

                is AdjustCustomerBalanceResult.StorageFailed ->
                    adjustFailed(
                        notice = CustomerFormNotice.SaveFailed(result.failure.isOutOfSpace),
                    )
            }
        }
    }

    private fun setAdjust(change: (AdjustSheet) -> AdjustSheet) {
        state.update { current ->
            current.adjust?.let { current.copy(adjust = change(it)) }
                ?: current
        }
    }

    private fun adjustFailed(
        errors: Set<CustomerFormError> = emptySet(),
        notice: CustomerFormNotice? = null,
    ) = setAdjust { it.copy(errors = errors, notice = notice, working = false) }

    // ------------------------------------------------------------------ montos

    private fun parseAmount(text: String): NumberInput<Money> = NumberInputParser.money(
        text,
        numbers.moneySymbols,
        numbers.currencyDecimals,
        Limit.DOCUMENT_TOTAL,
    )

    /** Por qué [amount] no sirve: un abono pide más que cero, un ajuste pide distinto de cero. */
    private fun amountError(amount: NumberInput<Money>, positiveOnly: Boolean): CustomerFormError? =
        when (amount) {
            is NumberInput.Value -> CustomerFormError.AMOUNT_ZERO.takeIf {
                if (positiveOnly) amount.value <= Money.ZERO else amount.value == Money.ZERO
            }

            NumberInput.Blank -> CustomerFormError.AMOUNT_ZERO

            NumberInput.Malformed -> CustomerFormError.AMOUNT_NOT_A_NUMBER

            is NumberInput.TooManyDecimals -> CustomerFormError.AMOUNT_DECIMALS

            is NumberInput.TooLarge -> CustomerFormError.AMOUNT_TOO_HIGH
        }

    /** Lo que dice el libro de una fila que no entra, junto al campo que lo causa. */
    private fun entryErrors(issue: CustomerEntryIssue): Set<CustomerFormError> = setOf(
        when (issue) {
            CustomerEntryIssue.ZERO_AMOUNT, CustomerEntryIssue.WRONG_SIGN,
            CustomerEntryIssue.WRONG_SOURCE,
            -> CustomerFormError.AMOUNT_ZERO

            CustomerEntryIssue.NOTE_REQUIRED -> CustomerFormError.NOTE_REQUIRED

            CustomerEntryIssue.NOTE_TOO_LONG -> CustomerFormError.NOTE_TOO_LONG

            CustomerEntryIssue.BALANCE_OVERFLOW -> CustomerFormError.AMOUNT_TOO_HIGH
        },
    )

    // ------------------------------------------------------------------ confirmaciones

    private fun confirmDialog() {
        val dialog = state.value.dialog ?: return
        state.update { it.copy(dialog = null) }
        when (dialog) {
            is CustomerDetailDialog.VoidPayment -> viewModelScope.launch {
                val message = when (
                    val result = operations.voidPayment(
                        customerId,
                        dialog.entryId,
                    )
                ) {
                    is VoidCustomerPaymentResult.Voided -> CustomerDetailMessage.PaymentVoided

                    // Ya estaba anulado, o la fila cambió: el extracto ya muestra cómo quedó.
                    VoidCustomerPaymentResult.EntryNotFound, VoidCustomerPaymentResult.NotAPayment,
                    VoidCustomerPaymentResult.AlreadyVoided,
                    -> null

                    is VoidCustomerPaymentResult.StorageFailed ->
                        CustomerDetailMessage.Failed(result.failure.isOutOfSpace)

                    is VoidCustomerPaymentResult.Invalid, VoidCustomerPaymentResult.AccountNotFound,
                    VoidCustomerPaymentResult.AccountOverflow,
                    is VoidCustomerPaymentResult.UnknownTimeZone,
                    -> CustomerDetailMessage.Failed(outOfSpace = false)
                }
                message?.let(::showMessage)
            }

            is CustomerDetailDialog.Archive -> lifecycle(CustomerDetailMessage.Archived) {
                operations.archive(customerId)
            }

            is CustomerDetailDialog.Anonymize -> viewModelScope.launch {
                val message = when (val result = operations.anonymize(customerId)) {
                    AnonymizeCustomerResult.Done -> CustomerDetailMessage.Anonymized

                    AnonymizeCustomerResult.NotFound,
                    AnonymizeCustomerResult.AlreadyAnonymized,
                    -> null

                    is AnonymizeCustomerResult.StorageFailed ->
                        CustomerDetailMessage.Failed(result.failure.isOutOfSpace)
                }
                message?.let(::showMessage)
            }
        }
    }

    private fun lifecycle(
        done: CustomerDetailMessage,
        block: suspend () -> CustomerLifecycleResult,
    ) {
        viewModelScope.launch {
            val message = when (val result = block()) {
                CustomerLifecycleResult.Done -> done

                // Ya estaba así, o ya no existe: el detalle ya muestra cómo quedó.
                CustomerLifecycleResult.Unchanged, CustomerLifecycleResult.NotFound,
                CustomerLifecycleResult.HasHistory,
                -> null

                is CustomerLifecycleResult.StorageFailed ->
                    CustomerDetailMessage.Failed(result.failure.isOutOfSpace)
            }
            message?.let(::showMessage)
        }
    }

    private fun showMessage(message: CustomerDetailMessage) {
        state.update { it.copy(message = message) }
    }

    private sealed interface Read {
        data class Shown(
            val calendar: BusinessCalendarResult,
            val statement: CustomerStatement?,
            val loyalty: Boolean,
        ) : Read

        data object Failed : Read
    }

    private companion object {
        const val SALES_PAGE = 30
    }
}
