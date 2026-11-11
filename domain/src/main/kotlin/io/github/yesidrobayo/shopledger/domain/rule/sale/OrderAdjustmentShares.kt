package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Reparte entre las líneas un ajuste de toda la venta, con signo: negativo es un descuento y
 * positivo un recargo. Así el margen de cada línea refleja lo que de verdad se cobró por ella.
 *
 * Cada línea recibe su parte en proporción a su base, que es su bruto menos su descuento, con el
 * redondeo de siempre. La línea de mayor base absorbe el residuo, para que las partes sumen el
 * ajuste exacto; con un empate, la última de las empatadas. Absorberlo en la línea de mayor base, y
 * no en la última, evita que una línea pequeña al final quede negativa por el redondeo.
 *
 * Aun así, el redondeo de muchas líneas pequeñas puede dejar alguna bajo cero. Entonces esa línea
 * queda en cero, y lo que le sobraba de descuento pasa a la siguiente de mayor base que tenga con
 * qué cubrirlo. La suma no cambia, y como el descuento nunca supera la suma de bases, siempre hay
 * dónde ponerlo. Con suma de bases cero, el ajuste va entero a la última línea.
 */
object OrderAdjustmentShares {
    /**
     * La parte de [adjustment] de cada línea, en el orden de [bases], o
     * [AdjustmentSplit.DiscountAboveBases] si el descuento supera la suma de bases. Cada base es
     * cero o más y, como mucho, el tope de un documento, igual que el ajuste.
     */
    fun split(adjustment: Money, bases: List<Money>): AdjustmentSplit {
        require(bases.isNotEmpty()) { "Una venta tiene al menos una línea" }
        require(bases.all { it >= Money.ZERO && Limit.DOCUMENT_TOTAL.allows(it.minor) }) {
            "Cada base está entre cero y el tope de un documento"
        }
        require(adjustment.minor in -Limit.DOCUMENT_TOTAL.max..Limit.DOCUMENT_TOTAL.max) {
            "El ajuste no pasa del tope de un documento"
        }
        // Hasta el tope de líneas por el tope de un documento: cabe en Long sin desbordar.
        val baseSum = bases.fold(Money.ZERO) { sum, base -> sum + base }
        if (-adjustment.minor > baseSum.minor) return AdjustmentSplit.DiscountAboveBases
        if (baseSum == Money.ZERO) {
            return AdjustmentSplit.Shares(List(bases.size) { Money.ZERO }.dropLast(1) + adjustment)
        }
        val absorber = byBaseDescending(bases).first()
        val shares = MutableList(bases.size) { index ->
            if (index == absorber) Money.ZERO else proportional(adjustment, bases[index], baseSum)
        }
        shares[absorber] = adjustment - shares.fold(Money.ZERO) { sum, share -> sum + share }
        moveNegativeRemainders(bases, shares)
        return AdjustmentSplit.Shares(shares)
    }

    /**
     * `mulDivRound(ajuste, base, suma de bases)`. Siempre cabe, porque la base no pasa de la suma y
     * la parte no pasa del ajuste: por eso el resultado se toma sin mirar un rechazo que no ocurre.
     */
    private fun proportional(adjustment: Money, base: Money, baseSum: Money): Money =
        Money((mulDivRound(adjustment.minor, base.minor, baseSum.minor) as Checked.Value).value)

    /**
     * Deja en cero cada línea que quedó negativa y pasa lo que le faltaba a las líneas de mayor
     * base, en orden, hasta donde cada una tenga con qué cubrirlo.
     */
    private fun moveNegativeRemainders(bases: List<Money>, shares: MutableList<Money>) {
        var excess = Money.ZERO
        for (index in bases.indices) {
            val after = bases[index] + shares[index]
            if (after < Money.ZERO) {
                excess += after
                shares[index] = -bases[index]
            }
        }
        // Sin exceso, cada paso mueve cero: no hace falta cortar el recorrido.
        for (index in byBaseDescending(bases)) {
            val room = bases[index] + shares[index]
            val moved = if (room < -excess) -room else excess
            shares[index] += moved
            excess -= moved
        }
    }

    /** Los índices de mayor a menor base; con un empate, el último primero. */
    private fun byBaseDescending(bases: List<Money>): List<Int> =
        bases.indices.sortedWith(compareByDescending<Int> { bases[it] }.thenByDescending { it })
}

/** Cómo quedó repartido un ajuste de toda la venta entre sus líneas. */
sealed interface AdjustmentSplit {
    /** La parte de cada línea, en el orden de sus bases. Suman el ajuste exacto. */
    data class Shares(val shares: List<Money>) : AdjustmentSplit

    /** El descuento supera la suma de bases: dejaría la venta con un total negativo. */
    data object DiscountAboveBases : AdjustmentSplit
}
