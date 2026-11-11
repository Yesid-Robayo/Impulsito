package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutBalance
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutProblem

/**
 * El estado del cobro: el bloque del total, los pagos, lo que falta o sobra y lo que está abierto
 * encima.
 *
 * [registered] llega una sola vez, al confirmar: la pantalla vuelve a Inicio con el mensaje.
 */
@Immutable
data class CheckoutUiState(
    val content: CheckoutContent = CheckoutContent.Loading,
    val summary: CheckoutSummary = CheckoutSummary(),
    val adjustment: Money = Money.ZERO,
    val payments: List<CheckoutPaymentRow> = emptyList(),
    val addable: List<SaleMethod> = emptyList(),
    val balance: CheckoutBalance? = null,
    val due: Money? = null,
    val balanceError: Boolean = false,
    val problem: CheckoutProblem? = null,
    val sheet: CheckoutSheet? = null,
    val qr: CheckoutQr? = null,
    val zeroDialog: Boolean = false,
    val status: RegisterStatus = RegisterStatus.IDLE,
    val registered: RegisteredSale? = null,
) {
    /** Si algún pago usa un método que ya no se ofrece: hay que quitarlo o cambiarlo. */
    val hasGoneMethod: Boolean get() = payments.any { it.gone }
}

/** Lo que muestra el cobro mientras lee el catálogo, o si la venta ya no está. */
sealed interface CheckoutContent {
    /** Leyendo. Casi nunca se ve: todo está en memoria. */
    data object Loading : CheckoutContent

    /** No hay venta que cobrar, o no se pudo leer: se vuelve a la venta. */
    data object Unavailable : CheckoutContent

    data object Ready : CheckoutContent
}

/**
 * El bloque del total. [amountDue] es lo que se cobra y nunca se oculta. Debajo, solo las filas que
 * aplican: [subtotal], el ajuste escrito, el recargo o descuento de cada método en [methodAdjustments]
 * y [tax], que solo existe cuando el impuesto se suma al precio: así las filas suman el total.
 */
@Immutable
data class CheckoutSummary(
    val amountDue: Money? = null,
    val subtotal: Money? = null,
    val writtenAdjustment: Money = Money.ZERO,
    val methodAdjustments: List<MethodAdjustment> = emptyList(),
    val tax: Money? = null,
)

/** El recargo, positivo, o el descuento, negativo, que suma pagar con [method]. */
@Immutable
data class MethodAdjustment(val method: SaleMethod, val amount: Money)

/**
 * Un pago del cobro, como lo ve su tarjeta.
 *
 * - [amountText] es lo escrito tal cual; [amountError] marca lo que no se lee.
 * - [covers] es lo que termina cubriendo con el recargo de su método, solo si es distinto de lo
 *   escrito.
 * - [showsFee] si el método cobra comisión o la persona escribió una. [feeText] es lo escrito, o la
 *   comisión calculada si no escribió nada. [net] es lo que de verdad llega.
 * - [settlesOn] es la fecha en que se acredita, si tarda.
 * - [gone] si el método se archivó mientras tanto: el pago no se puede guardar así.
 */
@Immutable
data class CheckoutPaymentRow(
    val method: SaleMethod,
    val amountText: String,
    val amountError: SaleFieldError? = null,
    val covers: Money? = null,
    val showsFee: Boolean = false,
    val feeText: String = "",
    val feeError: SaleFieldError? = null,
    val feeAboveAmount: Boolean = false,
    val net: Money? = null,
    val settlesOn: BusinessDate? = null,
    val gone: Boolean = false,
)

/** La hoja abierta sobre el cobro. */
sealed interface CheckoutSheet {
    /** El descuento o recargo de toda la venta. */
    data class Adjustment(val editor: AdjustmentEditor) : CheckoutSheet

    /** Elegir el método de un pago nuevo. */
    data object AddPayment : CheckoutSheet
}

/** Si el ajuste de la venta baja o sube el total. */
enum class AdjustmentKind {
    DISCOUNT,
    SURCHARGE,
}

/** Cómo se escribe el ajuste de la venta: en monto o en porcentaje de lo que suman las líneas. */
enum class AdjustmentInput {
    AMOUNT,
    PERCENT,
}

/** Lo escrito en la hoja del ajuste, tal cual, y su error. */
@Immutable
data class AdjustmentEditor(
    val kind: AdjustmentKind = AdjustmentKind.DISCOUNT,
    val input: AdjustmentInput = AdjustmentInput.AMOUNT,
    val text: String = "",
    val error: AdjustmentError? = null,
    val canRemove: Boolean = false,
)

/** Por qué el ajuste escrito no se aplica. */
enum class AdjustmentError {
    /** No es un número, o tiene más decimales de los que admite. */
    NOT_A_NUMBER,

    /** Un descuento mayor que lo que suman las líneas. */
    DISCOUNT_ABOVE_TOTAL,

    /** Un valor demasiado alto: casi siempre, un error al escribir. */
    TOO_HIGH,
}

/**
 * La imagen de cobro a pantalla completa, para enseñarla a quien paga. [image] falta si el método
 * solo tiene instrucciones, o si la imagen no se pudo leer. [amount] es lo que se paga con ese
 * método.
 */
@Immutable
data class CheckoutQr(
    val method: SaleMethod,
    val amount: Money?,
    val image: StoredImage?,
    val instructions: String?,
    val qrOnly: Boolean,
)
