package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity

/**
 * El inventario inicial: la mercancía que ya existía antes de usar la app.
 *
 * Entra como cualquier entrada de costo promedio, con el total de lo que entra, y no toca las
 * ganancias: no es una compra ni un gasto. Como toda entrada, no puede dejar el stock ni su valor
 * por encima de sus topes, que protegen las cuentas siguientes de desbordar.
 */
object OpeningBalanceRule {
    /**
     * `inventarioInicial`: el estado tras sumar [quantity] milésimas a [unitCost] por unidad
     * entera, sobre [state].
     *
     * El total es `mulDivRound(cantidad, costo, 1000)`, y no pasa del tope de un documento. Un
     * rechazo dice qué tope se pasó, o que la cuenta no cabe en `Long`.
     */
    fun entry(
        state: CostState,
        quantity: QuantityMilli,
        unitCost: Money,
    ): Checked<OpeningBalanceEntry> {
        val total = when (val checked = unitCost.timesQuantity(quantity, Limit.DOCUMENT_TOTAL)) {
            is Checked.Value -> checked.value
            Checked.Overflow -> return Checked.Overflow
            is Checked.AboveLimit -> return checked
        }
        // La entrada no aplica topes: su único rechazo posible es que no quepa en Long.
        val after = WeightedAverageCost.entry(state, quantity, total).valueOrNull()
            ?: return Checked.Overflow
        return when {
            !Limit.STOCK_VALUE.allows(after.stockValue.minor) ->
                Checked.AboveLimit(Limit.STOCK_VALUE)

            !Limit.ON_HAND_MILLI.allows(after.onHand.milli) ->
                Checked.AboveLimit(Limit.ON_HAND_MILLI)

            else -> Checked.Value(OpeningBalanceEntry(total, after))
        }
    }
}
