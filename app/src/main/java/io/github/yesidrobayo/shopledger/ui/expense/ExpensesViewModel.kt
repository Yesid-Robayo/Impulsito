package io.github.yesidrobayo.shopledger.ui.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.OTHER_EXPENSE_CATEGORY
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseMonth
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseResult
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpensesViewModel(
    private val observeMonth: (YearMonth, Long?) -> Flow<ExpenseMonth>,
    calendar: Flow<BusinessCalendarResult>,
    private val now: () -> Instant,
    private val register: suspend (ExpenseRequest) -> RegisterExpenseResult,
    private val void: suspend (Long, VoidReason, String?) -> VoidExpenseResult,
    private val numbers: ExpenseNumberFormat,
) : ViewModel() {
    private val state = MutableStateFlow(ExpensesUiState())
    val uiState: StateFlow<ExpensesUiState> = state.asStateFlow()

    /** El mes, la categoría y el número de intento: cambiar cualquiera vuelve a leer. */
    private val query = MutableStateFlow<Query?>(null)
    private var businessCalendar: BusinessCalendar? = null

    init {
        viewModelScope.launch {
            calendar.collect { result ->
                val ready = when (result) {
                    is BusinessCalendarResult.Ready -> result.calendar
                    is BusinessCalendarResult.UnknownZone -> null
                }
                businessCalendar = ready
                val today = ready?.businessDateOf(now())
                state.update { it.copy(today = today, weekStartDay = ready?.weekStartDay) }
                if (today == null) {
                    state.update { it.copy(content = ExpensesContent.ReadError) }
                } else if (query.value == null) {
                    query.value = Query(today.date.yearMonth, categoryId = null, attempt = 0)
                }
            }
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

            ExpensesAction.NewExpense -> state.update {
                it.copy(form = ExpenseForm(categoryId = defaultCategory(it.categories)?.id))
            }

            is ExpensesAction.AmountChange -> editForm { it.copy(amountText = action.text) }

            is ExpensesAction.FormCategoryChange -> editForm {
                it.copy(categoryId = action.categoryId)
            }

            is ExpensesAction.DescriptionChange -> editForm { it.copy(description = action.text) }

            is ExpensesAction.DateChange -> editForm { it.copy(date = action.date) }

            ExpensesAction.Save -> save()

            ExpensesAction.MessageShown -> state.update { it.copy(message = null) }

            ExpensesAction.CloseForm -> state.update {
                if (it.form?.status == ExpenseSheetStatus.WORKING) it else it.copy(form = null)
            }

            // Lo resuelve la navegación; la pantalla queda como estaba.
            ExpensesAction.ConfirmCurrency,
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
                        if (form.categoryId == null) {
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
                is VoidExpenseResult.StorageFailed,
                -> editDetail { it.copy(status = ExpenseSheetStatus.FAILED) }
            }
        }
    }

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
