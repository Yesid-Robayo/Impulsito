package io.github.yesidrobayo.shopledger.domain.model.payment

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que se guarda de un método de pago, ya validado.
 *
 * - Un método inicial nace sin nombre propio: se muestra con el texto traducido de su clave, en el
 *   idioma de la interfaz, hasta que la persona lo renombra. Por eso [name] y [normalizedName]
 *   pueden faltar, siempre juntos. [normalizedName] es [name] pasado por `TextNormalizer`.
 * - [kind] decide cómo se cobra, nunca una marca.
 * - [feeRate] y [feeFixed] son la comisión que escribió la persona, nunca una tarifa de la app.
 *   [settlementDays] es cuánto tarda en llegar el dinero.
 * - [priceAdjustment] lleva signo: un recargo o un descuento por pagar con este método.
 * - [moneyAccountId] es la cuenta a la que entra lo cobrado. Sin cuenta no se mueve dinero.
 * - [instructionsText] e [instructionsMediaId] son los datos de cobro y la imagen, como un código QR,
 *   que se muestran al cobrar. La app no lee ningún pago: la persona confirma a mano.
 *
 * Lanza [IllegalArgumentException] si trae nombre sin su forma normalizada, o al revés.
 */
data class PaymentMethodDetails(
    val name: String?,
    val normalizedName: String?,
    val kind: PaymentMethodKind,
    val feeRate: BasisPoints,
    val feeFixed: Money,
    val settlementDays: Int,
    val priceAdjustment: BasisPoints,
    val moneyAccountId: Long?,
    val instructionsText: String?,
    val instructionsMediaId: Long?,
) {
    init {
        require((name == null) == (normalizedName == null)) {
            "El nombre y su forma normalizada van juntos"
        }
    }
}
