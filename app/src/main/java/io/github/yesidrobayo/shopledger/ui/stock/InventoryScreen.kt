package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReviewReason
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryStatus
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.CountTile
import io.github.yesidrobayo.shopledger.ui.components.DISCREET_TEXT
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.FilterBar
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LocationSwitcher
import io.github.yesidrobayo.shopledger.ui.components.LotExpiry
import io.github.yesidrobayo.shopledger.ui.components.LotRow
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.ProductStock
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.SettingsButton
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.product.usageTagText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El inventario y lo que se está acabando. Es puro: todo llega en [uiState] y sale por [onAction].
 *
 * Arriba, las dos cifras: cuántos se están acabando y cuánto vale lo que hay, este en modo
 * discreto. Debajo, los chips y la lista. Cada fila abre el producto, ofrece `Reponer` a la
 * derecha, o `Producir` si tiene receta, y `Ajustar stock` en su menú. No lee las alertas, así que
 * funciona sin notificaciones.
 *
 * @param notificationsBlocked si los avisos de stock no pueden salir: sin permiso, con los avisos de
 *   la app apagados o con su canal silenciado. Un aviso discreto lo dice y lleva a los ajustes.
 * @param navigation cómo se sale: sin flecha como pestaña de Productos, con flecha si se abrió
 * desde   otra pantalla, como el aviso de Inicio.
 * @param tabs las pestañas de Productos, que da la pantalla que la contiene; vacías si va sola.
 *
 * @param backupBanner el banner del respaldo bajo la barra, que solo aparece si el estado pide
 *   atención.
 * @param onScan abre el escáner desde la búsqueda, o `null` si ningún producto tiene código.
 */
@Composable
fun InventoryScreen(
    uiState: InventoryUiState,
    onAction: (InventoryAction) -> Unit,
    modifier: Modifier = Modifier,
    navigation: TopBarNavigation = TopBarNavigation.NONE,
    onNavigate: () -> Unit = {},
    tabs: @Composable () -> Unit = {},
    notificationsBlocked: Boolean = false,
    backupBanner: @Composable () -> Unit = {},
    onScan: (() -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        InventoryBody(
            uiState,
            onAction,
            navigation,
            onNavigate,
            tabs,
            notificationsBlocked,
            backupBanner,
            onScan,
        )
        AppSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = AppTheme.sizes.bottomBar),
        )
    }
    val message = uiState.message?.let { messageText(it) }
    LaunchedEffect(uiState.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onAction(InventoryAction.MessageShown)
        }
    }
}

@Composable
private fun InventoryBody(
    uiState: InventoryUiState,
    onAction: (InventoryAction) -> Unit,
    navigation: TopBarNavigation,
    onNavigate: () -> Unit,
    tabs: @Composable () -> Unit,
    notificationsBlocked: Boolean,
    backupBanner: @Composable () -> Unit,
    onScan: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = stringResource(R.string.nav_products),
            navigation = navigation,
            onNavigate = onNavigate,
            navigationLabel =
                if (navigation == TopBarNavigation.NONE) {
                    null
                } else {
                    stringResource(R.string.a11y_back)
                },
        ) {
            ScreenHelpLink(HelpTopic.INVENTORY)
            // Como pestaña de Productos es una raíz, con los ajustes al borde como las demás.
            if (navigation == TopBarNavigation.NONE) SettingsButton()
        }
        backupBanner()
        Column(
            Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth(),
        ) {
            tabs()
            // La misma búsqueda de la lista de productos: por nombre o por código, con el escáner.
            if (uiState.content != InventoryContent.EmptyCatalog) {
                SearchField(
                    query = uiState.query,
                    onQueryChange = { onAction(InventoryAction.QueryChange(it)) },
                    placeholder = stringResource(R.string.sale_search_hint),
                    modifier = Modifier.padding(
                        horizontal = AppTheme.spacing.s16,
                        vertical = AppTheme.spacing.s8,
                    ),
                    onScan = onScan,
                )
            }
            when (val content = uiState.content) {
                InventoryContent.Loading -> DelayedSkeleton { InventorySkeleton() }

                InventoryContent.EmptyCatalog ->
                    EmptyState(
                        icon = R.drawable.ic_inventory_2,
                        title = stringResource(R.string.empty_inventory_title),
                    ) {
                        PrimaryActionButton(
                            stringResource(R.string.empty_inventory_action),
                            { onAction(InventoryAction.CreateProduct) },
                            ActionVariant.PRIMARY,
                        )
                    }

                InventoryContent.ReadError ->
                    EmptyState(
                        icon = R.drawable.ic_error,
                        title = stringResource(R.string.state_error_read),
                    ) {
                        InlineTextButton(
                            stringResource(R.string.action_retry),
                            { onAction(InventoryAction.Retry) },
                        )
                    }

                is InventoryContent.Ready ->
                    InventoryList(uiState, content, notificationsBlocked, onAction)
            }
        }
    }
}

@Composable
private fun InventoryList(
    uiState: InventoryUiState,
    content: InventoryContent.Ready,
    notificationsBlocked: Boolean,
    onAction: (InventoryAction) -> Unit,
) {
    val chip = uiState.chip
    val spacing = AppTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AppTheme.sizes.bottomBar + spacing.s24),
    ) {
        item(key = "summary") { Summary(content, notificationsBlocked, onAction) }
        item(key = "chips") {
            val chips = buildList {
                add(InventoryChip.LOW_STOCK)
                if (content.showsReview) add(InventoryChip.REVIEW)
                if (content.showsExpiring) add(InventoryChip.EXPIRING)
                if (content.negativeCount > 0) add(InventoryChip.NEGATIVE_STOCK)
                add(InventoryChip.ALL)
            }
            CompactChipRow(
                options = chips,
                label = { chipLabel(it) },
                selected = setOf(chip),
                onToggle = { onAction(InventoryAction.ChipChanged(it)) },
                multiple = false,
                modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
            )
        }
        // Solo con más de una ubicación. Mirar una cambia las cantidades, no el valor del
        // inventario, que es uno solo por producto. `Reposición sugerida` va al lado como botón de
        // texto: abre otra pantalla, y un chip aquí se leería como un filtro más.
        item(key = "location") {
            FilterBar(Modifier.padding(horizontal = spacing.s16)) {
                if (uiState.locations.isNotEmpty()) {
                    LocationSwitcher(
                        locations = locationChoices(uiState.locations),
                        selectedId = uiState.locationId,
                        onSelect = { onAction(InventoryAction.LocationChanged(it)) },
                        allowAll = true,
                        onManage = { onAction(InventoryAction.ManageLocations) },
                    )
                }
                InlineTextButton(
                    stringResource(R.string.reorder_title),
                    { onAction(InventoryAction.OpenReorder) },
                )
            }
        }
        if (chip == InventoryChip.REVIEW) {
            item(key = "review-all") {
                PrimaryActionButton(
                    stringResource(R.string.review_all_action),
                    { onAction(InventoryAction.ReviewAll) },
                    ActionVariant.TONAL,
                    modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                )
            }
            if (content.reviewItems.isEmpty()) searchEmpty(content.query)
            items(content.reviewItems, key = { "review-${it.id}" }) { item ->
                ReviewRow(item, onAction)
            }
            return@LazyColumn
        }
        if (chip == InventoryChip.EXPIRING) {
            if (content.expiringItems.isEmpty()) searchEmpty(content.query)
            items(content.expiringItems, key = { "lot-${it.lotId}" }) { item ->
                ExpiringRow(item, onAction)
            }
            return@LazyColumn
        }
        if (content.items.isEmpty() && content.query.isNotBlank()) {
            searchEmpty(content.query)
        } else if (content.items.isEmpty()) {
            item(key = "empty") {
                val title = stringResource(
                    if (chip == InventoryChip.ALL) {
                        R.string.empty_inventory_title
                    } else {
                        R.string.empty_low_stock_title
                    },
                )
                val lowBody = stringResource(R.string.empty_low_stock_body)
                val body = if (chip == InventoryChip.ALL) null else lowBody
                EmptyStateInline(
                    title = title,
                    body = body,
                    modifier = Modifier.padding(horizontal = spacing.s16),
                )
            }
        }
        items(content.items, key = { it.id }) { item -> InventoryRow(item, onAction) }
    }
}

/**
 * Lo que dice la lista cuando la búsqueda no encuentra nada en el chip elegido: lo buscado y
 * cómo seguir. No ofrece crear el producto, porque aquí se mira el stock de lo que ya existe.
 */
private fun LazyListScope.searchEmpty(query: String) {
    if (query.isBlank()) return
    item(key = "search-empty") {
        EmptyStateInline(
            title = stringResource(R.string.empty_search_title, query.trim()),
            body = stringResource(R.string.inventory_search_empty_body),
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        )
    }
}

@Composable
private fun Summary(
    content: InventoryContent.Ready,
    notificationsBlocked: Boolean,
    onAction: (InventoryAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(start = spacing.s16, end = spacing.s16, top = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        SummaryTiles(content)
        content.archivedValue?.let { ArchivedNote(it) }
        if (notificationsBlocked) {
            NoticeCard(
                text = stringResource(R.string.permission_notifications_denied_inventory),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.action_open_settings),
                onAction = { onAction(InventoryAction.OpenNotificationSettings) },
            )
        }
        if (content.hasStockWithoutCost) {
            NoticeCard(
                text = stringResource(R.string.notice_inventory_no_cost),
                variant = NoticeVariant.INFO,
            )
        }
    }
}

/**
 * Las dos cifras: lado a lado mientras la cifra y las etiquetas caben enteras en la mitad del
 * ancho, y una debajo de la otra cuando no, como con letra grande o un valor de trece cifras. Un
 * monto nunca se corta.
 */
@Composable
private fun SummaryTiles(content: InventoryContent.Ready) {
    val spacing = AppTheme.spacing
    val lowLabel = stringResource(R.string.term_low_stock)
    val valueLabel = stringResource(R.string.term_inventory_value)
    val formatter = LocalAppFormats.current.money
    val amount =
        content.value?.let { rememberMoneyDisplay(it, formatter).text.text } ?: NO_VALUE_TEXT
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val amountStyle = AppTheme.amounts.tile
    val density = LocalDensity.current
    val widest = remember(lowLabel, valueLabel, amount, labelStyle, amountStyle, density) {
        maxOf(
            measurer.measure(lowLabel, labelStyle, softWrap = false).size.width,
            measurer.measure(valueLabel, labelStyle, softWrap = false).size.width,
            measurer.measure(amount, amountStyle, softWrap = false).size.width,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val half = with(density) { ((maxWidth - spacing.s12) / 2 - spacing.s12 * 2).roundToPx() }
        val low: @Composable (Modifier) -> Unit = { modifier ->
            CountTile(lowLabel, content.lowStockCount, modifier, content.negativeCount > 0)
        }
        val value: @Composable (Modifier) -> Unit = { modifier ->
            StatTile(valueLabel, content.value, StatTileVariant.CARD, modifier, sensitive = true)
        }
        if (widest <= half) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s12)) {
                low(Modifier.weight(1f))
                value(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
                low(Modifier.fillMaxWidth())
                value(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Cuánto del valor es de productos archivados, que siguen contando con sus unidades. */
@Composable
private fun ArchivedNote(amount: Money) {
    val discreet = LocalDiscreetMode.current
    val display = rememberMoneyDisplay(amount, LocalAppFormats.current.money)
    val pattern = stringResource(R.string.inventory_archived_value)
    val hidden = stringResource(R.string.a11y_discreet_hidden)
    val spoken =
        stringResource(
            R.string.inventory_archived_value,
            if (discreet) hidden else display.description,
        )
    Text(
        text = formatAnnotated(pattern, if (discreet) DISCREET_TEXT else display.text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

/**
 * Una fila: el producto con su stock y su mínimo, que abre el detalle; `Reponer`, la acción de la
 * lista, a la derecha; y el menú con `Ajustar stock`, que es raro y no merece un botón propio.
 *
 * Un producto con receta dice `Producir` y no `Reponer`: abre la producción y no una compra, y el
 * botón lo dice antes de tocarlo, con la misma palabra que su detalle y su receta.
 */
@Composable
private fun InventoryRow(item: InventoryItem, onAction: (InventoryAction) -> Unit) {
    val adjust = stringResource(R.string.stock_adjust_title)
    val restock = if (item.producible) R.string.combo_produce_action else R.string.stock_restock
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductRow(
                data = inventoryRowData(item),
                modifier = Modifier.weight(1f),
                onClick = { onAction(InventoryAction.OpenProduct(item.id)) },
                divider = false,
            )
            InlineTextButton(stringResource(restock), { onAction(restockActionOf(item)) })
            OverflowMenu(
                listOf(OverflowAction(adjust, { onAction(InventoryAction.Adjust(item.id)) })),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = AppTheme.spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Una fila de `Revisar`: el producto con cada motivo como etiqueta, su precio y su margen, que
 * abre el detalle; `Cambiar precio` a la derecha, que abre su hoja; y, si está en la cola de costo
 * nuevo, `Descartar` en el menú, que lo saca de ella sin cambiar el precio. Los otros motivos no
 * se descartan: salen solos cuando el precio o el costo cambian.
 */
@Composable
private fun ReviewRow(item: PriceReviewItem, onAction: (InventoryAction) -> Unit) {
    val dismiss = stringResource(R.string.dialog_discard_action)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductRow(
                data = ProductRowData(
                    name = item.name,
                    stock = null,
                    trailing = item.trailing,
                    tags = item.reasons.map { reasonText(it, item.costSince) },
                ),
                modifier = Modifier.weight(1f),
                onClick = { onAction(InventoryAction.OpenProduct(item.id)) },
                divider = false,
            )
            InlineTextButton(
                stringResource(R.string.product_change_price),
                { onAction(InventoryAction.ChangePrice(item.id)) },
            )
            if (item.dismissable) {
                OverflowMenu(
                    listOf(
                        OverflowAction(dismiss, {
                            onAction(InventoryAction.DismissReview(item.id))
                        }),
                    ),
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = AppTheme.spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** El texto de un motivo. El costo viejo lleva su fecha, en el formato del negocio. */
@Composable
private fun reasonText(reason: PriceReviewReason, costSince: BusinessDate?): String =
    when (reason) {
        PriceReviewReason.NEW_COST -> stringResource(R.string.review_new_cost)

        PriceReviewReason.LOW_MARGIN -> stringResource(R.string.review_reason_low_margin)

        PriceReviewReason.OLD_COST -> {
            val date = costSince?.let { LocalAppFormats.current.date.dayMonthYear(it) }
            stringResource(R.string.review_reason_old_cost, date ?: NO_VALUE_TEXT)
        }
    }

@Composable
private fun messageText(message: InventoryMessage): String = when (message) {
    is InventoryMessage.DismissFailed -> stringResource(saveErrorText(message.outOfSpace))
}

/**
 * Lo que dice la fila de [item]: su nombre, su stock con el icono de su estado, la etiqueta de un
 * insumo, y a la derecha el stock y el mínimo. La usan el inventario y la tarjeta por agotarse de
 * Inicio.
 */
@Composable
internal fun inventoryRowData(item: InventoryItem): ProductRowData {
    val fallback = UnitLabel(stringResource(R.string.seed_units_unit), false)
    val unit = item.unit?.let { unitLabel(it) } ?: fallback
    return ProductRowData(
        name = item.name,
        stock = stockOf(item, unit),
        trailing = ProductRowTrailing.Stock(item.onHand, item.minStock, unit),
        tag = usageTagText(item.usage),
    )
}

private fun stockOf(item: InventoryItem, unit: UnitLabel): ProductStock = when (item.status) {
    InventoryStatus.NEGATIVE -> ProductStock.Negative(item.onHand, unit)
    InventoryStatus.OUT -> ProductStock.Out
    InventoryStatus.LOW -> ProductStock.Low(item.onHand, unit)
    InventoryStatus.NORMAL -> ProductStock.Normal(item.onHand, unit)
}

@Composable
private fun chipLabel(chip: InventoryChip): String = stringResource(
    when (chip) {
        InventoryChip.LOW_STOCK -> R.string.term_low_stock
        InventoryChip.NEGATIVE_STOCK -> R.string.stock_filter_negative
        InventoryChip.ALL -> R.string.stock_filter_all
        InventoryChip.REVIEW -> R.string.review_filter
        InventoryChip.EXPIRING -> R.string.lot_filter_expiring
    },
)

/**
 * Una fila de `Por vencer`: el producto arriba y su lote, con lo que tiene en todas las ubicaciones.
 * Tocarla abre el producto.
 */
@Composable
private fun ExpiringRow(item: ExpiringLotItem, onAction: (InventoryAction) -> Unit) {
    val fallback = UnitLabel(stringResource(R.string.seed_units_unit), false)
    LotRow(
        code = item.code,
        expiry = LotExpiry.Dated(item.expiryDate, item.daysLeft, soon = true),
        onHand = item.onHand,
        unit = item.unit?.let { unitLabel(it) } ?: fallback,
        productName = item.productName,
        onClick = { onAction(InventoryAction.OpenProduct(item.productId)) },
    )
}

@Composable
private fun InventorySkeleton() {
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxWidth().padding(top = spacing.s16)) {
        Row(
            Modifier.padding(horizontal = spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            CountTile("", 0, Modifier.weight(1f), loading = true)
            StatTile("", null, StatTileVariant.CARD, Modifier.weight(1f), loading = true)
        }
        Box(Modifier.padding(top = spacing.s16)) {
            Column {
                repeat(SKELETON_ROWS) {
                    ProductRow(
                        ProductRowData(
                            name = "",
                            stock = ProductStock.Out,
                            trailing = SKELETON_TRAILING,
                        ),
                        loading = true,
                    )
                }
            }
        }
    }
}

private const val SKELETON_ROWS = 6

private val SKELETON_TRAILING =
    ProductRowTrailing.Stock(QuantityMilli.ZERO, null, UnitLabel("", false))
