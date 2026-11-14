package io.github.yesidrobayo.shopledger.ui.receipt

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.print.ObservePrinterSetupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.document.ReceiptAssets
import io.github.yesidrobayo.shopledger.ui.document.ReceiptRenderer
import io.github.yesidrobayo.shopledger.ui.document.printPdfWithSystem
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.printer.AskBluetoothPermission
import io.github.yesidrobayo.shopledger.ui.printer.printFailureText
import io.github.yesidrobayo.shopledger.ui.rescue.ExportFolder
import io.github.yesidrobayo.shopledger.ui.rescue.exportFile
import io.github.yesidrobayo.shopledger.ui.rescue.pdfShareIntent
import io.github.yesidrobayo.shopledger.ui.rescue.pngShareIntent
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El comprobante de una venta, abierto desde su detalle.
 *
 * Compartir escribe el PDF o la imagen en `cache/exports/`, la única carpeta que comparte el
 * proveedor de archivos, y abre el selector del sistema con permiso solo de lectura: otra app hace
 * el envío, solo porque la persona lo tocó. Al volver del selector, la carpeta se vacía. El
 * archivo lleva el número de la venta y nada más del negocio.
 */
@Composable
fun SaleReceiptRoute(key: AppNavKey.SaleReceipt, navigator: AppNavigator) {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val palette = AppTheme.palette
    val renderer = remember(context, formats, palette) {
        ReceiptRenderer(context, formats, palette)
    }
    val model =
        rememberAppViewModel(key = "receipt-${key.saleId}") { dependencies ->
            val ports = dependencies.ports
            val printers = dependencies.container.data.printerSetup
            val printing = ThermalReceiptPrinting.bluetooth(
                dependencies.container.data.printers,
                dependencies.container.dispatchers.default,
            )
            val observe = ObserveSaleDetailUseCase(
                ports.sales,
                ports.salePayments,
                ports.products,
                ports.saleReturns,
            )
            SaleReceiptViewModel(
                saleId = key.saleId,
                observeDetail = { observe(it) },
                observeSource = {
                    combine(
                        ports.settings.observe(),
                        BusinessCalendarSource(ports.settings).observe(),
                    ) { settings, calendar ->
                        SaleReceiptSource(
                            template = ReceiptTemplateSource(
                                settings.receipt,
                                settings.profile.businessName,
                            ),
                            zone = (calendar as? BusinessCalendarResult.Ready)?.calendar?.zone,
                        )
                    }
                },
                observeUnits = { ports.units.observeAll() },
                customerName = { id ->
                    ports.transactions.read { ports.customers.findById(id) }?.contact?.name
                },
                loadImage = { id -> ports.transactions.read { ports.mediaAssets.findImage(id) } },
                computation = dependencies.container.dispatchers.default,
                observePrinters = ObservePrinterSetupUseCase(printers)::invoke,
                printReceipt = { printer, ready ->
                    val assets = ReceiptAssets(logo = null, units = ready.units, zone = ready.zone)
                    printing.printReceipt(printer, renderer, ready.document, assets)
                },
            )
        }
    when (model) {
        ScreenModel.Opening -> SaleReceiptScreen(SaleReceiptUiState.Loading, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = uiState as? SaleReceiptUiState.Ready
            ShareReceipt(ready) { shared ->
                viewModel.onAction(SaleReceiptAction.ShareFinished(shared))
            }
            AskBluetoothPermission(ready?.askingPermission == true) {
                viewModel.onAction(SaleReceiptAction.PrintPermission(it))
            }
            LaunchedEffect(ready?.openPrinters) {
                if (ready?.openPrinters != true) return@LaunchedEffect
                viewModel.onAction(SaleReceiptAction.PrintersOpened)
                navigator.open(AppNavKey.Printers)
            }
            ShowPrinted(ready) { viewModel.onAction(SaleReceiptAction.PrintedShown) }
            val activity = LocalActivity.current
            val scope = rememberCoroutineScope()
            val messages = LocalAppMessages.current
            val printFailed = stringResource(R.string.printer_error_print)
            val printTitle = stringResource(R.string.receipt_doc_title)
            SaleReceiptScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        SaleReceiptAction.Back -> navigator.back()

                        SaleReceiptAction.EditTemplate ->
                            navigator.open(AppNavKey.ReceiptTemplate)

                        SaleReceiptAction.PrintWithSystem -> ready?.let { shown ->
                            scope.launch {
                                val assets = withContext(Dispatchers.IO) {
                                    ReceiptAssets(
                                        logo = shown.logo?.let { decodeStored(context, it) },
                                        units = shown.units,
                                        zone = shown.zone,
                                    )
                                }
                                val opened = printPdfWithSystem(
                                    context,
                                    activity,
                                    renderer,
                                    formats,
                                    shown.document,
                                    printTitle,
                                    assets,
                                )
                                if (!opened) {
                                    messages.show(
                                        AppSnackbarVisuals(printFailed, SnackbarVariant.ERROR),
                                    )
                                }
                            }
                        }

                        is SaleReceiptAction.Share,
                        is SaleReceiptAction.ShareFinished,
                        SaleReceiptAction.Retry,
                        SaleReceiptAction.Print,
                        is SaleReceiptAction.PrintPermission,
                        SaleReceiptAction.PrintersOpened,
                        SaleReceiptAction.PrintedShown,
                        -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/**
 * Cuando [ready] pide compartir, escribe el archivo con el mismo motor de la vista previa y abre
 * el selector. [onFinished] dice si llegó a abrirse.
 */
@Composable
private fun ShareReceipt(ready: SaleReceiptUiState.Ready?, onFinished: (Boolean) -> Unit) {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val palette = AppTheme.palette
    val scope = rememberCoroutineScope()
    val baseName = ready?.let {
        stringResource(R.string.receipt_file_name, it.document.number.toString())
    }
    val chooser =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            scope.launch { withContext(Dispatchers.IO) { ExportFolder.clear(context) } }
        }
    val kind = ready?.sharing
    LaunchedEffect(kind) {
        if (ready == null || kind == null || baseName == null) return@LaunchedEffect
        val renderer = ReceiptRenderer(context, formats, palette)
        val uri = withContext(Dispatchers.IO) {
            val assets = ReceiptAssets(
                logo = ready.logo?.let { decodeStored(context, it) },
                units = ready.units,
                zone = ready.zone,
            )
            when (kind) {
                ReceiptShare.PDF -> exportFile(context, baseName + PDF_EXTENSION) { target ->
                    renderer.writePdf(ready.document, assets, target)
                }

                ReceiptShare.IMAGE -> exportFile(context, baseName + PNG_EXTENSION) { target ->
                    writePng(renderer.renderImage(ready.document, assets), target)
                }
            }
        }
        val shared = uri != null && try {
            val intent = when (kind) {
                ReceiptShare.PDF -> pdfShareIntent(uri)
                ReceiptShare.IMAGE -> pngShareIntent(uri)
            }
            chooser.launch(intent)
            true
        } catch (_: ActivityNotFoundException) {
            withContext(Dispatchers.IO) { ExportFolder.clear(context) }
            false
        }
        onFinished(shared)
    }
}

/** Cómo terminó la impresión, en un aviso: enviada, o por qué no. La venta nunca cambia. */
@Composable
private fun ShowPrinted(ready: SaleReceiptUiState.Ready?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    val printed = ready?.printed
    val name = ready?.printer?.name ?: stringResource(R.string.printer_section_bluetooth)
    LaunchedEffect(printed) {
        if (printed == null) return@LaunchedEffect
        val visuals = when (printed) {
            is PrintResult.Sent -> AppSnackbarVisuals(
                resources.getString(R.string.printer_sent, name),
                SnackbarVariant.SUCCESS,
            )

            is PrintResult.Failed -> AppSnackbarVisuals(
                printFailureText(resources, printed.reason, name),
                SnackbarVariant.ERROR,
            )
        }
        messages.show(visuals)
        onShown()
    }
}

/** Escribe [bitmap] como PNG en [target], hasta el disco. Devuelve `false` si no quedó entero. */
private fun writePng(bitmap: Bitmap, target: File): Boolean = try {
    FileOutputStream(target).use { out ->
        val written = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        out.flush()
        out.fd.sync()
        written
    }
} finally {
    bitmap.recycle()
}

/** Para que el selector y la app que recibe sepan qué es. */
private const val PDF_EXTENSION = ".pdf"
private const val PNG_EXTENSION = ".png"

/** PNG no pierde nada; el número solo se pide por la firma de la llamada. */
private const val PNG_QUALITY = 100
