package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftGap
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
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
 *   `null`, para que la persona elija.
 * - Un producto que no existe se ofrece crear donde tiene sentido: en una venta, una compra y una
 *   receta, y como el producto de una receta o el que se crea. En las demás operaciones queda como
 *   hueco: no se produce, ajusta ni corrige algo que no existe.
 * - Una cantidad que no se dijo vale una unidad en una venta, una compra, una producción o un
 *   ajuste, como en la pantalla de siempre. En un conteo y en una receta se pregunta: contar uno o
 *   poner un kilo de sal en la receta sería inventar el dato. Si alguna cantidad no se pudo leer,
 *   las que faltan se preguntan todas, porque no se sabe cuál era.
 * - En una venta o una compra de una sola línea sin monto, el "todo por" es el total de esa línea.
 */
fun PhraseReading.toDraft(): PhraseDraft? {
    val intent = intent ?: return null
    val unreadable = PhraseIssue.UNREADABLE_QUANTITY in issues
    val fallback = if (!unreadable && assumesOne(intent, reason)) ONE else null
    val creatable = intent in CREATABLE_LINES
    val lines = lines.map { it.toDraftLine(creatable, fallback) }
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
    )
}

/**
 * Lo que falta para poder registrar la tarjeta: vacío si ya se puede. No mira la base: lo que solo
 * la base sabe, como un producto archivado, lo rechaza el registro con su motivo.
 *
 * - Toda operación con líneas necesita al menos una, cada una con un producto que exista, y, salvo
 *   un cambio de precio o de costo, con una cantidad mayor que cero.
 * - Una venta necesita el método de pago, y a crédito, el cliente.
 * - Una compra necesita lo que costó cada línea, y a crédito, el proveedor. Si se dijo un total,
 *   tiene que ser la suma de las líneas: la tarjeta no registra un total distinto del que muestra.
 * - Un gasto necesita el monto y la categoría; un abono o un pago, el monto y la persona.
 * - Una receta necesita un producto que exista y lo que rinde mayor que cero; un producto nuevo, su
 *   nombre.
 * - Un ajuste necesita un motivo de los que se pueden decir: el motivo "otro" exige una nota que la
 *   frase no trae.
 */
fun PhraseDraft.gaps(): Set<DraftGap> = buildSet {
    when (intent) {
        PhraseIntent.SALE -> {
            addLineGaps(lines, needsQuantity = true)
            if (paymentMethodId == null) add(DraftGap.PAYMENT)
            if (credit && partyId == null) add(DraftGap.PARTY)
        }

        PhraseIntent.PURCHASE -> {
            addLineGaps(lines, needsQuantity = true)
            if (lines.any { it.unitPrice == null && it.lineTotal == null }) add(DraftGap.COST)
            if (credit && partyId == null) add(DraftGap.PARTY)
            if (!totalMatches(lines, total)) add(DraftGap.AMOUNT)
        }

        PhraseIntent.PRODUCTION -> addLineGaps(lines, needsQuantity = true)

        PhraseIntent.STOCK_ADJUSTMENT -> {
            addLineGaps(lines, needsQuantity = true)
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
            if (target !is DraftTarget.Existing) add(DraftGap.TARGET)
            addLineGaps(lines, needsQuantity = true)
            if (targetQuantity != null && targetQuantity.milli <= 0L) add(DraftGap.QUANTITY)
        }

        PhraseIntent.NEW_PRODUCT -> {
            val named = target is DraftTarget.New && target.name.isNotBlank()
            if (!named) add(DraftGap.TARGET)
        }

        PhraseIntent.COST_CORRECTION -> {
            addLineGaps(lines, needsQuantity = false)
            if (lines.any { it.unitPrice == null }) add(DraftGap.COST)
        }

        PhraseIntent.PRICE_CHANGE -> {
            addLineGaps(lines, needsQuantity = false)
            if (lines.any { it.unitPrice == null }) add(DraftGap.PRICE)
        }
    }
}

/** Una unidad entera: lo que vale una cantidad que no se dijo donde se supone una. */
private val ONE = QuantityMilli(1_000L)

/** Donde una línea con un producto que no existe ofrece crearlo. */
private val CREATABLE_LINES = setOf(PhraseIntent.SALE, PhraseIntent.PURCHASE, PhraseIntent.RECIPE)

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

private fun PhraseLine.toDraftLine(creatable: Boolean, fallback: QuantityMilli?): DraftLine =
    DraftLine(
        product = product.toDraftTarget(creatable),
        quantity = quantity ?: fallback,
        unitId = unitId,
        unitPrice = unitPrice,
        lineTotal = lineTotal,
    )

private fun ProductMatch.toDraftTarget(creatable: Boolean): DraftTarget? = when (this) {
    is ProductMatch.Found -> DraftTarget.Existing(id)
    is ProductMatch.Choose -> null
    is ProductMatch.Unknown -> if (creatable) DraftTarget.New(spokenName) else null
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

private fun MutableSet<DraftGap>.addLineGaps(lines: List<DraftLine>, needsQuantity: Boolean) {
    if (lines.isEmpty() || lines.any { it.product !is DraftTarget.Existing }) add(DraftGap.PRODUCT)
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
