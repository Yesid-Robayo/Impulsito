package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * El descuento de una línea tal como lo escribió la persona: un monto o un porcentaje.
 *
 * La línea guarda siempre el monto, en unidad menor. El porcentaje se convierte al confirmar, sobre
 * el bruto de la línea, y no se guarda: así un reporte suma descuentos sin volver a calcular nada.
 */
sealed interface LineDiscount {
    /** Sin descuento. */
    data object None : LineDiscount

    /** Un monto fijo, en unidad menor, de cero hasta el bruto de la línea. */
    data class Amount(val amount: Money) : LineDiscount

    /** Un porcentaje del bruto de la línea, de 0 a 100 %, en puntos básicos. */
    data class Percent(val rate: BasisPoints) : LineDiscount
}
