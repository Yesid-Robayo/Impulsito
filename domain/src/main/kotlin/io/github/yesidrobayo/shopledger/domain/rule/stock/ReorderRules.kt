package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.rule.ceilDiv
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv

/**
 * Lo que se sugiere reponer de un producto.
 *
 * [velocity] son las milésimas que salen por día, redondeadas. [daysLeft] son los días enteros
 * que alcanza lo que hay a esa velocidad, o `null` cuando la velocidad redondea a cero: sin ella
 * no hay cuenta. [suggested] es lo que conviene comprar, nunca negativo. [recentSales] dice si
 * hubo salidas en la ventana; sin ellas no se sugiere nada.
 */
data class ReorderSuggestion(
    val velocity: QuantityMilli,
    val daysLeft: Long?,
    val suggested: QuantityMilli,
    val recentSales: Boolean,
)

/**
 * La reposición sugerida: cuánto conviene comprar para cubrir unos días al ritmo de lo que se
 * vende, más el mínimo.
 *
 * Es una sugerencia: no crea ninguna compra, y la persona la cambia antes de comprar. La ventana
 * y la cobertura son de 30 días por defecto, y la pantalla deja cambiarlas en cada consulta.
 */
object ReorderRules {
    /** Los días de ventas que se miran, por defecto. */
    const val DEFAULT_WINDOW_DAYS: Long = 30L

    /** Los días que debe cubrir lo que se compra, por defecto. */
    const val DEFAULT_COVER_DAYS: Long = 30L

    /**
     * La sugerencia para un producto del que salieron [unitsOut] milésimas en [windowDays] días,
     * con [onHand] en stock y un mínimo de [minStock], para cubrir [coverDays] días.
     *
     * Sin salidas en la ventana no se sugiere nada: la cantidad es cero y la persona la llena a
     * mano si lo necesita. Con salidas tan pocas que la velocidad redondea a cero, la fórmula
     * sigue igual y solo pide llegar al mínimo. Con [purchaseFactor], las milésimas de una unidad
     * de compra, la sugerencia sube al múltiplo siguiente, porque no se compra media caja.
     */
    fun suggest(
        unitsOut: QuantityMilli,
        windowDays: Long,
        coverDays: Long,
        onHand: QuantityMilli,
        minStock: QuantityMilli,
        purchaseFactor: QuantityMilli? = null,
    ): ReorderSuggestion {
        require(windowDays > 0L) { "La ventana tiene al menos un día" }
        require(coverDays >= 0L) { "La cobertura nunca es negativa" }
        if (unitsOut.milli <= 0L) {
            return ReorderSuggestion(QuantityMilli.ZERO, null, QuantityMilli.ZERO, false)
        }
        val velocity = roundDiv(unitsOut.milli, windowDays)
        val daysLeft = if (velocity == 0L) null else onHand.milli.coerceAtLeast(0L) / velocity
        val target = Math.addExact(Math.multiplyExact(velocity, coverDays), minStock.milli)
        val need = Math.subtractExact(target, onHand.milli)
        val raw = need.coerceAtLeast(0L)
        val factor = purchaseFactor?.milli?.takeIf { it > 0L }
        val suggested =
            if (factor == null) raw else Math.multiplyExact(ceilDiv(raw, factor), factor)
        return ReorderSuggestion(
            QuantityMilli(velocity),
            daysLeft,
            QuantityMilli(suggested),
            true,
        )
    }
}
