package io.github.yesidrobayo.shopledger.domain.model.document

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * Qué documento es. Cada uno tiene su título y su leyenda, y ninguno se llama factura.
 *
 * Por ahora solo el de una venta. Cotizaciones y pedidos usan el mismo papel con su propio título
 * y su propia leyenda, para que nunca se confundan con un comprobante de venta.
 */
enum class ReceiptKind {
    /** El comprobante de una venta. */
    SALE,
}

/**
 * Un documento en papel para entregar a quien compra: comprobante, y más adelante cotización o
 * pedido. Es lo único que dibuja el motor de documentos, en PDF, imagen o térmica.
 *
 * Es un modelo aparte a propósito: no tiene costo, margen, ganancia, comisión, proveedor ni notas
 * internas, así que ningún documento puede llevarlos aunque la venta sí los tenga. Tampoco tiene
 * la leyenda: la pone siempre quien dibuja, y por eso no se puede quitar.
 *
 * - [number] es el número visible del documento, el id que le dio la base.
 * - [voided] marca el documento como anulado junto a su número. Un documento anulado se puede
 *   compartir: dice que se anuló.
 * - Los textos ya vienen congelados: nombres de producto, de método y de impuesto como estaban al
 *   vender, aunque después cambien.
 * - [amounts] son las filas de cifras, en el orden en que se dibujan.
 */
data class ReceiptDocument(
    val kind: ReceiptKind,
    val number: Long,
    val voided: Boolean,
    val header: ReceiptHeader,
    val businessDate: BusinessDate,
    val occurredAt: Instant,
    val customerName: String?,
    val lines: List<ReceiptLine>,
    val amounts: List<ReceiptAmount>,
    val footerText: String?,
)

/**
 * El encabezado del negocio, todo opcional: logo, nombre y datos que escribió la persona. Un texto
 * en blanco no encabeza nada.
 */
data class ReceiptHeader(
    val logoMediaId: Long?,
    val businessName: String?,
    val businessDetails: String?,
)

/**
 * Una línea del documento: cuánto, de qué y lo que cobra antes del ajuste de todo el documento.
 * [quantity] va en la unidad [unitId] en que se vendió. [extra] marca un extra o una opción, que
 * va sangrado bajo su producto.
 */
data class ReceiptLine(
    val productName: String,
    val quantity: QuantityMilli,
    val unitId: Long,
    val amount: Money,
    val extra: Boolean,
)

/** Una fila de cifras bajo las líneas. Cada clase sabe su nombre; quien dibuja pone el texto. */
sealed interface ReceiptAmount {
    val amount: Money

    /** Lo que suman las líneas, antes del ajuste de todo el documento. */
    data class Subtotal(override val amount: Money) : ReceiptAmount

    /** El descuento, si es negativo, o el recargo, si es positivo, de todo el documento. */
    data class Adjustment(override val amount: Money) : ReceiptAmount

    /**
     * El impuesto de una tasa, con el nombre que tenía al vender, o `null` si no tenía. Con
     * [included], el precio ya lo traía: se informa, pero no suma al total.
     */
    data class Tax(
        val name: String?,
        val rate: BasisPoints,
        override val amount: Money,
        val included: Boolean,
    ) : ReceiptAmount

    /** El redondeo del efectivo, con signo. */
    data class Rounding(override val amount: Money) : ReceiptAmount

    /** La propina. */
    data class Tip(override val amount: Money) : ReceiptAmount

    /** Lo que se cobra: el total con la propina y el redondeo. */
    data class Total(override val amount: Money) : ReceiptAmount

    /** Lo que cubrió un pago, con el nombre del método al cobrar. */
    data class Payment(val methodName: String, override val amount: Money) : ReceiptAmount

    /** El cambio que se devolvió en efectivo. */
    data class Change(override val amount: Money) : ReceiptAmount

    /** Lo que quedó a crédito. */
    data class BalanceDue(override val amount: Money) : ReceiptAmount

    /** Una devolución activa del documento, con su número y lo que devolvió, en negativo. */
    data class Return(val number: Long, override val amount: Money) : ReceiptAmount
}
