package io.github.yesidrobayo.shopledger.ui.lot

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.CountTile
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LotRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.stock.locationName
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La pantalla de lotes y vencimientos: arriba lo que está por vencer y lo que vale; debajo, los
 * filtros y la lista agrupada por producto, en el orden en que sale el stock. Tocar un lote abre
 * su hoja. Al final, los días de aviso de los ajustes.
 *
 * Un lote vencido se marca con texto e icono, nunca solo con color, y nunca bloquea la venta. El
 * valor por vencer se oculta en modo discreto; cantidades y fechas siguen a la vista. En una
 * ventana ancha la lista va en una columna de 600 dp, como todo lo que se lee de arriba abajo.
 */
@Composable
fun LotsScreen(
    uiState: LotsUiState,
    onAction: (LotsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.lot_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(LotsAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = { ScreenHelpLink(HelpTopic.LOTS) },
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val width = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth()
                when (val content = uiState.content) {
                    LotsContent.Loading -> LotsSkeleton(width)

                    LotsContent.ReadError -> EmptyState(
                        icon = R.drawable.ic_error,
                        title = stringResource(R.string.state_error_read),
                    ) {
                        InlineTextButton(
                            stringResource(R.string.action_retry),
                            { onAction(LotsAction.Retry) },
                        )
                    }

                    is LotsContent.Ready -> if (content.ready.hasLots) {
                        LotList(content.ready, uiState.filter, onAction, width)
                    } else {
                        EmptyState(
                            icon = R.drawable.ic_inventory_2,
                            title = stringResource(R.string.lot_empty_title),
                            body = stringResource(R.string.lot_empty_body),
                        ) {
                            InlineTextButton(
                                stringResource(R.string.lot_empty_action),
                                { onAction(LotsAction.OpenProducts) },
                            )
                        }
                    }
                }
            }
        }
        AppSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
    val ready = (uiState.content as? LotsContent.Ready)?.ready
    when (val sheet = uiState.sheet) {
        is LotSheet.Detail -> uiState.lot(sheet.lotId)?.let { lot ->
            LotDetailSheet(lot, sheet, ready?.showsLocations == true, onAction)
        }

        is LotSheet.FixExpiry -> FixExpirySheet(sheet, ready, onAction)

        is LotSheet.FixCode -> FixCodeSheet(sheet, onAction)

        is LotSheet.Notice -> NoticeSheet(sheet, onAction)

        null -> Unit
    }
    val message = uiState.message?.let {
        when (it) {
            LotsMessage.LOT_SAVED -> stringResource(R.string.lot_saved)
            LotsMessage.NOTICE_SAVED -> stringResource(R.string.lot_notice_saved)
        }
    }
    LaunchedEffect(uiState.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onAction(LotsAction.MessageShown)
        }
    }
}

@Composable
private fun LotList(
    ready: LotsReady,
    filter: LotFilter,
    onAction: (LotsAction) -> Unit,
    modifier: Modifier,
) {
    val spacing = AppTheme.spacing
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = spacing.s24)) {
        item(key = "tiles") {
            Row(
                Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                CountTile(
                    stringResource(R.string.lot_expiring_label),
                    ready.expiringCount,
                    Modifier.weight(1f),
                    attention = ready.expiringCount > 0,
                )
                StatTile(
                    stringResource(R.string.lot_expiring_value_label),
                    ready.expiringValue,
                    StatTileVariant.CARD,
                    Modifier.weight(1f),
                    sensitive = true,
                )
            }
        }
        item(key = "filters") {
            CompactChipRow(
                options = LotFilter.entries,
                label = { stringResource(filterLabel(it)) },
                selected = setOf(filter),
                onToggle = { onAction(LotsAction.SelectFilter(it)) },
                multiple = false,
                modifier = Modifier.padding(horizontal = spacing.s16),
            )
        }
        if (ready.groups.isEmpty()) {
            item(key = "none") {
                EmptyStateInline(
                    title = stringResource(emptyLabel(filter)),
                    modifier = Modifier.padding(horizontal = spacing.s16),
                )
            }
        }
        ready.groups.forEach { group ->
            stickyHeader(key = "product-${group.productId}") {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Text(
                        group.productName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppTheme.sizes.touchTarget)
                            .padding(horizontal = spacing.s16, vertical = spacing.s12)
                            .semantics { heading() },
                    )
                }
            }
            items(group.lots, key = { "lot-${it.lotId}" }) { lot ->
                LotListRow(lot, ready.showsLocations) { onAction(LotsAction.OpenLot(lot.lotId)) }
            }
        }
        item(key = "notice") {
            SettingsRow(
                title = pluralStringResource(
                    R.plurals.lot_notice_row,
                    ready.noticeDays,
                    ready.noticeDays,
                ),
                action = SettingsRowAction.Navigation { onAction(LotsAction.OpenNotice) },
                modifier = Modifier.padding(top = spacing.s8),
            )
        }
    }
}

/**
 * La fila de un lote en la lista. Con más de una ubicación dice dónde está: el nombre si está en
 * una sola, o en cuántas. El lote sin asignar dice por qué existe en lugar del vencimiento.
 */
@Composable
internal fun LotListRow(lot: LotItem, showsLocations: Boolean, onClick: () -> Unit) {
    val place = when {
        !showsLocations || lot.places.isEmpty() -> null

        lot.places.size == 1 -> locationName(lot.places.single().location)

        else -> pluralStringResource(
            R.plurals.lot_locations_count,
            lot.places.size,
            lot.places.size,
        )
    }
    LotRow(
        code = lot.code,
        expiry = lot.expiry,
        onHand = lot.onHand,
        unit = lotUnit(lot),
        onClick = onClick,
        name = if (lot.unassigned) stringResource(R.string.lot_unassigned_name) else null,
        location = place,
        note = if (lot.unassigned && lot.onHand < QuantityMilli.ZERO) {
            stringResource(R.string.lot_unassigned_help)
        } else {
            null
        },
    )
}

/** La unidad de un lote, o `und` si su unidad ya no se pudo leer. */
@Composable
internal fun lotUnit(lot: LotItem): UnitLabel =
    lot.unit?.let { unitLabel(it) } ?: UnitLabel(stringResource(R.string.seed_units_unit), false)

@Composable
private fun LotsSkeleton(modifier: Modifier) {
    val spacing = AppTheme.spacing
    DelayedSkeleton {
        Column(
            modifier.padding(spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s12)) {
                CountTile("", 0, Modifier.weight(1f), loading = true)
                StatTile("", null, StatTileVariant.CARD, Modifier.weight(1f), loading = true)
            }
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

private fun filterLabel(filter: LotFilter): Int = when (filter) {
    LotFilter.EXPIRING -> R.string.lot_filter_expiring
    LotFilter.EXPIRED -> R.string.lot_filter_expired
    LotFilter.ALL -> R.string.lot_filter_all
    LotFilter.UNASSIGNED -> R.string.lot_filter_unassigned
}

private fun emptyLabel(filter: LotFilter): Int = when (filter) {
    LotFilter.EXPIRING -> R.string.lot_none_expiring

    LotFilter.EXPIRED -> R.string.lot_none_expired

    // Con algún lote, `Todos` nunca queda vacío; si pasara, dice lo mismo que la pantalla vacía.
    LotFilter.ALL -> R.string.lot_empty_title

    LotFilter.UNASSIGNED -> R.string.lot_none_unassigned
}

/** Las filas del esqueleto, como pide la pantalla. */
private const val SKELETON_ROWS = 6
