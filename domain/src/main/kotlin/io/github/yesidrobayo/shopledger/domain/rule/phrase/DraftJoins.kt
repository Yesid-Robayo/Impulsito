package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason

/**
 * La misma tarjeta como otra operación con líneas, elegida a mano: las líneas, sus cantidades y sus
 * precios quedan, igual que al decir "no, fue una compra". El motivo solo vale en un ajuste, y la
 * persona solo si la operación no cambia, porque un cliente no es un proveedor. El pago y el total
 * quedan aunque la operación nueva no los use: así volver a la anterior no los pierde, y el registro
 * de un conteo o una producción no los mira.
 */
fun PhraseDraft.asOperation(intent: PhraseIntent, reason: StockAdjustmentReason?): PhraseDraft =
    copy(
        intent = intent,
        reason = reason.takeIf { intent == PhraseIntent.STOCK_ADJUSTMENT },
        partyId = partyId.takeIf { intent == this.intent },
    )

/**
 * Esta tarjeta con lo que dijo [next], una lectura de la misma operación. Nunca quita una línea:
 *
 * - **Un producto que no está** se agrega como línea nueva, al final.
 * - **Un producto que ya está** en una sola línea se suma o se corrige. Con [sums] se suma a lo que
 *   había, si las dos cantidades van en la misma unidad, sin total de línea y sin precios
 *   distintos; si no se pueden sumar sin inventar un precio, va como otra línea. Sin [sums], la
 *   cantidad dicha reemplaza la que había, y un precio dicho, el que tenía. Un producto que está en
 *   varias líneas no se adivina: va como otra línea.
 * - **El pago, el crédito, la persona y el total** dichos se aplican encima, solo en una venta o
 *   una compra; lo que no se dijo queda. El total de una sola línea ya va en esa línea y no se
 *   repite como total de la tarjeta.
 * - **El producto de una receta** queda, y lo que rinde se cambia si se dijo.
 */
internal fun PhraseDraft.joinedWith(
    next: PhraseDraft,
    sums: Boolean,
    unitOf: (DraftLine) -> Long?,
): PhraseDraft {
    val paid = intent == PhraseIntent.SALE || intent == PhraseIntent.PURCHASE
    val lineTotal = next.lines.singleOrNull()?.lineTotal
    val total = next.total.takeIf { it != lineTotal }
    return copy(
        lines = joinedLines(lines, next.lines, sums, unitOf),
        paymentMethodId = next.paymentMethodId.takeIf { paid } ?: paymentMethodId,
        credit = if (paid && (next.credit || next.paymentMethodId != null)) next.credit else credit,
        partyId = next.partyId.takeIf { paid } ?: partyId,
        total = total.takeIf { paid } ?: this.total,
        target = target ?: next.target,
        targetQuantity = next.targetQuantity ?: targetQuantity,
    )
}

/**
 * Las líneas de [old] con las de [said] sumadas o corregidas encima: ver [joinedWith]. [unitOf] es
 * la unidad en que va una línea, la dicha o la base de su producto: así "2 kilos" y "2" de algo que
 * se lleva por kilo son la misma unidad.
 */
internal fun joinedLines(
    old: List<DraftLine>,
    said: List<DraftLine>,
    sums: Boolean,
    unitOf: (DraftLine) -> Long?,
): List<DraftLine> {
    val lines = old.toMutableList()
    for (line in said) {
        val index = lines.indices.singleOrNull { sameProduct(lines[it].product, line.product) }
        val merged = index?.let {
            if (sums) summed(lines[it], line, unitOf) else restated(lines[it], line, unitOf)
        }
        if (index == null || merged == null) lines += line else lines[index] = merged
    }
    return lines
}

/**
 * Si dos líneas son del mismo producto: el mismo del catálogo, o uno por crear con el mismo nombre
 * sin mayúsculas. Una línea sin producto todavía no es de ninguno.
 */
internal fun sameProduct(one: DraftTarget?, other: DraftTarget?): Boolean = when (one) {
    is DraftTarget.Existing -> one == other
    is DraftTarget.New -> other is DraftTarget.New && other.name.lowercase() == one.name.lowercase()
    null -> false
}

/**
 * [line] con lo que dijo [said] del mismo producto: la cantidad, la unidad y el precio dichos
 * reemplazan los que tenía. Un precio anterior en otra unidad ya no vale y se olvida.
 */
private fun restated(line: DraftLine, said: DraftLine, unitOf: (DraftLine) -> Long?): DraftLine {
    val counted = said.quantity != null
    val priced = said.unitPrice != null || said.lineTotal != null
    val stale = !priced && counted && unitOf(said) != unitOf(line)
    return line.copy(
        product = withTraits(line.product, said.product),
        quantity = said.quantity ?: line.quantity,
        unitId = if (counted) said.unitId else line.unitId,
        unitPrice = if (priced || stale) said.unitPrice else line.unitPrice,
        lineTotal = if (priced || stale) said.lineTotal else line.lineTotal,
    )
}

/**
 * [line] con la cantidad de [said] sumada, o `null` si no se puede sumar sin inventar: falta una
 * cantidad, van en unidades distintas, alguna tiene total de línea, los precios son distintos o la
 * suma no cabe.
 */
private fun summed(line: DraftLine, said: DraftLine, unitOf: (DraftLine) -> Long?): DraftLine? {
    val had = line.quantity ?: return null
    val more = said.quantity ?: return null
    val samePrice = line.unitPrice == null || said.unitPrice == null ||
        line.unitPrice == said.unitPrice
    val plain = line.lineTotal == null && said.lineTotal == null
    val fits = more.milli <= Long.MAX_VALUE - had.milli
    if (unitOf(line) != unitOf(said) || !plain || !samePrice || !fits) return null
    return line.copy(
        product = withTraits(line.product, said.product),
        quantity = had + more,
        unitPrice = line.unitPrice ?: said.unitPrice,
    )
}

/**
 * El producto de la línea con lo nuevo que se dijo de él, si es uno por crear: [said] es entonces
 * uno por crear con el mismo nombre, porque solo así se juntan las dos líneas.
 */
private fun withTraits(product: DraftTarget?, said: DraftTarget?): DraftTarget? =
    if (product is DraftTarget.New) {
        product.copy(traits = product.traits.with((said as DraftTarget.New).traits))
    } else {
        product
    }
