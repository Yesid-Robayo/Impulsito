package io.github.yesidrobayo.shopledger.ui.document

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import kotlinx.datetime.YearMonth

/**
 * El estado de los pendientes de facturar: el mes y la marca elegidos, las ventas con su resumen,
 * la selección de varias y las dos hojas: `Registro externo` de una venta y `Configurar enlace`.
 *
 * [month] y [today] faltan hasta conocer el calendario del negocio. [portalUrl] es el enlace que
 * guardó la persona, o `null`: sin él no aparece `Abrir enlace`, y la lista funciona igual.
 */
@Immutable
data class FiscalUiState(
    val month: YearMonth? = null,
    val today: BusinessDate? = null,
    val filter: FiscalMarkStatus = FiscalMarkStatus.PENDING,
    val content: FiscalContent = FiscalContent.Loading,
    val selection: Set<Long> = emptySet(),
    val portalUrl: String? = null,
    val sheet: FiscalMarkSheet? = null,
    val linkSheet: FiscalLinkSheet? = null,
    val bulkStatus: FiscalSheetStatus = FiscalSheetStatus.IDLE,
    val message: FiscalMessage? = null,
) {
    /** Si la persona está eligiendo varias ventas: la barra cambia a marcarlas juntas. */
    val selecting: Boolean get() = selection.isNotEmpty()
}

/** Lo que muestra la lista del mes. */
sealed interface FiscalContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : FiscalContent

    /** No se pudo leer. Se puede reintentar. */
    data object ReadError : FiscalContent

    /** El teléfono no conoce la zona del negocio, así que no sabe qué mes es hoy. */
    data object UnknownZone : FiscalContent

    /**
     * Las ventas del mes con la marca elegida, de la más nueva a la más antigua. [total] es `null`
     * si la suma no cabe. [hasMarks] dice si alguna venta ya tiene marca: sin ninguna, es el
     * primer uso.
     */
    data class Sales(val sales: List<FiscalSale>, val total: Money?, val hasMarks: Boolean) :
        FiscalContent
}

/**
 * La hoja `Registro externo` de una venta: sus datos, la referencia que se escribe y en qué va
 * guardar. [sale] es `null` mientras se lee, o si la venta ya no está activa.
 */
@Immutable
data class FiscalMarkSheet(
    val saleId: Long,
    val sale: FiscalSale? = null,
    val reference: String = "",
    val referenceTooLong: Boolean = false,
    val status: FiscalSheetStatus = FiscalSheetStatus.IDLE,
    val unavailable: Boolean = false,
)

/** La hoja `Configurar enlace`: lo que se escribe y su error, si hay. */
@Immutable
data class FiscalLinkSheet(
    val text: String,
    val error: FiscalLinkError? = null,
    val status: FiscalSheetStatus = FiscalSheetStatus.IDLE,
)

/** Lo que impide guardar el enlace, en su campo. */
enum class FiscalLinkError {
    NOT_HTTPS,
    TOO_LONG,
}

/** En qué va lo que se confirma en una hoja o en la barra. */
enum class FiscalSheetStatus {
    IDLE,

    /** Guardando: el botón muestra su indicador e ignora más toques. */
    WORKING,

    /** No se pudo. Lo escrito se conserva, la venta no cambió, y se puede reintentar. */
    FAILED,
}

/** El aviso breve que sigue a algo que no pasó en una hoja, para mostrarlo una vez. */
enum class FiscalMessage {
    /** Ninguna app del teléfono abre el enlace. */
    NO_APP_FOR_LINK,
}

/** Lo que la persona hace en los pendientes de facturar. */
sealed interface FiscalAction {
    data object Back : FiscalAction

    data object PreviousMonth : FiscalAction

    data object NextMonth : FiscalAction

    data class FilterChange(val filter: FiscalMarkStatus) : FiscalAction

    data object Retry : FiscalAction

    /** Abre la configuración regional para elegir la zona del negocio. */
    data object ChooseZone : FiscalAction

    /**
     * Toca una venta: abre su hoja, o la suma o la quita de la selección si ya se eligen varias.
     */
    data class OpenSale(val saleId: Long) : FiscalAction

    /** Mantiene presionada una venta, o usa la acción `Seleccionar` de TalkBack. */
    data class ToggleSelection(val saleId: Long) : FiscalAction

    data object ClearSelection : FiscalAction

    /** Marca juntas las ventas elegidas, sin referencia. */
    data class MarkSelection(val status: FiscalMarkStatus) : FiscalAction

    data class ReferenceChange(val text: String) : FiscalAction

    /** Marca la venta de la hoja con la referencia escrita. */
    data class MarkSale(val status: FiscalMarkStatus) : FiscalAction

    data object CloseSheet : FiscalAction

    /** Abre el detalle de la venta de la hoja: lo resuelve la navegación. */
    data class ViewSale(val saleId: Long) : FiscalAction

    data object ConfigureLink : FiscalAction

    data class LinkChange(val text: String) : FiscalAction

    data object SaveLink : FiscalAction

    data object CloseLinkSheet : FiscalAction

    /** Abre el enlace en otra app: lo resuelve la navegación, solo porque la persona lo tocó. */
    data object OpenLink : FiscalAction

    /** Ninguna app abrió el enlace. */
    data object NoAppForLink : FiscalAction

    /** Comparte el resumen por el selector del sistema: lo resuelve la navegación. */
    data object ShareSummary : FiscalAction

    data object MessageShown : FiscalAction
}
