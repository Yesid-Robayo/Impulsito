package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleReceipt
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleResults
import kotlin.time.Instant

/** El estado del detalle de una venta. */
sealed interface SaleDetailUiState {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : SaleDetailUiState

    /** La venta no existe: un enlace viejo o una copia restaurada sin ella. */
    data object NotFound : SaleDetailUiState

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : SaleDetailUiState

    /**
     * La venta, con lo que está abierto encima. [voided] llega una sola vez, al anularla, para el
     * mensaje. [correcting] pregunta antes de anular y corregir; [corrected] llega una sola vez,
     * con la venta ya anulada, para abrir la nueva con sus datos, y [correctFailed] si no se pudo
     * anular. [returnVoided] llega una sola vez, al anular una devolución, para el mensaje.
     */
    @Immutable
    data class Ready(
        val sale: SaleDetailView,
        val sheet: VoidSheet? = null,
        val status: VoidStatus = VoidStatus.IDLE,
        val voided: Boolean = false,
        val correcting: Boolean = false,
        val corrected: Long? = null,
        val correctFailed: Boolean = false,
        val returnSheet: SaleReturnSheet? = null,
        val returnVoided: Boolean = false,
    ) : SaleDetailUiState
}

/**
 * Lo que muestra el detalle de una venta. [zone] falta si el teléfono no conoce la zona del
 * negocio: entonces no se muestra la hora. [voiding] es la anulación, o `null` si está activa.
 * [results] son el costo, la ganancia, el margen, las comisiones y el neto: se ocultan en modo
 * discreto. [charges] es lo que explica el total además de las líneas. [receipt] es el comprobante
 * que se comparte, solo de una venta activa, y [units] las unidades con que se escriben sus
 * cantidades. [hasActiveReturns] dice si alguna devolución sigue activa. [canReturn] dice si queda
 * algo por devolver, y [returns] son sus devoluciones, activas y anuladas, en el orden en que se
 * registraron.
 */
@Immutable
data class SaleDetailView(
    val saleId: Long,
    val total: Money,
    val businessDate: BusinessDate,
    val occurredAt: Instant,
    val zone: BusinessTimeZone?,
    val voiding: SaleVoidView?,
    val lines: List<SaleDetailLine>,
    val payments: List<SaleDetailPayment>,
    val results: SaleResults,
    val charges: SaleDetailCharges = SaleDetailCharges(),
    val note: String?,
    val receipt: SaleReceipt? = null,
    val units: Map<Long, MeasureUnit> = emptyMap(),
    val hasActiveReturns: Boolean = false,
    val canReturn: Boolean = false,
    val returns: List<SaleReturnRow> = emptyList(),
) {
    /**
     * Solo una venta activa se anula, una sola vez, y nunca con una devolución activa: lo que falta
     * se devuelve.
     */
    val canVoid: Boolean get() = voiding == null && !hasActiveReturns
}

/** Cuándo y por qué se anuló la venta. [date] falta sin zona conocida. */
@Immutable
data class SaleVoidView(val reason: VoidReason, val date: BusinessDate?, val note: String?)

/**
 * Una línea vendida, con el nombre que tenía al venderse. [archived] si el producto hoy está
 * archivado: se muestra igual, marcado. [returned] es lo que ya devolvieron sus devoluciones activas
 * y [left] lo que falta por devolver, los dos en la unidad de la línea.
 */
@Immutable
data class SaleDetailLine(
    val lineId: Long,
    val name: String,
    val quantity: QuantityMilli,
    val unit: MeasureUnit?,
    val unitPrice: Money,
    val discount: Money,
    val total: Money,
    val archived: Boolean,
    val returned: QuantityMilli = QuantityMilli.ZERO,
    val left: QuantityMilli = quantity,
    val factor: QuantityMilli = QuantityMilli(1_000L),
)

/**
 * Una devolución de la venta en su lista: su número, que es su id, el día en que cuenta, cómo se
 * resolvió, lo que devolvió y su nota. [voided] si se anuló: sigue en la lista, sin contar.
 * [replacementSaleId] es la venta nueva, si la devolución es parte de un cambio.
 */
@Immutable
data class SaleReturnRow(
    val returnId: Long,
    val date: BusinessDate,
    val resolution: ReturnResolution,
    val total: Money,
    val voided: Boolean,
    val note: String? = null,
    val replacementSaleId: Long? = null,
)

/**
 * La hoja de una devolución: la fila de la lista y sus líneas con el nombre congelado en la venta y
 * lo devuelto. [lines] falta mientras se leen. [replacementActive] dice si la venta nueva de su
 * cambio sigue activa: entonces no se anula desde aquí. [void] es el paso de anularla, con el
 * motivo y la nota, y [voidStatus] en qué va.
 */
@Immutable
data class SaleReturnSheet(
    val row: SaleReturnRow,
    val lines: List<SaleReturnSheetLine>? = null,
    val replacementActive: Boolean = false,
    val void: VoidSheet? = null,
    val voidStatus: VoidStatus = VoidStatus.IDLE,
) {
    /**
     * Se ofrece anular una devolución activa, ya leída, salvo que sea parte de un cambio cuya
     * venta nueva siga activa: ese cambio se deshace anulando primero la venta nueva.
     */
    val canVoid: Boolean get() = !row.voided && lines != null && !replacementActive

    /** La venta nueva que hay que anular antes, o `null` si nada lo impide. */
    val lockedBySale: Long?
        get() = row.replacementSaleId.takeIf { !row.voided && lines != null && replacementActive }
}

/** Una línea de una devolución: el producto, lo devuelto en la unidad de la línea y su total. */
@Immutable
data class SaleReturnSheetLine(
    val name: String,
    val quantity: QuantityMilli,
    val unit: MeasureUnit?,
    val total: Money,
    val restock: Boolean,
)

/**
 * Un pago, con el nombre del método que tenía al cobrar. [fee] es su comisión y [netReceived] lo que
 * llega de verdad por él: los dos se ocultan en modo discreto, y sin comisión no se muestran.
 * [settlesOn] es la fecha en que se acredita, solo si es posterior al día de la venta.
 * [foreign] es lo que se recibió en otra moneda, si se cobró así: moneda, monto y cotización.
 */
@Immutable
data class SaleDetailPayment(
    val paymentId: Long,
    val methodName: String,
    val amount: Money,
    val settlesOn: BusinessDate?,
    val fee: Money = Money.ZERO,
    val netReceived: Money = amount,
    val foreign: ForeignTender? = null,
) {
    val hasFee: Boolean get() = fee > Money.ZERO
}

/**
 * Lo que, además de las líneas, explica lo cobrado: el impuesto de cada tasa con su nombre, el
 * descuento o recargo de toda la venta, la propina y el redondeo del efectivo. Cada uno solo se
 * muestra si no es cero. Los ve también quien compra, así que no se ocultan en modo discreto.
 */
@Immutable
data class SaleDetailCharges(
    val taxes: List<SaleDetailTax> = emptyList(),
    val orderAdjustment: Money = Money.ZERO,
    val tip: Money = Money.ZERO,
    val cashRounding: Money = Money.ZERO,
) {
    val isEmpty: Boolean
        get() = taxes.isEmpty() && orderAdjustment == Money.ZERO && tip == Money.ZERO &&
            cashRounding == Money.ZERO
}

/** El impuesto de una tasa en la venta. [name] es el que tenía al vender, o `null` sin nombre. */
@Immutable
data class SaleDetailTax(val name: String?, val amount: Money)

/** La hoja de anular: el motivo, la nota opcional y lo que pasará. */
@Immutable
data class VoidSheet(val reason: VoidReason = VoidReason.ENTRY_ERROR, val note: String = "") {
    val noteTooLong: Boolean get() = note.length > MAX_VOID_NOTE_LENGTH

    companion object {
        /** Largo máximo de la nota de una anulación. */
        const val MAX_VOID_NOTE_LENGTH = 200

        /** Los motivos que la persona elige para una venta. Deshacer usa el suyo. */
        val REASONS: List<VoidReason> =
            listOf(VoidReason.ENTRY_ERROR, VoidReason.CUSTOMER_RETURN, VoidReason.OTHER)

        /**
         * Los motivos de anular una devolución: devolver lo devuelto no tiene sentido, y deshacer
         * es de la venta recién registrada.
         */
        val RETURN_REASONS: List<VoidReason> = listOf(VoidReason.ENTRY_ERROR, VoidReason.OTHER)
    }
}

/** En qué va la anulación. */
enum class VoidStatus {
    IDLE,

    /** Anulando: el botón muestra su indicador e ignora más toques. */
    WORKING,

    /** No se pudo anular. La venta sigue activa, y se puede reintentar. */
    FAILED,

    /**
     * No se anuló una devolución porque lo que volvió a un lote ya salió otra vez: el lote
     * quedaría en negativo. La devolución sigue activa.
     */
    LOT_USED,
}

/** Lo que la persona hace en el detalle de una venta. */
sealed interface SaleDetailAction {
    data object OpenVoid : SaleDetailAction

    data class ReasonChange(val reason: VoidReason) : SaleDetailAction

    data class NoteChange(val note: String) : SaleDetailAction

    data object ConfirmVoid : SaleDetailAction

    data object CloseSheet : SaleDetailAction

    data object Retry : SaleDetailAction

    data object VoidedShown : SaleDetailAction

    /** Pregunta antes de anular la venta y abrir otra con sus datos. */
    data object OpenCorrect : SaleDetailAction

    data object ConfirmCorrect : SaleDetailAction

    data object DismissCorrect : SaleDetailAction

    /** La venta nueva ya se abrió, o el aviso de la falla ya se mostró. */
    data object CorrectShown : SaleDetailAction

    /** Comparte el comprobante como texto, con el selector del sistema. */
    data object Share : SaleDetailAction

    data object Back : SaleDetailAction

    /** Abre la devolución de la venta. */
    data object OpenReturn : SaleDetailAction

    /** Abre la hoja de la devolución [returnId]. */
    data class OpenReturnDetail(val returnId: Long) : SaleDetailAction

    data object CloseReturnDetail : SaleDetailAction

    /** Pasa de la hoja de la devolución abierta a anularla. */
    data object OpenReturnVoid : SaleDetailAction

    data class ReturnVoidReasonChange(val reason: VoidReason) : SaleDetailAction

    data class ReturnVoidNoteChange(val note: String) : SaleDetailAction

    data object ConfirmReturnVoid : SaleDetailAction

    /** El aviso de devolución anulada ya se mostró. */
    data object ReturnVoidedShown : SaleDetailAction
}
