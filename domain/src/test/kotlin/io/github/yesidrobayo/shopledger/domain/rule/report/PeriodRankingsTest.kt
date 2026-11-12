package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Los más vendidos, los gastos por categoría y los rangos propios, con valores a mano. */
class PeriodRankingsTest {
    private val unit = 1L
    private val kilogram = 2L

    @Test
    fun `the top ten by sales and by profit leave out what did not sell or earn`() {
        val products =
            (1L..12L).map { id -> sales(id, net = id * 1_000, cost = id * 900) } +
                // Devuelto entero: no destaca. Vendido a pérdida: no está entre los que más ganan.
                sales(20L, net = 0, cost = 0, quantity = 0) +
                sales(21L, net = 500, cost = 600)

        val top = topOf(products)

        assertEquals((12L downTo 3L).toList(), top.bySales.map { it.productId })
        assertEquals((12L downTo 3L).toList(), top.byProfit.map { it.productId })
        // 12.000 vendidos con 10.800 de costo dejan 1.200.
        assertEquals(Money(1_200), top.byProfit.first().grossProfit)
    }

    @Test
    fun `ties are ordered by id so the list does not jump`() {
        val top = topOf(listOf(sales(7L, net = 1_000, cost = 0), sales(3L, net = 1_000, cost = 0)))

        assertEquals(listOf(3L, 7L), top.bySales.map { it.productId })
    }

    @Test
    fun `the top by quantity is separated by unit`() {
        val top =
            topOf(
                listOf(
                    sales(1L, net = 100, cost = 0, quantity = 2_000),
                    sales(2L, net = 100, cost = 0, quantity = 1_500, unitId = kilogram),
                    sales(3L, net = 100, cost = 0, quantity = 5_000),
                ),
            )

        assertEquals(listOf(unit, kilogram), top.byQuantity.map { it.unitId })
        assertEquals(listOf(3L, 1L), top.byQuantity.first().products.map { it.productId })
        assertEquals(listOf(2L), top.byQuantity.last().products.map { it.productId })
    }

    @Test
    fun `a profit that does not fit is an overflow`() {
        assertEquals(
            Checked.Overflow,
            PeriodRankings.top(listOf(sales(1L, net = Long.MAX_VALUE, cost = -1))),
        )
    }

    @Test
    fun `expense rows add up to the total expenses`() {
        // Gastos por 640.000 en dos categorías, daños por 85.000 y comisiones por 42.300.
        val categories =
            listOf(
                ExpenseCategoryTotal(1L, null, "RENT", Money(600_000)),
                ExpenseCategoryTotal(2L, "Bolsas inventadas", null, Money(40_000)),
            )
        val rows = PeriodRankings.expenses(categories, profit(shrinkage = 85_000, fees = 42_300))

        assertEquals(
            listOf(
                ExpenseRow.Category(categories[0]),
                ExpenseRow.Category(categories[1]),
                ExpenseRow.Shrinkage(Money(85_000)),
                ExpenseRow.Fees(Money(42_300)),
            ),
            rows,
        )
        assertEquals(767_300L, rows.sumOf { it.amount.minor })
    }

    @Test
    fun `a surplus subtracts and empty extra rows do not appear`() {
        val rows = PeriodRankings.expenses(emptyList(), profit(shrinkage = -1_355, fees = 0))

        assertEquals(listOf(ExpenseRow.Shrinkage(Money(-1_355))), rows)
        assertEquals(emptyList<ExpenseRow>(), PeriodRankings.expenses(emptyList(), profit(0, 0)))
    }

    @Test
    fun `a range of its own moves by its length and has no kind`() {
        val range = ReportPeriod.Range(date("2026-11-03"), date("2026-11-05"))

        assertNull(PeriodNavigation.kindOf(range))
        assertEquals(3, PeriodNavigation.lengthOf(range))
        assertEquals(
            ReportPeriod.Range(date("2026-10-31"), date("2026-11-02")),
            PeriodNavigation.previous(range),
        )
        assertEquals(
            ReportPeriod.Range(date("2026-11-06"), date("2026-11-08")),
            PeriodNavigation.next(range),
        )
        assertEquals(PeriodKind.DAY, PeriodNavigation.kindOf(ReportPeriod.Day(date("2026-11-03"))))
    }

    private fun topOf(products: List<ProductSales>): TopProducts =
        (PeriodRankings.top(products) as Checked.Value).value

    private fun sales(
        id: Long,
        net: Long,
        cost: Long,
        quantity: Long = 1_000,
        unitId: Long = unit,
    ) = ProductSales(id, "Producto $id", unitId, QuantityMilli(quantity), Money(net), Money(cost))

    private fun profit(shrinkage: Long, fees: Long) = ProfitSummary(
        netSales = Money.ZERO,
        cogs = Money.ZERO,
        grossProfit = Money.ZERO,
        grossMargin = Margin.Defined(BasisPoints.ZERO),
        recordedExpenses = Money(640_000),
        netShrinkage = Money(shrinkage),
        paymentCosts = Money(fees),
        totalExpenses = Money(640_000 + shrinkage + fees),
        netProfit = Money.ZERO,
    )

    private fun date(text: String) = BusinessDate(LocalDate.parse(text))
}
