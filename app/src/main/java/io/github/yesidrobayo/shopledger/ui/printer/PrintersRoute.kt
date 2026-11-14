package io.github.yesidrobayo.shopledger.ui.printer

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.di.ThermalReceiptPrinting
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.usecase.print.ObservePrinterSetupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.print.UpdatePrinterSetupUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.document.ReceiptRenderer
import io.github.yesidrobayo.shopledger.ui.document.printPdfWithSystem
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.receipt.rememberSampleReceipt
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * Las impresoras, abiertas desde los ajustes o al imprimir sin impresora por defecto.
 *
 * - `BLUETOOTH_CONNECT` se pide solo al tocar `Agregar impresora` o `Imprimir prueba`, desde
 *   Android 12. Antes basta `BLUETOOTH`, que se concede al instalar.
 * - La prueba se dibuja con el mismo motor de los comprobantes, a su ancho de papel.
 * - Emparejar o encender Bluetooth se hace en los ajustes del teléfono: la app nunca busca.
 */
@Composable
fun PrintersRoute(key: AppNavKey.Printers, navigator: AppNavigator) {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val palette = AppTheme.palette
    val renderer = remember(context, formats, palette) {
        ReceiptRenderer(context, formats, palette)
    }
    val testTitle = stringResource(R.string.printer_test_page_title)
    val testBody = stringResource(R.string.printer_test_page_body)
    val model =
        rememberAppViewModel { dependencies ->
            val container = dependencies.container
            val store = container.data.printerSetup
            val printing = ThermalReceiptPrinting.bluetooth(
                container.data.printers,
                container.dispatchers.default,
            )
            PrintersViewModel(
                observeSetup = ObservePrinterSetupUseCase(store)::invoke,
                update = UpdatePrinterSetupUseCase(store)::invoke,
                listPaired = container.data.printers::paired,
                printTest = { printer ->
                    printing.printTest(printer, renderer, testTitle, testBody)
                },
            )
        }
    when (model) {
        ScreenModel.Opening -> PrintersScreen(PrintersUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            AskBluetoothPermission(uiState.askingPermission) {
                viewModel.onAction(PrintersAction.PermissionResult(it))
            }
            ShowMessage(uiState.message) { viewModel.onAction(PrintersAction.MessageShown) }
            val systemTest = rememberSystemTest(renderer, testTitle)
            PrintersScreen(
                uiState = uiState,
                systemSection = { systemPrintSection(systemTest) },
                onAction = { action ->
                    when (action) {
                        PrintersAction.Back -> navigator.back()

                        PrintersAction.OpenBluetoothSettings -> context.openBluetoothSettings()

                        PrintersAction.OpenAppSettings -> context.openPrinterAppSettings()

                        PrintersAction.Add,
                        is PrintersAction.PermissionResult,
                        is PrintersAction.ChoosePaired,
                        is PrintersAction.ChoosePaper,
                        PrintersAction.DismissSheet,
                        is PrintersAction.Test,
                        is PrintersAction.MakeDefault,
                        is PrintersAction.ChangePaper,
                        is PrintersAction.Remove,
                        PrintersAction.MessageShown,
                        -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/**
 * La prueba del servicio de impresión del sistema: un comprobante de muestra, escrito como PDF en
 * `cache/exports/` y entregado al diálogo de Android, que elige la impresora de la red local, una
 * por USB o `Guardar como PDF`. Al terminar, la carpeta se vacía.
 */
@Composable
private fun rememberSystemTest(renderer: ReceiptRenderer, title: String): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val formats = LocalAppFormats.current
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val failed = stringResource(R.string.printer_error_print)
    val sample = rememberSampleReceipt(null, null, "", "")
    return {
        scope.launch {
            val opened = printPdfWithSystem(context, activity, renderer, formats, sample, title)
            if (!opened) messages.show(AppSnackbarVisuals(failed, SnackbarVariant.ERROR))
        }
    }
}

/** El aviso de la prueba o de un cambio que no se guardó, una sola vez. */
@Composable
private fun ShowMessage(message: PrinterMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    val fallback = stringResource(R.string.printer_section_bluetooth)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val visuals = when (message) {
            is PrinterMessage.Sent -> AppSnackbarVisuals(
                resources.getString(R.string.printer_sent, message.printer.name ?: fallback),
                SnackbarVariant.SUCCESS,
            )

            is PrinterMessage.Failed -> AppSnackbarVisuals(
                printFailureText(resources, message.reason, message.printer.name ?: fallback),
                SnackbarVariant.ERROR,
            )

            PrinterMessage.SaveFailed, PrinterMessage.Full -> AppSnackbarVisuals(
                resources.getString(R.string.state_error_save),
                SnackbarVariant.ERROR,
            )
        }
        messages.show(visuals)
        onShown()
    }
}

/** Lo que se dice cuando una impresión no salió: nunca toca lo que ya se guardó. */
internal fun printFailureText(
    resources: android.content.res.Resources,
    reason: PrintFailure,
    printer: String,
): String = when (reason) {
    PrintFailure.NO_ADAPTER -> resources.getString(R.string.printer_error_no_bluetooth)
    PrintFailure.PERMISSION_MISSING -> resources.getString(R.string.printer_error_permission)
    PrintFailure.BLUETOOTH_OFF -> resources.getString(R.string.printer_error_bluetooth_off)
    PrintFailure.NOT_BONDED -> resources.getString(R.string.printer_error_unpaired, printer)
    PrintFailure.CONNECT_FAILED -> resources.getString(R.string.printer_error_connect, printer)
    PrintFailure.WRITE_FAILED -> resources.getString(R.string.printer_error_print)
}
