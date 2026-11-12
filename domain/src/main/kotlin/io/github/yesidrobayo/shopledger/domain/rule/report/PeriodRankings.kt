package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales

/** Un producto en un ranking, con su ganancia en ventas ya calculada. */
data class RankedProduct(
    val productId: Long,
    val name: String,
    val baseUnitId: Long,
    val quantity: QuantityMilli,
    val netSales: Money,
    val grossProfit: Money,
)

/** Los más vendidos de una misma unidad: nunca se suman kilos con unidades. */
data class UnitRanking(val unitId: Long, val products: List<RankedProduct>)

/** Los destacados de un período: por ventas, por ganancia y por cantidad, separada por unidad. */
data class TopProducts(
    val bySales: List<RankedProduct>,
    val byProfit: List<RankedProduct>,
    val byQuantity: List<UnitRanking>,
)

/** Una fila de `Gastos por categoría`. Las filas suman los gastos totales del período. */
sealed interface ExpenseRow {
    /** El monto de la fila: lo que suma a los gastos totales, con su signo. */
    val amount: Money

    /** Lo registrado en una categoría. */
    data class Category(val total: ExpenseCategoryTotal) : ExpenseRow {
        override val amount: Money get() = total.amount
    }

    /** Daños y pérdidas; negativo si sobró más de lo que se perdió, y entonces se llama sobrante. */
    data class Shrinkage(override val amount: Money) : ExpenseRow

    /** Comisiones de cobro, menos lo que dejó el redondeo del efectivo. */
    data class Fees(override val amount: Money) : ExpenseRow
}

/** Los rankings y desgloses de un período, desde lo que suma la base. */
object PeriodRankings {
    /** Cuántos productos muestra cada ranking. */
    const val TOP_LIMIT: Int = 10

    /**
     * Los diez primeros por ventas, por ganancia y por cantidad de cada unidad. Solo entran los que
     * vendieron, ganaron o salieron de verdad: un producto devuelto entero no destaca. Los empates
     * se ordenan por id, para que la lista no salte entre lecturas. Devuelve [Checked.Overflow] si
     * una ganancia no cabe en `Long`.
     */
    fun top(products: List<ProductSales>): Checked<TopProducts> = try {
        val ranked =
            products.map {
                RankedProduct(
                    productId = it.productId,
                    name = it.name,
                    baseUnitId = it.baseUnitId,
                    quantity = it.quantity,
                    netSales = it.netSales,
                    grossProfit = it.netSales - it.cost,
                )
            }
        Checked.Value(
            TopProducts(
                bySales = ranked.filter { it.netSales > Money.ZERO }
                    .sortedWith(
                        compareByDescending<RankedProduct> {
                            it.netSales
                        }.thenBy { it.productId },
                    )
                    .take(TOP_LIMIT),
                byProfit = ranked.filter { it.grossProfit > Money.ZERO }
                    .sortedWith(
                        compareByDescending<RankedProduct> {
                            it.grossProfit
                        }.thenBy { it.productId },
                    )
                    .take(TOP_LIMIT),
                byQuantity = ranked.filter { it.quantity > QuantityMilli.ZERO }
                    .groupBy { it.baseUnitId }
                    .toSortedMap()
                    .map { (unit, inUnit) ->
                        UnitRanking(
                            unit,
                            inUnit.sortedWith(
                                compareByDescending<RankedProduct> {
                                    it.quantity
                                }.thenBy { it.productId },
                            ).take(TOP_LIMIT),
                        )
                    },
            ),
        )
    } catch (overflow: ArithmeticException) {
        Checked.Overflow
    }

    /**
     * Los gastos del período por categoría, en el orden dado, y después daños y pérdidas y las
     * comisiones como filas aparte, solo si no son cero. Las filas suman lo mismo que los gastos
     * totales de [profit].
     */
    fun expenses(categories: List<ExpenseCategoryTotal>, profit: ProfitSummary): List<ExpenseRow> =
        buildList {
            categories.forEach { add(ExpenseRow.Category(it)) }
            if (profit.netShrinkage != Money.ZERO) add(ExpenseRow.Shrinkage(profit.netShrinkage))
            if (profit.paymentCosts != Money.ZERO) add(ExpenseRow.Fees(profit.paymentCosts))
        }
}
