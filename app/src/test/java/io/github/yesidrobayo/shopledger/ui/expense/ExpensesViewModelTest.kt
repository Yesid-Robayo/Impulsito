package io.github.yesidrobayo.shopledger.ui.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseListDay
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseListRow
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseMonth
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La pantalla de gastos sin base: el mes de hoy, cambiar de mes sin pasar de hoy, el filtro, los
 * estados vacíos y de error, guardar con sus errores de campo y sus rechazos, y anular. Datos
 * inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpensesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val calendar = BusinessCalendar(zone, DayOfWeek.MONDAY)

    /** 2026-11-02 15:00 UTC: lunes 2026-11-02 en la zona del negocio. */
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private val today = BusinessDate(LocalDate(2026, 11, 2))
    private val november = YearMonth(2026, 11)
    private val symbols =
        NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = DigitGrouping('.', 3, 3))

    private val rent = category(1, "RENT")
    private val other = category(9, "OTHER", system = true)
    private val archived = category(10, null, name = "Fumigación", archived = true)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun category(
        id: Long,
        key: String?,
        name: String? = null,
        system: Boolean = false,
        archived: Boolean = false,
    ) = ExpenseCategory(
        id = id,
        systemKey = key,
        details = ExpenseCategoryDetails(name, name?.lowercase(), isFixed = false),
        isSystem = system,
        archivedAt = if (archived) now else null,
        createdAt = now,
        updatedAt = now,
    )

    private fun row(id: Long, amount: Long, voided: Boolean = false, date: BusinessDate = today) =
        ExpenseListRow(id, rent, null, Money(amount), date, voided)

    /** Los meses en memoria: cada lectura queda anotada. */
    private inner class Months {
        val asked = mutableListOf<Pair<YearMonth, Long?>>()
        val data = MutableStateFlow<Map<YearMonth, List<ExpenseListRow>>>(emptyMap())
        var fail = false

        fun observe(month: YearMonth, categoryId: Long?): Flow<ExpenseMonth> {
            asked += month to categoryId
            if (fail) return flow { error("lectura inventada") }
            return data.map { all ->
                val rows = all[month].orEmpty()
                val shown = rows.filter { categoryId == null || it.category.id == categoryId }
                ExpenseMonth(
                    month = month,
                    categoryId = categoryId,
                    days = shown.groupBy { it.date }.map { (date, list) ->
                        ExpenseListDay(
                            date,
                            Money(
                                list.filter {
                                    !it.voided
                                }.sumOf { it.amount.minor },
                            ),
                            list,
                        )
                    },
                    total = Money(shown.filter { !it.voided }.sumOf { it.amount.minor }),
                    categories = listOf(rent, other, archived),
                    hasAny = rows.isNotEmpty(),
                )
            }
        }
    }

    private val months = Months()
    private val requests = mutableListOf<ExpenseRequest>()
    private var registerResult: RegisterExpenseResult = RegisterExpenseResult.Registered(41)
    private val voids = mutableListOf<Triple<Long, VoidReason, String?>>()
    private var voidResult: VoidExpenseResult = VoidExpenseResult.Voided

    private fun TestScope.viewModel(
        calendarResult: BusinessCalendarResult = BusinessCalendarResult.Ready(calendar),
    ): ExpensesViewModel {
        val model = ExpensesViewModel(
            observeMonth = months::observe,
            calendar = flowOf(calendarResult),
            now = { now },
            register = {
                requests += it
                registerResult
            },
            void = { id, reason, note ->
                voids += Triple(id, reason, note)
                voidResult
            },
            numbers = ExpenseNumberFormat(symbols, currencyDecimals = 2),
        )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `it opens on this month, empty, with a zero total`() = runTest(dispatcher) {
        val model = viewModel()

        val state = model.uiState.value
        assertEquals(november, state.month)
        assertEquals(today, state.today)
        assertEquals(ExpensesContent.Empty, state.content)
        assertEquals(Money.ZERO, state.total)
        assertEquals(listOf(november to null), months.asked)
    }

    @Test
    fun `the month never goes past today, and goes back freely`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(ExpensesAction.NextMonth)
        advanceUntilIdle()
        assertEquals(november, model.uiState.value.month)

        model.onAction(ExpensesAction.PreviousMonth)
        model.onAction(ExpensesAction.PreviousMonth)
        advanceUntilIdle()
        assertEquals(YearMonth(2026, 9), model.uiState.value.month)
    }

    @Test
    fun `days show, a voided expense does not add up, and a new one shows by itself`() =
        runTest(dispatcher) {
            months.data.value =
                mapOf(november to listOf(row(2, 5_000, voided = true), row(1, 3_000)))
            val model = viewModel()

            val days = model.uiState.value.content as ExpensesContent.Days
            assertEquals(listOf(2L, 1L), days.days.single().rows.map { it.id })
            assertEquals(Money(3_000), model.uiState.value.total)

            months.data.value = mapOf(november to listOf(row(3, 1_000), row(1, 3_000)))
            advanceUntilIdle()
            assertEquals(Money(4_000), model.uiState.value.total)
        }

    @Test
    fun `the category filter reads again, and an empty filter is not an empty month`() =
        runTest(dispatcher) {
            months.data.value = mapOf(november to listOf(row(1, 3_000)))
            val model = viewModel()

            model.onAction(ExpensesAction.OpenCategories)
            assertTrue(model.uiState.value.categorySheet)
            model.onAction(ExpensesAction.CategoryFilterChange(other.id))
            advanceUntilIdle()

            val state = model.uiState.value
            assertEquals(false, state.categorySheet)
            assertEquals(other, state.filterCategory)
            assertEquals(ExpensesContent.EmptyFiltered, state.content)
            assertEquals(november to other.id, months.asked.last())
        }

    @Test
    fun `a read error can be retried, and an unknown zone is an error too`() = runTest(dispatcher) {
        months.fail = true
        val model = viewModel()
        assertEquals(ExpensesContent.ReadError, model.uiState.value.content)

        months.fail = false
        model.onAction(ExpensesAction.Retry)
        advanceUntilIdle()
        assertEquals(ExpensesContent.Empty, model.uiState.value.content)

        val lost = viewModel(BusinessCalendarResult.UnknownZone("Nowhere/Unknown"))
        assertEquals(ExpensesContent.ReadError, lost.uiState.value.content)
    }

    @Test
    fun `a new expense proposes other and today, and saves what was written`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.onAction(ExpensesAction.NewExpense)
            assertEquals(other.id, model.uiState.value.form?.categoryId)
            assertEquals(listOf(rent, other), model.uiState.value.activeCategories)
            model.onAction(ExpensesAction.AmountChange("6.000,50"))
            model.onAction(ExpensesAction.FormCategoryChange(rent.id))
            model.onAction(ExpensesAction.DescriptionChange("  Luz del local "))
            model.onAction(ExpensesAction.DateChange(BusinessDate(LocalDate(2026, 10, 30))))
            model.onAction(ExpensesAction.Save)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    ExpenseRequest(
                        Money(600_050),
                        rent.id,
                        "Luz del local",
                        BusinessDate(LocalDate(2026, 10, 30)),
                    ),
                ),
                requests,
            )
            val state = model.uiState.value
            assertNull(state.form)
            assertEquals(ExpenseMessage.Saved(41, Money(600_050)), state.message)
            model.onAction(ExpensesAction.MessageShown)
            assertNull(model.uiState.value.message)
        }

    @Test
    fun `a zero, a bad number and too many decimals are field errors`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(ExpensesAction.NewExpense)

        for ((text, error) in listOf(
            "" to ExpenseFormError.AMOUNT_ZERO,
            "0" to ExpenseFormError.AMOUNT_ZERO,
            "1,2,3" to ExpenseFormError.AMOUNT_NOT_A_NUMBER,
            "1,234" to ExpenseFormError.AMOUNT_DECIMALS,
            "99.999.999.999.999.999" to ExpenseFormError.AMOUNT_TOO_HIGH,
        )) {
            model.onAction(ExpensesAction.AmountChange(text))
            model.onAction(ExpensesAction.Save)
            advanceUntilIdle()
            assertEquals(text, setOf(error), model.uiState.value.form?.errors)
        }
        assertTrue(requests.isEmpty())

        // Corregir el campo quita el error.
        model.onAction(ExpensesAction.AmountChange("5"))
        assertEquals(emptySet<ExpenseFormError>(), model.uiState.value.form?.errors)
    }

    @Test
    fun `a long description is a field error before saving`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(ExpensesAction.NewExpense)
        model.onAction(ExpensesAction.AmountChange("5"))
        model.onAction(ExpensesAction.DescriptionChange("d".repeat(201)))
        model.onAction(ExpensesAction.Save)
        advanceUntilIdle()

        assertEquals(setOf(ExpenseFormError.DESCRIPTION_TOO_LONG), model.uiState.value.form?.errors)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `each rejection keeps what was written and says why`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(ExpensesAction.NewExpense)
        model.onAction(ExpensesAction.AmountChange("5"))
        val cases = listOf(
            RegisterExpenseResult.CurrencyNotConfirmed to
                (null to ExpenseFormNotice.CurrencyUnconfirmed),
            RegisterExpenseResult.CategoryArchived to
                (null to ExpenseFormNotice.CategoryUnavailable),
            RegisterExpenseResult.CategoryNotFound to
                (null to ExpenseFormNotice.CategoryUnavailable),
            RegisterExpenseResult.StorageFailed(StorageFailure.DISK_FULL) to
                (null to ExpenseFormNotice.SaveFailed(outOfSpace = true)),
            RegisterExpenseResult.StorageFailed(StorageFailure.IO_ERROR) to
                (null to ExpenseFormNotice.SaveFailed(outOfSpace = false)),
            RegisterExpenseResult.UnknownTimeZone("Nowhere/Unknown") to
                (null to ExpenseFormNotice.SaveFailed(outOfSpace = false)),
            RegisterExpenseResult.FutureDate to (ExpenseFormError.DATE_FUTURE to null),
            RegisterExpenseResult.Invalid(setOf(ExpenseInputError.AMOUNT_TOO_LARGE)) to
                (ExpenseFormError.AMOUNT_TOO_HIGH to null),
            RegisterExpenseResult.Invalid(setOf(ExpenseInputError.AMOUNT_NOT_POSITIVE)) to
                (ExpenseFormError.AMOUNT_ZERO to null),
            RegisterExpenseResult.Invalid(setOf(ExpenseInputError.DESCRIPTION_TOO_LONG)) to
                (ExpenseFormError.DESCRIPTION_TOO_LONG to null),
        )
        for ((result, expected) in cases) {
            registerResult = result
            model.onAction(ExpensesAction.Save)
            advanceUntilIdle()
            val form = requireNotNull(model.uiState.value.form) { result.toString() }
            assertEquals(result.toString(), "5", form.amountText)
            assertEquals(result.toString(), setOfNotNull(expected.first), form.errors)
            assertEquals(result.toString(), expected.second, form.notice)
            assertEquals(ExpenseSheetStatus.IDLE, form.status)
        }
    }

    @Test
    fun `a future date is caught before saving`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(ExpensesAction.NewExpense)
        model.onAction(ExpensesAction.AmountChange("5"))
        model.onAction(ExpensesAction.DateChange(today.plusDays(1)))
        model.onAction(ExpensesAction.Save)
        advanceUntilIdle()

        assertEquals(setOf(ExpenseFormError.DATE_FUTURE), model.uiState.value.form?.errors)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an expense opens in its sheet and is voided with its reason and note`() =
        runTest(dispatcher) {
            months.data.value = mapOf(november to listOf(row(7, 3_000)))
            val model = viewModel()

            model.onAction(ExpensesAction.OpenExpense(7))
            assertEquals(7L, model.uiState.value.detail?.row?.id)
            assertEquals(7L, model.uiState.value.selectedExpenseId)
            model.onAction(ExpensesAction.StartVoid)
            model.onAction(ExpensesAction.VoidReasonChange(VoidReason.OTHER))
            model.onAction(ExpensesAction.VoidNoteChange("  Se anotó dos veces "))
            model.onAction(ExpensesAction.ConfirmVoid)
            advanceUntilIdle()

            assertEquals(listOf(Triple(7L, VoidReason.OTHER, "Se anotó dos veces")), voids)
            assertNull(model.uiState.value.detail)
            assertEquals(ExpenseMessage.Voided, model.uiState.value.message)
        }

    @Test
    fun `a failed void keeps the sheet, and a long note does not void`() = runTest(dispatcher) {
        months.data.value = mapOf(november to listOf(row(7, 3_000)))
        val model = viewModel()
        model.onAction(ExpensesAction.OpenExpense(7))
        model.onAction(ExpensesAction.StartVoid)

        model.onAction(ExpensesAction.VoidNoteChange("n".repeat(201)))
        model.onAction(ExpensesAction.ConfirmVoid)
        advanceUntilIdle()
        assertTrue(voids.isEmpty())

        model.onAction(ExpensesAction.VoidNoteChange(""))
        voidResult = VoidExpenseResult.StorageFailed(StorageFailure.IO_ERROR)
        model.onAction(ExpensesAction.ConfirmVoid)
        advanceUntilIdle()
        assertEquals(ExpenseSheetStatus.FAILED, model.uiState.value.detail?.status)

        // Ya anulado en otra pantalla: se cierra sin aviso de error ni de anulación.
        voidResult = VoidExpenseResult.AlreadyVoided
        model.onAction(ExpensesAction.ConfirmVoid)
        advanceUntilIdle()
        assertNull(model.uiState.value.detail)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `the open sheet follows its expense when it is voided elsewhere`() = runTest(dispatcher) {
        months.data.value = mapOf(november to listOf(row(7, 3_000)))
        val model = viewModel()
        model.onAction(ExpensesAction.OpenExpense(7))

        months.data.value = mapOf(november to listOf(row(7, 3_000, voided = true)))
        advanceUntilIdle()

        assertEquals(true, model.uiState.value.detail?.row?.voided)
        model.onAction(ExpensesAction.CloseDetail)
        assertNull(model.uiState.value.detail)
    }

    @Test
    fun `closing the form drops it, and the filter ignores a missing month`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.onAction(ExpensesAction.NewExpense)
            model.onAction(ExpensesAction.CloseForm)
            assertNull(model.uiState.value.form)

            // Abrir un gasto que la lista ya no tiene no hace nada.
            model.onAction(ExpensesAction.OpenExpense(99))
            assertNull(model.uiState.value.detail)
            model.onAction(ExpensesAction.CloseCategories)
            assertEquals(false, model.uiState.value.categorySheet)
        }
}
