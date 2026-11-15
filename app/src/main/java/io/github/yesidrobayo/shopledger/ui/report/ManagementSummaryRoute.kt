package io.github.yesidrobayo.shopledger.ui.report

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.rule.report.ManagementSummaryRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.report.CreateManagementSummaryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveManagementSummaryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.SummaryTarget
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ObserveTaxEstimateUseCase
import io.github.yesidrobayo.shopledger.format.CsvFormatFactory
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.localization.SummaryExportNaming
import io.github.yesidrobayo.shopledger.lock.rememberSensitiveCheck
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.document.SummaryDocument
import io.github.yesidrobayo.shopledger.ui.document.SummaryRenderer
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.rescue.ExportFolder
import io.github.yesidrobayo.shopledger.ui.rescue.exportFile
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El resumen de gestión, desde el centro `Negocio` y el menú de `Números`.
 *
 * Todo se genera en el teléfono, sin red. `Generar resumen` pide antes la verificación del
 * teléfono si el bloqueo está encendido, porque el archivo lleva costos y ganancias sin cifrar.
 * Dibuja el PDF en una carpeta temporal, que borra siempre, y deja el `.zip` en `cache/exports/`,
 * la única carpeta que comparte el proveedor de archivos, y solo para leer. `Compartir` abre el
 * selector del sistema; `Guardar en una carpeta`, el selector de documentos. Al volver de
 * cualquiera de los dos, o al cerrar la hoja, la carpeta se vacía.
 */
@Composable
fun ManagementSummaryRoute(key: AppNavKey, navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val observe =
                ObserveManagementSummaryUseCase(
                    ports.periodReports,
                    ObserveTaxEstimateUseCase(ports.taxSettings, ports.taxReports),
                    ports.periodBreakdowns,
                    ports.managementSummaries,
                )
            ManagementSummaryViewModel(
                calendar = BusinessCalendarSource(ports.settings).observe(),
                now = dependencies.container.clock::now,
                observe = { period, calendar -> observe(period, calendar) },
            )
        }
    val writerModel = rememberAppViewModel { dependencies -> SummaryWriter(dependencies) }
    when (model) {
        ScreenModel.Opening -> ManagementSummaryScreen(ManagementSummaryUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val writer = (writerModel as? ScreenModel.Ready)?.viewModel
            val scope = rememberCoroutineScope()
            val check = rememberSensitiveCheck()
            val confirmTitle = stringResource(R.string.report_generate_action)
            val files = rememberSummaryFiles(viewModel, writer)
            ManagementSummaryScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        SummaryAction.Back -> navigator.back()

                        SummaryAction.ChooseZone ->
                            navigator.open(AppNavKey.RegionalSettings())

                        // El archivo lleva costos y ganancias: con bloqueo, se confirma antes.
                        SummaryAction.Generate -> scope.launch {
                            if (check.confirm(confirmTitle)) {
                                viewModel.onAction(SummaryAction.Confirmed)
                            }
                        }

                        SummaryAction.Share -> files.share()

                        SummaryAction.SaveToFolder -> files.save()

                        SummaryAction.DismissResult -> {
                            files.clear()
                            viewModel.onAction(action)
                        }

                        SummaryAction.PreviousPeriod,
                        SummaryAction.NextPeriod,
                        SummaryAction.ChooseRange,
                        SummaryAction.DismissRange,
                        is SummaryAction.ShowRange,
                        is SummaryAction.Include,
                        SummaryAction.Retry,
                        SummaryAction.Confirmed,
                        is SummaryAction.Finished,
                        -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/** Lo que la pantalla hace con el paquete listo: compartirlo, guardarlo o vaciar la carpeta. */
private class SummaryFiles(val share: () -> Unit, val save: () -> Unit, val clear: () -> Unit)

/**
 * Escribe el paquete que pide [viewModel] y da qué hacer con él. Sin [writer], que solo falta
 * mientras la base abre, no escribe nada.
 */
@Composable
private fun rememberSummaryFiles(
    viewModel: ManagementSummaryViewModel,
    writer: SummaryWriter?,
): SummaryFiles {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val palette = AppTheme.palette
    val renderer = remember(context, formats, palette) {
        SummaryRenderer(context, formats, palette)
    }
    val scope = rememberCoroutineScope()
    var written by remember { mutableStateOf<WrittenPackage?>(null) }
    val clear: () -> Unit = {
        written = null
        scope.launch { withContext(Dispatchers.IO) { ExportFolder.clear(context) } }
    }
    val done: () -> Unit = {
        clear()
        viewModel.onAction(SummaryAction.DismissResult)
    }
    val chooser =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            done()
        }
    val saver =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(ZIP_TYPE),
        ) { uri ->
            val source = written?.file
            if (uri == null || source == null) {
                // Sin lugar elegido, el paquete sigue listo para compartirlo.
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val copied = withContext(Dispatchers.IO) { copyPackage(context, source, uri) }
                clear()
                viewModel.onAction(
                    if (copied) {
                        SummaryAction.DismissResult
                    } else {
                        SummaryAction.Finished(SummaryOutcome.Failed)
                    },
                )
            }
        }
    val job by viewModel.job.collectAsStateWithLifecycle()
    LaunchedEffect(job, writer) {
        val current = job ?: return@LaunchedEffect
        val ready = writer ?: return@LaunchedEffect
        val baseName = context.resources.getString(
            R.string.report_file_name,
            ManagementSummaryRules.fileStamp(current.summary.period),
        )
        val (outcome, result) = withContext(Dispatchers.IO) {
            writePackage(context, renderer, ready, current, baseName)
        }
        written = result
        viewModel.onAction(SummaryAction.Finished(outcome))
    }
    return SummaryFiles(
        share = {
            val uri = written?.uri
            if (uri != null) {
                try {
                    chooser.launch(zipShareIntent(uri))
                } catch (_: ActivityNotFoundException) {
                    done()
                }
            }
        },
        save = {
            val name = written?.file?.name
            if (name != null) saver.launch(name)
        },
        clear = clear,
    )
}

/** El paquete escrito: el archivo en `cache/exports/` y su dirección para compartir. */
private class WrittenPackage(val file: File, val uri: Uri)

/**
 * Dibuja el PDF de [job] en la carpeta temporal y escribe el paquete en `cache/exports/`, con el
 * nombre [baseName]. El PDF temporal se borra siempre. Lee y escribe el disco: va fuera del hilo
 * principal.
 */
private suspend fun writePackage(
    context: Context,
    renderer: SummaryRenderer,
    writer: SummaryWriter,
    job: SummaryJob,
    baseName: String,
): Pair<SummaryOutcome, WrittenPackage?> {
    val work = File(context.cacheDir, WORK_FOLDER)
    val pdf = File(work, PDF_TEMPORARY)
    val name = baseName + ZIP_EXTENSION
    var result: CsvExport = CsvExport.Failed
    var target: File? = null
    try {
        if (!work.isDirectory && !work.mkdirs()) return SummaryOutcome.Failed to null
        val document = SummaryDocument(job.summary, writer.businessName(), job.today)
        if (!renderer.writePdf(document, pdf)) return SummaryOutcome.Failed to null
        val uri = exportFile(context, name) { file ->
            target = file
            val packaged = SummaryTarget(file.absolutePath, baseName + PDF_EXTENSION, pdf.path)
            result = writer.write(job, packaged)
            result is CsvExport.Saved
        }
        val file = target
        return when (result) {
            is CsvExport.Saved ->
                if (uri != null && file != null) {
                    SummaryOutcome.Saved(name, file.length()) to WrittenPackage(file, uri)
                } else {
                    SummaryOutcome.Failed to null
                }

            CsvExport.NoSpace -> SummaryOutcome.NoSpace to null

            CsvExport.Failed -> SummaryOutcome.Failed to null
        }
    } finally {
        pdf.delete()
    }
}

/** Copia el paquete a [uri], el lugar que eligió la persona. */
private fun copyPackage(context: Context, source: File, uri: Uri): Boolean = try {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        source.inputStream().use { it.copyTo(out) }
        true
    } ?: false
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

/**
 * El selector del sistema para compartir el paquete de `cache/exports/`, con permiso solo de
 * lectura. Lo lanza un toque de la persona: nunca sale solo.
 */
private fun zipShareIntent(uri: Uri): Intent {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(ZIP_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // El permiso viaja con el contenido del recorte: así llega también a la app que se elija.
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, null)
}

/**
 * Escribe el paquete con la base abierta: el caso de uso, los nombres en el idioma de la app y el
 * nombre del negocio que encabeza el PDF.
 */
private class SummaryWriter(private val dependencies: ScreenDependencies) : ViewModel() {
    private val container = dependencies.container
    private val create =
        CreateManagementSummaryUseCase(
            dependencies.ports.settings,
            container.data.managementSummaryPackage,
        ) { tag ->
            // Una etiqueta que este teléfono no reconoce sale con punto decimal, el de las hojas
            // de cálculo sin coma decimal.
            FormatLocale.of(tag)?.let(CsvFormatFactory::decimalSeparator) ?: DecimalSeparator.POINT
        }
    private val naming = SummaryExportNaming { container.localizedContext.context().resources }

    suspend fun businessName(): String? {
        val ports = dependencies.ports
        val settings = ports.transactions.read { ports.settings.get() }
        return settings.profile.businessName?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun write(job: SummaryJob, target: SummaryTarget): CsvExport = create(
        period = job.summary.period,
        chosen = job.chosen,
        offered = job.summary.contents,
        target = target,
        naming = naming,
        progress = {},
    )
}

private const val ZIP_TYPE = "application/zip"
private const val ZIP_EXTENSION = ".zip"
private const val PDF_EXTENSION = ".pdf"

/** La carpeta temporal del PDF, fuera de la que se comparte. */
private const val WORK_FOLDER = "summary-work"
private const val PDF_TEMPORARY = "summary.pdf"
