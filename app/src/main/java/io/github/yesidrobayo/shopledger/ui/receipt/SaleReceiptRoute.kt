package io.github.yesidrobayo.shopledger.ui.receipt

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.document.ReceiptAssets
import io.github.yesidrobayo.shopledger.ui.document.ReceiptRenderer
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
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
    val model =
        rememberAppViewModel(key = "receipt-${key.saleId}") { dependencies ->
            val ports = dependencies.ports
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
            )
        }
    when (model) {
        ScreenModel.Opening -> SaleReceiptScreen(SaleReceiptUiState.Loading, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ShareReceipt(uiState as? SaleReceiptUiState.Ready) { shared ->
                viewModel.onAction(SaleReceiptAction.ShareFinished(shared))
            }
            SaleReceiptScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        SaleReceiptAction.Back -> navigator.back()

                        SaleReceiptAction.EditTemplate ->
                            navigator.open(AppNavKey.ReceiptTemplate)

                        is SaleReceiptAction.Share,
                        is SaleReceiptAction.ShareFinished,
                        SaleReceiptAction.Retry,
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
