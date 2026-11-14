package io.github.yesidrobayo.shopledger.ui.imports

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportColumnMatcher
import io.github.yesidrobayo.shopledger.domain.usecase.imports.ApplyImportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.imports.ImportWrites
import io.github.yesidrobayo.shopledger.domain.usecase.imports.PrepareImportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.format.CsvFormatFactory
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.localization.ImportHeaderNaming
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModelWithState
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * `Importar datos` en la navegación. El archivo se elige con el selector del sistema, sin permiso
 * persistente y sin copiarlo, y las filas con error se guardan donde la persona elija con el
 * selector de crear documento. Nada de esta pantalla usa la red.
 */
@Composable
fun ImportRoute(navigator: AppNavigator) {
    val context = LocalContext.current
    val model =
        rememberAppViewModelWithState { dependencies, savedState ->
            val ports = dependencies.ports
            val container = dependencies.container
            val naming = ImportHeaderNaming(context.applicationContext)
            val prepare = PrepareImportUseCase(
                products = ports.products,
                customers = ports.customers,
                suppliers = ports.suppliers,
                categories = ports.categories,
                units = ports.units,
                priceLists = ports.priceLists,
                priceIndex = ports.priceIndex,
                systemRows = ports.systemRows,
                settings = ports.settings,
                seedLabels = naming,
            )
            val apply = ApplyImportUseCase(
                transactions = ports.transactions,
                writes = ImportWrites(
                    products = ports.products,
                    categories = ports.categories,
                    suppliers = ports.suppliers,
                    customers = ports.customers,
                    units = ports.units,
                    priceIndex = ports.priceIndex,
                    systemRows = ports.systemRows,
                    priceHistory = ports.priceHistory,
                    ledger = ports.stockLedger,
                    alerts = ports.lowStockAlerts,
                ),
                calendar = BusinessCalendarSource(ports.settings),
                currencyLock = CurrencyLock(ports.settings),
                clock = container.clock,
            )
            val resolver = context.applicationContext.contentResolver
            val io = container.dispatchers.io
            ImportViewModel(
                currency = ports.settings.observe().map { settings ->
                    val currency = settings.regional.currency
                    ImportCurrencyUi(
                        code = currency.code.code,
                        confirmed = currency.code.isConfirmed,
                        locked = currency.isLocked,
                    )
                },
                preferredSeparator = { ports.settings.get().regional.csvSeparator },
                defaultDecimal = {
                    FormatLocale.of(ports.settings.get().regional.formatLocaleTag)
                        ?.let(CsvFormatFactory::decimalSeparator)
                        ?: DecimalSeparator.POINT
                },
                tools = ImportTools(
                    reader = { source, separator, preferred, onRows ->
                        container.data.importFileReader.read(source, separator, preferred, onRows)
                    },
                    matcher = ImportColumnMatcher(naming),
                    prepare = { sheet, target, columns, decimal ->
                        prepare(sheet, target, columns, decimal)
                    },
                    apply = { preview, policy -> apply(preview, policy) },
                    writeErrors = { destination, content ->
                        writeText(resolver, destination, content, io)
                    },
                ),
                savedState = savedState,
                computation = container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> ImportScreen(ImportUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.ImportData, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            // Solo se lee lo elegido, una vez: no se pide permiso persistente.
            val picker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    viewModel.onAction(ImportAction.FileChosen(uri?.toString()))
                }
            val creator =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(CSV_TYPE),
                ) { uri -> viewModel.onAction(ImportAction.ErrorsDestination(uri?.toString())) }
            val errorsName = stringResource(R.string.import_errors_file_name)
            ImportScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        ImportAction.Close -> if (viewModel.closesDirectly()) navigator.back()

                        ImportAction.Back -> if (viewModel.backLeaves()) navigator.back()

                        ImportAction.ConfirmLeave -> navigator.back()

                        ImportAction.ChooseFile -> picker.launch(PICKER_TYPES)

                        ImportAction.SaveErrors -> creator.launch(errorsName)

                        ImportAction.ConfirmCurrency ->
                            navigator.open(AppNavKey.RegionalSettings())

                        ImportAction.ViewProducts -> navigator.open(AppNavKey.ProductList)

                        is ImportAction.ChooseTarget,
                        is ImportAction.FileChosen,
                        ImportAction.ToggleFormat,
                        is ImportAction.ChangeSeparator,
                        is ImportAction.ChangeDecimal,
                        is ImportAction.OpenColumn,
                        ImportAction.DismissColumn,
                        is ImportAction.ChooseField,
                        ImportAction.Review,
                        is ImportAction.ChoosePolicy,
                        is ImportAction.ErrorsDestination,
                        ImportAction.Apply,
                        ImportAction.ImportAnother,
                        ImportAction.DismissLeave,
                        ImportAction.DismissNotice,
                        -> Unit
                    }
                },
            )
        }
    }
}

/**
 * Escribe [content] en UTF-8 en el documento [destination] que creó el selector. `false` si el
 * proveedor no lo deja abrir. Lo que se escribe son las filas del archivo de la persona, que ella
 * eligió guardar: nada se registra.
 */
private suspend fun writeText(
    resolver: ContentResolver,
    destination: String,
    content: String,
    io: CoroutineContext,
): Boolean = withContext(io) {
    val stream = resolver.openOutputStream(Uri.parse(destination), WRITE_TRUNCATE)
        ?: return@withContext false
    stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    true
}

private const val CSV_TYPE = "text/csv"
private const val WRITE_TRUNCATE = "wt"

/**
 * Los tipos que ofrece el selector: CSV y hoja de cálculo, y texto genérico porque muchos
 * proveedores no saben el tipo de un CSV. El tipo real sale de los primeros bytes al leer.
 */
private val PICKER_TYPES = arrayOf(
    CSV_TYPE,
    "text/comma-separated-values",
    "text/plain",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/octet-stream",
)
