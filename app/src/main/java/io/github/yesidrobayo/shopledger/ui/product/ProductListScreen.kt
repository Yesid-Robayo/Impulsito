package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.ProductStock
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La lista de productos: la raíz de Productos. Busca por nombre, sin mayúsculas ni tildes, o por
 * código, y muestra de cada producto su stock, su precio y su margen.
 *
 * Es pura: recibe el estado y avisa cada acción. Con un catálogo vacío no hay búsqueda, solo la
 * invitación a crear el primer producto. Con muchos productos, la lista lleva encabezados fijos por
 * inicial. En una ventana ancha, la lista queda a la izquierda, a su ancho de lectura.
 *
 * @param floatingAction el botón flotante `Nuevo producto`, encima de la barra inferior.
 */
@Composable
fun ProductListScreen(
    uiState: ProductListUiState,
    onAction: (ProductListAction) -> Unit,
    modifier: Modifier = Modifier,
    floatingAction: @Composable () -> Unit = {},
) {
    val spacing = AppTheme.spacing
    val sizes = AppTheme.sizes
    // Las raíces dejan libre el alto de la barra inferior, más lo que sobresale Vender.
    val bottomClearance = sizes.bottomBar + sizes.sellButtonOverhang
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.nav_products),
                navigation = TopBarNavigation.NONE,
                onNavigate = {},
                navigationLabel = null,
            )
            // Sin detalle a la derecha, la lista queda centrada a su ancho de lectura.
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = sizes.maxContentWidth)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                if (uiState.content != ProductListContent.EmptyCatalog) {
                    SearchField(
                        query = uiState.query,
                        onQueryChange = { onAction(ProductListAction.QueryChange(it)) },
                        placeholder = stringResource(R.string.sale_search_hint),
                        modifier = Modifier.padding(
                            horizontal = spacing.s16,
                            vertical = spacing.s8,
                        ),
                    )
                }
                ProductListBody(
                    content = uiState.content,
                    onAction = onAction,
                    bottomClearance =
                        PaddingValues(bottom = bottomClearance + TonalFabDefaults.listEndPadding),
                )
            }
        }
        // El botón se aparta solo de la barra del sistema y del teclado; aquí, de la barra inferior.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = bottomClearance),
        ) { floatingAction() }
    }
}

@Composable
private fun ProductListBody(
    content: ProductListContent,
    onAction: (ProductListAction) -> Unit,
    bottomClearance: PaddingValues,
) {
    when (content) {
        ProductListContent.Loading -> DelayedSkeleton {
            Column(Modifier.fillMaxWidth()) {
                repeat(SKELETON_ROWS) { ProductRow(data = SKELETON_ROW, loading = true) }
            }
        }

        ProductListContent.EmptyCatalog -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_inventory_2,
                title = stringResource(R.string.empty_catalog_title),
                body = stringResource(R.string.empty_catalog_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.empty_catalog_action),
                    onClick = { onAction(ProductListAction.CreateProduct()) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        is ProductListContent.NoResults -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_search,
                title = stringResource(R.string.empty_search_title, content.query),
                body = stringResource(R.string.empty_search_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.empty_search_action),
                    onClick = { onAction(ProductListAction.CreateProduct(content.query)) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        is ProductListContent.Products -> ProductListRows(content, onAction, bottomClearance)

        ProductListContent.ReadError -> CenteredState {
            EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.state_error_read),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(ProductListAction.Retry) },
                    variant = ActionVariant.TONAL,
                )
            }
        }
    }
}

@Composable
private fun ProductListRows(
    content: ProductListContent.Products,
    onAction: (ProductListAction) -> Unit,
    bottomClearance: PaddingValues,
) {
    // El resultado de otra búsqueda empieza arriba, con un estado nuevo: el anterior anclaría la
    // lista a la fila que se veía, y el encabezado fijo taparía la primera de las nuevas. Un cambio
    // del catálogo con la misma búsqueda conserva la posición, también al volver de otra pantalla.
    val listState =
        rememberSaveable(content.query, saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = bottomClearance) {
        for (section in content.sections) {
            section.initial?.let { initial ->
                stickyHeader(key = "initial-$initial", contentType = HEADER) {
                    InitialHeader(initial)
                }
            }
            items(section.items, key = { it.id }, contentType = { ROW }) { item ->
                ProductRow(
                    data = rowDataOf(item),
                    onClick = { onAction(ProductListAction.OpenProduct(item.id)) },
                )
            }
        }
    }
}

/** El encabezado fijo de un grupo de la lista, con su inicial, sobre el papel. */
@Composable
private fun InitialHeader(initial: String) {
    Text(
        text = initial,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
                .semantics { heading() },
    )
}

/** Un estado que ocupa la pantalla: centrado y desplazable con letra grande. */
@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) { content() }
}

/** La fila del componente, con la unidad ya traducida al idioma de la interfaz. */
@Composable
private fun rowDataOf(item: ProductListItem): ProductRowData {
    val unit = unitLabelOf(item.unit)
    val stock =
        when (val stock = item.stock) {
            is ProductListStock.Normal -> ProductStock.Normal(stock.onHand, unit)
            is ProductListStock.Low -> ProductStock.Low(stock.onHand, unit)
            ProductListStock.Out -> ProductStock.Out
            is ProductListStock.Negative -> ProductStock.Negative(stock.onHand, unit)
            ProductListStock.NoStock -> ProductStock.Service
        }
    return ProductRowData(
        name = item.name,
        stock = stock,
        trailing = item.trailing,
        archived = item.archived,
    )
}

/**
 * Cómo se escribe la unidad junto a una cantidad: su símbolo, o su nombre si no tiene. Una unidad
 * que creó la persona no tiene plural conocido. Sin unidad, la de conteo.
 */
@Composable
private fun unitLabelOf(unit: MeasureUnit?): UnitLabel {
    val resources = LocalResources.current
    if (unit == null) return UnitLabel(stringResource(R.string.seed_units_unit), false)
    val symbol =
        SeedText.unitSymbol(resources, unit.systemKey, unit.symbol)
            ?: SeedText.displayName(resources, SeedTable.UNITS, unit.systemKey, unit.name)
    return UnitLabel(symbol, unit.allowsFraction, custom = unit.systemKey == null)
}

private const val SKELETON_ROWS = 8
private const val HEADER = "header"
private const val ROW = "row"

// La fila de los esqueletos no se lee: solo da la forma.
private val SKELETON_ROW =
    ProductRowData(
        name = "",
        stock = ProductStock.Service,
        trailing = ProductRowTrailing.Catalog(null, Margin.NoData, hasCost = true),
    )

// ---------------------------------------------------------------- vistas previas

private fun previewUnit(): MeasureUnit = MeasureUnit(
    id = 1L,
    systemKey = "UNIT",
    name = null,
    symbol = null,
    kind = UnitKind.COUNT,
    allowsFraction = false,
    archivedAt = null,
)

private fun previewItems(): List<ProductListItem> {
    val s = PreviewSamples
    val unit = previewUnit()
    fun item(
        id: Long,
        name: String,
        stock: ProductListStock,
        trailing: ProductRowTrailing.Catalog,
    ) = ProductListItem(id, name, stock, unit, trailing, archived = false)
    return listOf(
        item(
            1L,
            s.SECOND_NAME,
            ProductListStock.Low(s.LOW_STOCK),
            ProductRowTrailing.Catalog(s.SECOND_PRICE, Margin.Defined(BasisPoints(2_400L)), true),
        ),
        item(
            2L,
            s.SHORT_NAME,
            ProductListStock.Normal(s.STOCK),
            ProductRowTrailing.Catalog(s.PRICE, Margin.Defined(BasisPoints(-450L)), true),
        ),
        item(
            3L,
            s.LONG_NAME,
            ProductListStock.Negative(s.NEGATIVE_STOCK),
            ProductRowTrailing.Catalog(s.LONG_AMOUNT, Margin.Defined(BasisPoints(3_120L)), true),
        ),
        item(
            4L,
            s.THIRD_NAME,
            ProductListStock.Out,
            ProductRowTrailing.Catalog(null, Margin.NoData, hasCost = false),
        ),
        item(
            5L,
            s.TOOL_NAME,
            ProductListStock.NoStock,
            ProductRowTrailing.Catalog(Money(8_000L), Margin.Defined(BasisPoints(10_000L)), false),
        ),
    )
}

@ThemePreviews
@Composable
private fun ProductListPreview() {
    PreviewTheme {
        ProductListScreen(
            uiState =
                ProductListUiState(
                    content =
                        ProductListContent.Products(
                            listOf(ProductListSection(null, previewItems())),
                        ),
                ),
            onAction = {},
            floatingAction = { TonalFab(stringResource(R.string.product_new_title), {}) },
        )
    }
}

@ThemePreviews
@Composable
private fun ProductListHeadersPreview() {
    PreviewTheme(discreet = true) {
        val items = previewItems()
        ProductListScreen(
            uiState =
                ProductListUiState(
                    content =
                        ProductListContent.Products(
                            listOf(
                                ProductListSection("A", items.take(2)),
                                ProductListSection("C", items.drop(2)),
                            ),
                        ),
                ),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun ProductListEmptyPreview() {
    PreviewTheme {
        ProductListScreen(ProductListUiState(content = ProductListContent.EmptyCatalog), {})
    }
}

@ThemePreviews
@Composable
private fun ProductListNoResultsPreview() {
    PreviewTheme {
        val query = PreviewSamples.SEARCH_QUERY
        ProductListScreen(
            ProductListUiState(query = query, content = ProductListContent.NoResults(query)),
            {},
        )
    }
}

@Preview(name = "Error")
@Composable
private fun ProductListErrorPreview() {
    PreviewTheme {
        ProductListScreen(ProductListUiState(content = ProductListContent.ReadError), {})
    }
}

@Preview(name = "Cargando")
@Composable
private fun ProductListLoadingPreview() {
    PreviewTheme {
        ProductListScreen(ProductListUiState(), {})
    }
}
