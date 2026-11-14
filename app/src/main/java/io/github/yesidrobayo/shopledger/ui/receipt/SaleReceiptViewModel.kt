package io.github.yesidrobayo.shopledger.ui.receipt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.model.print.PrinterSetup
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.rule.document.SaleReceiptDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que el comprobante lee de los ajustes: la plantilla, el nombre del negocio y su zona. */
data class SaleReceiptSource(val template: ReceiptTemplateSource, val zone: BusinessTimeZone?)

/** Cómo se comparte el comprobante. */
enum class ReceiptShare {
    /** Un PDF en la hoja del formato regional. */
    PDF,

    /** Una imagen larga, para mensajería. */
    IMAGE,
}

/** Lo que muestra el comprobante de una venta. */
sealed interface SaleReceiptUiState {
    data object Loading : SaleReceiptUiState

    data object NotFound : SaleReceiptUiState

    data object ReadFailed : SaleReceiptUiState

    /**
     * El comprobante listo para ver y compartir. [logo] es la imagen del logo, que se lee de su
     * archivo; [units] y [zone] completan lo que el papel escribe. [sharing] es lo que se está
     * creando para compartir, y [shareFailed], lo que no se pudo crear: la venta no cambia.
     *
     * [printer] es la térmica por defecto, si hay. [printing] dice si se está enviando,
     * [askingPermission] si la ruta debe pedir el permiso de Bluetooth, [openPrinters] si hay que
     * abrir las impresoras porque no hay ninguna, y [printed] cómo terminó el último envío.
     */
    data class Ready(
        val document: ReceiptDocument,
        val logo: StoredImage?,
        val units: Map<Long, MeasureUnit>,
        val zone: BusinessTimeZone?,
        val sharing: ReceiptShare? = null,
        val shareFailed: ReceiptShare? = null,
        val printer: SavedPrinter? = null,
        val printing: Boolean = false,
        val askingPermission: Boolean = false,
        val openPrinters: Boolean = false,
        val printed: PrintResult? = null,
    ) : SaleReceiptUiState
}

/** Lo que la persona hace en el comprobante. */
sealed interface SaleReceiptAction {
    data class Share(val kind: ReceiptShare) : SaleReceiptAction

    /** La ruta terminó de crear el archivo y abrir el selector, o no pudo. */
    data class ShareFinished(val shared: Boolean) : SaleReceiptAction

    data object Retry : SaleReceiptAction

    data object Back : SaleReceiptAction

    data object EditTemplate : SaleReceiptAction

    /** Imprimir en la térmica por defecto, o abrir las impresoras si no hay ninguna. */
    data object Print : SaleReceiptAction

    /** La ruta pidió el permiso de Bluetooth, o ya estaba: [granted] dice cómo quedó. */
    data class PrintPermission(val granted: Boolean) : SaleReceiptAction

    /** La ruta ya abrió las impresoras. */
    data object PrintersOpened : SaleReceiptAction

    /** El aviso de la impresión ya se mostró. */
    data object PrintedShown : SaleReceiptAction

    /** Imprimir con el servicio del sistema, en cualquier impresora local o como PDF. */
    data object PrintWithSystem : SaleReceiptAction
}

/**
 * El comprobante de una venta, para verlo y compartirlo como PDF o imagen.
 *
 * Observa la venta y la plantilla: si la venta se anula o cambia la plantilla, el documento se
 * rehace. Sale de [SaleReceiptDocument], que no tiene costo, margen, ganancia, comisión ni nota.
 * Compartir lo hace la ruta, que tiene el disco y el selector; aquí solo se anota qué se pidió y
 * cómo terminó, una cosa a la vez.
 *
 * @param observeDetail la venta con sus líneas, pagos y devoluciones, o `null` si no existe.
 * @param observeSource la plantilla, el nombre del negocio y su zona.
 * @param observeUnits las unidades de medida, para escribir las cantidades.
 * @param customerName el nombre del cliente de la venta, o `null` si ya no tiene datos.
 * @param creditBefore el saldo a favor que el cliente tenía justo antes de la venta, leído de su
 *   libro: separa en el comprobante el anticipo aplicado de lo que quedó debiendo.
 * @param loadImage la imagen guardada de un id, para el logo.
 * @param observePrinters las impresoras guardadas, para saber cuál va por defecto.
 * @param printReceipt imprime el comprobante listo en una térmica. Un fallo no toca la venta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleReceiptViewModel(
    private val saleId: Long,
    private val observeDetail: (Long) -> Flow<SaleDetail?>,
    private val observeSource: () -> Flow<SaleReceiptSource>,
    private val observeUnits: () -> Flow<List<MeasureUnit>>,
    private val customerName: suspend (Long) -> String?,
    private val creditBefore: suspend (Long) -> Money,
    private val loadImage: suspend (Long) -> StoredImage?,
    computation: CoroutineDispatcher,
    observePrinters: () -> Flow<PrinterSetup> = { flowOf(PrinterSetup.EMPTY) },
    private val printReceipt: suspend (SavedPrinter, SaleReceiptUiState.Ready) -> PrintResult =
        { _, _ -> PrintResult.Failed(PrintFailure.NO_ADAPTER) },
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val sharing = MutableStateFlow(Sharing())
    private val printing = MutableStateFlow(Printing())

    private val content: Flow<SaleReceiptUiState> = attempts.flatMapLatest {
        combine(observeDetail(saleId), observeSource(), observeUnits()) { detail, source, units ->
            if (detail == null) return@combine SaleReceiptUiState.NotFound
            val template = source.template
            val customer = detail.sale.content.customerId?.let { customerName(it) }
            val document = SaleReceiptDocument.of(
                detail,
                template.receipt,
                template.businessName,
                customer,
                creditBefore(saleId),
            )
            SaleReceiptUiState.Ready(
                document = document,
                logo = template.receipt.logoMediaId?.let { loadImage(it) },
                units = units.associateBy { it.id },
                zone = source.zone,
            )
        }.flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(SaleReceiptUiState.ReadFailed) }
    }

    val uiState: StateFlow<SaleReceiptUiState> =
        combine(content, sharing, printing, observePrinters()) { state, shown, print, printers ->
            if (state is SaleReceiptUiState.Ready) {
                state.copy(
                    sharing = shown.working,
                    shareFailed = shown.failed,
                    printer = printers.default,
                    printing = print.working,
                    askingPermission = print.asking,
                    openPrinters = print.openPrinters,
                    printed = print.result,
                )
            } else {
                state
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            SaleReceiptUiState.Loading,
        )

    fun onAction(action: SaleReceiptAction) {
        when (action) {
            is SaleReceiptAction.Share -> sharing.update {
                // Un archivo a la vez: un segundo toque no crea otro.
                if (it.working != null) it else Sharing(working = action.kind)
            }

            is SaleReceiptAction.ShareFinished -> sharing.update {
                Sharing(failed = it.working.takeIf { _ -> !action.shared })
            }

            SaleReceiptAction.Retry -> attempts.update { it + 1 }

            // Los resuelve la ruta: navegan o abren el servicio del sistema.
            SaleReceiptAction.Back,
            SaleReceiptAction.EditTemplate,
            SaleReceiptAction.PrintWithSystem,
            -> Unit

            SaleReceiptAction.Print -> {
                val ready = uiState.value as? SaleReceiptUiState.Ready ?: return
                printing.update {
                    when {
                        it.working || it.asking -> it
                        ready.printer == null -> it.copy(openPrinters = true)
                        else -> it.copy(asking = true, result = null)
                    }
                }
            }

            is SaleReceiptAction.PrintPermission -> sendToPrinter(action.granted)

            SaleReceiptAction.PrintersOpened -> printing.update { it.copy(openPrinters = false) }

            SaleReceiptAction.PrintedShown -> printing.update { it.copy(result = null) }
        }
    }

    private fun sendToPrinter(granted: Boolean) {
        if (!printing.value.asking) return
        val ready = uiState.value as? SaleReceiptUiState.Ready
        val printer = ready?.printer
        if (!granted || ready == null || printer == null) {
            val failed = PrintResult.Failed(PrintFailure.PERMISSION_MISSING)
            printing.update { Printing(result = failed.takeIf { _ -> !granted }) }
            return
        }
        printing.update { Printing(working = true) }
        viewModelScope.launch {
            val result = printReceipt(printer, ready)
            printing.update { Printing(result = result) }
        }
    }

    private data class Sharing(val working: ReceiptShare? = null, val failed: ReceiptShare? = null)

    private data class Printing(
        val working: Boolean = false,
        val asking: Boolean = false,
        val openPrinters: Boolean = false,
        val result: PrintResult? = null,
    )

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
