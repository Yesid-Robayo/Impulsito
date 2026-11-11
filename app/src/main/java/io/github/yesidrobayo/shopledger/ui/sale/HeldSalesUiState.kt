package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Las ventas en espera: la lista, lo que está abierto encima y lo que pasa una sola vez.
 *
 * [resume] llega una vez, cuando la persona elige retomar una venta: la ruta vuelve a la venta
 * nueva con ella.
 */
@Immutable
data class HeldSalesUiState(
    val content: HeldSalesContent = HeldSalesContent.Loading,
    val dialog: HeldSalesDialog? = null,
    val rename: RenameEditor? = null,
    val notice: HeldSalesNotice? = null,
    val resume: Long? = null,
)

/** Lo que muestra la lista. */
sealed interface HeldSalesContent {
    /** Leyendo. El esqueleto solo aparece si tarda. */
    data object Loading : HeldSalesContent

    /** No hay ventas en espera. */
    data object Empty : HeldSalesContent

    /** No se pudo leer la lista. Se puede reintentar. */
    data object ReadError : HeldSalesContent

    /** Las ventas, de la más antigua a la más reciente. [zone] falta si no se conoce la zona. */
    data class Sales(val rows: List<HeldSaleRow>, val zone: BusinessTimeZone?) : HeldSalesContent
}

/**
 * Una venta en espera como la ve su fila. [label] falta si no tiene nombre. [itemCount] y
 * [estimatedTotal] salen de los precios vigentes, sin las líneas de productos ya no disponibles;
 * [estimatedTotal] falta si una línea tiene un problema, como un descuento mayor que ella.
 */
@Immutable
data class HeldSaleRow(
    val id: Long,
    val label: String?,
    val itemCount: Long,
    val estimatedTotal: Money?,
    val heldSince: Instant,
)

/** El diálogo abierto. Uno a la vez. */
sealed interface HeldSalesDialog {
    /** Hay otra venta en edición: pasa a espera y se retoma [heldSaleId]. */
    data class Swap(val heldSaleId: Long) : HeldSalesDialog

    /** Descartar [heldSaleId]: se borra lo guardado, nada más. */
    data class Discard(val heldSaleId: Long) : HeldSalesDialog
}

/** El nombre que se escribe al renombrar, tal cual. */
@Immutable
data class RenameEditor(
    val heldSaleId: Long,
    val text: String,
    val tooLong: Boolean = false,
    val working: Boolean = false,
)

/** Un aviso breve de la lista. */
enum class HeldSalesNotice {
    /** No se pudo descartar ni renombrar: la venta sigue como estaba. */
    CHANGE_FAILED,
}
