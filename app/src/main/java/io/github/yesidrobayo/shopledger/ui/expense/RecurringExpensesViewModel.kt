package io.github.yesidrobayo.shopledger.ui.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.OTHER_EXPENSE_CATEGORY
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurrenceRules
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RecurringExpenseLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RecurringExpenseOverview
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveRecurringExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SkipRecurringExpenseResult
import io.github.yesidrobayo.shopledger.ui.components.fixedPointInputText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Los gastos recurrentes: los que esperan confirmación, con `Registrar` y `Omitir`, los
 * programados, los archivados, y la hoja de crear o editar uno.
 *
 * - **Nada se registra aquí.** `Registrar` abre el gasto nuevo con sus datos, y lo resuelve la
 *   navegación; solo al guardarlo se crea el gasto. `Omitir` pide confirmar y pasa a la fecha
 *   siguiente sin crear ningún gasto.
 * - **El día y la próxima fecha van juntos.** Elegir el día propone la primera fecha en que cae,
 *   desde hoy; elegir la fecha deja el día en que cae, y cada año el día sale de la fecha.
 * - Un error de campo solo se muestra al intentar guardar, y un error al guardar nunca borra lo
 *   escrito.
 *
 * @param overview los vigentes, separados en los que esperan confirmación y los programados.
 * @param archived los archivados.
 * @param calendar el calendario del negocio, para el primer día de la semana del calendario.
 * @param categories todas las categorías de gasto, vigentes y archivadas.
 * @param accounts las cuentas de las que puede salir un gasto, si la persona lleva cuentas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringExpensesViewModel(
    private val overview: Flow<RecurringExpenseOverview>,
    private val archived: Flow<List<RecurringExpense>>,
    calendar: Flow<BusinessCalendarResult>,
    categories: Flow<List<ExpenseCategory>>,
    accounts: Flow<List<MoneyAccount>>,
    private val save: suspend (Long?, RecurringExpenseDraft) -> SaveRecurringExpenseResult,
    private val skip: suspend (RecurringOccurrence) -> SkipRecurringExpenseResult,
    private val setArchived: suspend (Long, Boolean) -> RecurringExpenseLifecycleResult,
    private val numbers: ExpenseNumberFormat,
) : ViewModel() {
    private val state = MutableStateFlow(RecurringExpensesUiState())
    val uiState: StateFlow<RecurringExpensesUiState> = state.asStateFlow()

    /** Qué lista se lee y el número de intento: cambiar cualquiera vuelve a leer. */
    private val query = MutableStateFlow(Query(archived = false, attempt = 0))

    init {
        viewModelScope.launch {
            calendar.catch {}.collect { result ->
                val ready = (result as? BusinessCalendarResult.Ready)?.calendar
                state.update { it.copy(weekStartDay = ready?.weekStartDay) }
            }
        }
        viewModelScope.launch {
            // Sin poder leerlas, solo se ven las que ya estaban.
            categories.catch {}.collect { all -> state.update { it.copy(categories = all) } }
        }
        viewModelScope.launch {
            // Sin cuentas legibles no se ofrece ninguna.
            accounts.catch { emit(emptyList()) }.collect { offered ->
                state.update { it.copy(accounts = offered) }
            }
        }
        viewModelScope.launch {
            query.flatMapLatest { current ->
                state.update { it.copy(showArchived = current.archived) }
                read(current.archived)
            }.collect { read ->
                state.update { it.copy(content = read.content, today = read.today ?: it.today) }
            }
        }
    }

    fun onAction(action: RecurringExpensesAction) {
        when (action) {
            RecurringExpensesAction.Retry -> query.update { it.copy(attempt = it.attempt + 1) }

            is RecurringExpensesAction.ShowArchived -> query.update {
                it.copy(archived = action.archived)
            }

            RecurringExpensesAction.New -> openNew()

            is RecurringExpensesAction.Edit -> openEdit(action.recurringExpenseId)

            is RecurringExpensesAction.StartSkip -> state.update {
                it.copy(skipping = it.dueItem(action.recurringExpenseId))
            }

            RecurringExpensesAction.ConfirmSkip -> confirmSkip()

            RecurringExpensesAction.DismissSkip -> state.update {
                if (it.working) it else it.copy(skipping = null)
            }

            RecurringExpensesAction.StartArchive -> state.update { current ->
                val editing = current.form?.editingId?.let { current.activeItem(it) }
                current.copy(archiving = editing)
            }

            RecurringExpensesAction.ConfirmArchive -> confirmArchive()

            RecurringExpensesAction.DismissArchive -> state.update {
                if (it.working) it else it.copy(archiving = null)
            }

            is RecurringExpensesAction.Reactivate -> reactivate(action.recurringExpenseId)

            is RecurringExpensesAction.AmountChange -> editForm {
                it.copy(amountText = action.text)
            }

            is RecurringExpensesAction.CategoryChange -> editForm {
                it.copy(categoryId = action.categoryId)
            }

            is RecurringExpensesAction.DescriptionChange -> editForm {
                it.copy(description = action.text)
            }

            is RecurringExpensesAction.RecurrenceChange -> editForm { form ->
                // La próxima fecha se queda: el día pasa a ser el que esa fecha tiene en la nueva.
                val day = RecurrenceRules.dayOf(action.recurrence, form.nextDate)
                form.copy(recurrence = action.recurrence, day = day, dayText = day.toString())
            }

            is RecurringExpensesAction.WeekdayChange -> editForm { form ->
                withDay(form, Recurrence.WEEKLY, action.day)
            }

            is RecurringExpensesAction.DayOfMonthChange -> editForm { form ->
                val text = action.text.filter(Char::isDigit).take(DAY_DIGITS)
                val day = text.toIntOrNull()
                if (day != null && RecurrenceRules.isValidDay(Recurrence.MONTHLY, day)) {
                    withDay(form, Recurrence.MONTHLY, day)
                } else {
                    form.copy(dayText = text)
                }
            }

            is RecurringExpensesAction.NextDateChange -> editForm { form ->
                val day = RecurrenceRules.dayOf(form.recurrence, action.date, form.day)
                form.copy(nextDate = action.date, day = day, dayText = day.toString())
            }

            is RecurringExpensesAction.AccountChange -> editForm {
                it.copy(accountId = action.accountId)
            }

            is RecurringExpensesAction.ReminderChange -> editForm {
                it.copy(reminder = action.enabled)
            }

            RecurringExpensesAction.Save -> saveForm()

            RecurringExpensesAction.CloseForm -> state.update {
                if (it.form?.status == ExpenseSheetStatus.WORKING) it else it.copy(form = null)
            }

            RecurringExpensesAction.MessageShown -> state.update { it.copy(message = null) }

            // Lo resuelve la navegación; la pantalla queda como estaba.
            RecurringExpensesAction.Back,
            RecurringExpensesAction.ChooseZone,
            is RecurringExpensesAction.Register,
            -> Unit
        }
    }

    private fun read(archivedOnly: Boolean): Flow<Read> = if (archivedOnly) {
        archived.map { Read(RecurringContent.Archived(it), today = null) }
    } else {
        overview.map { Read(contentOf(it), it.today) }
    }.catch { emit(Read(RecurringContent.ReadError, today = null)) }

    private fun contentOf(overview: RecurringExpenseOverview): RecurringContent = when {
        overview.today == null -> RecurringContent.UnknownZone
        overview.isEmpty -> RecurringContent.Empty
        else -> RecurringContent.Active(overview.due, overview.scheduled)
    }

    /** Un gasto recurrente nuevo: hoy, cada mes, en el día de hoy. */
    private fun openNew() = state.update { current ->
        val today = current.today ?: return@update current
        val day = RecurrenceRules.dayOf(Recurrence.MONTHLY, today)
        val proposed = defaultCategory(current.categories)?.id
        current.copy(form = RecurringForm(day = day, nextDate = today, categoryId = proposed))
    }

    private fun openEdit(id: Long) = state.update { current ->
        val item = current.activeItem(id) ?: return@update current
        val details = item.details
        current.copy(
            form = RecurringForm(
                editingId = item.id,
                amountText = fixedPointInputText(
                    details.amount.minor,
                    numbers.currencyDecimals,
                    numbers.moneySymbols,
                ),
                categoryId = details.expenseCategoryId,
                description = details.description.orEmpty(),
                recurrence = details.recurrence,
                day = details.dayOfPeriod,
                nextDate = details.nextDueDate,
                accountId = details.moneyAccountId,
                reminder = details.reminderEnabled,
                keptCategoryId = details.expenseCategoryId,
            ),
        )
    }

    /** El día elegido y la primera fecha en que cae, desde hoy. */
    private fun withDay(form: RecurringForm, recurrence: Recurrence, day: Int): RecurringForm {
        val from = state.value.today ?: form.nextDate
        val next = RecurrenceRules.firstOnOrAfter(recurrence, day, from)
        return form.copy(day = day, dayText = day.toString(), nextDate = next)
    }

    private fun editForm(change: (RecurringForm) -> RecurringForm) = state.update { current ->
        val form = current.form ?: return@update current
        if (form.status == ExpenseSheetStatus.WORKING) return@update current
        // Corregir un campo quita los errores y el aviso: la persona ya está en eso.
        current.copy(form = change(form).copy(errors = emptySet(), notice = null))
    }

    private fun saveForm() {
        val form = state.value.form ?: return
        if (form.status == ExpenseSheetStatus.WORKING) return
        val amount = NumberInputParser.money(
            form.amountText,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.DOCUMENT_TOTAL,
        )
        val dayTyped =
            form.recurrence != Recurrence.MONTHLY || form.dayText.toIntOrNull() == form.day
        val errors = buildSet {
            amountError(amount)?.let(::add)
            if (form.categoryId == null) add(RecurringFormError.CATEGORY_MISSING)
            if (form.descriptionTooLong) add(RecurringFormError.DESCRIPTION_TOO_LONG)
            if (!dayTyped) add(RecurringFormError.DAY_INVALID)
        }
        val categoryId = form.categoryId
        if (errors.isNotEmpty() || categoryId == null || amount !is NumberInput.Value<Money>) {
            editStatus { it.copy(errors = errors) }
            return
        }
        editStatus { it.copy(status = ExpenseSheetStatus.WORKING, notice = null) }
        val offered = state.value.accounts
        val draft = RecurringExpenseDraft(
            amount = amount.value,
            categoryId = categoryId,
            description = form.description.trim().ifEmpty { null },
            recurrence = form.recurrence,
            dayOfPeriod = form.day,
            nextDueDate = form.nextDate,
            // Una cuenta que dejó de ofrecerse mientras tanto no se guarda.
            moneyAccountId = form.accountId?.takeIf { id -> offered.any { it.id == id } },
            reminderEnabled = form.reminder,
        )
        viewModelScope.launch {
            when (val result = save(form.editingId, draft)) {
                is SaveRecurringExpenseResult.Saved -> state.update {
                    it.copy(form = null, message = RecurringMessage.Saved)
                }

                is SaveRecurringExpenseResult.Invalid ->
                    fail(errors = result.errors.map(::formError).toSet())

                SaveRecurringExpenseResult.CategoryNotFound,
                SaveRecurringExpenseResult.CategoryArchived,
                -> fail(notice = RecurringFormNotice.CategoryUnavailable)

                // El que se editaba ya no existe: nada que reintentar, pero lo escrito queda.
                SaveRecurringExpenseResult.NotFound ->
                    fail(notice = RecurringFormNotice.SaveFailed(outOfSpace = false))

                is SaveRecurringExpenseResult.StorageFailed ->
                    fail(notice = RecurringFormNotice.SaveFailed(result.failure.isOutOfSpace))
            }
        }
    }

    private fun confirmSkip() {
        val current = state.value
        val item = current.skipping ?: return
        if (current.working) return
        state.update { it.copy(working = true) }
        viewModelScope.launch {
            val occurrence = RecurringOccurrence(item.id, item.details.nextDueDate)
            val message = when (val result = skip(occurrence)) {
                is SkipRecurringExpenseResult.Skipped ->
                    RecurringMessage.Skipped(result.nextDueDate)

                SkipRecurringExpenseResult.AlreadyHandled,
                SkipRecurringExpenseResult.Archived,
                SkipRecurringExpenseResult.NotFound,
                -> RecurringMessage.AlreadyHandled

                is SkipRecurringExpenseResult.StorageFailed ->
                    RecurringMessage.Failed(result.failure.isOutOfSpace)
            }
            state.update { it.copy(working = false, skipping = null, message = message) }
        }
    }

    private fun confirmArchive() {
        val current = state.value
        val item = current.archiving ?: return
        if (current.working) return
        state.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = setArchived(item.id, true)
            state.update {
                val done = result == RecurringExpenseLifecycleResult.Done ||
                    result == RecurringExpenseLifecycleResult.Unchanged
                it.copy(
                    working = false,
                    archiving = null,
                    form = if (done) null else it.form,
                    message = lifecycleMessage(result, RecurringMessage.Archived),
                )
            }
        }
    }

    private fun reactivate(id: Long) {
        if (state.value.working) return
        state.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = setArchived(id, false)
            state.update {
                it.copy(
                    working = false,
                    message = lifecycleMessage(result, RecurringMessage.Reactivated),
                )
            }
        }
    }

    private fun lifecycleMessage(
        result: RecurringExpenseLifecycleResult,
        done: RecurringMessage,
    ): RecurringMessage = when (result) {
        RecurringExpenseLifecycleResult.Done, RecurringExpenseLifecycleResult.Unchanged -> done

        RecurringExpenseLifecycleResult.NotFound -> RecurringMessage.AlreadyHandled

        is RecurringExpenseLifecycleResult.StorageFailed ->
            RecurringMessage.Failed(result.failure.isOutOfSpace)
    }

    private fun fail(
        errors: Set<RecurringFormError> = emptySet(),
        notice: RecurringFormNotice? = null,
    ) = editStatus {
        it.copy(status = ExpenseSheetStatus.IDLE, errors = errors, notice = notice)
    }

    private fun editStatus(change: (RecurringForm) -> RecurringForm) =
        state.update { it.copy(form = it.form?.let(change)) }

    private fun amountError(amount: NumberInput<Money>): RecurringFormError? = when (amount) {
        is NumberInput.Value ->
            RecurringFormError.AMOUNT_ZERO.takeIf { amount.value <= Money.ZERO }

        NumberInput.Blank -> RecurringFormError.AMOUNT_ZERO

        NumberInput.Malformed -> RecurringFormError.AMOUNT_NOT_A_NUMBER

        is NumberInput.TooManyDecimals -> RecurringFormError.AMOUNT_DECIMALS

        is NumberInput.TooLarge -> RecurringFormError.AMOUNT_TOO_HIGH
    }

    private fun formError(error: RecurringExpenseInputError): RecurringFormError = when (error) {
        RecurringExpenseInputError.AMOUNT_NOT_POSITIVE -> RecurringFormError.AMOUNT_ZERO

        RecurringExpenseInputError.AMOUNT_TOO_LARGE -> RecurringFormError.AMOUNT_TOO_HIGH

        RecurringExpenseInputError.DESCRIPTION_TOO_LONG -> RecurringFormError.DESCRIPTION_TOO_LONG

        RecurringExpenseInputError.DAY_OUT_OF_RANGE,
        RecurringExpenseInputError.NEXT_DATE_MISMATCH,
        -> RecurringFormError.DAY_INVALID
    }

    /** La categoría de otros gastos, la que se propone; o la primera vigente si faltara. */
    private fun defaultCategory(categories: List<ExpenseCategory>): ExpenseCategory? {
        val active = categories.filter { !it.isArchived }
        return active.firstOrNull { it.systemKey == OTHER_EXPENSE_CATEGORY }
            ?: active.firstOrNull()
    }

    private data class Query(val archived: Boolean, val attempt: Int)

    private data class Read(val content: RecurringContent, val today: BusinessDate?)

    private companion object {
        /** El día del mes tiene dos cifras como máximo. */
        const val DAY_DIGITS = 2
    }
}

/** Un vigente por su id, esté por confirmar o programado. */
internal fun RecurringExpensesUiState.activeItem(id: Long): RecurringExpense? =
    (content as? RecurringContent.Active)?.let { active ->
        (active.due + active.scheduled).firstOrNull { it.id == id }
    }

/** Uno que espera confirmación, por su id. */
internal fun RecurringExpensesUiState.dueItem(id: Long): RecurringExpense? =
    (content as? RecurringContent.Active)?.due?.firstOrNull { it.id == id }
