package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales

/**
 * Un producto en un ranking, con su ganancia en ventas ya calculada.
 *
 * Un producto con variantes vendidas llega como su principal, con la suma de ellas, y [variants]
 * las trae debajo, en el orden del ranking: cada una con lo suyo y [values] con sus valores.
 * Solo faltan las que dan cero en la cifra del ranking, así que las de la lista suman lo del
 * principal.
 * Lo que el principal vendió antes de tener variantes va entre ellas, con [values] en `null`.
 */
data class RankedProduct(
    val productId: Long,
    val name: String,
    val baseUnitId: Long,
    val quantity: QuantityMilli,
    val netSales: Money,
    val grossProfit: Money,
    val variants: List<RankedProduct> = emptyList(),
    val values: VariantValues? = null,
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
     * Los diez primeros por ventas, por ganancia y por cantidad de cada unidad. Las variantes
     * cuentan en su producto principal, y en el ranking por cantidad solo con las de su misma
     * unidad. Solo entran los que vendieron, ganaron o salieron de verdad: un producto devuelto
     * entero no destaca. Los empates se ordenan por id, para que la lista no salte entre lecturas.
     * Devuelve [Checked.Overflow] si una suma o una ganancia no cabe en `Long`.
     */
    fun top(products: List<ProductSales>): Checked<TopProducts> = try {
        val sold = products.map(::rankedOf)
        val families = sold.groupBy { it.family }
        Checked.Value(
            TopProducts(
                bySales = families.values.ranked(byNetSales),
                byProfit = families.values.ranked(byGrossProfit),
                byQuantity = sold.groupBy { it.product.baseUnitId }
                    .toSortedMap()
                    .map { (unit, inUnit) ->
                        UnitRanking(unit, inUnit.groupBy { it.family }.values.ranked(byQuantity))
                    },
            ),
        )
    } catch (overflow: ArithmeticException) {
        Checked.Overflow
    }

    /**
     * Los primeros [TOP_LIMIT] de estas familias por [measure]: cada una sumada en su principal,
     * con sus variantes debajo si las tiene.
     */
    private fun Collection<List<Sold>>.ranked(measure: Measure): List<RankedProduct> =
        map { family -> familyOf(family, measure) }
            .filter { measure.of(it) > 0L }
            .sortedWith(measure.order)
            .take(TOP_LIMIT)

    private fun familyOf(family: List<Sold>, measure: Measure): RankedProduct {
        val first = family.first()
        if (family.none { it.isVariant }) return first.product
        val variants = family.map { it.product }.filter { measure.of(it) != 0L }
        return RankedProduct(
            productId = first.family,
            name = first.familyName,
            baseUnitId = first.product.baseUnitId,
            quantity = family.fold(QuantityMilli.ZERO) { sum, it -> sum + it.product.quantity },
            netSales = family.fold(Money.ZERO) { sum, it -> sum + it.product.netSales },
            grossProfit = family.fold(Money.ZERO) { sum, it -> sum + it.product.grossProfit },
            variants = variants.sortedWith(measure.order),
        )
    }

    private fun rankedOf(sales: ProductSales): Sold {
        val variantOf = sales.variantOf
        val product =
            RankedProduct(
                productId = sales.productId,
                name = sales.name,
                baseUnitId = sales.baseUnitId,
                quantity = sales.quantity,
                netSales = sales.netSales,
                grossProfit = sales.netSales - sales.cost,
            )
        return if (variantOf == null) {
            Sold(product, sales.productId, sales.name, isVariant = false)
        } else {
            Sold(
                product.copy(values = variantOf.values),
                variantOf.parentId,
                variantOf.parentName,
                isVariant = true,
            )
        }
    }

    /** Lo vendido de un producto, con la familia en que se suma: su principal, o él mismo. */
    private class Sold(
        val product: RankedProduct,
        val family: Long,
        val familyName: String,
        val isVariant: Boolean,
    )

    /** La cifra por la que se ordena un ranking, de mayor a menor y, en empate, por id. */
    private class Measure(val of: (RankedProduct) -> Long) {
        val order: Comparator<RankedProduct> =
            compareByDescending<RankedProduct> { of(it) }.thenBy { it.productId }
    }

    private val byNetSales = Measure { it.netSales.minor }
    private val byGrossProfit = Measure { it.grossProfit.minor }
    private val byQuantity = Measure { it.quantity.milli }

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
