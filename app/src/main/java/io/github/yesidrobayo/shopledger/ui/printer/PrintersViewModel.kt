package io.github.yesidrobayo.shopledger.ui.printer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrintersResult
import io.github.yesidrobayo.shopledger.domain.model.print.PaperWidth
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.model.print.PrinterSetup
import io.github.yesidrobayo.shopledger.domain.model.print.SavedPrinter
import io.github.yesidrobayo.shopledger.domain.usecase.print.PrinterSetupChange
import io.github.yesidrobayo.shopledger.domain.usecase.print.UpdatePrinterSetupResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que muestra la pantalla de impresoras. */
data class PrintersUiState(
    val setup: PrinterSetup = PrinterSetup.EMPTY,
    val loading: Boolean = true,
    val sheet: PrinterSheet? = null,
    val blocked: PrintFailure? = null,
    val askingPermission: Boolean = false,
    val printing: String? = null,
    val message: PrinterMessage? = null,
)

/** La hoja abierta para agregar una impresora. */
sealed interface PrinterSheet {
    /** Las emparejadas en el teléfono, para elegir una. Puede estar vacía. */
    data class Paired(val printers: List<PairedPrinter>) : PrinterSheet

    /** El ancho del papel de [printer], recién elegida. */
    data class Paper(val printer: PairedPrinter) : PrinterSheet
}

/** Un aviso breve, que se muestra una vez. */
sealed interface PrinterMessage {
    /** La prueba salió hacia [printer]. La app no puede saber si salió el papel. */
    data class Sent(val printer: SavedPrinter) : PrinterMessage

    /** La prueba no salió hacia [printer], por [reason]. */
    data class Failed(val printer: SavedPrinter, val reason: PrintFailure) : PrinterMessage

    /** `printers.json` no se pudo escribir: nada cambió. */
    data object SaveFailed : PrinterMessage

    /** Ya hay el máximo de impresoras guardadas. */
    data object Full : PrinterMessage
}

/** Lo que la persona hace en la pantalla de impresoras. */
sealed interface PrintersAction {
    data object Add : PrintersAction

    /** La ruta pidió el permiso de Bluetooth, o ya estaba: [granted] dice cómo quedó. */
    data class PermissionResult(val granted: Boolean) : PrintersAction

    data class ChoosePaired(val printer: PairedPrinter) : PrintersAction

    data class ChoosePaper(val paper: PaperWidth) : PrintersAction

    data object DismissSheet : PrintersAction

    data class Test(val printer: SavedPrinter) : PrintersAction

    data class MakeDefault(val printer: SavedPrinter) : PrintersAction

    data class ChangePaper(val printer: SavedPrinter, val paper: PaperWidth) : PrintersAction

    data class Remove(val printer: SavedPrinter) : PrintersAction

    data object MessageShown : PrintersAction

    data object Back : PrintersAction

    /** Abrir los ajustes de Bluetooth del teléfono, para emparejar o encenderlo. */
    data object OpenBluetoothSettings : PrintersAction

    /** Abrir los ajustes de la app, para conceder el permiso negado. */
    data object OpenAppSettings : PrintersAction
}

/**
 * Las impresoras térmicas guardadas: agregar una de las ya emparejadas con su papel, elegir la de
 * por defecto, cambiar el papel, quitarla e imprimir una prueba.
 *
 * El permiso de Bluetooth se pide solo al tocar `Agregar impresora` o `Imprimir prueba`: la ruta
 * lo pide y avisa con [PrintersAction.PermissionResult]. Negado, la pantalla lo explica y el
 * servicio de impresión del sistema sigue disponible. Un fallo al imprimir nunca toca un dato del
 * negocio.
 *
 * @param observeSetup las impresoras guardadas en `printers.json`.
 * @param update cambia las impresoras guardadas.
 * @param listPaired las emparejadas en el teléfono, con el permiso ya concedido.
 * @param printTest imprime la página de prueba en una impresora guardada.
 */
class PrintersViewModel(
    observeSetup: () -> Flow<PrinterSetup>,
    private val update: suspend (PrinterSetupChange) -> UpdatePrinterSetupResult,
    private val listPaired: () -> PairedPrintersResult,
    private val printTest: suspend (SavedPrinter) -> PrintResult,
) : ViewModel() {
    private val overlay = MutableStateFlow(Overlay())

    val uiState: StateFlow<PrintersUiState> =
        combine(observeSetup(), overlay) { setup, shown ->
            PrintersUiState(
                setup = setup,
                loading = false,
                sheet = shown.sheet,
                blocked = shown.blocked,
                askingPermission = shown.pending != null,
                printing = shown.printing,
                message = shown.message,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            PrintersUiState(),
        )

    fun onAction(action: PrintersAction) {
        when (action) {
            PrintersAction.Add -> overlay.update { it.copy(pending = Pending.Add) }

            is PrintersAction.Test -> overlay.update {
                if (it.printing != null) it else it.copy(pending = Pending.Test(action.printer))
            }

            is PrintersAction.PermissionResult -> afterPermission(action.granted)

            is PrintersAction.ChoosePaired -> overlay.update {
                it.copy(sheet = PrinterSheet.Paper(action.printer))
            }

            is PrintersAction.ChoosePaper -> {
                val chosen = (overlay.value.sheet as? PrinterSheet.Paper)?.printer ?: return
                overlay.update { it.copy(sheet = null) }
                val printer = SavedPrinter(chosen.address, chosen.name, action.paper)
                change(PrinterSetupChange.Add(printer))
            }

            PrintersAction.DismissSheet -> overlay.update { it.copy(sheet = null) }

            is PrintersAction.MakeDefault ->
                change(PrinterSetupChange.MakeDefault(action.printer.address))

            is PrintersAction.ChangePaper ->
                change(PrinterSetupChange.ChangePaper(action.printer.address, action.paper))

            is PrintersAction.Remove -> change(PrinterSetupChange.Remove(action.printer.address))

            PrintersAction.MessageShown -> overlay.update { it.copy(message = null) }

            // Los resuelve la ruta: navegan o abren los ajustes del sistema.
            PrintersAction.Back,
            PrintersAction.OpenBluetoothSettings,
            PrintersAction.OpenAppSettings,
            -> Unit
        }
    }

    private fun afterPermission(granted: Boolean) {
        val pending = overlay.value.pending ?: return
        if (!granted) {
            overlay.update { it.copy(pending = null, blocked = PrintFailure.PERMISSION_MISSING) }
            return
        }
        overlay.update { it.copy(pending = null, blocked = null) }
        when (pending) {
            Pending.Add -> when (val paired = listPaired()) {
                is PairedPrintersResult.Listed -> overlay.update {
                    it.copy(sheet = PrinterSheet.Paired(paired.printers))
                }

                is PairedPrintersResult.Unavailable -> overlay.update {
                    it.copy(blocked = paired.reason)
                }
            }

            is Pending.Test -> test(pending.printer)
        }
    }

    private fun test(printer: SavedPrinter) {
        overlay.update { it.copy(printing = printer.address) }
        viewModelScope.launch {
            val message = when (val result = printTest(printer)) {
                is PrintResult.Sent -> PrinterMessage.Sent(printer)
                is PrintResult.Failed -> PrinterMessage.Failed(printer, result.reason)
            }
            overlay.update { it.copy(printing = null, message = message) }
        }
    }

    private fun change(change: PrinterSetupChange) {
        viewModelScope.launch {
            val message = when (update(change)) {
                is UpdatePrinterSetupResult.Saved -> null
                UpdatePrinterSetupResult.Full -> PrinterMessage.Full
                UpdatePrinterSetupResult.Failed -> PrinterMessage.SaveFailed
            }
            if (message != null) overlay.update { it.copy(message = message) }
        }
    }

    /** Lo que espera el permiso de Bluetooth para seguir. */
    private sealed interface Pending {
        data object Add : Pending

        data class Test(val printer: SavedPrinter) : Pending
    }

    private data class Overlay(
        val sheet: PrinterSheet? = null,
        val blocked: PrintFailure? = null,
        val pending: Pending? = null,
        val printing: String? = null,
        val message: PrinterMessage? = null,
    )

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
