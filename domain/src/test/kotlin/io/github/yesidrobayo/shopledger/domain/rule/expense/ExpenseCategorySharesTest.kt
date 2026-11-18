package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El resumen por categoría de los gastos del mes, con las partes calculadas a mano. */
class ExpenseCategorySharesTest {
    @Test
    fun `categories go from largest to smallest with their share of the month`() {
        // 820.000 en el mes: 500.000 son 60,98 %, 200.000 son 24,39 % y 120.000 son 14,63 %.
        val summary =
            ExpenseCategoryShares.of(
                listOf(total(3, 120_000), total(1, 500_000), total(2, 200_000)),
            )

        assertEquals(
            ExpenseCategorySummary(
                total = Money(820_000),
                rows =
                    listOf(
                        ExpenseShareRow.Category(total(1, 500_000), BasisPoints(6_098)),
                        ExpenseShareRow.Category(total(2, 200_000), BasisPoints(2_439)),
                        ExpenseShareRow.Category(total(3, 120_000), BasisPoints(1_463)),
                    ),
            ),
            summary,
        )
    }

    @Test
    fun `a single category is the whole month`() {
        val summary = ExpenseCategoryShares.of(listOf(total(4, 35_000)))

        assertEquals(
            ExpenseCategorySummary(
                total = Money(35_000),
                rows = listOf(ExpenseShareRow.Category(total(4, 35_000), BasisPoints(10_000))),
            ),
            summary,
        )
    }

    @Test
    fun `a tie keeps the lower id first so the order does not jump`() {
        val summary = ExpenseCategoryShares.of(listOf(total(9, 1_000), total(2, 1_000)))

        assertEquals(
            listOf(2L, 9L),
            summary?.rows?.map { (it as ExpenseShareRow.Category).total.categoryId },
        )
        assertEquals(
            listOf(BasisPoints(5_000), BasisPoints(5_000)),
            summary?.rows?.map { it.share },
        )
    }

    @Test
    fun `beyond five categories the rest go together in one row`() {
        // 7 categorías que suman 10.000: las cinco mayores, y 700 + 300 juntas, el 10 %.
        val summary =
            ExpenseCategoryShares.of(
                listOf(
                    total(1, 3_000),
                    total(2, 2_000),
                    total(3, 1_500),
                    total(4, 1_500),
                    total(5, 1_000),
                    total(6, 700),
                    total(7, 300),
                ),
            )

        assertEquals(Money(10_000), summary?.total)
        assertEquals(6, summary?.rows?.size)
        assertEquals(
            ExpenseShareRow.Others(count = 2, amount = Money(1_000), share = BasisPoints(1_000)),
            summary?.rows?.last(),
        )
        assertEquals(
            listOf(3_000L, 2_000L, 1_500L, 1_500L, 1_000L, 1_000L),
            summary?.rows?.map { it.amount.minor },
        )
    }

    @Test
    fun `exactly five categories need no row of others`() {
        val summary = ExpenseCategoryShares.of((1L..5L).map { total(it, 2_000) })

        assertEquals(5, summary?.rows?.size)
        assertEquals(List(5) { BasisPoints(2_000) }, summary?.rows?.map { it.share })
    }

    @Test
    fun `each share rounds to the nearest basis point on its own`() {
        // 1 de 3 es 3333,33 puntos y 2 de 3 es 6666,67: suman 9999, y los montos sí suman 3.
        val summary = ExpenseCategoryShares.of(listOf(total(1, 2), total(2, 1)))

        assertEquals(Money(3), summary?.total)
        assertEquals(
            listOf(BasisPoints(6_667), BasisPoints(3_333)),
            summary?.rows?.map { it.share },
        )
    }

    @Test
    fun `a month without active expenses has no summary`() {
        assertNull(ExpenseCategoryShares.of(emptyList()))
        // Una categoría en cero no se muestra: no hay nada que repartir.
        assertNull(ExpenseCategoryShares.of(listOf(total(1, 0))))
    }

    @Test
    fun `a total that does not fit has no summary instead of a wrong share`() {
        assertNull(
            ExpenseCategoryShares.of(listOf(total(1, Long.MAX_VALUE), total(2, Long.MAX_VALUE))),
        )
    }

    private fun total(id: Long, minor: Long) =
        ExpenseCategoryTotal(id, "Categoría $id", systemKey = null, amount = Money(minor))
}
