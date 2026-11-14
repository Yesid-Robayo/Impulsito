package io.github.yesidrobayo.shopledger.ui.label

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.di.PortsLabelCatalog
import io.github.yesidrobayo.shopledger.di.ThermalLabelPrinting
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelItem
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.LabelPreview
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.document.FilePrintAdapter
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.rescue.ExportFolder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `Etiquetas imprimibles` en la navegación.
 *
 * - `BLUETOOTH_CONNECT` se pide solo cuando la persona toca `Imprimir` o `Configurar impresora`,
 *   desde Android 12. Antes basta `BLUETOOTH`, que se concede al instalar. Si lo niega, queda la
 *   hoja PDF.
 * - `Crear PDF` escribe la hoja en `cache/exports/` y abre el servicio de impresión del sistema,
 * que también la guarda como PDF. Al terminar, la carpeta se vacía.
 */
@Composable
fun LabelsRoute(key: AppNavKey.Labels, navigator: AppNavigator) {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val renderer = remember(context, formats) { LabelRenderer(context, formats) }
    val model =
        rememberAppViewModel { dependencies ->
            val container = dependencies.container
            LabelsViewModel(
                catalog = PortsLabelCatalog(dependencies.ports, container.clock),
                printing = ThermalLabelPrinting.bluetooth(
                    container.data.printers,
                    renderer,
                    container.dispatchers.default,
                ),
                now = { container.clock.now() },
                onlyRepriced = key.onlyRepriced,
            )
        }
    val preview: LabelPreviewSlot = { item, template, size, modifier ->
        RenderedPreview(renderer, item, template, size, modifier)
    }
    when (model) {
        ScreenModel.Opening -> LabelsScreen(LabelsUiState(), {}, preview)

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val permission =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    viewModel.onAction(LabelsAction.PermissionResult(it))
                }
            LaunchedEffect(uiState.askingPermission) {
                if (!uiState.askingPermission) return@LaunchedEffect
                val granted = context.canUsePairedPrinters()
                if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    viewModel.onAction(LabelsAction.PermissionResult(granted))
                } else {
                    permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            OpenSheet(uiState.sheetJob, renderer) {
                viewModel.onAction(LabelsAction.SheetOpened(it))
            }
            ShowMessage(uiState.message) { viewModel.onAction(LabelsAction.MessageShown) }
            LabelsScreen(
                uiState = uiState,
                onAction = { action ->
                    if (action == LabelsAction.Back) {
                        navigator.back()
                    } else {
                        viewModel.onAction(action)
                    }
                },
                preview = preview,
            )
        }
    }
}

/** La etiqueta dibujada fuera del hilo principal, con el mismo motor que imprime. */
@Composable
private fun RenderedPreview(
    renderer: LabelRenderer,
    item: LabelItem,
    template: LabelTemplate,
    size: LabelSize,
    modifier: Modifier,
) {
    val image by produceState<ImageBitmap?>(null, renderer, item, template, size) {
        value = withContext(Dispatchers.Default) {
            renderer.render(item, template, size).asImageBitmap()
        }
    }
    LabelPreview(image, stringResource(R.string.a11y_label_preview, item.name), modifier)
}

/**
 * Escribe la hoja de [job] y abre el servicio de impresión del sistema. El nombre del archivo y
 * del trabajo es el título de la pantalla: no dice nada del negocio.
 */
@Composable
private fun OpenSheet(job: SheetJob?, renderer: LabelRenderer, onOpened: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val title = stringResource(R.string.label_title)
    LaunchedEffect(job) {
        if (job == null) return@LaunchedEffect
        val target = File(ExportFolder.of(context), SHEET_FILE)
        val written = withContext(Dispatchers.IO) {
            ExportFolder.clear(context)
            ExportFolder.of(context).mkdirs()
            renderer.writeSheet(job.labels, job.template, target)
        }
        val manager = activity?.getSystemService(PrintManager::class.java)
        val opened = written && manager != null && try {
            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build()
            val adapter = FilePrintAdapter(target, title) { ExportFolder.clear(context) }
            // Por referencia: la regla del registro rechaza una llamada que se lee como la salida
            // estándar, y esta es la del servicio de impresión del sistema.
            val printWith = manager::print
            printWith(title, adapter, attributes)
            true
        } catch (_: IllegalStateException) {
            false
        }
        if (!opened) withContext(Dispatchers.IO) { ExportFolder.clear(context) }
        onOpened(opened)
    }
}

@Composable
private fun ShowMessage(message: LabelMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    val fallback = stringResource(R.string.printer_section_bluetooth)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val visuals = when (message) {
            is LabelMessage.Sent -> AppSnackbarVisuals(
                resources.getString(R.string.printer_sent, message.printer.name ?: fallback),
                SnackbarVariant.SUCCESS,
            )

            LabelMessage.SaveFailed ->
                AppSnackbarVisuals(
                    resources.getString(R.string.state_error_save),
                    SnackbarVariant.ERROR,
                )

            LabelMessage.SheetFailed ->
                AppSnackbarVisuals(
                    resources.getString(R.string.label_error_print),
                    SnackbarVariant.ERROR,
                )
        }
        messages.show(visuals)
        onShown()
    }
}

/**
 * Si se pueden leer las impresoras emparejadas: `BLUETOOTH_CONNECT` desde Android 12 y `BLUETOOTH`
 * antes, que se concede al instalar.
 */
private fun Context.canUsePairedPrinters(): Boolean {
    val permission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

/** El nombre de la hoja en `cache/exports/`: el mismo siempre, sin datos del negocio. */
private const val SHEET_FILE = "labels.pdf"
