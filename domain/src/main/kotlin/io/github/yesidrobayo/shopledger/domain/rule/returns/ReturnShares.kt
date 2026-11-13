package io.github.yesidrobayo.shopledger.domain.rule.returns

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/** La parte de una línea vendida que devuelve una devolución. El total es lo neto más el impuesto. */
data class ReturnShare(val net: Money, val tax: Money, val cost: Money) {
    val total: Money get() = net + tax
}

/** Si una cantidad se puede devolver de una línea, y cuánto vale. */
sealed interface ReturnShareCheck {
    data class Share(val share: ReturnShare) : ReturnShareCheck

    /** La cantidad es cero o negativa. */
    data object NotPositive : ReturnShareCheck

    /** Pasa de lo que falta por devolver, [remaining]: lo vendido menos lo ya devuelto. */
    data class AboveRemaining(val remaining: QuantityMilli) : ReturnShareCheck
}

/**
 * `share(X, q)`: reparte lo neto, el impuesto y el costo congelado de una línea vendida entre sus
 * devoluciones, con cantidades acumuladas, para que la suma de todas sea exacta.
 *
 * Cada valor `X` de la línea se reparte como `mulDivRound(X, devuelto_después, vendido)` menos lo
 * que ya se llevaron las devoluciones activas. Con `net_amount = 10000` y 3 unidades, devolver de a
 * una da 3333, 3334 y 3333, y devolver el resto siempre se lleva exactamente lo que queda.
 *
 * Se resta lo ya devuelto en dinero, no su cuenta con la cantidad anterior: sin anulaciones da lo
 * mismo, porque las partes se encadenan, y con una devolución anulada en medio la suma de las
 * activas sigue sin pasar de la línea. Ninguna parte es negativa ni pasa de lo que queda.
 *
 * El total de cada parte es su neto más su impuesto, como en la línea: así cada línea devuelta
 * cumple `line_total = net_amount + tax_amount`, y la suma de los totales también es exacta.
 */
object ReturnShares {
    /** Lo que falta por devolver de [sold], después de lo que ya devolvieron, [taken]. */
    fun remaining(sold: LineAmounts, taken: LineAmounts): QuantityMilli =
        sold.quantity - taken.quantity

    /** La parte de [sold] que devuelve [quantity], con [taken] ya devuelto por las activas. */
    fun of(sold: LineAmounts, taken: LineAmounts, quantity: QuantityMilli): ReturnShareCheck {
        if (quantity.milli <= 0L) return ReturnShareCheck.NotPositive
        val remaining = remaining(sold, taken)
        if (quantity > remaining) return ReturnShareCheck.AboveRemaining(remaining)
        val after = taken.quantity + quantity
        return ReturnShareCheck.Share(
            ReturnShare(
                net = part(sold.net, taken.net, after, sold.quantity),
                tax = part(sold.tax, taken.tax, after, sold.quantity),
                cost = part(sold.cost, taken.cost, after, sold.quantity),
            ),
        )
    }

    /**
     * Lo que corresponde a [after] de [whole], menos lo ya devuelto, [taken]. Nunca es negativo ni
     * pasa de lo que queda de la línea.
     */
    private fun part(whole: Money, taken: Money, after: QuantityMilli, sold: QuantityMilli): Money {
        // Lo devuelto después no pasa de lo vendido, así que el resultado no pasa de [whole]: cabe.
        val upTo = (mulDivRound(whole.minor, after.milli, sold.milli) as Checked.Value).value
        val left = whole.minor - taken.minor
        return Money(maxOf(0L, minOf(upTo - taken.minor, left)))
    }
}
