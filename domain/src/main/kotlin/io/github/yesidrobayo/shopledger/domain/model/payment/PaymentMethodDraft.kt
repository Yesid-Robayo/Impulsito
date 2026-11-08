package io.github.yesidrobayo.shopledger.domain.model.payment

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que la persona escribe en el formulario de un método de pago, antes de validarlo.
 *
 * [name] es el texto tal como lo escribió, sin recortar. `null` significa que un método inicial
 * conserva su texto traducido: la pantalla lo manda así mientras la persona no toque el nombre,
 * para que cambiar de idioma lo siga traduciendo. Un método de la persona siempre trae nombre.
 *
 * Los montos y porcentajes ya vienen como enteros: la pantalla los lee con el formato regional, y
 * aquí solo se revisa su rango. [image] dice qué pasa con la imagen de cobro, que ya llega
 * recodificada y guardada en su archivo.
 */
data class PaymentMethodDraft(
    val name: String?,
    val kind: PaymentMethodKind,
    val feeRate: BasisPoints,
    val feeFixed: Money,
    val settlementDays: Int,
    val priceAdjustment: BasisPoints,
    val moneyAccountId: Long?,
    val instructionsText: String?,
    val image: InstructionsImageChange = InstructionsImageChange.Keep,
)
