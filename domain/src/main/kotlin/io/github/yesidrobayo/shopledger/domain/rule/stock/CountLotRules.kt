package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock

/**
 * Una línea por contar de un producto con lotes: el lote [lotId], con lo que el libro dice que
 * tiene en la ubicación, [expected]. `null` es el lote sin asignar que el producto todavía no
 * tiene: se crea al aplicar, si hace falta.
 */
data class CountLotLine(val lotId: Long?, val expected: QuantityMilli)

/**
 * Cómo se reparte la corrección de un lote con código: [lot] va a ese lote y [unassigned] al lote
 * sin asignar. Suman la diferencia contada.
 */
data class CountCorrectionSplit(val lot: QuantityMilli, val unassigned: QuantityMilli)

/**
 * El conteo de un producto que se controla por lotes: se cuenta lote por lote, y cada diferencia
 * corrige su lote. Solo el lote sin asignar puede quedar en negativo, así que una corrección
 * nunca deja negativo un lote con código.
 */
object CountLotRules {
    /**
     * Las líneas de un producto con lotes al abrir un conteo, con [stocks] lo que tiene cada lote
     * en la ubicación contada.
     *
     * Entra cada lote con código con saldo distinto de cero allí, el que vence antes primero, los
     * que no vencen después y los empates por el más antiguo. El lote sin asignar entra al final
     * si tiene saldo, o si no hay ningún lote con código: así el producto siempre se puede contar,
     * y lo que se encuentre sin lote conocido va al sin asignar.
     */
    fun linesOf(stocks: List<LotStock>): List<CountLotLine> {
        val coded = stocks
            .filter { !it.isUnassigned && it.onHand != QuantityMilli.ZERO }
            .sortedWith(
                compareBy<LotStock>(
                    { it.expiryDate == null },
                    { it.expiryDate?.toString() },
                    { it.lotId },
                ),
            )
            .map { CountLotLine(it.lotId, it.onHand) }
        val unassigned = stocks.firstOrNull { it.isUnassigned }
        val unassignedHas = unassigned != null && unassigned.onHand != QuantityMilli.ZERO
        if (coded.isNotEmpty() && !unassignedHas) return coded
        return coded + CountLotLine(unassigned?.lotId, unassigned?.onHand ?: QuantityMilli.ZERO)
    }

    /**
     * Reparte la corrección [change] de un lote con código que hoy tiene [available] en la
     * ubicación. Lo que entra va al lote. Lo que sale, del lote hasta dejarlo en cero, y el resto
     * del lote sin asignar: pasa si el lote se movió después de abrir el conteo.
     *
     * Con 1 en el lote, corregir −3 da −1 al lote y −2 al sin asignar.
     */
    fun splitCorrection(change: QuantityMilli, available: QuantityMilli): CountCorrectionSplit {
        if (change >= QuantityMilli.ZERO) return CountCorrectionSplit(change, QuantityMilli.ZERO)
        val fromLot = maxOf(change, -maxOf(available, QuantityMilli.ZERO))
        return CountCorrectionSplit(fromLot, change - fromLot)
    }
}
