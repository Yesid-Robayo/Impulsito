package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.ExactUnitCost
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.PriceSuggestion
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator

/**
 * Cómo se mide el margen de un precio de un producto y qué precio se sugiere para un margen.
 *
 * [cost] es el costo de una unidad entera sin redondear, o `null` si no se conoce: sin costo, el
 * margen saldría inflado contra cero. [suggestionBase] es de donde parte el precio sugerido: el
 * costo de reposición si se conoce, porque el precio debe cubrir lo que costará volver a comprar,
 * y si no el mismo costo; en cero no hay sugerencia. [targetMargin] es el margen con que la
 * sugerencia abre, que la persona puede cambiar sin guardarlo en el producto.
 *
 * Todo margen se mide sin impuesto: con los precios con impuesto incluido, el precio escrito lleva
 * [taxRate] y la cuenta lo quita antes de medir, y el sugerido lo suma al final.
 */
data class ProductPricing(
    val cost: ExactUnitCost?,
    val suggestionBase: ExactUnitCost,
    val targetMargin: BasisPoints,
    val taxMode: TaxMode,
    val taxRate: BasisPoints,
    val roundingStep: Money,
) {
    /** Si hay de donde sugerir un precio. */
    val canSuggest: Boolean get() = suggestionBase.amount > Money.ZERO

    /**
     * El margen que deja [price], el precio escrito, contra el costo sin redondear. Sin precio o
     * sin costo no hay margen.
     */
    fun marginOf(price: Money): Margin {
        val unitCost = cost ?: return Margin.NoData
        return MarginCalculator.exactMargin(netOf(price), unitCost)
    }

    /**
     * Lo que queda de [price], el precio escrito, sin el impuesto que lleva dentro: la base de la
     * ganancia. Sin impuesto incluido es el mismo precio.
     */
    fun netOf(price: Money): Money =
        // El neto nunca supera al precio, así que la división nunca se rechaza.
        TaxCalculator.catalogNetPrice(price, taxMode, taxRate).valueOrNull()!!

    /**
     * El menor precio, con el impuesto incluido si corresponde y subido al paso, que deja [target]
     * sobre [suggestionBase]. Por defecto, el margen objetivo del producto.
     */
    fun suggest(target: BasisPoints = targetMargin): PriceSuggestion =
        MarginCalculator.suggestedPrice(
            base = suggestionBase,
            targetMargin = target,
            includedTaxRate = if (taxMode == TaxMode.INCLUDED) taxRate else BasisPoints.ZERO,
            roundingStep = roundingStep,
        )

    companion object {
        /**
         * Lo de [product], ya guardado, con lo que fija el negocio en [context].
         *
         * Con stock propio, el costo es el valor del stock sobre la cantidad, sin dividir, o el
         * costo promedio sin stock; sin stock propio, su costo fijo por unidad vendida. Un producto
         * por componentes no tiene costo propio aquí: sale de sus componentes.
         */
        fun of(product: Product, context: PricingContext): ProductPricing {
            val cost = when (product.details.stockMode) {
                ProductStockMode.TRACKED ->
                    MarginCalculator
                        .exactCatalogCost(product.onHand, product.stockValue, product.averageCost)
                        .takeIf { it.amount > Money.ZERO }

                ProductStockMode.UNTRACKED -> known(product.details.fixedUnitCost)

                ProductStockMode.COMPONENTS -> null
            }
            return draft(
                context = context,
                cost = cost,
                replacementCost =
                    product.replacementCost.takeIf {
                        product.details.stockMode != ProductStockMode.COMPONENTS
                    } ?: Money.ZERO,
                productTarget = product.details.targetMargin,
                categoryId = product.details.categoryId,
                taxRateId = product.details.taxRateId,
            )
        }

        /**
         * Lo de un producto mientras se escribe: [cost] es el costo de una unidad entera que se
         * conoce, o `null` sin costo, y el resto sale del producto y de su categoría elegida.
         */
        fun draft(
            context: PricingContext,
            cost: ExactUnitCost?,
            replacementCost: Money,
            productTarget: BasisPoints?,
            categoryId: Long?,
            taxRateId: Long?,
        ): ProductPricing = ProductPricing(
            cost = cost,
            suggestionBase = known(replacementCost) ?: cost ?: ExactUnitCost.perUnit(Money.ZERO),
            targetMargin = context.targetMargin(productTarget, categoryId),
            taxMode = context.taxMode,
            taxRate = context.taxRate(taxRateId),
            roundingStep = context.roundingStep,
        )

        /** [amount] por una unidad entera, o `null` si es cero: un costo en cero es desconocido. */
        private fun known(amount: Money): ExactUnitCost? =
            if (amount > Money.ZERO) ExactUnitCost.perUnit(amount) else null
    }
}
