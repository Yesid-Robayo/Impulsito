package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
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
     * anular.
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
    ) : SaleDetailUiState
}

/**
 * Lo que muestra el detalle de una venta. [zone] falta si el teléfono no conoce la zona del
 * negocio: entonces no se muestra la hora. [voiding] es la anulación, o `null` si está activa.
 * [results] son el costo, la ganancia, el margen, las comisiones y el neto: se ocultan en modo
 * discreto. [charges] es lo que explica el total además de las líneas. [receipt] es el comprobante
 * que se comparte, solo de una venta activa, y [units] las unidades con que se escriben sus
 * cantidades.
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
) {
    /** Solo una venta activa se anula, una sola vez. */
    val canVoid: Boolean get() = voiding == null
}

/** Cuándo y por qué se anuló la venta. [date] falta sin zona conocida. */
@Immutable
data class SaleVoidView(val reason: VoidReason, val date: BusinessDate?, val note: String?)

/**
 * Una línea vendida, con el nombre que tenía al venderse. [archived] si el producto hoy está
 * archivado: se muestra igual, marcado.
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
)

/**
 * Un pago, con el nombre del método que tenía al cobrar. [fee] es su comisión y [netReceived] lo que
 * llega de verdad por él: los dos se ocultan en modo discreto, y sin comisión no se muestran.
 * [settlesOn] es la fecha en que se acredita, solo si es posterior al día de la venta.
 */
@Immutable
data class SaleDetailPayment(
    val paymentId: Long,
    val methodName: String,
    val amount: Money,
    val settlesOn: BusinessDate?,
    val fee: Money = Money.ZERO,
    val netReceived: Money = amount,
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
    }
}

/** En qué va la anulación. */
enum class VoidStatus {
    IDLE,

    /** Anulando: el botón muestra su indicador e ignora más toques. */
    WORKING,

    /** No se pudo anular. La venta sigue activa, y se puede reintentar. */
    FAILED,
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
}
