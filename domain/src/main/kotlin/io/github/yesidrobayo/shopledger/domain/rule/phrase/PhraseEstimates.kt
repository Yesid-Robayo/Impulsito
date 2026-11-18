package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhrasePrices
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity

/**
 * Lo que cobra o cuesta cada línea de la tarjeta, en su orden, para mostrarlo antes de registrar.
 *
 * - Lo dicho manda: el total de la línea, o su precio por la cantidad.
 * - En una venta, una línea sin monto dicho vale lo que pondrá la venta: el precio de su
 *   presentación de venta, o el del producto en la unidad base, por la cantidad. Una unidad que no
 *   es una presentación de venta se lleva a la base como la lleva el registro, con el factor de la
 *   conversión o con la medida métrica. Se redondea igual que la venta.
 * - En las demás operaciones solo cuenta lo dicho: un costo que no se dijo no se inventa.
 *
 * `null` es una línea sin monto que mostrar: sin producto, sin cantidad, sin precio conocido o
 * fuera de los topes.
 */
fun PhraseDraft.estimatedLineAmounts(prices: PhrasePrices, catalog: PhraseCatalog): List<Money?> {
    val products = catalog.products.associateBy { it.id }
    val units = catalog.units.associateBy { it.id }
    return lines.map { line ->
        val said = line.lineTotal != null || line.unitPrice != null
        when {
            said -> PhraseAmounts.lineAmount(line)
            intent == PhraseIntent.SALE -> listedAmount(line, prices, products, units)
            else -> null
        }
    }
}

/**
 * El total que muestra la tarjeta: el dicho, que es el monto de un gasto, un abono o un pago y el
 * "todo por" de una venta o compra; si no se dijo, la suma de las líneas cuando todas tienen monto.
 * `null` si no hay cómo saberlo.
 */
fun PhraseDraft.estimatedTotal(prices: PhrasePrices, catalog: PhraseCatalog): Money? {
    total?.let { return it }
    val amounts = estimatedLineAmounts(prices, catalog)
    if (amounts.isEmpty() || amounts.any { it == null }) return null
    return amounts.filterNotNull().sumOrNull()
}

/** Lo que cobra la venta de siempre por una línea sin precio dicho. */
private fun listedAmount(
    line: DraftLine,
    prices: PhrasePrices,
    products: Map<Long, PhraseProduct>,
    units: Map<Long, PhraseUnit>,
): Money? {
    val productId = (line.product as? DraftTarget.Existing)?.productId ?: return null
    val product = products[productId] ?: return null
    val quantity = line.quantity ?: return null
    val unitId = line.unitId
    if (unitId != null && unitId != product.baseUnitId) {
        prices.saleByUnit[productId to unitId]?.let { return it.amountFor(quantity) }
        val inBase = baseQuantity(product, unitId, quantity, units) ?: return null
        return prices.sale[productId]?.amountFor(inBase)
    }
    return prices.sale[productId]?.amountFor(quantity)
}

/**
 * [quantity] de [unitId] en la unidad base del producto, como la lleva el registro: con la
 * conversión guardada, o entre unidades métricas del mismo tipo. `null` si no se puede.
 */
internal fun baseQuantity(
    product: PhraseProduct,
    unitId: Long,
    quantity: QuantityMilli,
    units: Map<Long, PhraseUnit>,
): QuantityMilli? = measuresOf(product, unitId, units)?.let { (said, base) ->
    PhraseMeasures.convert(quantity, said, base)
}

/**
 * [quantity] de la unidad base del producto llevada a [unitId], al revés que [baseQuantity]: 0,200
 * kg son 200 g. `null` si no se puede.
 */
internal fun quantityIn(
    product: PhraseProduct,
    unitId: Long,
    quantity: QuantityMilli,
    units: Map<Long, PhraseUnit>,
): QuantityMilli? = measuresOf(product, unitId, units)?.let { (said, base) ->
    PhraseMeasures.convert(quantity, base, said)
}

/**
 * Lo que mide una unidad de [unitId] y una de la base del producto, en la misma medida: con la
 * conversión guardada, en milésimas de la base, o entre unidades métricas del mismo tipo. `null`
 * si no se relacionan.
 */
private fun measuresOf(
    product: PhraseProduct,
    unitId: Long,
    units: Map<Long, PhraseUnit>,
): Pair<Long, Long>? {
    product.conversions.firstOrNull { it.unitId == unitId }?.let {
        return it.factorMilli to Scale.MILLI
    }
    val said = units[unitId] ?: return null
    val base = units[product.baseUnitId] ?: return null
    val saidAmount = said.baseAmountMilli ?: return null
    val baseAmount = base.baseAmountMilli ?: return null
    if (said.kind != base.kind) return null
    return saidAmount to baseAmount
}

private fun Money.amountFor(quantity: QuantityMilli): Money? =
    timesQuantity(quantity, Limit.DOCUMENT_TOTAL).valueOrNull()
