package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import io.github.yesidrobayo.shopledger.domain.model.report.VariantOf
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El producto principal de las variantes de prueba. */
private const val SHIRT = 10L

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
    fun `a parent adds up its variants and lists them under it`() {
        // Camiseta: M Rojo vende 8.000 con costo 5.000; M Azul, 7.000 con 4.000; L Rojo se
        // devolvió entera; y antes de tener variantes la camiseta vendió 2.000 con costo 2.500.
        val products =
            listOf(
                variant(11L, "M", "Rojo", net = 8_000, cost = 5_000),
                variant(12L, "M", "Azul", net = 7_000, cost = 4_000),
                variant(13L, "L", "Rojo", net = 0, cost = 0, quantity = 0),
                sales(SHIRT, net = 2_000, cost = 2_500).copy(name = "Camiseta"),
                sales(1L, net = 5_000, cost = 3_000),
            )

        val top = topOf(products)

        // Por ventas: 8.000 + 7.000 + 0 + 2.000 = 17.000, por encima de los 5.000 del café.
        val bySales = top.bySales.first()
        assertEquals(listOf(SHIRT, 1L), top.bySales.map { it.productId })
        assertEquals("Camiseta", bySales.name)
        assertEquals(Money(17_000), bySales.netSales)
        assertEquals(listOf(11L, 12L, SHIRT), bySales.variants.map { it.productId })
        assertEquals(
            listOf(Money(8_000), Money(7_000), Money(2_000)),
            bySales.variants.map { it.netSales },
        )
        assertEquals(VariantValues("M", "Rojo"), bySales.variants.first().values)
        assertNull(bySales.variants.last().values)
        assertEquals(emptyList<RankedProduct>(), top.bySales.last().variants)
        // Por ganancia: 3.000 + 3.000 + 0 − 500 = 5.500. La pérdida de antes resta y se ve.
        val byProfit = top.byProfit.first()
        assertEquals(Money(5_500), byProfit.grossProfit)
        assertEquals(listOf(11L, 12L, SHIRT), byProfit.variants.map { it.productId })
        assertEquals(Money(-500), byProfit.variants.last().grossProfit)
        // Por cantidad: tres unidades, una de cada una que salió.
        val byQuantity = top.byQuantity.single().products
        assertEquals(listOf(SHIRT, 1L), byQuantity.map { it.productId })
        assertEquals(QuantityMilli(3_000), byQuantity.first().quantity)
        assertEquals(listOf(SHIRT, 11L, 12L), byQuantity.first().variants.map { it.productId })
    }

    @Test
    fun `variants in another unit count apart by quantity`() {
        val top =
            topOf(
                listOf(
                    variant(21L, "Grande", null, net = 900, cost = 0, quantity = 1_500)
                        .copy(baseUnitId = kilogram),
                    variant(22L, "Chico", null, net = 600, cost = 0, quantity = 2_000),
                ),
            )

        assertEquals(Money(1_500), top.bySales.single().netSales)
        val (inUnits, inKilograms) = top.byQuantity
        assertEquals(QuantityMilli(2_000), inUnits.products.single().quantity)
        assertEquals(listOf(22L), inUnits.products.single().variants.map { it.productId })
        assertEquals(QuantityMilli(1_500), inKilograms.products.single().quantity)
        assertEquals(listOf(21L), inKilograms.products.single().variants.map { it.productId })
    }

    @Test
    fun `variants that do not fit together are an overflow`() {
        val half = Long.MAX_VALUE / 2 + 1

        assertEquals(
            Checked.Overflow,
            PeriodRankings.top(
                listOf(
                    variant(11L, "M", null, net = half, cost = 0),
                    variant(12L, "L", null, net = half, cost = 0),
                ),
            ),
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

    private fun variant(
        id: Long,
        first: String,
        second: String?,
        net: Long,
        cost: Long,
        quantity: Long = 1_000,
    ) = sales(id, net, cost, quantity).copy(
        name = "Camiseta · $first",
        variantOf = VariantOf(SHIRT, "Camiseta", VariantValues(first, second)),
    )

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
