package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SaleHistorySource
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleHistoryReader
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El historial sin base: lee por tramos hasta el final, agrupa por día con lo vendido en ventas
 * activas, vuelve a leer al cambiar un filtro o al registrar y anular, y distingue el historial vacío
 * del filtro sin resultados. Ventas inventadas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = BusinessDate(LocalDate(2026, 11, 2))

    /** 2026-11-02 15:00 UTC: lunes 2026-11-02 10:00 en `Etc/GMT+5`. */
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)

    private val source = FakeHistory()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    /** Un historial en memoria que filtra y corta como la base, y anota cada tramo pedido. */
    private class FakeHistory : SaleHistorySource {
        var rows: List<SaleHistoryRow> = emptyList()
        val pages = mutableListOf<Pair<SaleHistoryFilter, SaleHistoryCursor?>>()
        val mark = MutableStateFlow("0")
        var fail = false

        override suspend fun page(
            filter: SaleHistoryFilter,
            after: SaleHistoryCursor?,
            limit: Int,
        ): List<SaleHistoryRow> {
            if (fail) error("Falla inventada")
            pages += filter to after
            return matching(filter)
                .sortedWith(
                    compareByDescending<SaleHistoryRow> {
                        it.occurredAt
                    }.thenByDescending { it.id },
                )
                .dropWhile { row ->
                    after != null &&
                        (
                            row.occurredAt > after.occurredAt ||
                                (row.occurredAt == after.occurredAt && row.id >= after.id)
                            )
                }.take(limit)
        }

        override suspend fun dayTotals(
            filter: SaleHistoryFilter,
            dates: Set<BusinessDate>,
        ): Map<BusinessDate, Money> = matching(filter.copy(onlyVoided = false))
            .filter { !it.voided && it.businessDate in dates }
            .groupBy { it.businessDate }
            .mapValues { (_, day) -> Money(day.sumOf { it.total.minor }) }

        override fun observeChanges(): Flow<String> = mark

        // El filtro de métodos se arma en la navegación: esta prueba no lo pide.
        override suspend fun methodsWithSales(methodIds: Set<Long>): Set<Long> = methodIds

        private fun matching(filter: SaleHistoryFilter) = rows.filter { row ->
            (!filter.onlyVoided || row.voided) &&
                (filter.from?.let { row.businessDate >= it } ?: true) &&
                (filter.to?.let { row.businessDate <= it } ?: true)
        }
    }

    private fun row(id: Long, daysAgo: Int, total: Long = 1_000L, voided: Boolean = false) =
        SaleHistoryRow(
            id = id,
            occurredAt = Instant.fromEpochMilliseconds(
                now.toEpochMilliseconds() - daysAgo * DAY_MILLIS,
            ),
            businessDate = today.plusDays(-daysAgo),
            total = Money(total),
            voided = voided,
            firstProductName = "Producto $id",
            lineCount = 1,
            methodName = "Efectivo",
            paymentCount = 1,
        )

    private fun TestScope.viewModel(): SaleHistoryViewModel {
        val model = SaleHistoryViewModel(
            reader = SaleHistoryReader(source, Clock { now }),
            methods = MutableStateFlow(listOf(SaleMethod(1L, "CASH", null, false))),
            calendar = MutableStateFlow(BusinessCalendar.of("Etc/GMT+5", DayOfWeek.MONDAY)),
            computation = dispatcher,
        )
        advanceUntilIdle()
        return model
    }

    private fun SaleHistoryViewModel.act(scope: TestScope, action: SaleHistoryAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val SaleHistoryViewModel.days: HistoryContent.Days
        get() = uiState.value.content as HistoryContent.Days

    @Test
    fun `an empty history invites to sell`() = runTest(dispatcher) {
        assertEquals(HistoryContent.Empty, viewModel().uiState.value.content)
    }

    @Test
    fun `it reads in pages until the end, grouped by day with active sales only`() =
        runTest(dispatcher) {
            source.rows = (1L..120L).map { row(it, daysAgo = (it % 3).toInt()) } +
                row(500L, daysAgo = 0, total = 9_999L, voided = true)
            val model = viewModel()
            assertEquals(SaleHistoryViewModel.PAGE_SIZE, model.days.days.sumOf { it.rows.size })
            assertFalse(model.days.endReached)

            model.act(this, SaleHistoryAction.LoadMore)
            model.act(this, SaleHistoryAction.LoadMore)
            model.act(this, SaleHistoryAction.LoadMore)

            val days = model.days
            assertTrue(days.endReached)
            assertEquals(121, days.days.sumOf { it.rows.size })
            assertEquals(
                listOf(today, today.plusDays(-1), today.plusDays(-2)),
                days.days.map {
                    it.date
                },
            )
            // La anulada se ve en su día, pero no suma.
            assertEquals(Money(40_000L), days.days.first().total)
            // Tres tramos: 50, 50 y 21. Al final no se pide otro.
            assertEquals(3, source.pages.size)
        }

    @Test
    fun `a filter reads again from the start and an empty result offers to clear it`() =
        runTest(dispatcher) {
            source.rows = listOf(row(1L, daysAgo = 0), row(2L, daysAgo = 3))
            val model = viewModel()

            model.act(this, SaleHistoryAction.PeriodKindChange(HistoryPeriodKind.DAY))
            assertEquals(ReportPeriod.Day(today), model.uiState.value.filters.period)
            assertEquals(listOf(1L), model.days.days.single().rows.map { it.id })

            model.act(this, SaleHistoryAction.ToggleVoided)
            assertEquals(HistoryContent.EmptyFiltered, model.uiState.value.content)

            model.act(this, SaleHistoryAction.ClearFilters)
            assertFalse(model.uiState.value.filters.isFiltered)
            assertEquals(2, model.days.days.sumOf { it.rows.size })
        }

    @Test
    fun `moving the period goes back and forward by the same kind`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, SaleHistoryAction.PeriodKindChange(HistoryPeriodKind.DAY))

        model.act(this, SaleHistoryAction.PreviousPeriod)
        assertEquals(ReportPeriod.Day(today.plusDays(-1)), model.uiState.value.filters.period)
        model.act(this, SaleHistoryAction.NextPeriod)
        assertEquals(ReportPeriod.Day(today), model.uiState.value.filters.period)
        model.act(this, SaleHistoryAction.PeriodKindChange(null))
        assertNull(model.uiState.value.filters.period)
    }

    @Test
    fun `a new or voided sale elsewhere reads again what was shown`() = runTest(dispatcher) {
        source.rows = listOf(row(1L, daysAgo = 0))
        val model = viewModel()

        source.rows = source.rows + row(2L, daysAgo = 0)
        source.mark.value = "1"
        advanceUntilIdle()

        assertEquals(listOf(2L, 1L), model.days.days.single().rows.map { it.id })
    }

    @Test
    fun `a method filter, the sheet and the selection for the wide window`() = runTest(dispatcher) {
        source.rows = listOf(row(1L, daysAgo = 0))
        val model = viewModel()

        model.act(this, SaleHistoryAction.OpenFilters)
        assertTrue(model.uiState.value.filterSheet)
        model.act(this, SaleHistoryAction.MethodChange(1L))
        // La hoja de filtros sigue abierta: se puede elegir también `Solo anuladas`.
        assertTrue(model.uiState.value.filterSheet)
        assertEquals(1L, source.pages.last().first.paymentMethodId)
        assertEquals(1, model.uiState.value.filters.sheetFilterCount)
        model.act(this, SaleHistoryAction.ToggleVoided)
        assertEquals(2, model.uiState.value.filters.sheetFilterCount)
        model.act(this, SaleHistoryAction.CloseFilters)
        assertFalse(model.uiState.value.filterSheet)
        model.act(this, SaleHistoryAction.OpenSale(1L))
        assertEquals(1L, model.uiState.value.selectedSaleId)
        model.act(this, SaleHistoryAction.CloseSale)
        assertNull(model.uiState.value.selectedSaleId)
        model.act(this, SaleHistoryAction.Sell)
    }

    @Test
    fun `the period sheet closes on a choice and clearing the sheet keeps the period`() =
        runTest(dispatcher) {
            source.rows = listOf(row(1L, daysAgo = 0))
            val model = viewModel()

            model.act(this, SaleHistoryAction.OpenPeriods)
            assertTrue(model.uiState.value.periodSheet)
            model.act(this, SaleHistoryAction.ClosePeriods)
            assertFalse(model.uiState.value.periodSheet)
            model.act(this, SaleHistoryAction.OpenPeriods)
            model.act(this, SaleHistoryAction.PeriodKindChange(HistoryPeriodKind.WEEK))
            assertFalse(model.uiState.value.periodSheet)
            assertEquals(HistoryPeriodKind.WEEK, model.uiState.value.filters.periodKind)

            model.act(this, SaleHistoryAction.MethodChange(1L))
            model.act(this, SaleHistoryAction.ToggleVoided)
            model.act(this, SaleHistoryAction.ClearSheetFilters)
            val filters = model.uiState.value.filters
            assertNull(filters.methodId)
            assertFalse(filters.onlyVoided)
            assertEquals(0, filters.sheetFilterCount)
            // El período no es de la hoja: sigue elegido.
            assertEquals(HistoryPeriodKind.WEEK, filters.periodKind)
            assertNull(source.pages.last().first.paymentMethodId)
        }

    @Test
    fun `a read failure shows the error and retry reads again`() = runTest(dispatcher) {
        source.fail = true
        val model = viewModel()
        assertEquals(HistoryContent.ReadError, model.uiState.value.content)

        source.fail = false
        model.act(this, SaleHistoryAction.Retry)
        assertEquals(HistoryContent.Empty, model.uiState.value.content)
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
