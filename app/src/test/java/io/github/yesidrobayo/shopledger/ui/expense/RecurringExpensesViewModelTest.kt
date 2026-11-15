package io.github.yesidrobayo.shopledger.ui.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringOccurrence
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RecurringExpenseLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RecurringExpenseOverview
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SaveRecurringExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.SkipRecurringExpenseResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La pantalla de gastos recurrentes sin base: sus estados, la hoja con el día y la próxima fecha
 * juntos, guardar con sus errores, omitir, archivar y reactivar. Hoy es el lunes 2026-11-02. Datos
 * inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecurringExpensesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val calendar = BusinessCalendar(zone, DayOfWeek.MONDAY)
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private val today = BusinessDate(LocalDate(2026, 11, 2))
    private val symbols =
        NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = DigitGrouping('.', 3, 3))

    private val rent = category(1, "RENT")
    private val other = category(9, "OTHER")

    private val overview =
        MutableStateFlow(RecurringExpenseOverview(today, emptyList(), emptyList()))
    private val archived = MutableStateFlow<List<RecurringExpense>>(emptyList())
    private var failRead = false
    private val drafts = mutableListOf<Pair<Long?, RecurringExpenseDraft>>()
    private var saveResult: SaveRecurringExpenseResult = SaveRecurringExpenseResult.Saved(7)
    private val skipped = mutableListOf<RecurringOccurrence>()
    private var skipResult: SkipRecurringExpenseResult =
        SkipRecurringExpenseResult.Skipped(date("2026-12-01"))
    private val archiveCalls = mutableListOf<Pair<Long, Boolean>>()
    private var archiveResult: RecurringExpenseLifecycleResult =
        RecurringExpenseLifecycleResult.Done

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private fun category(id: Long, key: String) = ExpenseCategory(
        id = id,
        systemKey = key,
        details = ExpenseCategoryDetails(null, null, isFixed = false),
        isSystem = key == "OTHER",
        archivedAt = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun recurring(id: Long, next: String, day: Int, archivedAt: Instant? = null) =
        RecurringExpense(
            id = id,
            details = RecurringExpenseDetails(
                expenseCategoryId = rent.id,
                amount = Money(60_000),
                description = "Alquiler inventado",
                recurrence = Recurrence.MONTHLY,
                dayOfPeriod = day,
                nextDueDate = date(next),
                moneyAccountId = null,
                reminderEnabled = true,
            ),
            archivedAt = archivedAt,
            createdAt = now,
            updatedAt = now,
        )

    private fun TestScope.viewModel(): RecurringExpensesViewModel {
        val source: Flow<RecurringExpenseOverview> =
            if (failRead) flow { error("lectura inventada") } else overview
        val model = RecurringExpensesViewModel(
            overview = source,
            archived = archived,
            calendar = flowOf(BusinessCalendarResult.Ready(calendar)),
            categories = flowOf(listOf(rent, other)),
            accounts = flowOf(emptyList()),
            save = { id, draft ->
                drafts += id to draft
                saveResult
            },
            skip = {
                skipped += it
                skipResult
            },
            setArchived = { id, value ->
                archiveCalls += id to value
                archiveResult
            },
            numbers = ExpenseNumberFormat(symbols, currencyDecimals = 2),
        )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `due ones and scheduled ones are shown apart`() = runTest(dispatcher) {
        val due = recurring(1, "2026-11-01", day = 1)
        val next = recurring(2, "2026-11-05", day = 5)
        overview.value = RecurringExpenseOverview(today, listOf(due), listOf(next))

        val model = viewModel()

        assertEquals(
            RecurringContent.Active(listOf(due), listOf(next)),
            model.uiState.value.content,
        )
        assertEquals(today, model.uiState.value.today)
        assertEquals(DayOfWeek.MONDAY, model.uiState.value.weekStartDay)
    }

    @Test
    fun `nothing yet, an unknown zone and a failed read each have their state`() =
        runTest(dispatcher) {
            assertEquals(RecurringContent.Empty, viewModel().uiState.value.content)

            overview.value = RecurringExpenseOverview(null, emptyList(), emptyList())
            assertEquals(RecurringContent.UnknownZone, viewModel().uiState.value.content)

            failRead = true
            val failed = viewModel()
            assertEquals(RecurringContent.ReadError, failed.uiState.value.content)
        }

    @Test
    fun `a new one starts today, every month, and the day moves the next date`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.onAction(RecurringExpensesAction.New)
            val opened = requireNotNull(model.uiState.value.form)
            assertEquals(Recurrence.MONTHLY, opened.recurrence)
            assertEquals(2, opened.day)
            assertEquals(today, opened.nextDate)
            assertEquals(other.id, opened.categoryId)

            model.onAction(RecurringExpensesAction.DayOfMonthChange("31"))
            assertEquals(date("2026-11-30"), model.uiState.value.form?.nextDate)
            model.onAction(RecurringExpensesAction.DayOfMonthChange("1"))
            assertEquals(date("2026-12-01"), model.uiState.value.form?.nextDate)
        }

    @Test
    fun `picking the end of a short month keeps day 31`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(RecurringExpensesAction.New)
        model.onAction(RecurringExpensesAction.DayOfMonthChange("31"))

        model.onAction(RecurringExpensesAction.NextDateChange(date("2027-02-28")))

        val form = requireNotNull(model.uiState.value.form)
        assertEquals(31, form.day)
        assertTrue(form.mayFallOnMonthEnd)
    }

    @Test
    fun `weekly uses the chosen weekday from today on`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(RecurringExpensesAction.New)

        model.onAction(RecurringExpensesAction.RecurrenceChange(Recurrence.WEEKLY))
        assertEquals(1, model.uiState.value.form?.day)
        model.onAction(RecurringExpensesAction.WeekdayChange(5))

        // El viernes siguiente al lunes 2026-11-02.
        assertEquals(date("2026-11-06"), model.uiState.value.form?.nextDate)
    }

    @Test
    fun `saving sends what was written, and field errors stay in the sheet`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.onAction(RecurringExpensesAction.New)
            model.onAction(RecurringExpensesAction.DayOfMonthChange(""))
            model.onAction(RecurringExpensesAction.Save)
            advanceUntilIdle()

            assertEquals(
                setOf(RecurringFormError.AMOUNT_ZERO, RecurringFormError.DAY_INVALID),
                model.uiState.value.form?.errors,
            )
            assertTrue(drafts.isEmpty())

            model.onAction(RecurringExpensesAction.DayOfMonthChange("5"))
            model.onAction(RecurringExpensesAction.AmountChange("600"))
            model.onAction(RecurringExpensesAction.DescriptionChange("  Alquiler inventado "))
            model.onAction(RecurringExpensesAction.ReminderChange(true))
            model.onAction(RecurringExpensesAction.Save)
            advanceUntilIdle()

            val (id, draft) = drafts.single()
            assertNull(id)
            assertEquals(
                RecurringExpenseDraft(
                    amount = Money(60_000),
                    categoryId = other.id,
                    description = "Alquiler inventado",
                    recurrence = Recurrence.MONTHLY,
                    dayOfPeriod = 5,
                    nextDueDate = date("2026-11-05"),
                    moneyAccountId = null,
                    reminderEnabled = true,
                ),
                draft,
            )
            assertNull(model.uiState.value.form)
            assertEquals(RecurringMessage.Saved, model.uiState.value.message)
        }

    @Test
    fun `a refused save keeps what was written with its reason`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(RecurringExpensesAction.New)
        model.onAction(RecurringExpensesAction.AmountChange("600"))

        saveResult = SaveRecurringExpenseResult.Invalid(
            setOf(RecurringExpenseInputError.NEXT_DATE_MISMATCH),
        )
        model.onAction(RecurringExpensesAction.Save)
        advanceUntilIdle()
        assertEquals(setOf(RecurringFormError.DAY_INVALID), model.uiState.value.form?.errors)

        saveResult = SaveRecurringExpenseResult.CategoryArchived
        model.onAction(RecurringExpensesAction.Save)
        advanceUntilIdle()
        assertEquals(RecurringFormNotice.CategoryUnavailable, model.uiState.value.form?.notice)

        saveResult = SaveRecurringExpenseResult.StorageFailed(StorageFailure.DISK_FULL)
        model.onAction(RecurringExpensesAction.Save)
        advanceUntilIdle()
        assertEquals(
            RecurringFormNotice.SaveFailed(outOfSpace = true),
            model.uiState.value.form?.notice,
        )
        assertEquals("600", model.uiState.value.form?.amountText)
    }

    @Test
    fun `editing brings its data and saves over the same one`() = runTest(dispatcher) {
        val next = recurring(2, "2026-11-05", day = 5)
        overview.value = RecurringExpenseOverview(today, emptyList(), listOf(next))
        val model = viewModel()

        model.onAction(RecurringExpensesAction.Edit(2))
        val form = requireNotNull(model.uiState.value.form)
        assertEquals("600", form.amountText)
        assertEquals(rent.id, form.keptCategoryId)
        model.onAction(RecurringExpensesAction.Save)
        advanceUntilIdle()

        assertEquals(2L, drafts.single().first)
    }

    @Test
    fun `skipping asks first and only for a due one`() = runTest(dispatcher) {
        val due = recurring(1, "2026-11-01", day = 1)
        val next = recurring(2, "2026-11-05", day = 5)
        overview.value = RecurringExpenseOverview(today, listOf(due), listOf(next))
        val model = viewModel()

        model.onAction(RecurringExpensesAction.StartSkip(2))
        assertNull(model.uiState.value.skipping)
        model.onAction(RecurringExpensesAction.StartSkip(1))
        assertEquals(due, model.uiState.value.skipping)
        model.onAction(RecurringExpensesAction.ConfirmSkip)
        advanceUntilIdle()

        assertEquals(listOf(RecurringOccurrence(1, date("2026-11-01"))), skipped)
        assertEquals(RecurringMessage.Skipped(date("2026-12-01")), model.uiState.value.message)
        assertNull(model.uiState.value.skipping)

        skipResult = SkipRecurringExpenseResult.AlreadyHandled
        model.onAction(RecurringExpensesAction.StartSkip(1))
        model.onAction(RecurringExpensesAction.ConfirmSkip)
        advanceUntilIdle()
        assertEquals(RecurringMessage.AlreadyHandled, model.uiState.value.message)
    }

    @Test
    fun `archiving from the sheet closes it, and the archived list reactivates`() =
        runTest(dispatcher) {
            val next = recurring(2, "2026-11-05", day = 5)
            overview.value = RecurringExpenseOverview(today, emptyList(), listOf(next))
            val model = viewModel()
            model.onAction(RecurringExpensesAction.Edit(2))

            model.onAction(RecurringExpensesAction.StartArchive)
            assertEquals(next, model.uiState.value.archiving)
            model.onAction(RecurringExpensesAction.ConfirmArchive)
            advanceUntilIdle()

            assertEquals(listOf(2L to true), archiveCalls)
            assertNull(model.uiState.value.form)
            assertEquals(RecurringMessage.Archived, model.uiState.value.message)

            val gone = recurring(3, "2026-10-01", day = 1, archivedAt = now)
            archived.value = listOf(gone)
            model.onAction(RecurringExpensesAction.ShowArchived(true))
            advanceUntilIdle()
            assertEquals(RecurringContent.Archived(listOf(gone)), model.uiState.value.content)
            assertTrue(model.uiState.value.showArchived)

            archiveResult = RecurringExpenseLifecycleResult.StorageFailed(StorageFailure.DISK_FULL)
            model.onAction(RecurringExpensesAction.Reactivate(3))
            advanceUntilIdle()
            assertEquals(RecurringMessage.Failed(outOfSpace = true), model.uiState.value.message)
        }
}
