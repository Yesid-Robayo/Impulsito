package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull

/**
 * La tarjeta que propone esta lectura, o `null` si no se entendió ninguna operación.
 *
 * - Lo que tiene uno claro pasa a su id. Lo que deja elegir entre varios queda como hueco, en
 *   `null`, para que la persona elija; una línea guarda entre qué productos se pedía elegir.
 * - Un producto que no se parece a ninguno del catálogo es un producto nuevo, que se crea al
 *   registrar donde tiene sentido: en una venta, una compra, una producción, un conteo y una
 *   receta, y como el producto de una receta o el que se crea. En las demás operaciones queda como
 *   hueco: no se ajusta ni se corrige algo que no existe. Un ingrediente nuevo de una receta es un
 *   insumo, salvo que se dijera otro uso.
 * - Lo dicho en una unidad que el producto no admite deja la cantidad en hueco, con lo dicho a la
 *   vista para pedirla en una unidad del producto: nunca se lee como su unidad base.
 * - Una cantidad que no se dijo vale una unidad en una venta, una compra, una producción o un
 *   ajuste, como en la pantalla de siempre. En un conteo y en una receta se pregunta: contar uno o
 *   poner un kilo de sal en la receta sería inventar el dato. Si alguna cantidad no se pudo leer,
 *   las que faltan se preguntan todas, porque no se sabe cuál era.
 * - En una venta o una compra de una sola línea sin monto, el "todo por" es el total de esa línea.
 * - Lo que se dijo de un producto nuevo (su uso, su categoría) viaja con él. En una compra no
 *   viaja que sea un servicio: lo que se compra lleva stock, y un servicio no lo lleva.
 * - Los insumos dichos de una producción pasan como sus líneas, y uno que no existe se ofrece
 *   crear. Una cantidad que no se dijo se pregunta, como en una receta.
 */
fun PhraseReading.toDraft(): PhraseDraft? = intent?.let(::draftAs)

/**
 * La tarjeta que propone esta lectura como [intent], aunque la frase dijera otra o ninguna: así lo
 * dicho sin verbo con una tarjeta abierta se lee como de su operación. Ver [toDraft].
 */
internal fun PhraseReading.draftAs(intent: PhraseIntent): PhraseDraft {
    val unreadable = PhraseIssue.UNREADABLE_QUANTITY in issues
    val fallback = if (!unreadable && assumesOne(intent, reason)) ONE else null
    val creatable = createsLineProducts(intent, reason)
    val stocked = intent == PhraseIntent.PURCHASE
    val used = if (intent == PhraseIntent.RECIPE) INPUT_USE else NewProductTraits.NONE
    val lines = lines.map { it.toDraftLine(creatable, stocked, fallback, used) }
    return PhraseDraft(
        intent = intent,
        reason = reason,
        lines = withTotalAsLine(intent, lines, total),
        paymentMethodId = payment.foundId(),
        partyId = party.foundId(),
        categoryId = category.foundId(),
        total = total,
        credit = credit,
        target = target?.toDraftTarget(intent in CREATABLE_TARGETS),
        targetQuantity = targetQuantity,
        price = price,
        cost = cost,
        unitId = unitId,
        inputs = inputs.map {
            it.toDraftLine(creatable = true, stocked = false, fallback = null, used = INPUT_USE)
        },
    )
}

/**
 * Si las líneas de esta tarjeta pueden llevar un producto nuevo, que se crea al registrar, en la
 * misma escritura: una venta, una compra, una producción, una receta y un conteo.
 */
fun PhraseDraft.createsLineProducts(): Boolean = createsLineProducts(intent, reason)

/**
 * Si una línea de [intent] con [reason] puede llevar un producto que todavía no existe: en una
 * venta, una compra, una producción y una receta, y en un conteo, que dice lo que hay de algo que
 * todavía no se registró. Un ajuste con otro motivo no saca ni corrige algo que no existe.
 */
fun createsLineProducts(intent: PhraseIntent, reason: StockAdjustmentReason?): Boolean {
    val counted = reason == StockAdjustmentReason.COUNT_CORRECTION
    return intent in CREATABLE_LINES || (intent == PhraseIntent.STOCK_ADJUSTMENT && counted)
}

/**
 * Lo que falta para poder registrar la tarjeta: vacío si ya se puede. No mira la base: lo que solo
 * la base sabe, como un producto archivado, lo rechaza el registro con su motivo.
 *
 * - Toda operación con líneas necesita al menos una, cada una con un producto que exista o, donde
 *   se crea al registrar, uno nuevo con nombre, y, salvo un cambio de precio o de costo, con una
 *   cantidad mayor que cero.
 * - Una venta necesita el método de pago, y a crédito, el cliente. Un producto nuevo no tiene
 *   precio: su línea necesita el precio o el total dicho.
 * - Una compra necesita lo que costó cada línea, y a crédito, el proveedor. Si se dijo un total,
 *   tiene que ser la suma de las líneas: la tarjeta no registra un total distinto del que muestra.
 * - Un gasto necesita el monto y la categoría; un abono o un pago, el monto y la persona.
 * - Una receta necesita su producto, uno que exista o uno nuevo con nombre, y lo que rinde mayor
 *   que cero; un producto nuevo, su nombre.
 * - Un ajuste necesita un motivo de los que se pueden decir: el motivo "otro" exige una nota que la
 *   frase no trae.
 * - Una producción necesita, en cada insumo dicho, un producto que exista y su cantidad. Si no se
 *   dijo ninguno, cada producto que se produce necesita receta: [hasRecipe] lo dice de cada id, y
 *   por omisión supone que la tiene, porque eso solo lo sabe la base o el catálogo leído. Uno nuevo
 *   no la tiene.
 */
fun PhraseDraft.gaps(hasRecipe: (Long) -> Boolean = { true }): Set<DraftGap> = buildSet {
    val creates = createsLineProducts()
    when (intent) {
        PhraseIntent.SALE -> {
            addLineGaps(lines, needsQuantity = true, creates)
            if (paymentMethodId == null) add(DraftGap.PAYMENT)
            if (credit && partyId == null) add(DraftGap.PARTY)
            val unpriced = lines.any { line ->
                line.product is DraftTarget.New && line.unitPrice == null && line.lineTotal == null
            }
            if (unpriced) add(DraftGap.PRICE)
        }

        PhraseIntent.PURCHASE -> {
            addLineGaps(lines, needsQuantity = true, creates)
            if (lines.any { it.unitPrice == null && it.lineTotal == null }) add(DraftGap.COST)
            if (credit && partyId == null) add(DraftGap.PARTY)
            if (!totalMatches(lines, total)) add(DraftGap.AMOUNT)
        }

        PhraseIntent.PRODUCTION -> {
            addLineGaps(lines, needsQuantity = true, creates)
            if (inputs.isNotEmpty()) addLineGaps(inputs, needsQuantity = true, creates)
            val unwritten = lines.any { line ->
                when (val product = line.product) {
                    is DraftTarget.Existing -> !hasRecipe(product.productId)
                    is DraftTarget.New -> true
                    null -> false
                }
            }
            if (inputs.isEmpty() && unwritten) add(DraftGap.INPUTS)
        }

        PhraseIntent.STOCK_ADJUSTMENT -> {
            addLineGaps(lines, needsQuantity = true, creates)
            if (reason == null || reason == StockAdjustmentReason.OTHER) add(DraftGap.NOTHING)
        }

        PhraseIntent.EXPENSE -> {
            addAmountGap(total)
            if (categoryId == null) add(DraftGap.CATEGORY)
        }

        PhraseIntent.CUSTOMER_PAYMENT, PhraseIntent.SUPPLIER_PAYMENT -> {
            addAmountGap(total)
            if (partyId == null) add(DraftGap.PARTY)
        }

        PhraseIntent.RECIPE -> {
            if (!target.isNamed()) add(DraftGap.TARGET)
            addLineGaps(lines, needsQuantity = true, creates)
            if (targetQuantity != null && targetQuantity.milli <= 0L) add(DraftGap.QUANTITY)
        }

        PhraseIntent.NEW_PRODUCT -> {
            if (target !is DraftTarget.New || !target.isNamed()) add(DraftGap.TARGET)
        }

        PhraseIntent.COST_CORRECTION -> {
            addLineGaps(lines, needsQuantity = false, creates)
            if (lines.any { it.unitPrice == null }) add(DraftGap.COST)
        }

        PhraseIntent.PRICE_CHANGE -> {
            addLineGaps(lines, needsQuantity = false, creates)
            if (lines.any { it.unitPrice == null }) add(DraftGap.PRICE)
        }
    }
}

/** Una unidad entera: lo que vale una cantidad que no se dijo donde se supone una. */
private val ONE = QuantityMilli(1_000L)

/** Donde una línea puede llevar un producto que no existe, salvo un conteo, que va aparte. */
private val CREATABLE_LINES = setOf(
    PhraseIntent.SALE,
    PhraseIntent.PURCHASE,
    PhraseIntent.PRODUCTION,
    PhraseIntent.RECIPE,
)

/** Lo que se sabe de un producto nuevo que entra en otro: un insumo, salvo que se diga otro uso. */
private val INPUT_USE = NewProductTraits(usage = ProductUsage.INPUT)

/** Donde el producto de la operación puede ser uno que todavía no existe. */
private val CREATABLE_TARGETS = setOf(PhraseIntent.RECIPE, PhraseIntent.NEW_PRODUCT)

/** Donde el "todo por" de una sola línea es el total de esa línea. */
private val TOTAL_AS_LINE = setOf(PhraseIntent.SALE, PhraseIntent.PURCHASE)

/** Si una cantidad que no se dijo vale una unidad en esta operación. */
private fun assumesOne(intent: PhraseIntent, reason: StockAdjustmentReason?): Boolean =
    when (intent) {
        PhraseIntent.SALE, PhraseIntent.PURCHASE, PhraseIntent.PRODUCTION -> true

        PhraseIntent.STOCK_ADJUSTMENT -> reason != StockAdjustmentReason.COUNT_CORRECTION

        PhraseIntent.EXPENSE,
        PhraseIntent.CUSTOMER_PAYMENT,
        PhraseIntent.SUPPLIER_PAYMENT,
        PhraseIntent.RECIPE,
        PhraseIntent.NEW_PRODUCT,
        PhraseIntent.COST_CORRECTION,
        PhraseIntent.PRICE_CHANGE,
        -> false
    }

/**
 * La línea de la tarjeta. Lo dicho en una unidad que el producto no admite deja la cantidad en
 * hueco, sin suponer una, y lo dicho a la vista en [DraftLine.unfit]. [used] es lo que se sabe de
 * un producto nuevo por dónde va, como un ingrediente, que es un insumo.
 */
private fun PhraseLine.toDraftLine(
    creatable: Boolean,
    stocked: Boolean,
    fallback: QuantityMilli?,
    used: NewProductTraits,
): DraftLine = DraftLine(
    product = product.toDraftTarget(creatable, stocked, used),
    quantity = if (unfit == null) quantity ?: fallback else null,
    unitId = unitId,
    unitPrice = unitPrice,
    lineTotal = lineTotal,
    candidates = (product as? ProductMatch.Choose)?.ids.orEmpty(),
    unfit = unfit,
)

private fun ProductMatch.toDraftTarget(
    creatable: Boolean,
    stocked: Boolean = false,
    used: NewProductTraits = NewProductTraits.NONE,
): DraftTarget? = when (this) {
    is ProductMatch.Found -> DraftTarget.Existing(id)

    is ProductMatch.Choose -> null

    is ProductMatch.Unknown -> when {
        !creatable -> null
        stocked -> DraftTarget.New(spokenName, used.with(traits.copy(stockMode = null)))
        else -> DraftTarget.New(spokenName, used.with(traits))
    }
}

/** Si es un producto que existe, o uno nuevo con nombre. */
private fun DraftTarget?.isNamed(): Boolean = when (this) {
    is DraftTarget.Existing -> true
    is DraftTarget.New -> name.isNotBlank()
    null -> false
}

private fun NameMatch?.foundId(): Long? = (this as? NameMatch.Found)?.id

private fun withTotalAsLine(
    intent: PhraseIntent,
    lines: List<DraftLine>,
    total: Money?,
): List<DraftLine> {
    val only = lines.singleOrNull() ?: return lines
    val unpriced = only.unitPrice == null && only.lineTotal == null
    return if (total != null && unpriced && intent in TOTAL_AS_LINE) {
        listOf(only.copy(lineTotal = total))
    } else {
        lines
    }
}

/**
 * Lo que falta en [lines]: un producto, que puede ser uno nuevo con nombre solo si [creates], y la
 * cantidad si [needsQuantity].
 */
private fun MutableSet<DraftGap>.addLineGaps(
    lines: List<DraftLine>,
    needsQuantity: Boolean,
    creates: Boolean,
) {
    val unchosen = lines.any { line ->
        val product = line.product
        !product.isNamed() || (product is DraftTarget.New && !creates)
    }
    if (lines.isEmpty() || unchosen) add(DraftGap.PRODUCT)
    if (needsQuantity && lines.any { !it.hasQuantity() }) add(DraftGap.QUANTITY)
}

private fun DraftLine.hasQuantity(): Boolean = quantity != null && quantity.milli > 0L

private fun MutableSet<DraftGap>.addAmountGap(total: Money?) {
    if (total == null || total <= Money.ZERO) add(DraftGap.AMOUNT)
}

/**
 * Si el total dicho de una compra es la suma de sus líneas. Sin total no hay nada que cuadrar, y
 * una línea sin cantidad o sin monto ya es otro hueco: no se marca dos veces.
 */
private fun totalMatches(lines: List<DraftLine>, total: Money?): Boolean {
    if (total == null) return true
    val amounts = lines.map { PhraseAmounts.lineAmount(it) ?: return true }
    return amounts.sumOrNull() == total
}
