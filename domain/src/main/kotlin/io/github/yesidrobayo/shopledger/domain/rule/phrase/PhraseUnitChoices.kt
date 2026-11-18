package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.phrase.SaidConversion
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Las unidades en que se puede llevar [product] en una línea de la tarjeta: su base, sus
 * conversiones y, si alguna de ellas tiene medida fija, las demás unidades métricas de su misma
 * clase, que se convierten solas, como el gramo de algo que se lleva por kilo. En ese orden, sin
 * repetir, y solo las que están en el catálogo.
 */
fun PhraseCatalog.unitsFor(product: PhraseProduct): List<PhraseUnit> {
    val byId = units.associateBy { it.id }
    val own = (listOf(product.baseUnitId) + product.conversions.map { it.unitId })
        .distinct()
        .mapNotNull(byId::get)
    val measured = own.filter { it.baseAmountMilli != null }.mapTo(HashSet()) { it.kind }
    return own + units.filter { it.baseAmountMilli != null && it.kind in measured && it !in own }
}

/**
 * Lo que se pregunta de una línea dicha en [saidUnitId], una unidad que [product] no admite pero
 * de la misma clase que su base, cuando una de las dos no tiene medida fija: "200 g" de harina que
 * se lleva por libra, o "2 libras" de queso que se lleva por kilo. Se pregunta cuánto mide una de
 * la que no la tiene, [MeasureQuestion.unitId], en [MeasureQuestion.measureUnitId]. `null` si no
 * hay nada que preguntar así: otra clase, las dos con medida o las dos sin ella.
 */
fun PhraseCatalog.measureQuestion(product: PhraseProduct, saidUnitId: Long): MeasureQuestion? {
    val byId = units.associateBy { it.id }
    val said = byId[saidUnitId] ?: return null
    val base = byId[product.baseUnitId] ?: return null
    if (said.kind != base.kind || said.id == base.id) return null
    val loose = listOf(said, base).singleOrNull { it.baseAmountMilli == null } ?: return null
    val metric = if (loose == said) base else said
    val shown = SHOWN_IN[said.kind]?.let { key -> units.firstOrNull { it.systemKey == key } }
        ?: metric
    val options = STANDARD[loose.systemKey].orEmpty()
    return MeasureQuestion(loose.id, shown.id, options)
}

/**
 * Cuánto mide una de [unitId] para preguntarlo: se responde en [measureUnitId], una unidad métrica
 * de su clase, eligiendo una de [optionsMilli], lo que mide en milésimas de gramo o de mililitro
 * según los usos comunes, o escribiendo otro valor. Una unidad propia no trae opciones.
 */
data class MeasureQuestion(val unitId: Long, val measureUnitId: Long, val optionsMilli: List<Long>)

/**
 * La línea de [product] dicha en otra unidad, [DraftLine.unfit], llevada a su unidad base con lo
 * que mide una de las dos según la persona, [amountMilli] milésimas de gramo o de mililitro, y la
 * conversión que eso deja guardada al registrar. El precio dicho por la unidad dicha se lleva a la
 * base igual. `null` si la línea no es de las que pregunta [measureQuestion], si la medida no es
 * mayor que cero o si algo redondea a cero o pasa de sus topes.
 *
 * La conversión es de la unidad sin medida, si es la dicha: una libra de queso trae 0,454 kg. Si
 * la que no tiene medida es la base, es de la unidad métrica más grande de su clase, para no perder
 * precisión: un kilo de harina trae 2,205 libras, y desde ahí el gramo se convierte solo.
 */
fun DraftLine.measuredWith(
    product: PhraseProduct,
    catalog: PhraseCatalog,
    amountMilli: Long,
): MeasuredLine? {
    val unfit = unfit ?: return null
    val question = catalog.measureQuestion(product, unfit.unitId) ?: return null
    if (amountMilli <= 0L) return null
    val byId = catalog.units.associateBy { it.id }
    val said = byId.getValue(unfit.unitId)
    val base = byId.getValue(product.baseUnitId)
    val saidAmount = said.baseAmountMilli ?: amountMilli
    val baseAmount = base.baseAmountMilli ?: amountMilli
    val quantity = unfit.quantity?.let {
        PhraseMeasures.convert(it, saidAmount, baseAmount) ?: return null
    }
    val price = unitPrice?.let { PhraseMeasures.priceIn(it, saidAmount, baseAmount) ?: return null }
    val conversion = if (question.unitId == said.id) {
        SaidConversion(product.id, said.id, factorOf(amountMilli, baseAmount) ?: return null)
    } else {
        // La dicha es métrica: siempre hay al menos una.
        val (largest, amount) = catalog.units
            .filter { it.kind == said.kind }
            .mapNotNull { unit -> unit.baseAmountMilli?.let { unit to it } }
            .maxBy { it.second }
        SaidConversion(product.id, largest.id, factorOf(amount, amountMilli) ?: return null)
    }
    val line = copy(quantity = quantity, unitId = null, unitPrice = price, unfit = null)
    return MeasuredLine(line, conversion)
}

/** La línea ya llevada a la base del producto, y la conversión que se guarda al registrar. */
data class MeasuredLine(val line: DraftLine, val conversion: SaidConversion)

/**
 * Milésimas de la base que trae una unidad que mide [unitAmount], si la base mide [baseAmount]:
 * `null` si redondea a cero o pasa del tope de una línea.
 */
private fun factorOf(unitAmount: Long, baseAmount: Long): Long? =
    mulDivRound(unitAmount, Scale.MILLI, baseAmount, Limit.LINE_QUANTITY_MILLI)
        .valueOrNull()
        ?.takeIf { it > 0L }

/** En qué unidad se pregunta la medida de cada clase: el gramo y el litro, si están. */
private val SHOWN_IN = mapOf(UnitKind.MASS to "gram", UnitKind.VOLUME to "liter")

/**
 * Lo que mide una unidad sin medida fija según los usos comunes, en milésimas de gramo o de
 * mililitro, sin nombrar dónde se usa cada uno: la libra de 500 g y la de 453,59 g, la onza de
 * 28,35 g, y el galón de 3,785 L y el de 4,546 L.
 */
private val STANDARD: Map<String?, List<Long>> = mapOf(
    "pound" to listOf(500_000L, 453_590L),
    "ounce" to listOf(28_350L),
    "gallon" to listOf(3_785_000L, 4_546_000L),
)
