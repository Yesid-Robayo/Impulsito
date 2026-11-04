package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.map
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull

/**
 * Margen, ganancia en ventas, ganancia final y precio sugerido.
 *
 * Todo se mide sin impuesto, porque el impuesto no es de quien vende: quien calcula sobre el precio
 * con impuesto incluido cree ganar más de lo que gana. Quien llama ya pasa los montos sin él.
 */
object MarginCalculator {
    /**
     * El margen de [price] con este [cost]: `(precio − costo) × 10000 / precio`, al punto básico
     * más cercano.
     *
     * Sirve igual para un producto, una línea, una venta o un período: cambia de dónde salen los
     * dos montos, no la cuenta. Un precio de cero o negativo no tiene margen. Con costo cero el
     * margen es 100 %, aunque el costo falte: la marca de producto sin costo lo advierte.
     *
     * El producto intermedio va en 128 bits: una venta al total máximo de un documento multiplicada
     * por 10000 no cabe en `Long`, aunque su margen sí.
     */
    fun margin(price: Money, cost: Money): Margin {
        if (price <= Money.ZERO) return Margin.NoData
        val grossProfit =
            try {
                price - cost
            } catch (overflow: ArithmeticException) {
                return Margin.OutOfRange
            }
        val marginBp =
            mulDivRound(grossProfit.minor, Scale.BASIS_POINTS, price.minor).valueOrNull()
                ?: return Margin.OutOfRange
        return Margin.Defined(BasisPoints(marginBp))
    }

    /**
     * El costo de una unidad entera con el que se mide el margen de un producto en el catálogo.
     *
     * Con stock y valor de stock positivos es el valor del stock dividido por la cantidad, y no el
     * costo promedio: el valor lleva el costo sin redondear, y el promedio, redondeado a la unidad
     * menor, puede desviarse de él. Sin stock, o sin valor, es el costo promedio. Un producto sin
     * control de stock usa su costo fijo y no pasa por aquí.
     *
     * Devuelve [Checked.Overflow] si el costo no cabe en `Long`, algo que con un valor de stock
     * dentro de su tope no pasa.
     */
    fun catalogUnitCost(
        onHand: QuantityMilli,
        stockValue: Money,
        averageCost: Money,
    ): Checked<Money> {
        if (onHand <= QuantityMilli.ZERO || stockValue <= Money.ZERO) {
            return Checked.Value(averageCost)
        }
        return mulDivRound(stockValue.minor, Scale.MILLI, onHand.milli).map(::Money)
    }

    /**
     * La base del precio sugerido: el costo de reposición, si se conoce, porque el precio debe
     * cubrir lo que costará volver a comprar; si no, el costo de catálogo de [catalogUnitCost].
     */
    fun suggestionBase(replacementCost: Money, catalogUnitCost: Money): Money =
        if (replacementCost > Money.ZERO) replacementCost else catalogUnitCost

    /**
     * El menor precio que deja [targetMargin] sobre [base]: `base × 10000 / (10000 − margen)`.
     *
     * Redondea hacia arriba, igual que el impuesto y el paso, para que el margen resultante nunca
     * quede por debajo del pedido. Si los precios incluyen impuesto, [includedTaxRate] es la tasa
     * del producto y el precio la suma, porque el margen se mide sin ella; si no lo incluyen, o se
     * agrega al cobrar, es cero. Con [roundingStep] positivo sube al siguiente múltiplo del paso,
     * ya con el impuesto, y con cero no redondea.
     *
     * Una tasa fuera de 0 a 100 % o un paso negativo son errores de programación: los dos vienen de
     * datos ya validados. El margen pedido, en cambio, lo escribe la persona en la pantalla.
     */
    fun suggestedPrice(
        base: Money,
        targetMargin: BasisPoints,
        includedTaxRate: BasisPoints,
        roundingStep: Money,
    ): PriceSuggestion {
        require(includedTaxRate.bp in 0L..Scale.BASIS_POINTS) {
            "La tasa de impuesto va de 0 a 100 %"
        }
        require(roundingStep >= Money.ZERO) { "El paso de redondeo no puede ser negativo" }
        if (targetMargin < BasisPoints.ZERO || targetMargin > BasisPoints.MAX_TARGET_MARGIN) {
            return PriceSuggestion.TargetMarginOutOfRange
        }
        if (base <= Money.ZERO) return PriceSuggestion.NoCost
        // El sugerido nunca baja de la base. Si la base ya pasa el tope, el precio también, y
        // descartarla aquí deja cada multiplicación siguiente muy lejos del máximo de Long.
        if (!Limit.UNIT_MONEY.allows(base.minor)) return PriceSuggestion.AbovePriceLimit
        val net =
            ceilDiv(
                Math.multiplyExact(base.minor, Scale.BASIS_POINTS),
                Scale.BASIS_POINTS - targetMargin.bp,
            )
        val withTax =
            ceilDiv(
                Math.multiplyExact(net, Scale.BASIS_POINTS + includedTaxRate.bp),
                Scale.BASIS_POINTS,
            )
        val rounded =
            if (roundingStep > Money.ZERO) ceilToStep(withTax, roundingStep.minor) else withTax
        if (!Limit.UNIT_MONEY.allows(rounded)) return PriceSuggestion.AbovePriceLimit
        return PriceSuggestion.Suggested(Money(rounded))
    }

    /**
     * La ganancia de un período desde sus sumas.
     *
     * Devuelve [Checked.Overflow] si alguna cifra no cabe en `Long`. Solo pasa con sumas absurdas,
     * y el reporte muestra «sin valor» en vez de caerse.
     */
    fun profit(totals: ProfitTotals): Checked<ProfitSummary> = try {
        val netSales = totals.salesNetTotal - totals.returnsNetRefund
        val cogs = totals.salesTotalCost - totals.returnsCostReturned
        val grossProfit = netSales - cogs
        // Un redondeo negativo cobró menos de lo vendido: es costo, y se resta con su signo.
        val paymentCosts = totals.paymentFees - totals.cashRoundingTotal
        val totalExpenses = totals.recordedExpenses + totals.netShrinkage + paymentCosts
        Checked.Value(
            ProfitSummary(
                netSales = netSales,
                cogs = cogs,
                grossProfit = grossProfit,
                grossMargin = margin(netSales, cogs),
                recordedExpenses = totals.recordedExpenses,
                netShrinkage = totals.netShrinkage,
                paymentCosts = paymentCosts,
                totalExpenses = totalExpenses,
                netProfit = grossProfit - totalExpenses,
            ),
        )
    } catch (overflow: ArithmeticException) {
        Checked.Overflow
    }
}
