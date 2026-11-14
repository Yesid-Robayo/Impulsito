package io.github.yesidrobayo.shopledger.ui.location

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationSummary
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationsOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockTransferSummary
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
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
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.stock.inventoryRowData
import io.github.yesidrobayo.shopledger.ui.stock.locationName
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.unit.EXPANDED_WIDTH

/**
 * La pantalla de ubicaciones y transferencias, sin ViewModel: dibuja [uiState] y avisa cada
 * acción.
 *
 * Arriba se elige entre `Ubicaciones` y `Transferencias`. En una ventana angosta, tocar una
 * ubicación abre su stock en lugar de la lista; en una `Expanded`, la lista queda a la izquierda y
 * el stock de la elegida a la derecha. Nada aquí muestra costos ni valores: solo cantidades.
 */
@Composable
fun LocationsScreen(
    uiState: LocationsUiState,
    onAction: (LocationsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val names = locationNames(uiState.content)
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val expanded = maxWidth >= EXPANDED_WIDTH
        val openDetail = uiState.tab == LocationsTab.LOCATIONS && uiState.selectedId != null
        // En una ventana angosta, atrás desde el stock de una ubicación vuelve a la lista.
        BackHandler(enabled = openDetail && !expanded) { onAction(LocationsAction.Select(null)) }
        if (openDetail && !expanded) {
            DetailPane(uiState, onAction, withBack = true)
        } else {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.location_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(LocationsAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                    titleStyle = MaterialTheme.typography.titleLarge,
                )
                SegmentedTabs(
                    options = LocationsTab.entries.map { stringResource(tabLabel(it)) },
                    selectedIndex = LocationsTab.entries.indexOf(uiState.tab),
                    onSelect = { onAction(LocationsAction.SelectTab(LocationsTab.entries[it])) },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .padding(
                            horizontal = AppTheme.spacing.s16,
                            vertical = AppTheme.spacing.s8,
                        ),
                )
                when {
                    uiState.tab == LocationsTab.TRANSFERS ->
                        TransfersPane(uiState, names, onAction, Modifier.weight(1f))

                    expanded -> Row(Modifier.weight(1f).fillMaxWidth()) {
                        LocationsPane(uiState, onAction, Modifier.weight(1f))
                        VerticalDivider(
                            thickness = AppTheme.sizes.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Box(Modifier.weight(DETAIL_WEIGHT).fillMaxHeight()) {
                            if (uiState.selectedId != null) {
                                DetailPane(uiState, onAction, withBack = false)
                            }
                        }
                    }

                    else -> Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        LocationsPane(
                            uiState,
                            onAction,
                            Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth(),
                        )
                    }
                }
            }
        }
        AppSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = TonalFabDefaults.listEndPadding),
        )
        // En una ventana ancha siempre hay una ubicación abierta a la derecha: la primera.
        val first = (uiState.content as? LocationsContent.Ready)?.overview?.active?.firstOrNull()
        LaunchedEffect(expanded, uiState.selectedId, first?.location?.id) {
            if (expanded && uiState.selectedId == null && first != null) {
                onAction(LocationsAction.Select(first.location.id))
            }
        }
    }
    uiState.editor?.let { LocationEditorSheet(it, onAction) }
    uiState.transfer?.let { TransferDetailSheet(it, names, onAction) }
    uiState.dialog?.let { LocationsDialogView(it, onAction) }
    val message = uiState.message?.let { messageText(it) }
    LaunchedEffect(uiState.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onAction(LocationsAction.MessageShown)
        }
    }
}

/** El segmento de ubicaciones: las vigentes, las archivadas aparte y `Nueva ubicación`. */
@Composable
private fun LocationsPane(
    uiState: LocationsUiState,
    onAction: (LocationsAction) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxHeight()) {
        when (val content = uiState.content) {
            LocationsContent.Loading -> SkeletonRows(LOCATION_SKELETON_ROWS)

            LocationsContent.ReadError -> ReadFailed(onAction)

            is LocationsContent.Ready ->
                LocationList(content.overview, uiState.selectedId, onAction)
        }
        TonalFab(
            text = stringResource(R.string.location_new_action),
            onClick = { onAction(LocationsAction.NewLocation) },
            modifier = Modifier.align(Alignment.BottomEnd).keepsMessagesAbove(),
        )
    }
}

@Composable
private fun LocationList(
    overview: LocationsOverview,
    selectedId: Long?,
    onAction: (LocationsAction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
    ) {
        items(overview.active, key = { "active-${it.location.id}" }) { summary ->
            LocationRow(summary.location, summary, selectedId, onAction)
        }
        if (!overview.showsLocations) {
            // Con un solo lugar se explica para qué sirve una segunda ubicación.
            item(key = "single") {
                EmptyStateInline(
                    title = stringResource(R.string.location_empty_title),
                    body = stringResource(R.string.location_empty_body),
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                    action = {
                        InlineTextButton(
                            stringResource(R.string.location_new_action),
                            { onAction(LocationsAction.NewLocation) },
                        )
                    },
                )
            }
        }
        if (overview.archived.isNotEmpty()) {
            item(key = "archived-header") {
                SectionHeader(stringResource(R.string.location_archived_group))
            }
            items(overview.archived, key = { "archived-${it.id}" }) { location ->
                LocationRow(location, null, selectedId, onAction)
            }
        }
    }
}

/**
 * Una ubicación: su nombre, su tipo y cuántos productos tienen stock allí; una archivada lleva su
 * marca en lugar de la cuenta. Es un solo foco, que se lee entero, y tocarla abre su stock.
 */
@Composable
private fun LocationRow(
    location: Location,
    summary: LocationSummary?,
    selectedId: Long?,
    onAction: (LocationsAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val name = locationName(location)
    val kind = stringResource(kindLabel(location.details.kind))
    val detail = if (summary != null) {
        pluralStringResource(
            R.plurals.location_products_with_stock,
            summary.stockedProducts,
            summary.stockedProducts,
        )
    } else {
        stringResource(R.string.status_archived)
    }
    val isSelected = location.id == selectedId
    val background = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surface
    }
    Column(Modifier.fillMaxWidth().background(background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .clickable { onAction(LocationsAction.Select(location.id)) }
                .clearAndSetSemantics {
                    contentDescription = listOf(name, kind, detail).joinToString()
                    role = Role.Button
                    selected = isSelected
                }
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    kind,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StatusTag(detail)
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * El stock de la ubicación abierta: su nombre arriba, con `Renombrar` y `Archivar` o `Reactivar`
 * en el menú, la búsqueda y una fila por producto con lo que hay allí. La inicial nunca se
 * archiva, así que su menú no lo ofrece. [withBack] lleva la flecha que vuelve a la lista.
 */
@Composable
private fun DetailPane(
    uiState: LocationsUiState,
    onAction: (LocationsAction) -> Unit,
    withBack: Boolean,
) {
    val detail = uiState.detail
    val location = (detail as? LocationDetailContent.Ready)?.location
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = location?.let { locationName(it) }.orEmpty(),
            navigation = if (withBack) TopBarNavigation.BACK else TopBarNavigation.NONE,
            onNavigate = { onAction(LocationsAction.Select(null)) },
            navigationLabel = stringResource(R.string.a11y_back),
            titleStyle = MaterialTheme.typography.titleLarge,
            actions = { if (location != null) DetailMenu(location, onAction) },
        )
        when (detail) {
            LocationDetailContent.Loading -> SkeletonRows(DETAIL_SKELETON_ROWS)

            LocationDetailContent.Missing -> EmptyState(
                icon = R.drawable.ic_location_on,
                title = stringResource(R.string.state_error_read),
            )

            is LocationDetailContent.Ready -> DetailContent(uiState, detail, onAction)
        }
    }
}

@Composable
private fun DetailMenu(location: Location, onAction: (LocationsAction) -> Unit) {
    val actions = buildList {
        add(
            OverflowAction(stringResource(R.string.action_rename), {
                onAction(LocationsAction.RenameLocation)
            }),
        )
        when {
            location.isArchived -> add(
                OverflowAction(stringResource(R.string.action_reactivate), {
                    onAction(LocationsAction.Reactivate)
                }),
            )

            !location.isInitial -> add(
                OverflowAction(stringResource(R.string.action_archive), {
                    onAction(LocationsAction.AskArchive)
                }),
            )
        }
    }
    OverflowMenu(actions)
}

@Composable
private fun DetailContent(
    uiState: LocationsUiState,
    detail: LocationDetailContent.Ready,
    onAction: (LocationsAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxSize()) {
        if (uiState.archiveBlocked) {
            NoticeCard(
                text = stringResource(R.string.location_error_archive_with_stock),
                variant = NoticeVariant.WARNING,
                modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                actionLabel = stringResource(R.string.location_new_transfer_action).takeIf {
                    uiState.canTransfer
                },
                onAction = { onAction(LocationsAction.NewTransfer(detail.location.id)) },
            )
        }
        SearchField(
            query = uiState.detailQuery,
            onQueryChange = { onAction(LocationsAction.DetailQuery(it)) },
            placeholder = stringResource(R.string.sale_search_hint),
            modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        )
        when {
            detail.items.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                items(detail.items, key = { it.id }) { item -> ProductRow(inventoryRowData(item)) }
            }

            uiState.detailQuery.isNotBlank() -> EmptyStateInline(
                title = stringResource(R.string.empty_search_title, uiState.detailQuery.trim()),
                modifier = Modifier.padding(spacing.s16),
            )

            else -> EmptyStateInline(
                title = pluralStringResource(R.plurals.location_products_with_stock, 0, 0),
                modifier = Modifier.padding(spacing.s16),
            )
        }
    }
}

/**
 * Las transferencias por día, de la más nueva a la más antigua, con el tramo siguiente pedido al
 * llegar al final. `Nueva transferencia` solo existe con dos ubicaciones o más.
 */
@Composable
private fun TransfersPane(
    uiState: LocationsUiState,
    names: Map<Long, String>,
    onAction: (LocationsAction) -> Unit,
    modifier: Modifier,
) {
    // Una columna de lectura, centrada en una ventana ancha.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        TransfersBody(uiState, names, onAction)
    }
}

@Composable
private fun TransfersBody(
    uiState: LocationsUiState,
    names: Map<Long, String>,
    onAction: (LocationsAction) -> Unit,
) {
    Box(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize()) {
        when (val content = uiState.transfers) {
            TransfersContent.Loading -> SkeletonRows(LOCATION_SKELETON_ROWS)

            TransfersContent.ReadError -> ReadFailed(onAction)

            TransfersContent.UnknownZone -> EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.stats_zone_unknown_title),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.stats_zone_unknown_action),
                    onClick = { onAction(LocationsAction.ChooseZone) },
                    variant = ActionVariant.TONAL,
                )
            }

            is TransfersContent.Ready -> if (content.days.isEmpty() && !content.hasOlder) {
                EmptyState(
                    icon = R.drawable.ic_location_on,
                    title = stringResource(R.string.location_transfer_empty_title),
                )
            } else {
                TransferList(content, names, onAction)
            }
        }
        if (uiState.canTransfer) {
            TonalFab(
                text = stringResource(R.string.location_new_transfer_action),
                onClick = { onAction(LocationsAction.NewTransfer()) },
                modifier = Modifier.align(Alignment.BottomEnd).keepsMessagesAbove(),
            )
        }
    }
}

@Composable
private fun TransferList(
    content: TransfersContent.Ready,
    names: Map<Long, String>,
    onAction: (LocationsAction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
    ) {
        content.days.forEach { day ->
            item(key = "day-${day.date}") { DayHeader(day) }
            items(day.transfers, key = { "transfer-${it.transfer.id}" }) { summary ->
                TransferRow(summary, names, onAction)
            }
        }
        if (content.hasOlder) {
            item(key = "older") {
                // Al llegar aquí se pide el tramo siguiente; mientras llega, un esqueleto.
                LaunchedEffect(content.days.size, content.loadingOlder) {
                    if (!content.loadingOlder) onAction(LocationsAction.LoadOlderTransfers)
                }
                SkeletonBlock(
                    Modifier
                        .fillMaxWidth()
                        .padding(AppTheme.spacing.s16)
                        .heightIn(min = AppTheme.sizes.listRowSingle),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(day: TransferDay) {
    val formats = LocalAppFormats.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Text(
            formats.date.weekdayDayMonth(day.date),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s12)
                .semantics { heading() },
        )
    }
}

/**
 * Una transferencia: `Desde a Hacia`, cuántos productos movió y, si se anuló, su marca. Un solo
 * foco, que se lee entero, y tocarla abre su hoja.
 */
@Composable
private fun TransferRow(
    summary: StockTransferSummary,
    names: Map<Long, String>,
    onAction: (LocationsAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val content = summary.transfer.content
    val from = names[content.fromLocationId].orEmpty()
    val to = names[content.toLocationId].orEmpty()
    val products = pluralStringResource(
        R.plurals.location_transfer_products,
        summary.productCount,
        summary.productCount,
    )
    val voided = summary.transfer.voiding != null
    val voidedText = stringResource(R.string.status_voided)
    val reading = stringResource(R.string.a11y_transfer_row, from, to, products)
        .let { if (voided) listOf(it, voidedText).joinToString() else it }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .clickable { onAction(LocationsAction.OpenTransfer(summary.transfer.id)) }
                .clearAndSetSemantics {
                    contentDescription = reading
                    role = Role.Button
                }
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(
                    stringResource(R.string.location_transfer_row, from, to),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (voided) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    products,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (voided) StatusTag(voidedText)
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun SkeletonRows(count: Int) {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(count) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle))
            }
        }
    }
}

/** La lectura falló: se dice, y `Reintentar` vuelve a leer. Nada de lo guardado se tocó. */
@Composable
private fun ReadFailed(onAction: (LocationsAction) -> Unit) {
    EmptyState(icon = R.drawable.ic_error, title = stringResource(R.string.state_error_read)) {
        InlineTextButton(
            stringResource(R.string.action_retry),
            { onAction(LocationsAction.Retry) },
        )
    }
}

/** El nombre visible de cada ubicación leída, vigente o archivada, por id. */
@Composable
internal fun locationNames(content: LocationsContent): Map<Long, String> {
    val overview = (content as? LocationsContent.Ready)?.overview ?: return emptyMap()
    val all = overview.active.map { it.location } + overview.archived
    return all.associate { it.id to locationName(it) }
}

internal fun kindLabel(kind: LocationKind): Int = when (kind) {
    LocationKind.STORE -> R.string.location_kind_store
    LocationKind.WAREHOUSE -> R.string.location_kind_warehouse
    LocationKind.STALL -> R.string.location_kind_stall
    LocationKind.OTHER -> R.string.location_kind_other
}

private fun tabLabel(tab: LocationsTab): Int = when (tab) {
    LocationsTab.LOCATIONS -> R.string.location_tab_locations
    LocationsTab.TRANSFERS -> R.string.location_tab_transfers
}

@Composable
private fun messageText(message: LocationsMessage): String = when (message) {
    LocationsMessage.LocationSaved -> stringResource(R.string.location_saved)
    LocationsMessage.TransferVoided -> stringResource(R.string.location_transfer_voided)
    LocationsMessage.NameTaken -> stringResource(R.string.location_error_name_repeated)
    is LocationsMessage.Failed -> stringResource(saveErrorText(message.outOfSpace))
}

private const val LOCATION_SKELETON_ROWS = 4
private const val DETAIL_SKELETON_ROWS = 6

/** El stock de la ubicación ocupa algo más que la lista, en una ventana ancha. */
private const val DETAIL_WEIGHT = 1.4f
