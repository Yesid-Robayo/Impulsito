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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryStatus
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.CountTile
import io.github.yesidrobayo.shopledger.ui.components.DISCREET_TEXT
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.ProductStock
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El inventario y lo que se está acabando. Es puro: todo llega en [uiState] y sale por [onAction].
 *
 * Arriba, las dos cifras: cuántos se están acabando y cuánto vale lo que hay, este en modo
 * discreto. Debajo, los chips y la lista. Cada fila abre el producto, ofrece `Reponer` a la derecha
 * y `Ajustar stock` en su menú. No lee las alertas, así que funciona sin notificaciones.
 *
 * @param notificationsBlocked si los avisos de stock no pueden salir: sin permiso, con los avisos de
 *   la app apagados o con su canal silenciado. Un aviso discreto lo dice y lleva a los ajustes.
 * @param navigation cómo se sale: sin flecha como pestaña de Productos, con flecha si se abrió
 * desde   otra pantalla, como el aviso de Inicio.
 * @param tabs las pestañas de Productos, que da la pantalla que la contiene; vacías si va sola.
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
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
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
        )
        Column(
            Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth(),
        ) {
            tabs()
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
                    InventoryList(uiState.chip, content, notificationsBlocked, onAction)
            }
        }
    }
}

@Composable
private fun InventoryList(
    chip: InventoryChip,
    content: InventoryContent.Ready,
    notificationsBlocked: Boolean,
    onAction: (InventoryAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AppTheme.sizes.bottomBar + spacing.s24),
    ) {
        item(key = "summary") { Summary(content, notificationsBlocked, onAction) }
        item(key = "chips") {
            val chips = buildList {
                add(InventoryChip.LOW_STOCK)
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
        if (content.items.isEmpty()) {
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
 */
@Composable
private fun InventoryRow(item: InventoryItem, onAction: (InventoryAction) -> Unit) {
    val adjust = stringResource(R.string.stock_adjust_title)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductRow(
                data = inventoryRowData(item),
                modifier = Modifier.weight(1f),
                onClick = { onAction(InventoryAction.OpenProduct(item.id)) },
                divider = false,
            )
            InlineTextButton(
                stringResource(R.string.stock_restock),
                { onAction(InventoryAction.Restock(item.id)) },
            )
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
 * Lo que dice la fila de [item]: su nombre, su stock con el icono de su estado, y a la derecha el
 * stock y el mínimo. La usan el inventario y la tarjeta por agotarse de Inicio.
 */
@Composable
internal fun inventoryRowData(item: InventoryItem): ProductRowData {
    val fallback = UnitLabel(stringResource(R.string.seed_units_unit), false)
    val unit = item.unit?.let { unitLabel(it) } ?: fallback
    return ProductRowData(
        name = item.name,
        stock = stockOf(item, unit),
        trailing = ProductRowTrailing.Stock(item.onHand, item.minStock, unit),
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
    },
)

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
