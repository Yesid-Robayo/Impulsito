package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitConcentration
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitLeader
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Los productos que dejan el 80 % de la ganancia en ventas de un período.
 *
 * Se ordenan por lo que dejó cada uno, lo vendido sin impuesto menos su costo, de mayor a menor, y
 * se acumulan hasta llegar a 8000 puntos básicos de la ganancia del período. Un producto entra si
 * lo acumulado antes de él todavía no llega: con `5000`, `3000`, `1000`, `500` y `500`, los dos
 * primeros suman 8000 de 10000 y ahí se corta.
 *
 * Las variantes cuentan en su producto principal, como en `Lo que más se vendió`, y un empate se
 * ordena por id, para que la lista no salte. La comparación con el 80 % es exacta, sin redondear.
 */
object ProfitConcentrationRules {
    /** El corte: el 80 % de la ganancia del período. */
    val THRESHOLD: BasisPoints = BasisPoints(8_000L)

    /** Qué productos de [products] dejan el 80 % de su ganancia, o por qué no hay. */
    fun of(products: List<ProductSales>): ProfitConcentration {
        if (products.isEmpty()) return ProfitConcentration.NoSales
        return try {
            leadersOf(families(products))
        } catch (overflow: ArithmeticException) {
            ProfitConcentration.OutOfRange
        }
    }

    private fun leadersOf(families: List<Family>): ProfitConcentration {
        val total = families.map { it.profit }.sumOrNull()
            ?: return ProfitConcentration.OutOfRange
        if (total <= Money.ZERO) return ProfitConcentration.NoProfit
        val ordered =
            families.sortedWith(compareByDescending<Family> { it.profit }.thenBy { it.id })
        val leaders = mutableListOf<ProfitLeader>()
        var before = Money.ZERO
        for (family in ordered) {
            if (!below(before, total)) break
            val share = shareOf(family.profit, total)
            leaders += ProfitLeader(family.id, family.name, family.profit, share)
            before += family.profit
        }
        return ProfitConcentration.Leaders(leaders, families.size, shareOf(before, total))
    }

    /**
     * Si [before] todavía no llega al 80 % de [total]: `before × 10000 < 8000 × total`, que es lo
     * mismo que `before × 5 < total × 4`. Lanza [ArithmeticException] si no cabe.
     */
    private fun below(before: Money, total: Money): Boolean =
        Math.multiplyExact(before.minor, CUT_DENOMINATOR) <
            Math.multiplyExact(total.minor, CUT_NUMERATOR)

    /** La parte de [total] que es [amount], al punto básico más cercano. */
    private fun shareOf(amount: Money, total: Money): BasisPoints {
        val share = mulDivRound(amount.minor, Scale.BASIS_POINTS, total.minor).valueOrNull()
            ?: throw ArithmeticException("La parte no cabe")
        return BasisPoints(share)
    }

    /** Lo vendido por producto, con las variantes sumadas en su principal. */
    private fun families(products: List<ProductSales>): List<Family> =
        products
            .groupBy { it.variantOf?.parentId ?: it.productId }
            .map { (id, rows) ->
                val parentName = rows.firstNotNullOfOrNull { it.variantOf?.parentName }
                Family(
                    id = id,
                    name = parentName ?: rows.first().name,
                    profit = rows.fold(Money.ZERO) { sum, row -> sum + (row.netSales - row.cost) },
                )
            }

    private class Family(val id: Long, val name: String, val profit: Money)

    // 8000 de 10000 son 4 de 5.
    private const val CUT_NUMERATOR = 4L
    private const val CUT_DENOMINATOR = 5L
}
