package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversion
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitConversionResult
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * La unidad en que se vende una línea: su id, cuántas milésimas de la unidad base trae una unidad
 * entera, y el precio de una unidad entera si nadie lo cambia en la línea. [listPrice] falta si el
 * precio calculado con el factor pasa del tope de un precio por unidad: entonces la línea solo se
 * vende con un precio escrito.
 */
data class SaleUnit(val unitId: Long, val factorMilli: Long, val listPrice: Money?) {
    /** Si es la unidad base del producto: la cantidad escrita ya es la del libro. */
    val isBase: Boolean get() = factorMilli == UnitConversion.BASE_UNIT_FACTOR_MILLI
}

/** Cómo resultó la unidad de una línea. */
sealed interface SaleUnitResult {
    data class Resolved(val unit: SaleUnit) : SaleUnitResult

    /** El producto no se vende en esa unidad: no tiene esa conversión, o no se ofrece al vender. */
    data object Unavailable : SaleUnitResult
}

/**
 * Vender en otra unidad: comprar por caja y vender por unidad, o vender por gramos lo que se lleva
 * en kilogramos. El libro de stock siempre queda en la unidad base.
 *
 * - Sin unidad, o con la unidad base, la línea es la de siempre: factor de una unidad y el precio
 *   del producto.
 * - Con otra unidad, la línea usa su conversión: el factor que la línea congela y su precio propio,
 *   o, si no tiene, el del producto llevado a esa unidad con el factor, redondeado al entero más
 *   cercano. Una caja de 24 de un producto de 500 cuesta 12000.
 * - La cantidad del libro sale de la escrita con el factor, también redondeada: 3 cajas de 24 son
 *   72 unidades, y 250 gramos de un producto en kilogramos son 0,250 kg.
 */
object SaleUnits {
    /**
     * La unidad de [line] para [product], con [conversions] de ese producto por id de unidad: solo
     * las que se ofrecen al vender.
     */
    fun of(
        line: SaleLineRequest,
        product: Product,
        conversions: Map<Long, ConversionDetails>,
    ): SaleUnitResult {
        val unitId = line.unitId ?: product.details.baseUnitId
        if (unitId == product.details.baseUnitId) {
            return SaleUnitResult.Resolved(
                SaleUnit(unitId, UnitConversion.BASE_UNIT_FACTOR_MILLI, product.salePrice),
            )
        }
        val conversion = conversions[unitId]?.takeIf { it.forSale }
            ?: return SaleUnitResult.Unavailable
        val price = conversion.salePrice ?: mulDivRound(
            product.salePrice.minor,
            conversion.factorMilli,
            Scale.MILLI,
            Limit.UNIT_MONEY,
        ).valueOrNull()?.let(::Money)
        return SaleUnitResult.Resolved(SaleUnit(unitId, conversion.factorMilli, price))
    }

    /**
     * La cantidad del libro, en la unidad base, de [entered] escrita en [unit], o `null` si no se
     * puede guardar: no es positiva, pasa del tope de una línea o redondea a cero.
     */
    fun baseQuantity(entered: QuantityMilli, unit: SaleUnit): QuantityMilli? {
        if (unit.isBase) return entered.takeIf { it > QuantityMilli.ZERO }
        return when (val converted = UnitConversion.toBaseQuantity(entered, unit.factorMilli)) {
            is UnitConversionResult.Converted -> converted.quantity
            else -> null
        }
    }
}
