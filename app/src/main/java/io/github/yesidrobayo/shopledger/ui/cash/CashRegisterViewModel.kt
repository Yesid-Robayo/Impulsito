package io.github.yesidrobayo.shopledger.ui.cash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashCounted
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSession
import io.github.yesidrobayo.shopledger.domain.model.cash.CashSessionStatus
import io.github.yesidrobayo.shopledger.domain.model.cash.DenominationCount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.cash.CashCount
import io.github.yesidrobayo.shopledger.domain.rule.cash.CashSessionSummary
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.cash.CloseCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.OpenCashSessionResult
import io.github.yesidrobayo.shopledger.domain.usecase.cash.SaveCashDenominationResult
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Los símbolos y decimales con que se leen los montos que escribe la persona. */
data class CashNumberFormat(val moneySymbols: NumberSymbols, val currencyDecimals: Int)

/**
 * La caja: la sesión abierta de una cuenta de efectivo con lo que se espera, los cierres
 * anteriores, abrir, cerrar con conteo y diferencia, y las denominaciones de la persona.
 *
 * **Es opcional.** Nada de esto se pide para vender: esta pantalla solo se abre desde `Negocio`. Una
 * sesión nunca se edita: abrir, cerrar y una diferencia son registros nuevos, cada uno en su propia
 * transacción del dominio, y lo que se ve se vuelve a leer al confirmarse. Un error conserva lo
 * escrito y lo dice junto al campo o en un aviso.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashRegisterViewModel(
    private val operations: CashOperations,
    private val numbers: CashNumberFormat,
) : ViewModel() {
    private val state = MutableStateFlow(CashRegisterUiState())
    val uiState: StateFlow<CashRegisterUiState> = state.asStateFlow()

    private val attempts = MutableStateFlow(0)
    private val selected = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            operations.denominations().catch {}.collect { list ->
                state.update { current ->
                    current.copy(denominations = list).let { it.withCounts(list) }
                }
            }
        }
        viewModelScope.launch {
            attempts.flatMapLatest { reads() }.collect(::show)
        }
    }

    fun onAction(action: CashRegisterAction) {
        when (action) {
            CashRegisterAction.Retry -> attempts.update { it + 1 }

            is CashRegisterAction.SelectAccount -> selected.value = action.accountId

            CashRegisterAction.StartOpen -> startOpen()

            is CashRegisterAction.OpenAccount -> state.update { current ->
                val sheet = current.openSheet ?: return@update current
                if (sheet.working) return@update current
                val account = current.accounts.firstOrNull { it.id == action.accountId }
                    ?: return@update current
                current.copy(
                    openSheet = sheet.copy(
                        accountId = account.id,
                        count = sheet.count.copy(amountText = suggestion(account)).counted(current),
                        notice = null,
                    ),
                )
            }

            CashRegisterAction.StartClose -> startClose()

            is CashRegisterAction.CountAmount -> editCount { it.copy(amountText = action.text) }

            is CashRegisterAction.CountByDenomination ->
                editCount { it.copy(byDenomination = action.enabled) }

            is CashRegisterAction.CountPieces -> editCount {
                it.copy(counts = it.counts + (action.denominationId to action.text))
            }

            is CashRegisterAction.CloseNote -> state.update { current ->
                val form = current.closeForm ?: return@update current
                if (form.working) {
                    current
                } else {
                    current.copy(
                        closeForm = form.copy(note = action.text),
                    )
                }
            }

            CashRegisterAction.ConfirmOpen -> confirmOpen()

            CashRegisterAction.ConfirmClose -> confirmClose(differenceSeen = false)

            CashRegisterAction.ConfirmDifference -> confirmClose(differenceSeen = true)

            CashRegisterAction.DismissDifference -> state.update { current ->
                current.copy(closeForm = current.closeForm?.copy(confirmDifference = false))
            }

            CashRegisterAction.DismissSheet -> state.update { current ->
                val busy = current.openSheet?.working == true ||
                    current.closeForm?.working == true ||
                    current.denominationEditor?.working == true
                when {
                    busy -> current
                    current.denominationEditor != null -> current.copy(denominationEditor = null)
                    current.detail != null -> current.copy(detail = null)
                    current.openSheet != null -> current.copy(openSheet = null)
                    else -> current.copy(closeForm = null)
                }
            }

            is CashRegisterAction.OpenClosed -> openClosed(action.sessionId)

            CashRegisterAction.OpenDenominations -> state.update {
                it.copy(showDenominations = true)
            }

            CashRegisterAction.CloseDenominations -> state.update {
                it.copy(showDenominations = false, denominationEditor = null)
            }

            CashRegisterAction.NewDenomination -> state.update {
                it.copy(denominationEditor = DenominationEditor(id = null))
            }

            is CashRegisterAction.EditDenomination -> state.update { current ->
                val denomination = current.denominations.firstOrNull { it.id == action.id }
                    ?: return@update current
                current.copy(
                    denominationEditor = DenominationEditor(
                        id = denomination.id,
                        valueText = moneyText(denomination.value),
                        isActive = denomination.isActive,
                    ),
                )
            }

            is CashRegisterAction.DenominationValue -> editDenomination {
                it.copy(valueText = action.text, error = null, failed = null)
            }

            is CashRegisterAction.DenominationActive -> editDenomination {
                it.copy(isActive = action.active, failed = null)
            }

            CashRegisterAction.SaveDenomination -> saveDenomination()

            CashRegisterAction.MessageShown -> state.update { it.copy(message = null) }

            // Las resuelve la pantalla con la navegación.
            CashRegisterAction.Back, CashRegisterAction.ChooseZone,
            CashRegisterAction.ConfirmCurrency,
            -> Unit
        }
    }

    // ------------------------------------------------------------------ lectura

    private fun reads(): Flow<Read> = combine(
        operations.cashAccounts(),
        operations.calendar(),
        selected,
    ) { accounts, calendar, chosen -> Triple(accounts, calendar, chosen) }
        .flatMapLatest { (accounts, calendarResult, chosen) ->
            val calendar = when (calendarResult) {
                is BusinessCalendarResult.Ready -> calendarResult.calendar
                is BusinessCalendarResult.UnknownZone -> null
            }
            // La cuenta elegida, o la primera: la inicial `Efectivo`, que existe desde el principio.
            val account = accounts.firstOrNull { it.id == chosen } ?: accounts.firstOrNull()
            when {
                calendar == null -> flowOf(Read.UnknownZone(accounts))

                account == null -> flowOf(Read.NoAccount)

                else -> combine(
                    operations.sessions(account.id),
                    operations.movements(account.id),
                ) { sessions, movements ->
                    Read.Shown(accounts, account, calendar, sessions, movements)
                }
            }
        }
        .catch<Read> { emit(Read.Failed) }

    private fun show(read: Read) {
        state.update { current ->
            when (read) {
                Read.Failed -> current.copy(content = CashRegisterContent.ReadError)

                Read.NoAccount -> current.copy(
                    content = CashRegisterContent.NoCashAccount,
                    accounts = emptyList(),
                )

                is Read.UnknownZone -> current.copy(
                    content = CashRegisterContent.UnknownZone,
                    accounts = read.accounts,
                )

                is Read.Shown -> {
                    val today = read.calendar.businessDateOf(operations.now())
                    val content = contentOf(read, today)
                    val open = (content as? CashRegisterContent.Ready)?.view?.open
                    current.copy(
                        content = content,
                        accounts = read.accounts,
                        today = today,
                        zone = read.calendar.zone,
                        // Lo esperado sigue a lo que entra mientras se cuenta: la misma sesión.
                        closeForm = current.closeForm?.let { form ->
                            if (open != null && open.session.id == form.sessionId) {
                                form.copy(expected = open.figures.expected)
                            } else {
                                form
                            }
                        },
                    )
                }
            }
        }
    }

    private fun contentOf(read: Read.Shown, today: BusinessDate): CashRegisterContent {
        val openSession = read.sessions.firstOrNull { it.status == CashSessionStatus.OPEN }
        val open = openSession?.let { session ->
            val figures = CashSessionSummary.of(session, read.movements)
                ?: return CashRegisterContent.ReadError
            OpenCash(session, figures, fromAnotherDay = session.opening.businessDate < today)
        }
        val closed = read.sessions.filter { it.status == CashSessionStatus.CLOSED }
        return CashRegisterContent.Ready(CashRegisterView(read.account, open, closed))
    }

    private fun view(): CashRegisterView? =
        (state.value.content as? CashRegisterContent.Ready)?.view

    // ------------------------------------------------------------------ abrir

    private fun startOpen() {
        val view = view() ?: return
        if (view.open != null) return
        state.update { current ->
            current.copy(
                openSheet = OpenCashSheet(
                    accountId = view.account.id,
                    count = CashCountInput(amountText = suggestion(view.account)).counted(current),
                ),
            )
        }
    }

    /** El saldo de la cuenta como sugerencia de lo que hay al abrir; negativo, nada. */
    private fun suggestion(account: MoneyAccount): String =
        if (account.balance >= Money.ZERO) moneyText(account.balance) else ""

    private fun confirmOpen() {
        val current = state.value
        val sheet = current.openSheet ?: return
        if (sheet.working) return
        // Al abrir, el monto vacío es el saldo de la cuenta: no hay nada que corregir.
        val counted = when (val read = readCount(sheet.count, current, blankAllowed = true)) {
            is CountRead.Counted -> read.counted

            CountRead.Blank -> null

            is CountRead.Invalid -> {
                setOpen { it.copy(errors = setOf(read.error)) }
                return
            }
        }
        setOpen { it.copy(errors = emptySet(), notice = null, working = true) }
        viewModelScope.launch {
            val result = operations.open(sheet.accountId, counted)
            // La cuenta abierta pasa a ser la que se muestra.
            if (result is OpenCashSessionResult.Opened) selected.value = sheet.accountId
            when (result) {
                is OpenCashSessionResult.Opened -> state.update {
                    it.copy(openSheet = null, message = CashMessage.Opened)
                }

                OpenCashSessionResult.AlreadyOpen -> openFailed(notice = CashFormNotice.AlreadyOpen)

                OpenCashSessionResult.CountedOutOfRange, OpenCashSessionResult.BalanceTooLarge ->
                    openFailed(errors = setOf(CashFormError.AMOUNT_TOO_HIGH))

                OpenCashSessionResult.UnknownDenomination ->
                    openFailed(notice = CashFormNotice.DenominationChanged)

                OpenCashSessionResult.CurrencyNotConfirmed ->
                    openFailed(notice = CashFormNotice.CurrencyUnconfirmed)

                is OpenCashSessionResult.UnknownTimeZone ->
                    openFailed(notice = CashFormNotice.UnknownZone)

                // La cuenta se archivó o cambió mientras tanto: la pantalla ya muestra otra.
                OpenCashSessionResult.AccountNotFound, OpenCashSessionResult.AccountArchived,
                OpenCashSessionResult.NotCashAccount,
                -> state.update {
                    it.copy(openSheet = null, message = CashMessage.Failed(outOfSpace = false))
                }

                is OpenCashSessionResult.StorageFailed ->
                    openFailed(notice = CashFormNotice.SaveFailed(result.failure.isOutOfSpace))
            }
        }
    }

    private fun setOpen(change: (OpenCashSheet) -> OpenCashSheet) = state.update { current ->
        current.openSheet?.let { current.copy(openSheet = change(it)) }
            ?: current
    }

    private fun openFailed(
        errors: Set<CashFormError> = emptySet(),
        notice: CashFormNotice? = null,
    ) = setOpen { it.copy(errors = errors, notice = notice, working = false) }

    // ------------------------------------------------------------------ cerrar

    private fun startClose() {
        val open = view()?.open ?: return
        state.update {
            it.copy(closeForm = CloseCashForm(open.session.id, open.figures.expected))
        }
    }

    private fun confirmClose(differenceSeen: Boolean) {
        val current = state.value
        val form = current.closeForm ?: return
        if (form.working) return
        val note = form.note.trim()
        val counted = when (val read = readCount(form.count, current, blankAllowed = false)) {
            is CountRead.Counted -> read.counted

            CountRead.Blank -> {
                setClose { it.copy(errors = setOf(CashFormError.AMOUNT_REQUIRED)) }
                return
            }

            is CountRead.Invalid -> {
                setClose { it.copy(errors = setOf(read.error)) }
                return
            }
        }
        if (note.length > NOTE_MAX) {
            setClose { it.copy(errors = setOf(CashFormError.NOTE_TOO_LONG)) }
            return
        }
        // Con diferencia se pregunta antes: queda en el historial y no se edita.
        val difference = form.difference
        if (!differenceSeen && difference != null && difference != Money.ZERO) {
            setClose { it.copy(errors = emptySet(), confirmDifference = true) }
            return
        }
        setClose {
            it.copy(errors = emptySet(), notice = null, working = true, confirmDifference = false)
        }
        viewModelScope.launch {
            when (val result = operations.close(form.sessionId, counted, note.ifEmpty { null })) {
                is CloseCashSessionResult.Closed -> state.update {
                    it.copy(closeForm = null, message = CashMessage.Closed)
                }

                // Ya se cerró, o ya no existe: la pantalla ya muestra cómo quedó.
                CloseCashSessionResult.SessionNotFound, CloseCashSessionResult.AlreadyClosed ->
                    state.update { it.copy(closeForm = null) }

                CloseCashSessionResult.CountedOutOfRange, CloseCashSessionResult.AmountTooLarge ->
                    closeFailed(errors = setOf(CashFormError.AMOUNT_TOO_HIGH))

                CloseCashSessionResult.NoteTooLong ->
                    closeFailed(errors = setOf(CashFormError.NOTE_TOO_LONG))

                CloseCashSessionResult.UnknownDenomination ->
                    closeFailed(notice = CashFormNotice.DenominationChanged)

                CloseCashSessionResult.CurrencyNotConfirmed ->
                    closeFailed(notice = CashFormNotice.CurrencyUnconfirmed)

                is CloseCashSessionResult.UnknownTimeZone ->
                    closeFailed(notice = CashFormNotice.UnknownZone)

                is CloseCashSessionResult.StorageFailed ->
                    closeFailed(notice = CashFormNotice.SaveFailed(result.failure.isOutOfSpace))
            }
        }
    }

    private fun setClose(change: (CloseCashForm) -> CloseCashForm) = state.update { current ->
        current.closeForm?.let { current.copy(closeForm = change(it)) }
            ?: current
    }

    private fun closeFailed(
        errors: Set<CashFormError> = emptySet(),
        notice: CashFormNotice? = null,
    ) = setClose { it.copy(errors = errors, notice = notice, working = false) }

    // ------------------------------------------------------------------ conteo

    /** Cambia lo contado en la hoja de abrir, o en el cierre si no hay hoja, y rehace la suma. */
    private fun editCount(change: (CashCountInput) -> CashCountInput) {
        state.update { current ->
            val sheet = current.openSheet
            val form = current.closeForm
            when {
                sheet != null && !sheet.working -> current.copy(
                    openSheet = sheet.copy(
                        count = change(sheet.count).counted(current),
                        errors = emptySet(),
                    ),
                )

                sheet == null && form != null && !form.working -> current.copy(
                    closeForm = form.copy(
                        count = change(form.count).counted(current),
                        errors = emptySet(),
                    ),
                )

                else -> current
            }
        }
    }

    /** El estado con lo contado de la hoja o del cierre sumado de nuevo con [denominations]. */
    private fun CashRegisterUiState.withCounts(
        denominations: List<CashDenomination>,
    ): CashRegisterUiState {
        val active = copy(denominations = denominations)
        return active.copy(
            openSheet = openSheet?.let { it.copy(count = it.count.counted(active)) },
            closeForm = closeForm?.let { it.copy(count = it.count.counted(active)) },
        )
    }

    /** [this] con la suma de lo contado, o `null` si todavía no se puede leer. */
    private fun CashCountInput.counted(current: CashRegisterUiState): CashCountInput = copy(
        counted = (readCount(this, current, blankAllowed = false) as? CountRead.Counted)?.total,
    )

    /**
     * Lee lo contado: el monto escrito o, pieza por pieza, cada denominación activa por sus piezas.
     * Las piezas vacías o en cero no cuentan y no se guardan.
     */
    private fun readCount(
        input: CashCountInput,
        current: CashRegisterUiState,
        blankAllowed: Boolean,
    ): CountRead {
        if (input.byDenomination) {
            val counts = ArrayList<DenominationCount>()
            val values = ArrayList<Pair<Money, Long>>()
            for (denomination in current.activeDenominations) {
                val text = input.counts[denomination.id]?.trim().orEmpty()
                if (text.isEmpty()) continue
                val pieces =
                    text.toLongOrNull() ?: return CountRead.Invalid(CashFormError.AMOUNT_TOO_HIGH)
                if (pieces == 0L) continue
                counts += DenominationCount(denomination.id, pieces)
                values += denomination.value to pieces
            }
            val total =
                CashCount.countedOf(values)
                    ?: return CountRead.Invalid(CashFormError.AMOUNT_TOO_HIGH)
            return CountRead.Counted(CashCounted.ByDenomination(counts), total)
        }
        return when (val amount = parseMoney(input.amountText, Limit.DOCUMENT_TOTAL)) {
            is NumberInput.Value -> if (amount.value < Money.ZERO) {
                CountRead.Invalid(CashFormError.AMOUNT_NOT_A_NUMBER)
            } else {
                CountRead.Counted(CashCounted.Amount(amount.value), amount.value)
            }

            NumberInput.Blank -> if (blankAllowed) {
                CountRead.Blank
            } else {
                CountRead.Invalid(CashFormError.AMOUNT_REQUIRED)
            }

            NumberInput.Malformed -> CountRead.Invalid(CashFormError.AMOUNT_NOT_A_NUMBER)

            is NumberInput.TooManyDecimals -> CountRead.Invalid(CashFormError.AMOUNT_DECIMALS)

            is NumberInput.TooLarge -> CountRead.Invalid(CashFormError.AMOUNT_TOO_HIGH)
        }
    }

    private sealed interface CountRead {
        data class Counted(val counted: CashCounted, val total: Money) : CountRead

        data object Blank : CountRead

        data class Invalid(val error: CashFormError) : CountRead
    }

    // ------------------------------------------------------------------ cierres anteriores

    private fun openClosed(sessionId: Long) {
        val session = view()?.closed?.firstOrNull { it.id == sessionId } ?: return
        viewModelScope.launch {
            val counts = try {
                operations.closingCounts(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Sin los conteos, el detalle muestra igual lo esperado, lo contado y la diferencia.
                emptyList()
            }
            state.update { it.copy(detail = ClosedCashDetail(session, counts)) }
        }
    }

    // ------------------------------------------------------------------ denominaciones

    private fun editDenomination(change: (DenominationEditor) -> DenominationEditor) {
        state.update { current ->
            val editor = current.denominationEditor ?: return@update current
            if (editor.working) current else current.copy(denominationEditor = change(editor))
        }
    }

    private fun saveDenomination() {
        val current = state.value
        val editor = current.denominationEditor ?: return
        if (editor.working) return
        val value = when (val read = parseMoney(editor.valueText, Limit.UNIT_MONEY)) {
            is NumberInput.Value -> read.value

            NumberInput.Blank -> Money.ZERO

            NumberInput.Malformed, is NumberInput.TooManyDecimals -> {
                editDenomination { it.copy(error = DenominationError.NOT_A_NUMBER) }
                return
            }

            is NumberInput.TooLarge -> {
                editDenomination { it.copy(error = DenominationError.TOO_HIGH) }
                return
            }
        }
        val error = when {
            value <= Money.ZERO -> DenominationError.VALUE_ZERO

            current.denominations.any { it.id != editor.id && it.value == value } ->
                DenominationError.DUPLICATE

            else -> null
        }
        if (error != null) {
            editDenomination { it.copy(error = error) }
            return
        }
        val sortOrder = current.denominations.firstOrNull { it.id == editor.id }?.sortOrder ?: 0
        editDenomination { it.copy(error = null, failed = null, working = true) }
        viewModelScope.launch {
            when (
                val result = operations.saveDenomination(
                    editor.id,
                    value,
                    editor.isActive,
                    sortOrder,
                )
            ) {
                is SaveCashDenominationResult.Saved, SaveCashDenominationResult.NotFound ->
                    state.update { it.copy(denominationEditor = null) }

                SaveCashDenominationResult.ValueOutOfRange -> state.update { now ->
                    now.copy(
                        denominationEditor = now.denominationEditor?.copy(
                            error = DenominationError.TOO_HIGH,
                            working = false,
                        ),
                    )
                }

                is SaveCashDenominationResult.StorageFailed -> state.update { now ->
                    now.copy(
                        denominationEditor = now.denominationEditor?.copy(
                            failed = CashFormNotice.SaveFailed(result.failure.isOutOfSpace),
                            working = false,
                        ),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ montos

    private fun parseMoney(text: String, limit: Limit): NumberInput<Money> =
        NumberInputParser.money(text, numbers.moneySymbols, numbers.currencyDecimals, limit)

    private fun moneyText(amount: Money): String =
        fixedPointInputText(amount.minor, numbers.currencyDecimals, numbers.moneySymbols)

    private sealed interface Read {
        data object Failed : Read

        data object NoAccount : Read

        data class UnknownZone(val accounts: List<MoneyAccount>) : Read

        data class Shown(
            val accounts: List<MoneyAccount>,
            val account: MoneyAccount,
            val calendar: BusinessCalendar,
            val sessions: List<CashSession>,
            val movements: List<MoneyMovement>,
        ) : Read
    }

    private companion object {
        /** Largo máximo de la nota del cierre: el que guarda la sesión. */
        const val NOTE_MAX = 200
    }
}
