package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Un costo por unidad listo para mostrarse: [minor] en una moneda con [extraDecimals] decimales más
 * que la del negocio. Con cero decimales extra es un monto normal.
 */
data class ScaledUnitCost(val minor: Long, val extraDecimals: Int)

/**
 * El costo por unidad base que muestra una conversión, como `Costo por und ¤ 417`.
 *
 * Sale del valor del stock y no del promedio redondeado: una caja de 1000 por 400 cuesta 0,4 por
 * unidad, y redondeado a la unidad menor diría 0, como si el producto no tuviera costo. En ese caso
 * se muestra con los decimales justos para que no sea cero, sin pasar de los que admite el formato.
 */
object UnitCostDisplay {
    private const val RADIX = 10L

    /**
     * El costo exacto de una unidad base de [product], o `null` si no tiene uno que mostrar: hecho de
     * componentes, cuyo costo sale de ellos, o sin costo.
     */
    fun costOf(product: Product): ExactUnitCost? {
        val cost =
            when (product.details.stockMode) {
                ProductStockMode.TRACKED ->
                    MarginCalculator.exactCatalogCost(
                        product.onHand,
                        product.stockValue,
                        product.averageCost,
                    )

                ProductStockMode.UNTRACKED -> ExactUnitCost.perUnit(product.details.fixedUnitCost)

                ProductStockMode.COMPONENTS -> return null
            }
        return cost.takeIf { it.amount > Money.ZERO }
    }

    /**
     * [cost] redondeado a la unidad menor, o con hasta [maxExtraDecimals] decimales más si así
     * redondea a cero. Devuelve [Checked.Overflow] si no cabe en `Long`, algo que con un valor de
     * stock dentro de su tope no pasa.
     */
    fun scaled(cost: ExactUnitCost, maxExtraDecimals: Int): Checked<ScaledUnitCost> {
        require(maxExtraDecimals >= 0) { "Los decimales extra no pueden ser negativos" }
        var extra = 0
        var scale = Scale.MILLI
        while (true) {
            val value =
                when (val result = mulDivRound(cost.amount.minor, scale, cost.quantity.milli)) {
                    is Checked.Value -> result.value
                    else -> return Checked.Overflow
                }
            if (value != 0L || extra == maxExtraDecimals) {
                return Checked.Value(ScaledUnitCost(value, extra))
            }
            extra++
            scale *= RADIX
        }
    }
}
