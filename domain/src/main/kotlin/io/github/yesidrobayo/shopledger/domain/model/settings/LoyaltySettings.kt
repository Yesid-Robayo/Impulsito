package io.github.yesidrobayo.shopledger.domain.model.settings

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * El programa de puntos de los clientes. [amountPerPoint] es cuánto hay que comprar para ganar un
 * punto, y [pointValue] cuánto descuenta un punto, los dos en unidad menor. Encendido, los dos
 * existen siempre; apagado, pueden quedar los valores de la última vez.
 */
data class LoyaltySettings(
    val enabled: Boolean,
    val amountPerPoint: Money?,
    val pointValue: Money?,
) {
    /**
     * Si el programa está activo y tiene sus dos montos: solo así una venta gana o canjea puntos.
     */
    val active: Boolean get() = enabled && amountPerPoint != null && pointValue != null

    companion object {
        /** El programa apagado y sin montos, como en un negocio nuevo. */
        val OFF: LoyaltySettings = LoyaltySettings(false, amountPerPoint = null, pointValue = null)
    }
}
