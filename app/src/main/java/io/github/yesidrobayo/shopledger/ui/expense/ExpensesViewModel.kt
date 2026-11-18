package io.github.yesidrobayo.shopledger.ui.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.OTHER_EXPENSE_CATEGORY
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseCategoryShares
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseCategorySummary
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseCorrection
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurringRegistration
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseMonth
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveExpenseCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseResult
import io.github.yesidrobayo.shopledger.ui.ReadOutcome
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import io.github.yesidrobayo.shopledger.ui.readOutcome
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth

/**
 * La pantalla de gastos: los de un mes por día, con su total y el filtro por categoría, el detalle de
 * cada uno con su anulación y la hoja del gasto nuevo.
 *
 * - Abre en el mes de hoy, según la zona del negocio, y no deja pasar de él: no hay gastos futuros.
 * - Un gasto nuevo o anulado se ve solo: la lista vuelve a leer el mes.
 * - El gasto nuevo propone la categoría de otros gastos y la fecha de hoy. Un error de campo solo se
 *   muestra al intentar guardar, y un error al guardar nunca borra lo escrito.
 *
 * @param observeMonth los gastos de un mes, con la categoría del filtro o `null` para todas.
 * @param calendar el calendario del negocio, para el mes de hoy y el selector de fecha.
 * @param now el instante actual, del reloj inyectado.
 * @param register registra un gasto en una sola transacción.
 * @param void anula un gasto en una sola transacción.
 * @param createCategory crea una categoría de gasto desde el gasto nuevo.
 * @param loadCorrection lee lo que trae corregir un gasto, o `null` si ya no existe.
 * @param loadRecurring lee lo que trae registrar un gasto recurrente, o `null` si ya no se
 *   propone.
 * @param dueRecurring cuántos gastos recurrentes esperan confirmación.
 * @param categoryTotals lo gastado por categoría en un mes, la misma suma de las estadísticas:
 *   solo gastos activos con fecha en el mes. De ahí sale el resumen por categoría, que no cambia
 *   con el filtro: marca la categoría elegida.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpensesViewModel(
    private val observeMonth: (YearMonth, Long?) -> Flow<ExpenseMonth>,
    calendar: Flow<BusinessCalendarResult>,
    private val now: () -> Instant,
    private val register: suspend (ExpenseRequest) -> RegisterExpenseResult,
    private val void: suspend (Long, VoidReason, String?) -> VoidExpenseResult,
    private val numbers: ExpenseNumberFormat,
    private val createCategory: suspend (ExpenseCategoryDraft) -> SaveExpenseCategoryResult = {
        SaveExpenseCategoryResult.NotFound
    },
    private val loadCorrection: suspend (Long) -> ExpenseCorrection? = { null },
    payingAccounts: Flow<List<MoneyAccount>> = flowOf(emptyList()),
    private val loadRecurring: suspend (Long) -> RecurringRegistration? = { null },
    dueRecurring: Flow<Int> = flowOf(0),
    private val categoryTotals: (YearMonth) -> Flow<List<ExpenseCategoryTotal>> = {
        flowOf(emptyList())
    },
) : ViewModel() {
    private val state = MutableStateFlow(ExpensesUiState())
    val uiState: StateFlow<ExpensesUiState> = state.asStateFlow()

    /** El mes, la categoría y el número de intento: cambiar cualquiera vuelve a leer. */
    private val query = MutableStateFlow<Query?>(null)
    private var businessCalendar: BusinessCalendar? = null

    init {
        viewModelScope.launch {
            // Si el calendario no se puede leer, la pantalla lo trata como una zona que no conoce:
            // no se cierra, y ofrece elegirla.
            calendar.catch {}.collect { result ->
                val ready = when (result) {
                    is BusinessCalendarResult.Ready -> result.calendar
                    is BusinessCalendarResult.UnknownZone -> null
                }
                businessCalendar = ready
                val today = ready?.businessDateOf(now())
                state.update { it.copy(today = today, weekStartDay = ready?.weekStartDay) }
                if (today == null) {
                    state.update { it.copy(content = ExpensesContent.UnknownZone) }
                } else if (query.value == null) {
                    query.value = Query(today.date.yearMonth, categoryId = null, attempt = 0)
                }
            }
        }
        viewModelScope.launch {
            // Sin cuentas legibles, el gasto no ofrece ninguna: se guarda sin dinero.
            payingAccounts.catch { emit(emptyList()) }.collect { accounts ->
                state.update { it.copy(accounts = accounts) }
            }
        }
        viewModelScope.launch {
            // Sin poder leerlos, el aviso de gastos recurrentes por confirmar no aparece.
            dueRecurring.catch { emit(0) }.collect { count ->
                state.update { it.copy(dueRecurring = count) }
            }
        }
        viewModelScope.launch {
            // El resumen por categoría del mes elegido. Al cambiar de mes se quita el del anterior
            // mientras se lee; si no se puede leer, no aparece y la lista sigue.
            query.filterNotNull().map { it.month to it.attempt }.distinctUntilChanged()
                .flatMapLatest { (month, _) ->
                    categoryTotals(month)
                        .map<List<ExpenseCategoryTotal>, ExpenseCategorySummary?> {
                            ExpenseCategoryShares.of(it)
                        }.onStart { emit(null) }
                        .catch { emit(null) }
                }.collect { summary -> state.update { it.copy(categorySummary = summary) } }
        }
        viewModelScope.launch {
            query.filterNotNull().flatMapLatest { current ->
                state.update {
                    it.copy(month = current.month, categoryId = current.categoryId)
                }
                observeMonth(current.month, current.categoryId)
                    .map<ExpenseMonth, Read> { Read.Month(it) }
                    .catch { emit(Read.Failed) }
            }.collect { read -> show(read) }
        }
    }

    fun onAction(action: ExpensesAction) {
        when (action) {
            ExpensesAction.PreviousMonth -> moveMonth(forward = false)

            ExpensesAction.NextMonth -> moveMonth(forward = true)

            ExpensesAction.OpenCategories -> state.update { it.copy(categorySheet = true) }

            is ExpensesAction.CategoryFilterChange -> {
                state.update { it.copy(categorySheet = false) }
                query.update { it?.copy(categoryId = action.categoryId) }
            }

            ExpensesAction.CloseCategories -> state.update { it.copy(categorySheet = false) }

            ExpensesAction.Retry -> query.update { it?.copy(attempt = it.attempt + 1) }

            is ExpensesAction.OpenExpense -> openExpense(action.expenseId)

            ExpensesAction.StartVoid -> editDetail {
                it.copy(voiding = ExpenseVoidDraft(), status = ExpenseSheetStatus.IDLE)
            }

            is ExpensesAction.VoidReasonChange -> editDetail {
                it.copy(voiding = it.voiding?.copy(reason = action.reason))
            }

            is ExpensesAction.VoidNoteChange -> editDetail {
                it.copy(voiding = it.voiding?.copy(note = action.note))
            }

            ExpensesAction.ConfirmVoid -> confirmVoid()

            ExpensesAction.CloseDetail -> state.update {
                it.copy(detail = null, selectedExpenseId = null)
            }

            ExpensesAction.StartCorrect -> editDetail {
                it.copy(confirmCorrect = true, status = ExpenseSheetStatus.IDLE)
            }

            ExpensesAction.CloseCorrect -> editDetail { it.copy(confirmCorrect = false) }

            ExpensesAction.ConfirmCorrect -> confirmCorrect()

            ExpensesAction.NewExpense -> state.update {
                it.copy(form = ExpenseForm(categoryId = defaultCategory(it.categories)?.id))
            }

            is ExpensesAction.StartRecurring -> startRecurring(action.recurringExpenseId)

            is ExpensesAction.AmountChange -> editForm { it.copy(amountText = action.text) }

            is ExpensesAction.FormCategoryChange -> editForm {
                it.copy(categoryId = action.categoryId)
            }

            is ExpensesAction.DescriptionChange -> editForm { it.copy(description = action.text) }

            is ExpensesAction.DateChange -> editForm { it.copy(date = action.date) }

            is ExpensesAction.AccountChange -> editForm { it.copy(accountId = action.accountId) }

            ExpensesAction.Save -> save()

            ExpensesAction.MessageShown -> state.update { it.copy(message = null) }

            ExpensesAction.NewCategory -> state.update {
                if (it.form == null) it else it.copy(newCategory = NewExpenseCategory())
            }

            is ExpensesAction.NewCategoryNameChange -> state.update {
                val draft = it.newCategory
                if (draft == null || draft.saving) {
                    it
                } else {
                    it.copy(newCategory = draft.copy(name = action.name, error = null))
                }
            }

            ExpensesAction.SaveCategory -> saveCategory()

            ExpensesAction.CloseCategory -> state.update {
                if (it.newCategory?.saving == true) it else it.copy(newCategory = null)
            }

            ExpensesAction.CloseForm -> state.update {
                if (it.form?.status == ExpenseSheetStatus.WORKING) it else it.copy(form = null)
            }

            // Lo resuelve la navegación; la pantalla queda como estaba.
            ExpensesAction.ConfirmCurrency,
            ExpensesAction.ChooseZone,
            ExpensesAction.OpenRecurring,
            ExpensesAction.ShowSales,
            ExpensesAction.ShowPurchases,
            -> Unit
        }
    }

    private fun show(read: Read) {
        when (read) {
            Read.Failed -> state.update {
                it.copy(content = ExpensesContent.ReadError, total = null)
            }

            is Read.Month -> state.update { current ->
                val month = read.month
                // El detalle abierto sigue al gasto: si se anuló, se ve anulado.
                val detail = current.detail?.let { open ->
                    month.days.flatMap { it.rows }.firstOrNull { it.id == open.row.id }
                        ?.let { open.copy(row = it) } ?: open
                }
                current.copy(
                    categories = month.categories,
                    content = contentOf(month.days, month.hasAny),
                    total = month.total,
                    detail = detail,
                    form = current.form?.let { form ->
                        // Sin categoría elegida todavía, se propone la de otros gastos.
                        if (form.categoryId == null && form.proposeCategory) {
                            form.copy(categoryId = defaultCategory(month.categories)?.id)
                        } else {
                            form
                        }
                    },
                )
            }
        }
    }

    private fun moveMonth(forward: Boolean) {
        val today = state.value.today ?: return
        query.update { current ->
            current ?: return@update null
            val next = if (forward) current.month.plusMonth() else current.month.minusMonth()
            // No hay gastos futuros: el mes de hoy es el último.
            if (next > today.date.yearMonth) current else current.copy(month = next)
        }
    }

    private fun openExpense(expenseId: Long) {
        val content = state.value.content as? ExpensesContent.Days ?: return
        val row = content.days.flatMap { it.rows }.firstOrNull { it.id == expenseId } ?: return
        state.update {
            it.copy(detail = ExpenseDetailSheet(row), selectedExpenseId = expenseId)
        }
    }

    private fun editDetail(change: (ExpenseDetailSheet) -> ExpenseDetailSheet) =
        state.update { it.copy(detail = it.detail?.let(change)) }

    private fun editForm(change: (ExpenseForm) -> ExpenseForm) = state.update { current ->
        val form = current.form ?: return@update current
        if (form.status == ExpenseSheetStatus.WORKING) return@update current
        // Corregir un campo quita su error y el aviso: la persona ya está en eso.
        current.copy(form = change(form).copy(errors = emptySet(), notice = null))
    }

    private fun confirmVoid() {
        val detail = state.value.detail ?: return
        val draft = detail.voiding ?: return
        if (detail.status == ExpenseSheetStatus.WORKING || draft.noteTooLong) return
        editDetail { it.copy(status = ExpenseSheetStatus.WORKING) }
        viewModelScope.launch {
            val result = void(detail.row.id, draft.reason, draft.note.trim().ifEmpty { null })
            when (result) {
                // Ya anulado en otra pantalla: la lista lo muestra, sin error.
                VoidExpenseResult.Voided, VoidExpenseResult.AlreadyVoided -> {
                    state.update {
                        it.copy(
                            detail = null,
                            selectedExpenseId = null,
                            message = ExpenseMessage.Voided.takeIf {
                                result == VoidExpenseResult.Voided
                            },
                        )
                    }
                }

                VoidExpenseResult.ExpenseNotFound,
                VoidExpenseResult.ReasonNotAllowed,
                VoidExpenseResult.NoteTooLong,
                is VoidExpenseResult.UnknownTimeZone,
                VoidExpenseResult.AccountOverflow,
                is VoidExpenseResult.StorageFailed,
                -> editDetail { it.copy(status = ExpenseSheetStatus.FAILED) }
            }
        }
    }

    /**
     * Anula el gasto con el motivo de error al registrar y abre el gasto nuevo con su valor, su
     * categoría si sigue vigente, su descripción y su fecha. Son dos pasos: si el nuevo no se
     * guarda, el original queda anulado, y la hoja lo advirtió antes. Si no se pudo anular, el gasto
     * sigue activo y la hoja lo dice.
     */
    private fun confirmCorrect() {
        val detail = state.value.detail ?: return
        if (detail.status == ExpenseSheetStatus.WORKING) return
        editDetail { it.copy(confirmCorrect = false, status = ExpenseSheetStatus.WORKING) }
        viewModelScope.launch {
            val voided = when (void(detail.row.id, VoidReason.ENTRY_ERROR, null)) {
                // Ya anulado en otra pantalla: igual se corrige.
                VoidExpenseResult.Voided, VoidExpenseResult.AlreadyVoided -> true

                VoidExpenseResult.ExpenseNotFound,
                VoidExpenseResult.ReasonNotAllowed,
                VoidExpenseResult.NoteTooLong,
                is VoidExpenseResult.UnknownTimeZone,
                VoidExpenseResult.AccountOverflow,
                is VoidExpenseResult.StorageFailed,
                -> false
            }
            if (!voided) {
                editDetail { it.copy(status = ExpenseSheetStatus.FAILED) }
                return@launch
            }
            // El gasto ya quedó anulado: si sus datos no se pueden leer, la hoja nueva no abre, y la
            // pantalla no se cierra.
            val correction = (
                readOutcome {
                    loadCorrection(detail.row.id)
                } as? ReadOutcome.Read
                )?.value
            state.update { current ->
                current.copy(
                    detail = null,
                    selectedExpenseId = null,
                    form = correction?.let { formOf(it, current.today) },
                )
            }
        }
    }

    private fun formOf(correction: ExpenseCorrection, today: BusinessDate?) = ExpenseForm(
        amountText = fixedPointInputText(
            correction.amount.minor,
            numbers.currencyDecimals,
            numbers.moneySymbols,
        ),
        categoryId = correction.categoryId,
        description = correction.description.orEmpty(),
        date = correction.date.takeIf { it != today },
        notice = ExpenseFormNotice.CategoryLeftOut.takeIf { correction.categoryLeftOut },
        proposeCategory = !correction.categoryLeftOut,
    )

    /**
     * Abre el gasto nuevo con el valor, la categoría si sigue vigente, la descripción, la cuenta y
     * la fecha del vencimiento de un gasto recurrente: todo se puede cambiar antes de guardar. Si
     * ya no se propone, o no se puede leer, no abre nada.
     */
    private fun startRecurring(recurringExpenseId: Long) {
        viewModelScope.launch {
            val registration = (
                readOutcome {
                    loadRecurring(recurringExpenseId)
                } as? ReadOutcome.Read
                )?.value ?: return@launch
            state.update { it.copy(form = formOf(registration, it.today)) }
        }
    }

    private fun formOf(registration: RecurringRegistration, today: BusinessDate?) = ExpenseForm(
        amountText = fixedPointInputText(
            registration.amount.minor,
            numbers.currencyDecimals,
            numbers.moneySymbols,
        ),
        categoryId = registration.categoryId,
        description = registration.description.orEmpty(),
        date = registration.occurrence.dueDate.takeIf { it != today },
        notice = ExpenseFormNotice.RecurringCategoryLeftOut.takeIf {
            registration.categoryLeftOut
        },
        proposeCategory = !registration.categoryLeftOut,
        accountId = registration.moneyAccountId,
        recurring = registration.occurrence,
    )

    private fun save() {
        val current = state.value
        val form = current.form ?: return
        if (form.status == ExpenseSheetStatus.WORKING) return
        val today = current.today
        val amount = NumberInputParser.money(
            form.amountText,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.DOCUMENT_TOTAL,
        )
        val errors = buildSet {
            amountError(amount)?.let(::add)
            if (form.descriptionTooLong) add(ExpenseFormError.DESCRIPTION_TOO_LONG)
            if (today != null && form.date != null && form.date > today) {
                add(ExpenseFormError.DATE_FUTURE)
            }
        }
        val categoryId = form.categoryId
        if (errors.isNotEmpty() || categoryId == null || amount !is NumberInput.Value<Money>) {
            editFormStatus { it.copy(errors = errors) }
            return
        }
        editFormStatus { it.copy(status = ExpenseSheetStatus.WORKING, notice = null) }
        val request = ExpenseRequest(
            amount = amount.value,
            categoryId = categoryId,
            description = form.description.trim().ifEmpty { null },
            date = form.date,
            // Una cuenta que dejó de ofrecerse mientras tanto no se usa.
            moneyAccountId = form.accountId?.takeIf { id -> current.accounts.any { it.id == id } },
            recurring = form.recurring,
        )
        viewModelScope.launch {
            val result = register(request)
            when (result) {
                is RegisterExpenseResult.Registered -> state.update {
                    it.copy(
                        form = null,
                        message = ExpenseMessage.Saved(result.expenseId, request.amount),
                    )
                }

                is RegisterExpenseResult.Invalid -> fail(
                    errors = result.errors.map(::formError).toSet(),
                )

                RegisterExpenseResult.FutureDate -> fail(
                    errors = setOf(ExpenseFormError.DATE_FUTURE),
                )

                RegisterExpenseResult.CategoryNotFound, RegisterExpenseResult.CategoryArchived ->
                    fail(notice = ExpenseFormNotice.CategoryUnavailable)

                RegisterExpenseResult.CurrencyNotConfirmed ->
                    fail(notice = ExpenseFormNotice.CurrencyUnconfirmed)

                // Sin una zona que el teléfono conozca no hay cómo fechar el gasto: se reintenta.
                is RegisterExpenseResult.UnknownTimeZone ->
                    fail(notice = ExpenseFormNotice.SaveFailed(outOfSpace = false))

                is RegisterExpenseResult.StorageFailed ->
                    fail(notice = ExpenseFormNotice.SaveFailed(result.failure.isOutOfSpace))

                is RegisterExpenseResult.AccountUnavailable ->
                    fail(notice = ExpenseFormNotice.AccountUnavailable)

                // Un saldo que no cabe en la cuenta no se corrige escribiendo: se reintenta.
                RegisterExpenseResult.AccountOverflow ->
                    fail(notice = ExpenseFormNotice.SaveFailed(outOfSpace = false))

                // Otra pantalla ya lo resolvió: guardar de nuevo lo contaría dos veces.
                RegisterExpenseResult.RecurringUnavailable,
                RegisterExpenseResult.RecurringAlreadyHandled,
                -> fail(notice = ExpenseFormNotice.RecurringHandled)
            }
        }
    }

    private fun saveCategory() {
        val draft = state.value.newCategory ?: return
        if (draft.saving) return
        state.update { it.copy(newCategory = draft.copy(saving = true, saveFailed = false)) }
        viewModelScope.launch {
            val result = createCategory(ExpenseCategoryDraft(draft.name))
            state.update { current ->
                when (result) {
                    // La categoría nueva queda elegida en el gasto, que sigue con lo escrito.
                    is SaveExpenseCategoryResult.Saved -> current.copy(
                        newCategory = null,
                        form = current.form?.copy(categoryId = result.categoryId),
                    )

                    is SaveExpenseCategoryResult.Invalid -> current.copy(
                        newCategory = draft.copy(
                            error = if (ExpenseCategoryInputError.NAME_EMPTY in result.errors) {
                                ExpenseCategoryNameError.EMPTY
                            } else {
                                ExpenseCategoryNameError.TOO_LONG
                            },
                        ),
                    )

                    SaveExpenseCategoryResult.DuplicateName -> current.copy(
                        newCategory = draft.copy(error = ExpenseCategoryNameError.DUPLICATE),
                    )

                    SaveExpenseCategoryResult.NotFound,
                    is SaveExpenseCategoryResult.StorageFailed,
                    -> current.copy(newCategory = draft.copy(saveFailed = true))
                }
            }
        }
    }

    private fun fail(
        errors: Set<ExpenseFormError> = emptySet(),
        notice: ExpenseFormNotice? = null,
    ) = editFormStatus {
        it.copy(status = ExpenseSheetStatus.IDLE, errors = errors, notice = notice)
    }

    private fun editFormStatus(change: (ExpenseForm) -> ExpenseForm) =
        state.update { it.copy(form = it.form?.let(change)) }

    private fun amountError(amount: NumberInput<Money>): ExpenseFormError? = when (amount) {
        is NumberInput.Value -> ExpenseFormError.AMOUNT_ZERO.takeIf { amount.value <= Money.ZERO }
        NumberInput.Blank -> ExpenseFormError.AMOUNT_ZERO
        NumberInput.Malformed -> ExpenseFormError.AMOUNT_NOT_A_NUMBER
        is NumberInput.TooManyDecimals -> ExpenseFormError.AMOUNT_DECIMALS
        is NumberInput.TooLarge -> ExpenseFormError.AMOUNT_TOO_HIGH
    }

    private fun formError(error: ExpenseInputError): ExpenseFormError = when (error) {
        ExpenseInputError.AMOUNT_NOT_POSITIVE -> ExpenseFormError.AMOUNT_ZERO
        ExpenseInputError.AMOUNT_TOO_LARGE -> ExpenseFormError.AMOUNT_TOO_HIGH
        ExpenseInputError.DESCRIPTION_TOO_LONG -> ExpenseFormError.DESCRIPTION_TOO_LONG
    }

    /** La categoría de otros gastos, la que se propone; o la primera vigente si faltara. */
    private fun defaultCategory(categories: List<ExpenseCategory>): ExpenseCategory? {
        val active = categories.filter { !it.isArchived }
        return active.firstOrNull { it.systemKey == OTHER_EXPENSE_CATEGORY } ?: active.firstOrNull()
    }

    private data class Query(val month: YearMonth, val categoryId: Long?, val attempt: Int)

    private sealed interface Read {
        data class Month(val month: ExpenseMonth) : Read

        data object Failed : Read
    }
}
