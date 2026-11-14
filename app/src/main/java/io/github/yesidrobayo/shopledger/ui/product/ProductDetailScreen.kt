package io.github.yesidrobayo.shopledger.ui.product

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.MovementDocument
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductMovement
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierLastCost
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.stock.ProductLocationStock
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.report.ReplacementProfitRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockAdjustmentRules
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LotRow
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PhotoThumbSize
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.media.ProductPhoto
import io.github.yesidrobayo.shopledger.ui.stock.locationName
import io.github.yesidrobayo.shopledger.ui.stock.reasonLabel
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import io.github.yesidrobayo.shopledger.ui.unit.EditorContainer

/**
 * El detalle de un producto, sin ViewModel: cuánto hay, cuánto deja y qué le ha pasado.
 *
 * Arriba el stock con su mínimo y su estado, y las acciones de comprar y ajustar; debajo, precio,
 * costo promedio y margen, con sus avisos. Los segmentos muestran el libro, del movimiento más
 * reciente al más antiguo, y el historial de precios. La acción fija es `Vender este`. El costo y el
 * margen son cifras del negocio: con el modo discreto se ocultan; el precio, no, porque se atiende
 * con él.
 *
 * Un insumo puro (`INPUT`) no se vende: no muestra precio, margen, ganancia si se repone,
 * avisos de precio, `Cambiar precio` ni el historial de precios; deja el stock, el costo promedio
 * y el costo de reposición. `BOTH` y `SALE` se ven como siempre.
 */
@Composable
fun ProductDetailScreen(
    uiState: ProductDetailUiState,
    onAction: (ProductDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = (uiState.content as? ProductDetailContent.Ready)?.overview
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = ready?.product?.details?.name ?: stringResource(R.string.nav_products),
            navigation = TopBarNavigation.BACK,
            onNavigate = { onAction(ProductDetailAction.Back) },
            navigationLabel = stringResource(R.string.a11y_back),
            titleStyle = MaterialTheme.typography.titleLarge,
        ) {
            if (ready != null) TopBarActions(ready, onAction)
        }
        Box(
            Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth(),
        ) {
            when (val content = uiState.content) {
                ProductDetailContent.Loading -> DelayedSkeleton {
                    Column(
                        Modifier.padding(AppTheme.spacing.s16),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
                    ) {
                        SkeletonBlock(
                            Modifier.fillMaxWidth().heightIn(
                                min =
                                    AppTheme.sizes.listRow * 2,
                            ),
                        )
                        repeat(SKELETON_ROWS) {
                            SkeletonBlock(
                                Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow),
                            )
                        }
                    }
                }

                ProductDetailContent.Missing, ProductDetailContent.ReadError ->
                    EmptyState(
                        icon = R.drawable.ic_error,
                        title = stringResource(R.string.state_error_read),
                    ) {
                        if (content == ProductDetailContent.ReadError) {
                            PrimaryActionButton(
                                text = stringResource(R.string.action_retry),
                                onClick = { onAction(ProductDetailAction.Retry) },
                                variant = ActionVariant.TONAL,
                            )
                        }
                    }

                is ProductDetailContent.Ready ->
                    DetailBody(
                        content.overview,
                        content.pricing,
                        uiState.tab,
                        onAction,
                        uiState.locationStock,
                        uiState.lots,
                    )
            }
        }
        // Un insumo no se vende: sin `Vender este`.
        if (ready != null && !ready.product.isArchived && ready.product.details.usage.isSold) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .keepsMessagesAbove()
                    .navigationBarsPadding()
                    .padding(AppTheme.spacing.s16),
                contentAlignment = Alignment.Center,
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.product_sell_this),
                    onClick = { onAction(ProductDetailAction.Sell) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.widthIn(
                        max = AppTheme.sizes.maxContentWidth,
                    ).fillMaxWidth(),
                )
            }
        }
    }
    uiState.costSheet?.let { CostSheetView(it, onAction) }
    val pricing = (uiState.content as? ProductDetailContent.Ready)?.pricing
    if (pricing != null) uiState.priceSheet?.let { PriceSheetView(it, pricing, onAction) }
    uiState.dialog?.let { ProductConfirm(it, onAction) }
}

/** `Editar` siempre; en el menú, corregir el costo si lleva libro, y archivar o eliminar. */
@Composable
private fun TopBarActions(overview: ProductOverview, onAction: (ProductDetailAction) -> Unit) {
    TopBarAction(R.drawable.ic_edit, stringResource(R.string.action_edit)) {
        onAction(ProductDetailAction.Edit)
    }
    val product = overview.product
    if (product.isArchived) return
    val actions = buildList {
        // Un insumo puro no tiene precio de venta que cambiar.
        if (product.details.usage.isSold) {
            add(
                OverflowAction(stringResource(R.string.product_change_price), {
                    onAction(ProductDetailAction.OpenPriceSheet)
                }),
            )
        }
        if (product.details.stockMode == ProductStockMode.TRACKED) {
            add(
                OverflowAction(stringResource(R.string.product_fix_cost), {
                    onAction(ProductDetailAction.OpenCostSheet)
                }),
            )
            // Un producto con stock propio puede producirse con una receta, también con lotes.
            add(
                OverflowAction(stringResource(R.string.combo_add_recipe_action), {
                    onAction(ProductDetailAction.OpenRecipe)
                }),
            )
        }
        if (overview.movements.isEmpty()) {
            add(
                OverflowAction(
                    stringResource(R.string.action_delete),
                    { onAction(ProductDetailAction.AskDelete) },
                    destructive = true,
                ),
            )
        } else {
            add(
                OverflowAction(stringResource(R.string.action_archive), {
                    onAction(ProductDetailAction.AskArchive)
                }),
            )
        }
    }
    OverflowMenu(actions)
}

@Composable
private fun DetailBody(
    overview: ProductOverview,
    pricing: ProductPricing,
    tab: ProductDetailTab,
    onAction: (ProductDetailAction) -> Unit,
    locationStock: ProductLocationStock = ProductLocationStock.Hidden,
    lots: List<ProductLotItem> = emptyList(),
) {
    val spacing = AppTheme.spacing
    val product = overview.product
    val tracked = product.details.stockMode == ProductStockMode.TRACKED
    val unit = overview.unit?.let { unitLabel(it) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = spacing.s16)) {
        // La foto, solo si el producto la tiene: sin foto no hay ningún relleno.
        product.details.photoMediaId?.let { photo ->
            item(key = "photo") {
                ProductPhoto(
                    photo,
                    Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                    PhotoThumbSize.DETAIL,
                )
            }
        }
        if (product.isArchived) {
            item(key = "archived") {
                Row(
                    Modifier.fillMaxWidth().padding(
                        horizontal = spacing.s16,
                        vertical = spacing.s8,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    StatusTag(stringResource(R.string.status_archived))
                    InlineTextButton(stringResource(R.string.action_reactivate), {
                        onAction(ProductDetailAction.Reactivate)
                    })
                }
            }
        }
        // Un insumo lo dice arriba: no aparece al vender.
        if (product.details.usage != ProductUsage.SALE) {
            item(key = "usage") {
                Box(
                    Modifier.fillMaxWidth().padding(
                        horizontal = spacing.s16,
                        vertical = spacing.s8,
                    ),
                ) {
                    StatusTag(stringResource(usageLabel(product.details.usage)))
                }
            }
        }
        if (tracked && unit != null) {
            item(key = "stock") { StockBlock(product, unit, onAction) }
        }
        item(key = "figures") { Figures(product, pricing) }
        item(key = "replacement") { ReplacementFigures(product, pricing) }
        item(key = "notices") { Notices(product) }
        // Solo con más de una ubicación: con una sola, el stock de arriba ya es todo.
        val byLocation = locationStock as? ProductLocationStock.Shown
        if (tracked && unit != null && byLocation != null) {
            item(key = "by-location") {
                SectionHeader(stringResource(R.string.product_stock_by_location))
            }
            items(byLocation.rows, key = { "location-${it.location.id}" }) { row ->
                LocationStockRow(locationName(row.location), row.onHand, unit)
            }
        }
        // `Lotes` solo con control por lotes, y `Precios` solo en lo que se vende.
        val tabs = ProductDetailTab.entries.filter {
            (it != ProductDetailTab.LOTS || product.details.tracksLots) &&
                (it != ProductDetailTab.PRICES || product.details.usage.isSold)
        }
        // Si el segmento elegido ya no existe, como los precios de un producto que pasó a insumo,
        // se ven los movimientos.
        val shownTab = tab.takeIf { it in tabs } ?: ProductDetailTab.MOVEMENTS
        item(key = "tabs") {
            SegmentedTabs(
                options = tabs.map { stringResource(tabLabel(it)) },
                selectedIndex = tabs.indexOf(shownTab),
                onSelect = { onAction(ProductDetailAction.SelectTab(tabs[it])) },
                modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
            )
        }
        when (shownTab) {
            ProductDetailTab.MOVEMENTS -> movementRows(overview, unit, onAction)
            ProductDetailTab.PRICES -> priceRows(overview.prices)
            ProductDetailTab.SUPPLIERS -> lastCostRows(overview, onAction)
            ProductDetailTab.LOTS -> lotRows(lots, unit, onAction)
        }
    }
}

/**
 * Los lotes con stock, del que vence antes al que vence después; tocar uno abre su hoja en la
 * pantalla de lotes. Sin lotes con stock, lo dice. Al final, `Ver lotes` abre todos.
 */
private fun LazyListScope.lotRows(
    lots: List<ProductLotItem>,
    unit: UnitLabel?,
    onAction: (ProductDetailAction) -> Unit,
) {
    if (lots.isEmpty()) {
        item(key = "lots-empty") {
            EmptyStateInline(
                title = stringResource(R.string.lot_empty_title),
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
        }
    }
    val shown = unit ?: return
    items(lots, key = { "lot-${it.lotId}" }) { lot ->
        LotRow(
            code = lot.code,
            expiry = lot.expiry,
            onHand = lot.onHand,
            unit = shown,
            onClick = { onAction(ProductDetailAction.OpenLots(lot.lotId)) },
            name = if (lot.unassigned) stringResource(R.string.lot_unassigned_name) else null,
            note = if (lot.unassigned && lot.onHand < QuantityMilli.ZERO) {
                stringResource(R.string.lot_unassigned_help)
            } else {
                null
            },
        )
    }
    item(key = "lots-all") {
        InlineTextButton(
            stringResource(R.string.lot_view_action),
            { onAction(ProductDetailAction.OpenLots()) },
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s8),
        )
    }
}

/** Lo que hay en una ubicación: su nombre a la izquierda y la cantidad a la derecha. */
@Composable
private fun LocationStockRow(name: String, onHand: QuantityMilli, unit: UnitLabel) {
    val spacing = AppTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.listRowSingle)
            .padding(horizontal = spacing.s16, vertical = spacing.s8)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            quantityText(onHand, unit),
            style = AppTheme.amounts.row,
            color = if (onHand < QuantityMilli.ZERO) {
                AppTheme.colors.loss
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Cuánto hay, en cifra grande, con el mínimo debajo y su estado: por agotarse, agotado o negativo.
 * El estado lleva su texto, no solo su color.
 */
@Composable
private fun StockBlock(product: Product, unit: UnitLabel, onAction: (ProductDetailAction) -> Unit) {
    val spacing = AppTheme.spacing
    val onHand = product.onHand
    val minStock = product.details.minStock
    val status: Pair<String, StatusTone>? = when {
        // La cifra ya está arriba: la etiqueta solo dice el estado.
        onHand < QuantityMilli.ZERO -> stringResource(R.string.stock_filter_negative) to
            StatusTone.LOSS

        onHand == QuantityMilli.ZERO -> stringResource(R.string.term_out_of_stock) to
            StatusTone.LOSS

        LowStockEvaluator.isLow(product.details.stockMode, false, onHand, minStock) ->
            stringResource(R.string.term_low_stock) to StatusTone.WARNING

        else -> null
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        // La cifra va sola en su línea y sin partirse; el estado, debajo, con letra grande también.
        Text(
            quantityText(onHand, unit),
            style = AppTheme.amounts.large,
            color = if (onHand <
                QuantityMilli.ZERO
            ) {
                AppTheme.colors.loss
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            softWrap = false,
        )
        status?.let { (text, tone) -> StatusTag(text, tone = tone) }
        if (minStock > QuantityMilli.ZERO) {
            Text(
                stringResource(R.string.stock_minimum, quantityText(minStock, unit)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s16)) {
            InlineTextButton(stringResource(R.string.empty_movements_action), {
                onAction(ProductDetailAction.RegisterPurchase)
            })
            InlineTextButton(stringResource(R.string.stock_adjust_title), {
                onAction(ProductDetailAction.AdjustStock)
            })
        }
    }
}

/**
 * Precio, costo y margen en una fila, o en columna con letra grande. Un servicio muestra su costo
 * fijo. El margen se mide sin el impuesto incluido y contra el costo sin redondear, el mismo con
 * que se sugiere el precio; sin costo va sin dato, y el de un producto por componentes no se
 * calcula aquí.
 */
@Composable
private fun Figures(product: Product, pricing: ProductPricing) {
    val spacing = AppTheme.spacing
    val details = product.details
    val price = product.salePrice
    val margin = pricing.marginOf(price)
    val shownCost = when (details.stockMode) {
        ProductStockMode.TRACKED -> product.averageCost
        ProductStockMode.UNTRACKED -> details.fixedUnitCost
        ProductStockMode.COMPONENTS -> null
    }
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    val cost: @Composable (Modifier) -> Unit = { m ->
        StatTile(
            stringResource(R.string.term_average_cost),
            shownCost,
            StatTileVariant.PLAIN,
            m,
            sensitive = true,
        )
    }
    // Un insumo puro no se vende: solo su costo, sin precio ni margen.
    val tiles: List<@Composable (Modifier) -> Unit> = if (details.usage.isSold) {
        listOf(
            { m ->
                StatTile(
                    stringResource(R.string.product_sale_price),
                    price.takeIf { it > Money.ZERO },
                    StatTileVariant.PLAIN,
                    m,
                )
            },
            cost,
            { m -> MarginTile(margin, m) },
        )
    } else {
        listOf(cost)
    }
    val padding = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8)
    if (stacked) {
        Column(padding.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
            tiles.forEach { it(Modifier.fillMaxWidth()) }
        }
    } else {
        Row(padding.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.s12)) {
            tiles.forEach { it(Modifier.weight(1f)) }
        }
    }
}

/**
 * El costo de reposición y la ganancia por unidad si hubiera que reponer, debajo de las tres cifras,
 * rotulados aparte para que no se confundan con el costo promedio ni con el margen. Solo aparecen
 * con un costo de reposición conocido: sin él no se inventa ninguna cifra. Los dos se ocultan en
 * modo discreto, y una línea explica la diferencia.
 */
@Composable
private fun ReplacementFigures(product: Product, pricing: ProductPricing) {
    val replacement = product.replacementCost
    if (product.details.stockMode != ProductStockMode.TRACKED || replacement <= Money.ZERO) return
    val spacing = AppTheme.spacing
    val profit = ReplacementProfitRules.unitProfit(pricing.netOf(product.salePrice), replacement)
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    val sold = product.details.usage.isSold
    val replacementTile: @Composable (Modifier) -> Unit = { m ->
        StatTile(
            stringResource(R.string.product_replacement_cost),
            replacement,
            StatTileVariant.PLAIN,
            m,
            sensitive = true,
        )
    }
    val profitTile: @Composable (Modifier) -> Unit = { m ->
        StatTile(
            stringResource(R.string.product_profit_if_replaced),
            profit,
            StatTileVariant.PLAIN,
            m,
            sensitive = true,
        )
    }
    // La ganancia si se repone es de una venta: un insumo puro no la tiene.
    val tiles = if (sold) listOf(replacementTile, profitTile) else listOf(replacementTile)
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        if (stacked) {
            tiles.forEach { it(Modifier.fillMaxWidth()) }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                tiles.forEach { it(Modifier.weight(1f)) }
            }
        }
        if (sold) {
            Text(
                stringResource(R.string.product_replacement_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** El margen como cifra de tarjeta, oculto en modo discreto. Sin precio o sin costo, un guion. */
@Composable
private fun MarginTile(margin: Margin, modifier: Modifier) {
    val text = when (margin) {
        is Margin.Defined -> LocalAppFormats.current.percent.format(margin.value)

        // Sin precio o sin costo, o un margen que no cabe en la cifra: un guion, nunca un número.
        Margin.NoData, Margin.OutOfRange -> NO_VALUE_TEXT
    }
    val negative = margin is Margin.Defined && margin.value < BasisPoints.ZERO
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(
            stringResource(R.string.term_margin),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DiscreetValue(style = AppTheme.amounts.tile) {
            Text(
                text,
                style = AppTheme.amounts.tile,
                color = if (negative) AppTheme.colors.loss else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Los avisos que cambian lo que la persona haría: sin costo, sin precio o pérdida en cada venta. */
@Composable
private fun Notices(product: Product) {
    val details = product.details
    val noCost = when (details.stockMode) {
        ProductStockMode.TRACKED ->
            product.averageCost == Money.ZERO &&
                product.stockValue == Money.ZERO

        ProductStockMode.UNTRACKED -> details.fixedUnitCost == Money.ZERO

        ProductStockMode.COMPONENTS -> false
    }
    // Un insumo puro no se vende: sin avisos de precio ni de pérdida.
    val noPrice = details.usage.isSold && product.salePrice == Money.ZERO
    val cost = when (details.stockMode) {
        ProductStockMode.TRACKED -> product.averageCost
        ProductStockMode.UNTRACKED -> details.fixedUnitCost
        ProductStockMode.COMPONENTS -> Money.ZERO
    }
    val loss = details.usage.isSold && !noPrice && !noCost && product.salePrice < cost
    val notices = buildList {
        if (noCost) add(R.string.product_no_cost_hint)
        if (noPrice) add(R.string.notice_no_price)
        if (loss) add(R.string.notice_negative_margin)
    }
    if (notices.isEmpty()) return
    Column(
        Modifier.padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        notices.forEach {
            NoticeCard(
                text = stringResource(it),
                variant = if (it ==
                    R.string.notice_negative_margin
                ) {
                    NoticeVariant.ERROR
                } else {
                    NoticeVariant.WARNING
                },
                icon = R.drawable.ic_warning,
            )
        }
    }
}

private fun LazyListScope.movementRows(
    overview: ProductOverview,
    unit: UnitLabel?,
    onAction: (ProductDetailAction) -> Unit,
) {
    if (overview.movements.isEmpty()) {
        item(key = "no-movements") {
            Column(Modifier.padding(horizontal = AppTheme.spacing.s16)) {
                EmptyStateInline(
                    title = stringResource(R.string.empty_movements_title),
                    body = stringResource(R.string.empty_movements_body),
                )
            }
        }
        return
    }
    val byId = overview.movements.associateBy { it.id }
    val voidable = !overview.product.isArchived
    items(overview.movements, key = { "movement-${it.id}" }) { movement ->
        MovementRow(movement, byId, unit, voidable, onAction)
    }
}

/**
 * Una fila del libro: su tipo y su fecha, la cantidad con signo y el saldo que dejó. Una corrección
 * de costo dice de cuánto a cuánto; una fila anulada dice cuándo, y la que anula, a cuál. Una venta
 * o una compra abre su documento.
 */
@Composable
private fun MovementRow(
    movement: ProductMovement,
    byId: Map<Long, ProductMovement>,
    unit: UnitLabel?,
    voidable: Boolean,
    onAction: (ProductDetailAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    val record = movement.record
    val type = record.movement.type
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val discreet = LocalDiscreetMode.current
    val details = buildList {
        add(AnnotatedString(formats.date.dayMonthYear(record.businessDate)))
        // Los costos de la historia se ocultan en modo discreto: queda el tipo de movimiento.
        if (type == StockMovementType.COST_CORRECTION && !discreet) {
            add(
                formatAnnotated(
                    stringResource(R.string.product_change_from_to),
                    rememberMoneyDisplay(movement.averageCostBefore, formats.money).text,
                    rememberMoneyDisplay(record.averageCostAfter, formats.money).text,
                ),
            )
        }
        movement.compensatedById?.let { byId[it] }?.let {
            add(
                AnnotatedString(
                    stringResource(
                        R.string.product_movement_voided_on,
                        formats.date.dayMonthYear(it.record.businessDate),
                    ),
                ),
            )
        }
        record.movement.reversesMovementId?.let { byId[it] }?.let {
            add(
                AnnotatedString(
                    stringResource(
                        R.string.product_movement_voids,
                        formats.date.dayMonthYear(it.record.businessDate),
                    ),
                ),
            )
        }
        record.note?.let { add(AnnotatedString(it)) }
    }
    val document = movement.document
    val clickable = if (document != null) {
        Modifier.clickable(role = Role.Button) {
            onAction(ProductDetailAction.OpenDocument(document))
        }
    } else {
        Modifier
    }
    Column(clickable.semantics(mergeDescendants = true) {}) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                val reason = record.reason
                Text(
                    if (type == StockMovementType.ADJUSTMENT && reason != null) {
                        stringResource(
                            R.string.stock_movement_adjustment_reason,
                            reasonLabel(reason),
                        )
                    } else {
                        stringResource(movementLabel(type))
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (movement.compensatedById !=
                        null
                    ) {
                        muted
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                details.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                // Un ajuste o un inventario inicial vigente se anula con su fila compensatoria.
                val canVoid = voidable && movement.compensatedById == null &&
                    StockAdjustmentRules.isVoidable(type)
                if (canVoid) {
                    InlineTextButton(stringResource(R.string.dialog_void_adjustment_action), {
                        onAction(ProductDetailAction.AskVoid(movement.id))
                    })
                }
            }
            if (unit != null && type != StockMovementType.COST_CORRECTION) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    Text(
                        signedQuantity(record.movement.quantity, unit),
                        style = AppTheme.amounts.row,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        stringResource(
                            R.string.stock_left,
                            quantityText(record.balanceAfter, unit),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** La cantidad con su signo: `+` para lo que entra; lo que sale ya trae el suyo. */
@Composable
internal fun signedQuantity(quantity: QuantityMilli, unit: UnitLabel): String {
    val text = quantityText(quantity, unit)
    return if (quantity > QuantityMilli.ZERO) PLUS + text else text
}

/**
 * Lo último que se pagó a cada proveedor, de la compra más nueva a la más antigua: el proveedor o
 * `Sin proveedor`, la fecha y el costo por unidad de la compra. Tocar una fila abre su compra. Sin
 * compras, lo dice.
 */
private fun LazyListScope.lastCostRows(
    overview: ProductOverview,
    onAction: (ProductDetailAction) -> Unit,
) {
    if (overview.lastCosts.isEmpty()) {
        item(key = "no-costs") {
            EmptyStateInline(title = stringResource(R.string.empty_purchases_title))
        }
        return
    }
    items(overview.lastCosts, key = { "cost-${it.supplierId}-${it.purchaseId}" }) { cost ->
        LastCostRow(cost, overview.purchaseUnits[cost.unitId]) {
            onAction(ProductDetailAction.OpenDocument(MovementDocument.Purchase(cost.purchaseId)))
        }
    }
}

/** Un proveedor: su nombre, la fecha de su última compra y lo que costó cada unidad de compra. */
@Composable
private fun LastCostRow(cost: SupplierLastCost, unit: MeasureUnit?, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.clickable(onClick = onClick).semantics(mergeDescendants = true) {}) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(
                    cost.supplierName ?: stringResource(R.string.purchase_no_supplier),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    formats.date.dayMonthYear(cost.businessDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(cost.unitCost, MoneySize.ROW, sensitive = true)
                unit?.let {
                    Text(
                        unitLabel(it).symbol,
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

private fun LazyListScope.priceRows(prices: List<PriceChange>) {
    items(prices, key = { "price-${it.id}" }) { change -> PriceRow(change) }
}

/** Un cambio de precio: su origen y su fecha, y el precio nuevo, con el anterior si lo hubo. */
@Composable
private fun PriceRow(change: PriceChange) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    val content = change.content
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(
                    stringResource(priceSourceLabel(content.source)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    formats.date.dayMonthYear(content.businessDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                content.oldPrice?.let { old ->
                    Text(
                        formatAnnotated(
                            stringResource(R.string.product_change_from_to),
                            rememberMoneyDisplay(old, formats.money).text,
                            rememberMoneyDisplay(content.newPrice, formats.money).text,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
            MoneyText(content.newPrice, MoneySize.ROW)
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * La hoja de cambiar el precio: el precio nuevo y, debajo, el margen que dejaría con el costo de
 * ahora, que cambia con cada dígito, y la sugerencia para un margen objetivo. `Usar este precio`
 * pone el sugerido en el campo, para revisarlo antes de cambiarlo. Los márgenes y el sugerido se
 * ocultan en modo discreto; el precio, no, porque se está escribiendo.
 */
@Composable
private fun PriceSheetView(
    sheet: PriceSheet,
    pricing: ProductPricing,
    onAction: (ProductDetailAction) -> Unit,
) {
    val money = LocalAppFormats.current.money
    val typed = NumberInputParser.money(
        sheet.price,
        money.inputSymbols,
        money.currencyDecimals,
        Limit.UNIT_MONEY,
    )
    val price = when (typed) {
        is NumberInput.Value -> typed.value
        NumberInput.Blank -> Money.ZERO
        NumberInput.Malformed, is NumberInput.TooManyDecimals, is NumberInput.TooLarge -> null
    }
    val margin = price?.let(pricing::marginOf) ?: Margin.NoData
    EditorContainer(
        asPanel = false,
        title = stringResource(R.string.product_change_price),
        onDismiss = { onAction(ProductDetailAction.DismissPriceSheet) },
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                sheet.saveFailed?.let {
                    NoticeCard(
                        text = stringResource(saveErrorText(it.outOfSpace)),
                        variant = NoticeVariant.ERROR,
                        icon = R.drawable.ic_error,
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.product_change_price),
                    onClick = { onAction(ProductDetailAction.SavePrice) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                    state = if (sheet.saving) ActionState.WORKING else ActionState.IDLE,
                )
            }
        },
    ) {
        MoneyField(
            value = sheet.price,
            onValueChange = { onAction(ProductDetailAction.PriceText(it)) },
            label = stringResource(R.string.product_sale_price),
            error = sheet.priceProblem?.let { problemText(it, ProductEditField.SALE_PRICE) },
            imeAction = ImeAction.Done,
        )
        val marginText = when (margin) {
            is Margin.Defined -> LocalAppFormats.current.percent.format(margin.value)
            Margin.NoData, Margin.OutOfRange -> NO_VALUE_TEXT
        }
        DiscreetValue(style = MaterialTheme.typography.bodyMedium) {
            Text(
                stringResource(R.string.product_price_margin, marginText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        sheet.suggestion?.let { suggestion ->
            Text(
                stringResource(R.string.product_suggest_price),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = AppTheme.spacing.s8).semantics { heading() },
            )
            PriceSuggestionPanel(
                draft = suggestion,
                onTargetChange = { onAction(ProductDetailAction.SuggestionTarget(it)) },
                onUse = { onAction(ProductDetailAction.UseSuggestedPrice) },
                onFixCost =
                    if (sheet.canFixCost) {
                        { onAction(ProductDetailAction.FixCostForSuggestion) }
                    } else {
                        null
                    },
            )
        }
    }
}

/** La hoja de corregir el costo: el costo nuevo, una nota opcional y qué cambia y qué no. */
@Composable
private fun CostSheetView(sheet: CostSheet, onAction: (ProductDetailAction) -> Unit) {
    EditorContainer(
        asPanel = false,
        title = stringResource(R.string.product_fix_cost),
        onDismiss = { onAction(ProductDetailAction.DismissCostSheet) },
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                sheet.saveFailed?.let {
                    NoticeCard(
                        text = stringResource(saveErrorText(it.outOfSpace)),
                        variant = NoticeVariant.ERROR,
                        icon = R.drawable.ic_error,
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.product_fix_cost),
                    onClick = { onAction(ProductDetailAction.SaveCost) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                    state = if (sheet.saving) ActionState.WORKING else ActionState.IDLE,
                )
            }
        },
    ) {
        MoneyField(
            value = sheet.cost,
            onValueChange = { onAction(ProductDetailAction.CostText(it)) },
            label = stringResource(R.string.product_fix_cost_new),
            error = sheet.costProblem?.let { problemText(it, ProductEditField.COST) },
        )
        AppTextField(
            value = sheet.note,
            onValueChange = { onAction(ProductDetailAction.CostNote(it)) },
            label = stringResource(R.string.form_note),
            error =
                if (sheet.noteTooLong) {
                    val max = ProductDetailViewModel.NOTE_MAX
                    pluralStringResource(R.plurals.error_text_long, max, max)
                } else {
                    null
                },
            supportingText = stringResource(R.string.product_fix_cost_help),
            imeAction = ImeAction.Done,
        )
    }
}

/** La confirmación de archivar o eliminar, con el nombre del producto en el título. */
@Composable
private fun ProductConfirm(dialog: ProductDetailDialog, onAction: (ProductDetailAction) -> Unit) {
    when (dialog) {
        is ProductDetailDialog.Archive ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_archive_product_title, dialog.name),
                body = stringResource(R.string.dialog_archive_product_body),
                confirmLabel = stringResource(R.string.dialog_archive_product_action),
                onConfirm = { onAction(ProductDetailAction.ConfirmDialog) },
                onDismiss = { onAction(ProductDetailAction.DismissDialog) },
            )

        is ProductDetailDialog.VoidAdjustment ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_void_adjustment_title),
                body = stringResource(R.string.dialog_void_adjustment_body),
                confirmLabel = stringResource(R.string.dialog_void_adjustment_action),
                onConfirm = { onAction(ProductDetailAction.ConfirmDialog) },
                onDismiss = { onAction(ProductDetailAction.DismissDialog) },
            )

        is ProductDetailDialog.Delete ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_delete_product_title, dialog.name),
                body = stringResource(R.string.dialog_delete_product_body),
                confirmLabel = stringResource(R.string.dialog_delete_product_action),
                onConfirm = { onAction(ProductDetailAction.ConfirmDialog) },
                onDismiss = { onAction(ProductDetailAction.DismissDialog) },
                variant = ConfirmVariant.DESTRUCTIVE,
            )
    }
}

@StringRes
private fun tabLabel(tab: ProductDetailTab): Int = when (tab) {
    ProductDetailTab.MOVEMENTS -> R.string.product_tab_movements
    ProductDetailTab.PRICES -> R.string.product_tab_prices
    ProductDetailTab.SUPPLIERS -> R.string.product_tab_suppliers
    ProductDetailTab.LOTS -> R.string.product_tab_lots
}

@StringRes
internal fun movementLabel(type: StockMovementType): Int = when (type) {
    StockMovementType.OPENING_BALANCE -> R.string.stock_movement_opening_balance
    StockMovementType.PURCHASE -> R.string.stock_movement_purchase
    StockMovementType.SALE -> R.string.stock_movement_sale
    StockMovementType.ADJUSTMENT -> R.string.stock_movement_adjustment
    StockMovementType.COST_CORRECTION -> R.string.stock_movement_cost_correction
    StockMovementType.PURCHASE_VOID -> R.string.stock_movement_purchase_void
    StockMovementType.SALE_VOID -> R.string.stock_movement_sale_void
    StockMovementType.ADJUSTMENT_VOID -> R.string.stock_movement_adjustment_void
    StockMovementType.SALE_RETURN -> R.string.stock_movement_sale_return
    StockMovementType.SALE_RETURN_VOID -> R.string.stock_movement_sale_return_void
    StockMovementType.PURCHASE_RETURN -> R.string.stock_movement_purchase_return
    StockMovementType.PURCHASE_RETURN_VOID -> R.string.stock_movement_purchase_return_void
    StockMovementType.PRODUCTION_OUT -> R.string.stock_movement_production_out
    StockMovementType.PRODUCTION_IN -> R.string.stock_movement_production_in
    StockMovementType.PRODUCTION_VOID -> R.string.stock_movement_production_void
    StockMovementType.TRANSFER_OUT -> R.string.stock_movement_transfer_out
    StockMovementType.TRANSFER_IN -> R.string.stock_movement_transfer_in
    StockMovementType.TRANSFER_VOID -> R.string.stock_movement_transfer_void
}

@StringRes
internal fun priceSourceLabel(source: PriceChangeSource): Int = when (source) {
    PriceChangeSource.INITIAL -> R.string.price_source_initial
    PriceChangeSource.MANUAL -> R.string.price_source_manual
    PriceChangeSource.BULK -> R.string.price_source_bulk
    PriceChangeSource.BULK_UNDO -> R.string.price_source_bulk_undo
    PriceChangeSource.IMPORT -> R.string.price_source_import
}

private const val SKELETON_ROWS = 5
private const val STACK_FONT_SCALE = 1.3f

/** El signo de lo que entra al stock. */
private const val PLUS = "+"

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun ProductDetailPreview() {
    PreviewTheme {
        ProductDetailScreen(
            ProductDetailSamples.overview().let { overview ->
                ProductDetailUiState(
                    ProductDetailContent.Ready(overview, ProductDetailSamples.pricing(overview)),
                )
            },
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun ProductDetailNoCostPreview() {
    PreviewTheme(discreet = true) {
        val product = ProductDetailSamples.product(cost = 0L, onHand = -2_000L)
        ProductDetailScreen(
            ProductDetailUiState(
                ProductDetailSamples.overview(product, withHistory = false).let { overview ->
                    ProductDetailContent.Ready(overview, ProductDetailSamples.pricing(overview))
                },
                tab = ProductDetailTab.PRICES,
            ),
            {},
        )
    }
}

@Preview(name = "Cargando")
@Composable
private fun ProductDetailLoadingPreview() {
    PreviewTheme { ProductDetailScreen(ProductDetailUiState(), {}) }
}
