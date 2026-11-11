package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartLineIssue
import kotlinx.datetime.DayOfWeek

/**
 * El estado de la venta nueva: lo que se ofrece para vender, la venta que se arma, cómo se cobra y
 * lo que está abierto encima.
 *
 * [query] es lo escrito en la búsqueda, tal cual. [registered] llega una sola vez, al confirmar: la
 * pantalla vuelve a Inicio con el mensaje. Con [closed], la persona salió sin registrar.
 *
 * [heldCount] cuenta las ventas en espera, sin la que se edita. [heldSaleId] es la venta en espera
 * de la que se retomó esta, si se retomó: al confirmar, sale de la espera.
 */
@Immutable
data class NewSaleUiState(
    val content: SaleContent = SaleContent.Loading,
    val query: String = "",
    val cart: SaleCartState = SaleCartState(),
    val payment: SalePaymentState = SalePaymentState(),
    val sheet: SaleSheet? = null,
    val dialog: SaleDialog? = null,
    val status: RegisterStatus = RegisterStatus.IDLE,
    val notice: SaleNotice? = null,
    val leftOut: Int = 0,
    val registered: RegisteredSale? = null,
    val closed: Boolean = false,
    val heldCount: Int = 0,
    val heldSaleId: Long? = null,
    val heldNotice: HeldNotice? = null,
)

/** Lo que muestra la cuadrícula de productos. */
sealed interface SaleContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : SaleContent

    /** No hay ningún producto vigente: se invita a crear el primero. */
    data object EmptyCatalog : SaleContent

    /** No se pudo leer el catálogo. Se puede reintentar. */
    data object ReadError : SaleContent

    /**
     * Las tarjetas: los más vendidos si no se busca nada ([searching] falso), o lo que coincide con
     * la búsqueda. [archived] son productos archivados que coinciden con ella, que se pueden
     * reactivar. [query] es la búsqueda con que se armó: otra búsqueda empieza arriba.
     */
    data class Products(
        val tiles: List<SaleTile>,
        val searching: Boolean,
        val archived: List<ArchivedMatch> = emptyList(),
        val query: String = "",
    ) : SaleContent

    /** Nada coincide con [query]: se ofrece crear el producto con ese nombre. */
    data class NoResults(val query: String, val archived: List<ArchivedMatch> = emptyList()) :
        SaleContent
}

/**
 * La tarjeta de un producto en la venta. [price] falta si vale cero: el producto no tiene precio, y
 * tocarlo abre la línea con el precio enfocado. [line] es lo que lleva de él la venta, o `null`.
 */
@Immutable
data class SaleTile(
    val productId: Long,
    val name: String,
    val price: Money?,
    val stock: TileStock,
    val unit: MeasureUnit?,
    val line: TileLine?,
)

/** La cantidad de un producto en la venta, como la ve su tarjeta. */
@Immutable
data class TileLine(
    val quantity: QuantityMilli,
    val unit: MeasureUnit? = null,
    val removesLine: Boolean,
    val canIncrement: Boolean,
    val oversell: QuantityMilli?,
)

/** Lo que dice una tarjeta del stock del producto. */
sealed interface TileStock {
    data class Normal(val onHand: QuantityMilli) : TileStock

    /** En su mínimo o por debajo, pero todavía con unidades. */
    data class Low(val onHand: QuantityMilli) : TileStock

    /** Sin unidades. */
    data object Out : TileStock

    /** Vendido de más: falta registrar una compra o un conteo. */
    data class Negative(val onHand: QuantityMilli) : TileStock

    /** Un servicio o un producto hecho de componentes: no lleva stock propio. */
    data object None : TileStock
}

/** Un producto archivado que coincide con la búsqueda: no se vende sin reactivarlo antes. */
@Immutable
data class ArchivedMatch(val productId: Long, val name: String)

/**
 * La venta que se arma, con sus cuentas. [total] es lo que se cobra, con el redondeo del efectivo
 * [cashRounding] si se cobra en efectivo; falta si una línea tiene un problema o si pasa del tope
 * de un documento. [tax] es el impuesto que lleva, solo si el negocio cobra impuesto. [date] es la fecha elegida, o `null` para hoy; [today] falta si el
 * teléfono no conoce la zona del negocio.
 */
@Immutable
data class SaleCartState(
    val lines: List<CartLine> = emptyList(),
    val itemCount: Long = 0L,
    val tax: Money? = null,
    val total: Money? = Money.ZERO,
    val cashRounding: Money = Money.ZERO,
    val date: BusinessDate? = null,
    val today: BusinessDate? = null,
    val weekStartDay: DayOfWeek? = null,
    val note: String = "",
    val noteTooLong: Boolean = false,
    val full: Boolean = false,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** Si hay algo que confirmar y nada lo impide. */
    val canRegister: Boolean get() = lines.isNotEmpty() && total != null && !noteTooLong
}

/** Una línea de la venta, con sus cuentas y lo que necesita su selector de cantidad. */
@Immutable
data class CartLine(
    val productId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val quantity: QuantityMilli,
    val unitPrice: Money,
    val discount: Money?,
    val total: Money?,
    val issue: CartLineIssue?,
    val removesLine: Boolean,
    val canIncrement: Boolean,
)

/** Los métodos con que se cobra, y el elegido. */
@Immutable
data class SalePaymentState(
    val methods: List<SaleMethod> = emptyList(),
    val selectedId: Long? = null,
) {
    val selected: SaleMethod? get() = methods.firstOrNull { it.id == selectedId }
}

/**
 * Un método de pago como se ofrece al cobrar. El nombre visible es [name], o el texto de
 * [systemKey] en el idioma de la interfaz si nadie lo renombró.
 */
@Immutable
data class SaleMethod(val id: Long, val systemKey: String?, val name: String?, val hasQr: Boolean)

/** La hoja abierta sobre la venta. */
sealed interface SaleSheet {
    /** Las líneas, la fecha y la nota. */
    data object Cart : SaleSheet

    /** Una línea: cantidad, precio de esta línea y descuento. */
    data class Line(val editor: LineEditor) : SaleSheet

    /**
     * La cantidad de un producto por peso o medida. Con [adding] suma lo escrito a la venta; sin él,
     * reemplaza la cantidad de su línea.
     */
    data class Quantity(
        val productId: Long,
        val name: String,
        val unit: MeasureUnit?,
        val text: String,
        val adding: Boolean,
        val error: SaleFieldError? = null,
    ) : SaleSheet

    /** Elegir el método cuando la letra es grande y los chips no caben. */
    data object PaymentMethods : SaleSheet

    /** La moneda del negocio no está confirmada: nada con dinero se guarda antes. */
    data object CurrencyRequired : SaleSheet

    /**
     * Poner la venta en espera, con el nombre escrito tal cual. [tooLong] marca un nombre de más de
     * 40 caracteres, y [working] apaga el botón mientras se guarda.
     */
    data class Hold(
        val label: String,
        val tooLong: Boolean = false,
        val working: Boolean = false,
    ) : SaleSheet
}

/** Cómo se escribe el descuento de una línea. */
enum class DiscountMode {
    AMOUNT,
    PERCENT,
}

/**
 * Lo escrito en la hoja de una línea, tal cual. Cada campo que se lee bien cambia la línea al
 * momento; uno que no, se marca y la línea conserva su último valor bueno.
 *
 * @param focusPrice si la hoja se abre con el precio enfocado, como con un producto sin precio.
 * @param unitId la unidad en que se vende la línea, y [saleUnits] las que ofrece el producto: la
 *   base y sus conversiones de venta. Con una sola, la hoja no ofrece elegir.
 */
@Immutable
data class LineEditor(
    val productId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val quantityText: String,
    val priceText: String,
    val discountMode: DiscountMode,
    val discountText: String,
    val focusPrice: Boolean = false,
    val unitId: Long? = null,
    val saleUnits: List<MeasureUnit> = emptyList(),
    val quantityError: SaleFieldError? = null,
    val priceError: SaleFieldError? = null,
    val discountError: SaleFieldError? = null,
)

/** Por qué un campo de la venta no se puede usar así. La pantalla lo dice con su texto. */
enum class SaleFieldError {
    /** No es un número, o tiene más decimales de los que admite. */
    NOT_A_NUMBER,

    /** Es demasiado alto: casi siempre, un error al escribir. */
    TOO_HIGH,

    /** La unidad no admite decimales. */
    FRACTION_NOT_ALLOWED,

    /** La cantidad debe ser mayor que cero. */
    ZERO_QUANTITY,

    /** El descuento pasa del total de la línea, o de 100 %. */
    DISCOUNT_OVER_LINE,
}

/** El diálogo abierto. Uno a la vez. */
sealed interface SaleDialog {
    /** Salir con productos: lo agregado se pierde. */
    data class Leave(val itemCount: Long) : SaleDialog

    /** Vaciar la venta. */
    data class Clear(val itemCount: Long) : SaleDialog

    /** Una venta en cero: el único caso que pide confirmar. */
    data object ZeroTotal : SaleDialog
}

/** En qué va la confirmación de la venta. */
enum class RegisterStatus {
    IDLE,

    /** Guardando: el botón muestra su indicador e ignora más toques. */
    WORKING,

    /** No se pudo guardar. La venta sigue intacta, y se puede reintentar. */
    FAILED,
}

/** Un aviso breve sobre la venta. */
enum class SaleNotice {
    /** La venta llegó al máximo de líneas. */
    LINES_FULL,

    /** No se pudo reactivar el producto archivado. */
    REACTIVATE_FAILED,

    /**
     * La venta que corrige a otra abrió sin las líneas de productos hoy archivados: cuántas dice
     * `leftOut` del estado.
     */
    LINES_LEFT_OUT,
}

/** Lo que pasó con la espera, para decirlo una vez. */
sealed interface HeldNotice {
    /** La venta quedó en espera: la pantalla queda lista para otra. */
    data object Held : HeldNotice

    /** No se pudo poner en espera. La venta sigue aquí. */
    data object HoldFailed : HeldNotice

    /** No se pudo abrir la venta elegida: ya no existe. */
    data object ResumeFailed : HeldNotice

    /**
     * La venta se retomó, pero [changedPrices] líneas toman un precio que cambió desde que se
     * guardó, y [removed] quedaron fuera porque su producto ya no está disponible.
     */
    data class Resumed(val changedPrices: Int, val removed: Int) : HeldNotice
}

/** La venta recién guardada: su número y su total, para el mensaje de Inicio. */
@Immutable
data class RegisteredSale(val saleId: Long, val total: Money)
