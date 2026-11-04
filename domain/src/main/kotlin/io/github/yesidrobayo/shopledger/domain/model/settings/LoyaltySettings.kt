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
)
