package io.github.yesidrobayo.shopledger.ui.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyInputError
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyRequest
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountInputError
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.TransferInputError
import io.github.yesidrobayo.shopledger.domain.model.money.TransferRequest
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyLedger
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyOverview
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyStatement
import io.github.yesidrobayo.shopledger.domain.rule.money.TransferRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.money.MoneyAccountLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.RecordManualMoneyResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.SaveMoneyAccountResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.TransferMoneyResult
import io.github.yesidrobayo.shopledger.domain.usecase.money.VoidMoneyMovementResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Las cuentas de dinero: cuánto hay en cada una, el libro de la que se abre, y las hojas de retiro,
 * aporte, otro ingreso, transferencia, cuenta nueva y edición, con anular y archivar.
 *
 * **El libro solo crece.** Cada hoja llama a un caso de uso que escribe en una sola transacción, y lo
 * que se ve se vuelve a leer al confirmarse: aquí nada se suma a mano. Un error conserva lo escrito y
 * lo dice junto al campo o en un aviso de la hoja. Un saldo que quedaría en negativo se avisa antes
 * de guardar, sin bloquear. Cada monto se lee con los decimales de la moneda de su cuenta, que puede
 * no ser la del negocio.
 *
 * @param initialAccountId la cuenta que se abre al llegar, o `null` para la lista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoneyAccountsViewModel(
    initialAccountId: Long?,
    private val operations: MoneyAccountsOperations,
    private val numbers: MoneyInputFormat,
    private val currencies: CurrencyChoices,
) : ViewModel() {
    private val state = MutableStateFlow(MoneyAccountsUiState(selectedId = initialAccountId))
    val uiState: StateFlow<MoneyAccountsUiState> = state.asStateFlow()

    private val reads = ScreenReads()
    private val selected = MutableStateFlow(initialAccountId)

    init {
        viewModelScope.launch { reads.guard { readList() }.collect(::showList) }
        viewModelScope.launch { reads.guard { readDetail() }.collect(::showDetail) }
        viewModelScope.launch {
            reads.failed.collect { failed ->
                if (failed) state.update { it.copy(content = MoneyAccountsContent.ReadError) }
            }
        }
    }

    fun onAction(action: MoneyAccountsAction) {
        when (action) {
            MoneyAccountsAction.Retry -> reads.retry()

            is MoneyAccountsAction.ShowArchived -> state.update {
                it.copy(showArchived = action.show)
            }

            is MoneyAccountsAction.Select -> select(action.accountId)

            is MoneyAccountsAction.StartManual -> startManual(action.kind)

            MoneyAccountsAction.StartTransfer -> startTransfer()

            MoneyAccountsAction.NewAccount -> newAccount()

            MoneyAccountsAction.EditAccount -> editAccount()

            is MoneyAccountsAction.ChooseAccount -> editManual {
                it.copy(accountId = action.accountId)
            }

            is MoneyAccountsAction.ChooseFrom -> editTransfer {
                it.copy(fromId = action.accountId, errors = it.errors - SAME_ACCOUNT)
            }

            is MoneyAccountsAction.ChooseTo -> editTransfer {
                it.copy(toId = action.accountId, errors = it.errors - SAME_ACCOUNT)
            }

            is MoneyAccountsAction.Amount -> {
                editManual { it.copy(amountText = action.text, errors = it.errors - AMOUNT_ERRORS) }
                editTransfer {
                    it.copy(amountText = action.text, errors = it.errors - AMOUNT_ERRORS)
                }
            }

            is MoneyAccountsAction.Rate -> editTransfer {
                it.copy(rateText = action.text, errors = it.errors - RATE_ERRORS)
            }

            is MoneyAccountsAction.Note -> {
                editManual { it.copy(note = action.text, errors = it.errors - NOTE_TOO_LONG) }
                editTransfer { it.copy(note = action.text, errors = it.errors - NOTE_TOO_LONG) }
            }

            is MoneyAccountsAction.Name -> editEditor {
                it.copy(name = action.text, nameEdited = true, errors = it.errors - NAME_ERRORS)
            }

            is MoneyAccountsAction.Kind -> editEditor { it.copy(kind = action.kind) }

            is MoneyAccountsAction.OpeningBalance -> editEditor {
                it.copy(openingText = action.text, errors = it.errors - OPENING_ERRORS)
            }

            MoneyAccountsAction.OpenCurrencies -> editEditor { editor ->
                if (editor.editingId != null) {
                    editor
                } else {
                    editor.copy(
                        choosingCurrency = true,
                        currencyQuery = "",
                        currencyOptions = currencies.options(),
                    )
                }
            }

            is MoneyAccountsAction.CurrencyQuery -> editEditor { editor ->
                val folded = TextNormalizer.normalize(action.text)
                editor.copy(
                    currencyQuery = action.text,
                    currencyOptions = currencies.options().filter { folded in it.searchKey },
                )
            }

            is MoneyAccountsAction.ChooseCurrency -> chooseCurrency(action.code)

            MoneyAccountsAction.CloseCurrencies -> editEditor {
                it.copy(choosingCurrency = false, currencyQuery = "")
            }

            MoneyAccountsAction.Save -> save()

            MoneyAccountsAction.DismissSheet -> dismissSheet()

            is MoneyAccountsAction.OpenMovement -> openMovement(action.movementId)

            is MoneyAccountsAction.OpenDocument -> state.update { it.copy(movement = null) }

            MoneyAccountsAction.AskVoid -> state.update { current ->
                val line = current.movement?.line ?: return@update current
                if (!line.voidable) return@update current
                current.copy(
                    movement = null,
                    dialog = MoneyDialog.Void(
                        line.movement.id,
                        isTransfer = line.movement.draft.origin is MoneyOrigin.Transfer,
                    ),
                )
            }

            MoneyAccountsAction.AskArchive -> state.update { current ->
                val account = selectedRow(current)?.account ?: return@update current
                if (account.isArchived) {
                    current
                } else {
                    current.copy(dialog = MoneyDialog.Archive(account))
                }
            }

            MoneyAccountsAction.Reactivate -> reactivate()

            MoneyAccountsAction.ConfirmDialog -> confirmDialog()

            MoneyAccountsAction.DismissDialog -> state.update { it.copy(dialog = null) }

            MoneyAccountsAction.MessageShown -> state.update { it.copy(message = null) }

            // Las resuelve la pantalla con la navegación.
            MoneyAccountsAction.Back, MoneyAccountsAction.ChooseZone,
            MoneyAccountsAction.ConfirmCurrency,
            -> Unit
        }
    }

    // ------------------------------------------------------------------ lectura

    private fun readList(): Flow<ListRead> = combine(
        operations.overview(),
        operations.archived(),
        operations.inUse(),
        operations.calendar(),
    ) { overview, archived, inUse, calendar ->
        val ready = (calendar as? BusinessCalendarResult.Ready)?.calendar
        ListBase(overview, archived, inUse, ready)
    }.flatMapLatest { base ->
        val date = base.calendar?.businessDateOf(operations.now())
        val pending = if (date == null) flowOf(emptyMap()) else operations.pending(date)
        pending.map { ListRead(base, date, it) }
    }

    private fun readDetail(): Flow<DetailRead> = selected.flatMapLatest { id ->
        if (id == null) {
            flowOf(DetailRead(null, emptyList()))
        } else {
            operations.movements(id).map { DetailRead(id, it) }
        }
    }

    private fun showList(read: ListRead) {
        val pending = read.pending
        fun row(account: MoneyAccount) =
            AccountRow(account, pending[account.id]?.takeIf { it != Money.ZERO })
        val view = MoneyAccountsView(
            bookCurrency = read.base.overview.bookCurrency,
            available = read.base.overview.available,
            accounts = read.base.overview.inBookCurrency.map(::row),
            others = read.base.overview.inOtherCurrencies.map(::row),
            archived = read.base.archived.map(::row),
            inUse = read.base.inUse,
        )
        state.update { current ->
            current.copy(
                content = MoneyAccountsContent.Ready(view),
                zone = read.base.calendar?.zone,
                today = read.today,
                // Sin archivadas no queda nada que ver en ese modo.
                showArchived = current.showArchived && view.archived.isNotEmpty(),
            ).withDetail(lastMovements)
        }
    }

    /** El último libro leído de la cuenta abierta, para rehacer el detalle al cambiar la lista. */
    private var lastMovements: DetailRead = DetailRead(null, emptyList())

    private fun showDetail(read: DetailRead) {
        lastMovements = read
        state.update { it.withDetail(read) }
    }

    /** El estado con el detalle de la cuenta abierta rehecho con [read] y la lista vigente. */
    private fun MoneyAccountsUiState.withDetail(read: DetailRead): MoneyAccountsUiState {
        val id = selectedId ?: return copy(detail = AccountDetailContent.Loading)
        val view = (content as? MoneyAccountsContent.Ready)?.view
            ?: return copy(detail = AccountDetailContent.Loading)
        if (read.accountId != id) return copy(detail = AccountDetailContent.Loading)
        val row = view.find(id) ?: return copy(detail = AccountDetailContent.Missing)
        return copy(
            detail = AccountDetailContent.Ready(row, MoneyStatement.byDay(read.movements), zone),
        )
    }

    private fun select(accountId: Long?) {
        selected.value = accountId
        state.update {
            it.copy(selectedId = accountId, movement = null).withDetail(lastMovements)
        }
    }

    private fun view(): MoneyAccountsView? =
        (state.value.content as? MoneyAccountsContent.Ready)?.view

    private fun selectedRow(current: MoneyAccountsUiState): AccountRow? {
        val id = current.selectedId ?: return null
        return (current.content as? MoneyAccountsContent.Ready)?.view?.find(id)
    }

    /** La cuenta abierta si está vigente, o la primera vigente: la inicial `Efectivo`. */
    private fun defaultAccount(view: MoneyAccountsView): MoneyAccount? {
        val active = view.active
        return active.firstOrNull { it.id == state.value.selectedId } ?: active.firstOrNull()
    }

    // ------------------------------------------------------------------ retiro, aporte y otros

    private fun startManual(kind: ManualMoneyKind) {
        val view = view() ?: return
        val account = defaultAccount(view) ?: return
        state.update { it.copy(manual = ManualSheet(kind, account.id)) }
    }

    private fun editManual(change: (ManualSheet) -> ManualSheet) {
        state.update { current ->
            val sheet = current.manual ?: return@update current
            if (sheet.working) return@update current
            val changed = change(sheet).copy(notice = null)
            current.copy(manual = changed.copy(negativeAfter = negativeAfterManual(changed)))
        }
    }

    /** El saldo negativo en que quedaría la cuenta con un retiro, o `null` si no queda negativo. */
    private fun negativeAfterManual(sheet: ManualSheet): Money? {
        if (sheet.kind != ManualMoneyKind.OWNER_DRAW) return null
        val account = sheet.accountId?.let { view()?.find(it)?.account } ?: return null
        val amount = positiveAmount(sheet.amountText, account.currency) ?: return null
        return negativeAfter(account.balance, amount)
    }

    private fun saveManual(sheet: ManualSheet) {
        val account = sheet.accountId?.let { view()?.find(it)?.account } ?: return
        val errors = mutableSetOf<MoneyFormError>()
        val amount = readAmount(sheet.amountText, account.currency, errors)
        if (sheet.note.trim().length > MoneyMovementDraft.NOTE_MAX) errors += NOTE_TOO_LONG
        if (errors.isNotEmpty() || amount == null) {
            editManual { it.copy(errors = errors) }
            return
        }
        state.update { it.copy(manual = sheet.copy(errors = emptySet(), working = true)) }
        viewModelScope.launch {
            val request = ManualMoneyRequest(account.id, sheet.kind, amount, sheet.note)
            when (val result = safely { operations.record(request) }) {
                is RecordManualMoneyResult.Recorded -> state.update {
                    it.copy(manual = null, message = MoneyMessage.MovementSaved)
                }

                is RecordManualMoneyResult.Invalid ->
                    manualFailed(errors = result.errors.map(::manualError).toSet())

                RecordManualMoneyResult.Overflow -> manualFailed(errors = setOf(AMOUNT_TOO_HIGH))

                RecordManualMoneyResult.CurrencyNotConfirmed ->
                    manualFailed(notice = MoneyFormNotice.CurrencyUnconfirmed)

                is RecordManualMoneyResult.UnknownTimeZone ->
                    manualFailed(notice = MoneyFormNotice.UnknownZone)

                // La cuenta se archivó o se borró mientras tanto: la lista ya muestra cómo quedó.
                RecordManualMoneyResult.AccountNotFound, RecordManualMoneyResult.AccountArchived,
                -> state.update {
                    it.copy(manual = null, message = MoneyMessage.Failed(outOfSpace = false))
                }

                is RecordManualMoneyResult.StorageFailed ->
                    manualFailed(notice = MoneyFormNotice.SaveFailed(result.failure.isOutOfSpace))

                null -> manualFailed(notice = MoneyFormNotice.SaveFailed(outOfSpace = false))
            }
        }
    }

    private fun manualFailed(
        errors: Set<MoneyFormError> = emptySet(),
        notice: MoneyFormNotice? = null,
    ) = state.update { current ->
        val sheet = current.manual ?: return@update current
        current.copy(manual = sheet.copy(errors = errors, notice = notice, working = false))
    }

    private fun manualError(error: ManualMoneyInputError): MoneyFormError = when (error) {
        ManualMoneyInputError.AMOUNT_NOT_POSITIVE -> AMOUNT_ZERO
        ManualMoneyInputError.AMOUNT_TOO_LARGE -> AMOUNT_TOO_HIGH
        ManualMoneyInputError.NOTE_TOO_LONG -> NOTE_TOO_LONG
    }

    // ------------------------------------------------------------------ transferir

    private fun startTransfer() {
        val view = view() ?: return
        val from = defaultAccount(view) ?: return
        val to = view.active.firstOrNull { it.id != from.id }
        state.update { it.copy(transfer = TransferSheet(fromId = from.id, toId = to?.id)) }
    }

    private fun editTransfer(change: (TransferSheet) -> TransferSheet) {
        state.update { current ->
            val sheet = current.transfer ?: return@update current
            if (sheet.working) return@update current
            current.copy(transfer = withPreview(change(sheet).copy(notice = null)))
        }
    }

    /** La hoja con lo que llega y el saldo en que quedaría el origen, mientras se escribe. */
    private fun withPreview(sheet: TransferSheet): TransferSheet {
        val view = view() ?: return sheet
        val from = sheet.fromId?.let { view.find(it)?.account }
        val to = sheet.toId?.let { view.find(it)?.account }
        val amount = from?.let { positiveAmount(sheet.amountText, it.currency) }
        val negative = if (from != null && amount != null) {
            negativeAfter(from.balance, amount)
        } else {
            null
        }
        val crosses = from != null && to != null && from.currency != to.currency
        val rate = readRate(sheet.rateText)
        val arrives = if (crosses && amount != null && rate != null) {
            val converted = TransferRules.convert(
                amount,
                requireNotNull(from).currency.decimals,
                requireNotNull(to).currency.decimals,
                rate,
            )
            (converted as? Checked.Value)?.value?.takeIf { it > Money.ZERO }
        } else {
            null
        }
        return sheet.copy(negativeAfter = negative, arrives = arrives)
    }

    private fun saveTransfer(sheet: TransferSheet) {
        val view = view() ?: return
        val from = sheet.fromId?.let { view.find(it)?.account } ?: return
        val to = sheet.toId?.let { view.find(it)?.account } ?: return
        val errors = mutableSetOf<MoneyFormError>()
        if (from.id == to.id) errors += SAME_ACCOUNT
        val amount = readAmount(sheet.amountText, from.currency, errors)
        if (sheet.note.trim().length > MoneyMovementDraft.NOTE_MAX) errors += NOTE_TOO_LONG
        val rate = if (from.currency == to.currency) {
            null
        } else {
            val read = NumberInputParser.rateMicro(
                sheet.rateText,
                numbers.rate,
                Limit.DOCUMENT_TOTAL,
            )
            when (read) {
                is NumberInput.Value -> read.value.takeIf { it > 0 } ?: null.also {
                    errors += RATE_REQUIRED
                }

                NumberInput.Blank -> null.also { errors += RATE_REQUIRED }

                NumberInput.Malformed, is NumberInput.TooManyDecimals ->
                    null.also { errors += RATE_NOT_A_NUMBER }

                is NumberInput.TooLarge -> null.also { errors += RATE_TOO_HIGH }
            }
        }
        if (errors.isNotEmpty() || amount == null) {
            editTransfer { it.copy(errors = errors) }
            return
        }
        state.update { it.copy(transfer = sheet.copy(errors = emptySet(), working = true)) }
        viewModelScope.launch {
            val request = TransferRequest(from.id, to.id, amount, rate, sheet.note)
            when (val result = safely { operations.transfer(request) }) {
                is TransferMoneyResult.Transferred -> state.update {
                    it.copy(transfer = null, message = MoneyMessage.MovementSaved)
                }

                is TransferMoneyResult.Invalid ->
                    transferFailed(errors = result.errors.map(::transferError).toSet())

                TransferMoneyResult.Overflow -> transferFailed(errors = setOf(AMOUNT_TOO_HIGH))

                TransferMoneyResult.CurrencyNotConfirmed ->
                    transferFailed(notice = MoneyFormNotice.CurrencyUnconfirmed)

                is TransferMoneyResult.UnknownTimeZone ->
                    transferFailed(notice = MoneyFormNotice.UnknownZone)

                TransferMoneyResult.AccountNotFound, TransferMoneyResult.AccountArchived,
                -> state.update {
                    it.copy(transfer = null, message = MoneyMessage.Failed(outOfSpace = false))
                }

                is TransferMoneyResult.StorageFailed ->
                    transferFailed(notice = MoneyFormNotice.SaveFailed(result.failure.isOutOfSpace))

                null -> transferFailed(notice = MoneyFormNotice.SaveFailed(outOfSpace = false))
            }
        }
    }

    private fun transferFailed(
        errors: Set<MoneyFormError> = emptySet(),
        notice: MoneyFormNotice? = null,
    ) = state.update { current ->
        val sheet = current.transfer ?: return@update current
        current.copy(transfer = sheet.copy(errors = errors, notice = notice, working = false))
    }

    private fun transferError(error: TransferInputError): MoneyFormError = when (error) {
        TransferInputError.SAME_ACCOUNT -> SAME_ACCOUNT

        TransferInputError.AMOUNT_NOT_POSITIVE, TransferInputError.RECEIVED_ZERO -> AMOUNT_ZERO

        TransferInputError.AMOUNT_TOO_LARGE, TransferInputError.RECEIVED_TOO_LARGE,
        -> AMOUNT_TOO_HIGH

        TransferInputError.RATE_REQUIRED -> RATE_REQUIRED

        TransferInputError.RATE_OUT_OF_RANGE -> RATE_TOO_HIGH

        TransferInputError.NOTE_TOO_LONG -> NOTE_TOO_LONG
    }

    // ------------------------------------------------------------------ cuenta nueva y edición

    private fun newAccount() {
        val view = view() ?: return
        val book = view.bookCurrency
        state.update {
            it.copy(
                editor = AccountEditor(
                    editingId = null,
                    currency = book,
                    currencyName = currencyName(book.code),
                ),
            )
        }
    }

    private fun editAccount() {
        val account = selectedRow(state.value)?.account ?: return
        state.update {
            it.copy(
                editor = AccountEditor(
                    editingId = account.id,
                    systemKey = account.systemKey,
                    name = account.details.name.orEmpty(),
                    nameEdited = account.details.name != null,
                    kind = account.details.kind,
                    currency = account.currency,
                    currencyName = currencyName(account.currency.code),
                ),
            )
        }
    }

    private fun currencyName(code: CurrencyCode): String? =
        currencies.options().firstOrNull { it.key == code.code }?.title

    private fun chooseCurrency(code: CurrencyCode) {
        val book = view()?.bookCurrency ?: return
        // La del negocio lleva sus decimales, que pueden no ser los de la norma.
        val decimals = if (code == book.code) book.decimals else currencies.decimals(code) ?: return
        val decimalsInRange = decimals.coerceIn(0, AccountCurrency.MAX_DECIMALS)
        editEditor {
            it.copy(
                currency = AccountCurrency(code, decimalsInRange),
                currencyName = currencyName(code),
                choosingCurrency = false,
                currencyQuery = "",
                errors = it.errors - OPENING_ERRORS,
            )
        }
    }

    private fun editEditor(change: (AccountEditor) -> AccountEditor) {
        state.update { current ->
            val editor = current.editor ?: return@update current
            if (editor.working) {
                current
            } else {
                current.copy(editor = change(editor).copy(notice = null))
            }
        }
    }

    private fun saveEditor(editor: AccountEditor) {
        val errors = mutableSetOf<MoneyFormError>()
        val opening = if (editor.editingId != null) {
            null
        } else {
            when (val read = parseMoney(editor.openingText, editor.currency)) {
                is NumberInput.Value -> read.value
                NumberInput.Blank -> null
                NumberInput.Malformed -> null.also { errors += OPENING_NOT_A_NUMBER }
                is NumberInput.TooManyDecimals -> null.also { errors += OPENING_DECIMALS }
                is NumberInput.TooLarge -> null.also { errors += OPENING_TOO_HIGH }
            }
        }
        if (errors.isNotEmpty()) {
            editEditor { it.copy(errors = errors) }
            return
        }
        // La cuenta inicial sin nombre propio lo conserva traducido mientras no se toque.
        val name = if (editor.systemKey != null && !editor.nameEdited) "" else editor.name
        val draft = MoneyAccountDraft(
            name = name,
            kind = editor.kind,
            currency = editor.currency.takeIf { editor.editingId == null },
            openingBalance = opening,
        )
        state.update { it.copy(editor = editor.copy(errors = emptySet(), working = true)) }
        viewModelScope.launch {
            when (val result = safely { operations.save(editor.editingId, draft) }) {
                is SaveMoneyAccountResult.Saved -> state.update {
                    it.copy(editor = null, message = MoneyMessage.AccountSaved)
                }

                is SaveMoneyAccountResult.Invalid ->
                    editorFailed(errors = result.errors.map(::accountError).toSet())

                SaveMoneyAccountResult.DuplicateName -> editorFailed(errors = setOf(NAME_TAKEN))

                SaveMoneyAccountResult.NotFound -> state.update {
                    it.copy(editor = null, message = MoneyMessage.Failed(outOfSpace = false))
                }

                SaveMoneyAccountResult.CurrencyNotConfirmed ->
                    editorFailed(notice = MoneyFormNotice.CurrencyUnconfirmed)

                is SaveMoneyAccountResult.UnknownTimeZone ->
                    editorFailed(notice = MoneyFormNotice.UnknownZone)

                is SaveMoneyAccountResult.StorageFailed ->
                    editorFailed(notice = MoneyFormNotice.SaveFailed(result.failure.isOutOfSpace))

                null -> editorFailed(notice = MoneyFormNotice.SaveFailed(outOfSpace = false))
            }
        }
    }

    private fun editorFailed(
        errors: Set<MoneyFormError> = emptySet(),
        notice: MoneyFormNotice? = null,
    ) = state.update { current ->
        val editor = current.editor ?: return@update current
        current.copy(editor = editor.copy(errors = errors, notice = notice, working = false))
    }

    private fun accountError(error: MoneyAccountInputError): MoneyFormError = when (error) {
        MoneyAccountInputError.NAME_EMPTY -> NAME_EMPTY
        MoneyAccountInputError.NAME_TOO_LONG -> NAME_TOO_LONG
        MoneyAccountInputError.OPENING_BALANCE_TOO_LARGE -> OPENING_TOO_HIGH
    }

    // ------------------------------------------------------------------ guardar y cerrar

    private fun save() {
        val current = state.value
        val editor = current.editor
        val transfer = current.transfer
        val manual = current.manual
        when {
            editor != null -> if (!editor.working && !editor.choosingCurrency) saveEditor(editor)
            transfer != null -> if (!transfer.working) saveTransfer(transfer)
            manual != null -> if (!manual.working) saveManual(manual)
        }
    }

    private fun dismissSheet() {
        state.update { current ->
            val editor = current.editor
            when {
                editor != null && editor.choosingCurrency ->
                    current.copy(editor = editor.copy(choosingCurrency = false, currencyQuery = ""))

                editor?.working == true || current.transfer?.working == true ||
                    current.manual?.working == true -> current

                else -> current.copy(manual = null, transfer = null, editor = null, movement = null)
            }
        }
    }

    // ------------------------------------------------------------------ movimientos

    private fun openMovement(movementId: Long) {
        val detail = state.value.detail as? AccountDetailContent.Ready ?: return
        val line = detail.days.asSequence().flatMap { it.lines }
            .firstOrNull { it.movement.id == movementId } ?: return
        state.update { it.copy(movement = MovementSheet(line)) }
        if (line.movement.type.isManual) return
        viewModelScope.launch {
            val document = safely { operations.document(movementId) } ?: return@launch
            state.update { current ->
                val open = current.movement
                if (open == null || open.line.movement.id != movementId) {
                    current
                } else {
                    current.copy(movement = open.copy(document = document))
                }
            }
        }
    }

    private fun confirmDialog() {
        val dialog = state.value.dialog ?: return
        state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            when (dialog) {
                is MoneyDialog.Void -> voided(safely { operations.void(dialog.movementId) })
                is MoneyDialog.Archive -> archived(safely { operations.archive(dialog.account.id) })
            }
        }
    }

    private fun voided(result: VoidMoneyMovementResult?) {
        val message = when (result) {
            is VoidMoneyMovementResult.Voided -> MoneyMessage.MovementSaved

            // Ya estaba anulado, o ya no está: el libro ya muestra cómo quedó.
            VoidMoneyMovementResult.AlreadyVoided, VoidMoneyMovementResult.NotFound,
            VoidMoneyMovementResult.FromDocument,
            -> null

            VoidMoneyMovementResult.Overflow, is VoidMoneyMovementResult.UnknownTimeZone, null ->
                MoneyMessage.Failed(outOfSpace = false)

            is VoidMoneyMovementResult.StorageFailed ->
                MoneyMessage.Failed(result.failure.isOutOfSpace)
        }
        state.update { it.copy(message = message) }
    }

    private fun archived(result: MoneyAccountLifecycleResult?) {
        when (result) {
            // Archivada, la cuenta se cierra y la lista ya no la ofrece.
            MoneyAccountLifecycleResult.Done, MoneyAccountLifecycleResult.Unchanged -> select(null)

            MoneyAccountLifecycleResult.NotFound -> select(null)

            MoneyAccountLifecycleResult.NameTaken, null -> state.update {
                it.copy(message = MoneyMessage.Failed(outOfSpace = false))
            }

            is MoneyAccountLifecycleResult.StorageFailed -> state.update {
                it.copy(message = MoneyMessage.Failed(result.failure.isOutOfSpace))
            }
        }
    }

    private fun reactivate() {
        val account = selectedRow(state.value)?.account ?: return
        if (!account.isArchived) return
        viewModelScope.launch {
            val message = when (val result = safely { operations.reactivate(account.id) }) {
                MoneyAccountLifecycleResult.Done, MoneyAccountLifecycleResult.Unchanged,
                MoneyAccountLifecycleResult.NotFound,
                -> null

                MoneyAccountLifecycleResult.NameTaken -> MoneyMessage.NameTaken

                is MoneyAccountLifecycleResult.StorageFailed ->
                    MoneyMessage.Failed(result.failure.isOutOfSpace)

                null -> MoneyMessage.Failed(outOfSpace = false)
            }
            state.update { it.copy(message = message) }
        }
    }

    // ------------------------------------------------------------------ montos

    private fun parseMoney(text: String, currency: AccountCurrency): NumberInput<Money> =
        NumberInputParser.money(text, numbers.money, currency.decimals, Limit.DOCUMENT_TOTAL)

    /** Lee un monto que tiene que ser mayor que cero, y deja en [errors] por qué no se pudo. */
    private fun readAmount(
        text: String,
        currency: AccountCurrency,
        errors: MutableSet<MoneyFormError>,
    ): Money? = when (val read = parseMoney(text, currency)) {
        is NumberInput.Value -> read.value.takeIf { it > Money.ZERO } ?: null.also {
            errors += AMOUNT_ZERO
        }

        NumberInput.Blank -> null.also { errors += AMOUNT_ZERO }

        NumberInput.Malformed -> null.also { errors += AMOUNT_NOT_A_NUMBER }

        is NumberInput.TooManyDecimals -> null.also { errors += AMOUNT_DECIMALS }

        is NumberInput.TooLarge -> null.also { errors += AMOUNT_TOO_HIGH }
    }

    private fun positiveAmount(text: String, currency: AccountCurrency): Money? =
        (parseMoney(text, currency) as? NumberInput.Value)?.value?.takeIf { it > Money.ZERO }

    private fun readRate(text: String): Long? = (
        NumberInputParser.rateMicro(
            text,
            numbers.rate,
            Limit.DOCUMENT_TOTAL,
        ) as? NumberInput.Value
        )
        ?.value
        ?.takeIf { it > 0 }

    /** El saldo en que quedaría una cuenta con [balance] al sacarle [amount], si queda negativo. */
    private fun negativeAfter(balance: Money, amount: Money): Money? =
        (MoneyLedger.next(balance, -amount) as? Checked.Value)?.value?.takeIf { it < Money.ZERO }

    /**
     * Corre una escritura del dominio. Los casos de uso ya devuelven sus fallas de la base como
     * resultado; esto solo cubre lo inesperado, para que la pantalla diga que no se guardó en vez de
     * cerrarse.
     */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private data class ListBase(
        val overview: MoneyOverview,
        val archived: List<MoneyAccount>,
        val inUse: Boolean,
        val calendar: BusinessCalendar?,
    )

    private data class ListRead(
        val base: ListBase,
        val today: BusinessDate?,
        val pending: Map<Long, Money>,
    )

    private data class DetailRead(val accountId: Long?, val movements: List<MoneyMovement>)

    private companion object {
        val AMOUNT_ZERO = MoneyFormError.AMOUNT_ZERO
        val AMOUNT_NOT_A_NUMBER = MoneyFormError.AMOUNT_NOT_A_NUMBER
        val AMOUNT_DECIMALS = MoneyFormError.AMOUNT_DECIMALS
        val AMOUNT_TOO_HIGH = MoneyFormError.AMOUNT_TOO_HIGH
        val NOTE_TOO_LONG = MoneyFormError.NOTE_TOO_LONG
        val SAME_ACCOUNT = MoneyFormError.SAME_ACCOUNT
        val RATE_REQUIRED = MoneyFormError.RATE_REQUIRED
        val RATE_NOT_A_NUMBER = MoneyFormError.RATE_NOT_A_NUMBER
        val RATE_TOO_HIGH = MoneyFormError.RATE_TOO_HIGH
        val NAME_EMPTY = MoneyFormError.NAME_EMPTY
        val NAME_TOO_LONG = MoneyFormError.NAME_TOO_LONG
        val NAME_TAKEN = MoneyFormError.NAME_TAKEN
        val OPENING_NOT_A_NUMBER = MoneyFormError.OPENING_NOT_A_NUMBER
        val OPENING_DECIMALS = MoneyFormError.OPENING_DECIMALS
        val OPENING_TOO_HIGH = MoneyFormError.OPENING_TOO_HIGH

        val AMOUNT_ERRORS =
            setOf(AMOUNT_ZERO, AMOUNT_NOT_A_NUMBER, AMOUNT_DECIMALS, AMOUNT_TOO_HIGH)
        val RATE_ERRORS = setOf(RATE_REQUIRED, RATE_NOT_A_NUMBER, RATE_TOO_HIGH)
        val NAME_ERRORS = setOf(NAME_EMPTY, NAME_TOO_LONG, NAME_TAKEN)
        val OPENING_ERRORS = setOf(OPENING_NOT_A_NUMBER, OPENING_DECIMALS, OPENING_TOO_HIGH)
    }
}
