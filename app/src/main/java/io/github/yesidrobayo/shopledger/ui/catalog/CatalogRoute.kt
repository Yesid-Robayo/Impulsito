package io.github.yesidrobayo.shopledger.ui.catalog

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.CatalogDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenDependencies
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.document.CatalogAssets
import io.github.yesidrobayo.shopledger.ui.document.CatalogRenderer
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.receipt.decodeStored
import io.github.yesidrobayo.shopledger.ui.rescue.ExportFolder
import io.github.yesidrobayo.shopledger.ui.rescue.exportFile
import io.github.yesidrobayo.shopledger.ui.rescue.pdfShareIntent
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El catálogo compartible, desde el centro `Negocio`.
 *
 * Todo se genera en el teléfono, sin red. `Compartir PDF` lo escribe en `cache/exports/` y abre el
 * selector del sistema con permiso solo de lectura; al volver, la carpeta se vacía. `Guardar PDF`
 * lo escribe donde la persona elija con el selector de documentos. El archivo se llama como el
 * título por defecto y no dice nada más del negocio.
 */
@Composable
fun CatalogRoute(key: AppNavKey.ShareableCatalog, navigator: AppNavigator) {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val palette = AppTheme.palette
    val renderer = remember(context, formats, palette) {
        CatalogRenderer(context, formats, palette)
    }
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            val calendar = BusinessCalendarSource(ports.settings).observe()
                .filterIsInstance<BusinessCalendarResult.Ready>()
            val prices = ports.productPrices.observeAll().map { byList ->
                // Por lista y producto en la base; por producto y lista para el precio de venta.
                buildMap<Long, MutableMap<Long, Money>> {
                    byList.forEach { (listId, byProduct) ->
                        byProduct.forEach { (productId, price) ->
                            getOrPut(productId) { mutableMapOf() }[listId] = price
                        }
                    }
                }
            }
            val listsAndPrices = combine(ports.priceLists.observeActive(), prices) { lists, own ->
                lists to own
            }
            CatalogViewModel(
                observeSources = {
                    combine(
                        ports.products.observe(ArchiveFilter.ACTIVE),
                        ports.categories.observeAll(),
                        listsAndPrices,
                        ports.settings.observe(),
                        calendar,
                    ) { products, categories, (lists, own), settings, ready ->
                        CatalogSources(
                            products = products,
                            categories = categories,
                            lists = lists,
                            ownPrices = own,
                            header = ReceiptHeader(
                                logoMediaId = settings.receipt.logoMediaId,
                                businessName = settings.profile.businessName
                                    ?.trim()?.takeIf { it.isNotEmpty() },
                                businessDetails = null,
                            ),
                            today = ready.calendar.businessDateOf(
                                dependencies.container.clock.now(),
                            ),
                            currencyConfirmed = settings.regional.currency.code.isConfirmed,
                        )
                    }
                },
                computation = dependencies.container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening -> CatalogScreen(CatalogUiState.Loading, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = uiState as? CatalogUiState.Ready
            val assets = rememberCatalogAssets(ready?.document)
            val scale = LocalDensity.current.density / 2
            val preview by produceState<Pair<ImageBitmap, Int>?>(null, ready?.document, assets) {
                val document = ready?.document ?: return@produceState
                val loaded = assets ?: return@produceState
                // Al escribir el título, solo se dibuja lo último.
                delay(PREVIEW_DEBOUNCE_MILLIS)
                value = withContext(Dispatchers.Default) {
                    renderer.renderFirstPage(document, loaded, scale).asImageBitmap() to
                        renderer.pageCount(document, loaded)
                }
            }
            CreateCatalog(ready, assets, renderer) {
                viewModel.onAction(CatalogAction.Created(it))
            }
            CatalogScreen(
                uiState = uiState,
                preview = preview?.first,
                pages = preview?.second,
                listName = { list ->
                    list.name ?: stringResource(R.string.seed_price_lists_default)
                },
                onAction = { action ->
                    when (action) {
                        CatalogAction.Back -> navigator.back()

                        CatalogAction.OpenProducts -> navigator.open(AppNavKey.ProductList)

                        CatalogAction.ConfirmCurrency ->
                            navigator.open(AppNavKey.RegionalSettings(firstRun = false))

                        is CatalogAction.Filter,
                        is CatalogAction.PriceListChosen,
                        is CatalogAction.ShowPhotos,
                        is CatalogAction.GroupByCategory,
                        is CatalogAction.IncludeOutOfStock,
                        is CatalogAction.TitleChanged,
                        is CatalogAction.Create,
                        is CatalogAction.Created,
                        CatalogAction.Retry,
                        -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/**
 * El logo y las fotos que pide [document], leídos de sus archivos fuera del hilo principal. Una
 * foto que falta, porque todavía no llega del respaldo, no se dibuja.
 */
@Composable
private fun rememberCatalogAssets(document: CatalogDocument?): CatalogAssets? {
    val context = LocalContext.current
    val model = rememberAppViewModel { dependencies -> MediaReader(dependencies) }
    val reader = (model as? ScreenModel.Ready)?.viewModel
    val assets by produceState<CatalogAssets?>(null, document, reader) {
        if (document == null || reader == null) return@produceState
        val ids = document.sections.flatMap { s -> s.items.mapNotNull { it.photoMediaId } } +
            listOfNotNull(document.header.logoMediaId)
        value = withContext(Dispatchers.IO) {
            val images = ids.distinct().mapNotNull { id ->
                reader.find(id)?.let { image -> decodeStored(context, image)?.let { id to it } }
            }.toMap()
            CatalogAssets(
                logo = document.header.logoMediaId?.let { images[it] },
                photos = images,
                units = reader.units(),
            )
        }
    }
    return assets
}

/**
 * Cuando [ready] pide crear el archivo, lo escribe con el mismo motor de la vista previa y lo
 * comparte o lo guarda. [onDone] dice si salió.
 */
@Composable
private fun CreateCatalog(
    ready: CatalogUiState.Ready?,
    assets: CatalogAssets?,
    renderer: CatalogRenderer,
    onDone: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileName = stringResource(R.string.catalog_default_title) + PDF_EXTENSION
    val chooser =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            scope.launch { withContext(Dispatchers.IO) { ExportFolder.clear(context) } }
        }
    val saver =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(PDF_TYPE),
        ) { uri ->
            if (uri == null) {
                onDone(true)
                return@rememberLauncherForActivityResult
            }
            val document = ready?.document
            val loaded = assets
            scope.launch {
                val saved = document != null && loaded != null &&
                    withContext(Dispatchers.IO) {
                        saveTo(context, uri, renderer, document, loaded)
                    }
                onDone(saved)
            }
        }
    val output = ready?.sharing
    LaunchedEffect(output) {
        val document = ready?.document
        if (output == null || document == null || assets == null) return@LaunchedEffect
        when (output) {
            CatalogOutput.SAVE -> saver.launch(fileName)

            CatalogOutput.SHARE -> {
                val uri = withContext(Dispatchers.IO) {
                    exportFile(context, fileName) { renderer.writePdf(document, assets, it) }
                }
                val shared = uri != null && try {
                    chooser.launch(pdfShareIntent(uri))
                    true
                } catch (_: ActivityNotFoundException) {
                    withContext(Dispatchers.IO) { ExportFolder.clear(context) }
                    false
                }
                onDone(shared)
            }
        }
    }
}

/** Escribe el catálogo en un temporal y lo copia a [uri], el lugar que eligió la persona. */
private fun saveTo(
    context: Context,
    uri: Uri,
    renderer: CatalogRenderer,
    document: CatalogDocument,
    assets: CatalogAssets,
): Boolean {
    val temporary = File(ExportFolder.of(context).apply { mkdirs() }, SAVE_TEMPORARY)
    return try {
        renderer.writePdf(document, assets, temporary) &&
            (
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    temporary.inputStream().use { it.copyTo(out) }
                    true
                } ?: false
                )
    } catch (_: IOException) {
        false
    } finally {
        temporary.delete()
    }
}

/** Lee imágenes y unidades para el catálogo, con la base abierta. */
private class MediaReader(private val dependencies: ScreenDependencies) : ViewModel() {
    suspend fun find(id: Long): StoredImage? =
        dependencies.ports.transactions.read { dependencies.ports.mediaAssets.findImage(id) }

    suspend fun units() = dependencies.ports.units.observeAll().first().associateBy { it.id }
}

private const val PDF_EXTENSION = ".pdf"
private const val PDF_TYPE = "application/pdf"
private const val SAVE_TEMPORARY = "catalog-save.pdf"
private const val PREVIEW_DEBOUNCE_MILLIS = 200L
