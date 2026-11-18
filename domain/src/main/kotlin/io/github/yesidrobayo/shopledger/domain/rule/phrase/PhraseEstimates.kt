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
 * Las líneas de una venta que se registrarían en cero: su producto no tiene precio, como uno
 * creado al producirlo, ni en la unidad dicha ni en la base, y no se dijo ninguno. La tarjeta lo
 * pide, como en un producto nuevo. Fuera de una venta, ninguna.
 */
fun PhraseDraft.unpricedSaleLines(prices: PhrasePrices): Set<Int> {
    if (intent != PhraseIntent.SALE) return emptySet()
    return lines.indices.filterTo(HashSet()) { index ->
        val line = lines[index]
        val id = (line.product as? DraftTarget.Existing)?.productId ?: return@filterTo false
        val listed = prices.sale[id] ?: line.unitId?.let { prices.saleByUnit[id to it] }
        line.unitPrice == null && line.lineTotal == null && listed == null
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
 * conversión guardada, en milésimas de la base; entre unidades métricas del mismo tipo; o, si la
 * base no tiene medida fija, como la libra, a través de la conversión guardada de una unidad
 * métrica del mismo tipo: 200 g de algo que se lleva por libra, con el kilo guardado en 2,205
 * libras. `null` si no se relacionan.
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
    if (said.kind != base.kind) return null
    base.baseAmountMilli?.let { return saidAmount to it }
    // Con los topes de una medida y de un factor, los dos productos caben en Long.
    return product.conversions.firstNotNullOfOrNull { conversion ->
        val measured = units[conversion.unitId]?.takeIf { it.kind == said.kind }
        measured?.baseAmountMilli?.let { amount ->
            saidAmount * conversion.factorMilli to amount * Scale.MILLI
        }
    }
}

private fun Money.amountFor(quantity: QuantityMilli): Money? =
    timesQuantity(quantity, Limit.DOCUMENT_TOTAL).valueOrNull()
