package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutBalance
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutProblem
import io.github.yesidrobayo.shopledger.ui.reference.RateProblem
import kotlinx.datetime.DayOfWeek

/**
 * El estado del cobro: el bloque del total, los pagos, lo que falta o sobra y lo que está abierto
 * encima.
 *
 * [registered] llega una sola vez, al confirmar: la pantalla vuelve a Inicio con el mensaje. Con
 * [tipsEnabled], el cobro ofrece la propina, escrita en [tipText].
 *
 * [foreignOffered] es si cada pago ofrece `Cobrar en otra moneda`: solo cuando la persona eligió una
 * moneda de referencia, porque esa es la otra moneda que se cobra. Sin ella, el cobro no cambia.
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
    val tipsEnabled: Boolean = false,
    val tipText: String = "",
    val tipError: SaleFieldError? = null,
    val customer: CheckoutCustomer? = null,
    val customerChoices: List<Customer> = emptyList(),
    val customerQuery: String = "",
    val customerRemoved: Boolean = false,
    val credit: CheckoutCredit? = null,
    val points: CheckoutPoints? = null,
    val customerRecents: List<Customer> = emptyList(),
    val creditBalance: CheckoutCreditBalance? = null,
    val foreignOffered: Boolean = false,
) {
    /** Si algún pago usa un método que ya no se ofrece: hay que quitarlo o cambiarlo. */
    val hasGoneMethod: Boolean get() = payments.any { it.gone }
}

/** Lo que muestra el cobro mientras lee el catálogo, o si la venta ya no está. */
sealed interface CheckoutContent {
    /** Leyendo. Casi nunca se ve: todo está en memoria. */
    data object Loading : CheckoutContent

    /** No hay venta que cobrar: se vuelve a la venta. */
    data object Unavailable : CheckoutContent

    /**
     * No se pudo leer el catálogo: se dice, con `Reintentar`, y los pagos escritos se conservan.
     */
    data object ReadFailed : CheckoutContent

    data object Ready : CheckoutContent
}

/**
 * El bloque del total. [amountDue] es lo que se cobra y nunca se oculta. Debajo, solo las filas que
 * aplican: [subtotal], el ajuste escrito, el recargo o descuento de cada método en [methodAdjustments]
 * [tax], que solo existe cuando el impuesto se suma al precio, [cashRounding], el redondeo del
 * efectivo, [tip], la propina, y [pointsDiscount], el descuento de los puntos canjeados, positivo:
 * así las filas suman el total.
 */
@Immutable
data class CheckoutSummary(
    val amountDue: Money? = null,
    val subtotal: Money? = null,
    val writtenAdjustment: Money = Money.ZERO,
    val methodAdjustments: List<MethodAdjustment> = emptyList(),
    val tax: Money? = null,
    val cashRounding: Money = Money.ZERO,
    val tip: Money = Money.ZERO,
    val pointsDiscount: Money = Money.ZERO,
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
 * - [isCash] si el método es de efectivo: entonces ofrece lo recibido, en [tenderedText], y el
 *   [change]. Con [tenderedBelow], lo recibido no alcanza para lo que cubre el pago redondeado.
 * - [foreign] es lo que se recibió en otra moneda, si se cobró así: debajo del pago se ve qué y a qué
 *   cotización.
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
    val isCash: Boolean = false,
    val tenderedText: String = "",
    val tenderedError: SaleFieldError? = null,
    val change: Money? = null,
    val tenderedBelow: Boolean = false,
    val foreign: ForeignTender? = null,
)

/** La hoja abierta sobre el cobro. */
sealed interface CheckoutSheet {
    /** El descuento o recargo de toda la venta. */
    data class Adjustment(val editor: AdjustmentEditor) : CheckoutSheet

    /** Elegir el método de un pago nuevo. */
    data object AddPayment : CheckoutSheet

    /** Elegir el cliente de la venta. */
    data object Customers : CheckoutSheet

    /** Lo recibido en otra moneda por un pago. */
    data class Foreign(val editor: ForeignEditor) : CheckoutSheet
}

/**
 * La hoja `Cobrar en otra moneda` del pago número [index]. La moneda es [currency], la de
 * referencia, y no se elige aquí. [rateText] empieza con la cotización vigente, si hay, y se puede
 * cambiar solo para este cobro: no se guarda como cotización nueva. [equivalent] es lo entregado
 * pasado a la moneda del negocio, en vivo, o `null` mientras no se pueda calcular.
 *
 * [canRemove] si el pago ya tenía otra moneda: entonces se ofrece `Quitar otra moneda`.
 */
@Immutable
data class ForeignEditor(
    val index: Int,
    val currency: ReferenceCurrency,
    val rateText: String = "",
    val amountText: String = "",
    val equivalent: Money? = null,
    val rateProblem: RateProblem? = null,
    val amountError: SaleFieldError? = null,
    val canRemove: Boolean = false,
)

/** El cliente de la venta como lo muestra el cobro. */
@Immutable
sealed interface CheckoutCustomer {
    /** El cliente elegido, con su saldo de antes de la venta. */
    data class Chosen(val id: Long, val name: String, val balance: Money) : CheckoutCustomer

    /** Hay un pago a crédito y ningún cliente: el cobro lo pide antes de registrar. */
    data object Missing : CheckoutCustomer
}

/**
 * Lo que agrega un pago a crédito: el vencimiento, [due], o ninguno, y el saldo que le queda al
 * cliente tras esta venta, [balanceAfter], si ya hay cliente. [dueError] marca un vencimiento
 * anterior a la venta, [saleDate]. Sin [owes], el saldo a favor cubre todo lo que va a crédito: no
 * queda deuda, y no se pide vencimiento.
 */
@Immutable
data class CheckoutCredit(
    val due: BusinessDate?,
    val dueError: Boolean,
    val balanceAfter: Money?,
    val saleDate: BusinessDate?,
    val weekStartDay: DayOfWeek?,
    val owes: Boolean = true,
)

/**
 * La sección `Saldo a favor`, solo con un cliente con saldo negativo: lo que queda por usar en esta
 * venta, [available], después de los pagos a crédito ya escritos, y si `Usar saldo a favor` tiene
 * algo que pasar a crédito, [canUse].
 */
@Immutable
data class CheckoutCreditBalance(val available: Money, val canUse: Boolean)

/**
 * La sección `Puntos`, solo con el programa activo y un cliente. [available] son sus puntos antes
 * de la venta. [text] es lo escrito en `Puntos por canjear`, y [discount], lo que descontaría, si se
 * lee. [redeemed] son los puntos ya canjeados en esta venta, cuyo descuento está en el total.
 *
 * Los puntos no son dinero: el canje es un descuento de la venta, nunca un pago.
 */
@Immutable
data class CheckoutPoints(
    val available: Long,
    val text: String,
    val discount: Money?,
    val redeemed: Long,
    val error: CheckoutPointsError?,
)

/** Por qué no se canjean los puntos escritos. */
@Immutable
sealed interface CheckoutPointsError {
    /** No es un número entero. */
    data object NotANumber : CheckoutPointsError

    /** Pide más puntos de los que tiene el cliente: solo hay [available]. */
    data class NotEnough(val available: Long) : CheckoutPointsError

    /** Su descuento, con el ajuste escrito, supera el total de la venta. */
    data object AboveSale : CheckoutPointsError
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
