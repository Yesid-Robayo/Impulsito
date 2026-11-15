package io.github.yesidrobayo.shopledger.ui.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.rule.document.FiscalInputs
import io.github.yesidrobayo.shopledger.domain.rule.document.ReferenceInput
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PeriodSelector
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SelectableDocumentRow
import io.github.yesidrobayo.shopledger.ui.components.SelectableDocumentRowData
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.canGoForward
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Pendientes de facturar: qué ventas falta registrar en el sistema que la persona use fuera de la
 * app. Es un control propio: no genera, envía ni valida ningún documento, y lo dice arriba, fijo.
 *
 * - Arriba, el mes con sus flechas, el aviso, las tres marcas en chips y el resumen de la marca
 *   elegida: cuántas ventas y su total, que se oculta en modo discreto por ser de un período.
 * - Cada venta va en una [SelectableDocumentRow]: tocarla abre la hoja `Registro externo`;
 *   mantenerla presionada empieza a elegir varias, y la barra cambia a marcarlas juntas.
 * - Abajo, `Compartir resumen` y, con enlace, `Abrir enlace`, que lo abre otra app.
 * - En una ventana ancha, la hoja va como panel fijo a la derecha.
 *
 * Nada espera a la red. Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun FiscalScreen(
    uiState: FiscalUiState,
    onAction: (FiscalAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = uiState.selecting) { onAction(FiscalAction.ClearSelection) }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // El ancho del bloque, leído aquí: dentro de una fila o columna no se alcanza.
            val boxWidth = maxWidth
            val wide = maxWidth >= EXPANDED_WIDTH
            Column(Modifier.fillMaxSize()) {
                TopBar(uiState, onAction)
                Row(Modifier.weight(1f)) {
                    val listWidth = if (wide) {
                        Modifier.width(minOf(AppTheme.sizes.maxContentWidth, boxWidth * 2 / 5))
                    } else {
                        Modifier.weight(1f)
                    }
                    Column(listWidth.fillMaxHeight()) {
                        SalesList(uiState, onAction, Modifier.weight(1f))
                        BottomActions(uiState, onAction)
                    }
                    if (wide) {
                        VerticalDivider(
                            thickness = AppTheme.sizes.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .consumeWindowInsets(WindowInsets.statusBars),
                        ) {
                            uiState.sheet?.let { SidePanel(it, onAction) }
                        }
                    }
                }
            }
            if (!wide) uiState.sheet?.let { MarkSheet(it, onAction) }
        }
    }
    uiState.linkSheet?.let { LinkSheet(it, onAction) }
}

/** Atrás y el título; al elegir varias, cerrar y cuántas van, que TalkBack anuncia sin cortar. */
@Composable
private fun TopBar(uiState: FiscalUiState, onAction: (FiscalAction) -> Unit) {
    val count = uiState.selection.size
    if (uiState.selecting) {
        AppTopBar(
            title = pluralStringResource(R.plurals.a11y_doc_selection_count, count, count),
            navigation = TopBarNavigation.CLOSE,
            onNavigate = { onAction(FiscalAction.ClearSelection) },
            navigationLabel = stringResource(R.string.a11y_close),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    } else {
        AppTopBar(
            title = stringResource(R.string.fiscal_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = { onAction(FiscalAction.Back) },
            navigationLabel = stringResource(R.string.a11y_back),
            actions = {
                ScreenHelpLink(HelpTopic.NOT_ACCOUNTING)
                OverflowMenu(
                    listOf(
                        OverflowAction(stringResource(R.string.fiscal_configure_link_action), {
                            onAction(FiscalAction.ConfigureLink)
                        }),
                    ),
                )
            },
        )
    }
}

/**
 * El mes, el aviso fijo, las marcas y el resumen van en la misma lista que las ventas: con letra
 * grande, se desplazan con ella y la lista no queda sin espacio.
 */
@Composable
private fun SalesList(
    uiState: FiscalUiState,
    onAction: (FiscalAction) -> Unit,
    modifier: Modifier,
) {
    val spacing = AppTheme.spacing
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = spacing.s16)) {
        item(key = HEADER, contentType = HEADER) {
            Column {
                Header(uiState, onAction)
                HorizontalDivider(
                    thickness = AppTheme.sizes.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        when (val content = uiState.content) {
            FiscalContent.Loading -> item(key = SKELETON) { DelayedSkeleton { ListSkeleton() } }

            FiscalContent.ReadError -> item(key = STATE) {
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(FiscalAction.Retry) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            FiscalContent.UnknownZone -> item(key = STATE) {
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.fiscal_zone_unknown_title),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.stats_zone_unknown_action),
                        onClick = { onAction(FiscalAction.ChooseZone) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            is FiscalContent.Sales -> if (content.sales.isEmpty()) {
                item(key = STATE) { EmptyList(uiState.filter, content.hasMarks, onAction) }
            } else {
                items(content.sales, key = { it.saleId }, contentType = { ROW }) { sale ->
                    SelectableDocumentRow(
                        data = rowData(sale),
                        onClick = { onAction(FiscalAction.OpenSale(sale.saleId)) },
                        onLongClick = { onAction(FiscalAction.ToggleSelection(sale.saleId)) },
                        selecting = uiState.selecting,
                        selected = sale.saleId in uiState.selection ||
                            sale.saleId == uiState.sheet?.saleId,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(uiState: FiscalUiState, onAction: (FiscalAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        val month = uiState.month
        val today = uiState.today
        if (month != null && today != null) {
            val period = ReportPeriod.Month(month)
            PeriodSelector(
                period = period,
                onPrevious = { onAction(FiscalAction.PreviousMonth) },
                onNext = { onAction(FiscalAction.NextMonth) },
                showNext = canGoForward(period, today),
                today = today,
            )
        }
        Text(
            stringResource(R.string.fiscal_disclaimer, stringResource(R.string.app_name)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CompactChipRow(
            options = FiscalMarkStatus.entries,
            label = { filterLabel(it) },
            selected = setOf(uiState.filter),
            onToggle = { onAction(FiscalAction.FilterChange(it)) },
            multiple = false,
        )
        Summary(uiState.content)
    }
}

/** Cuántas ventas tiene la marca elegida en el mes y su total, oculto en modo discreto. */
@Composable
private fun Summary(content: FiscalContent) {
    val sales = content as? FiscalContent.Sales
    val loading = content == FiscalContent.Loading
    if (sales == null && !loading) return
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        val count = sales?.sales?.size ?: 0
        val number = LocalAppFormats.current.quantity.format(
            QuantityMilli.ofUnits(count.toLong()),
            allowsFraction = false,
        )
        Text(
            stringResource(R.string.fiscal_summary_count, number),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        MoneyText(sales?.total, MoneySize.ROW, sensitive = true, loading = loading)
    }
}

/**
 * La lista vacía. Sin ninguna marca todavía, dice para qué sirve la pantalla. Sin pendientes pero
 * con ventas ya marcadas, sugiere mirar las registradas aparte.
 */
@Composable
private fun EmptyList(
    filter: FiscalMarkStatus,
    hasMarks: Boolean,
    onAction: (FiscalAction) -> Unit,
) {
    when (filter) {
        FiscalMarkStatus.PENDING -> EmptyState(
            icon = R.drawable.ic_fact_check,
            title = stringResource(R.string.fiscal_empty_title),
            body = if (hasMarks) null else stringResource(R.string.fiscal_first_use_body),
        ) {
            if (hasMarks) {
                PrimaryActionButton(
                    text = stringResource(R.string.fiscal_filter_recorded),
                    onClick = { onAction(FiscalAction.FilterChange(FiscalMarkStatus.RECORDED)) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        FiscalMarkStatus.RECORDED, FiscalMarkStatus.NOT_NEEDED -> EmptyState(
            icon = R.drawable.ic_search,
            title = stringResource(R.string.fiscal_filter_empty),
        )
    }
}

/**
 * La barra fija de abajo. Sin elegir varias: `Compartir resumen` y, con enlace, `Abrir enlace`.
 * Eligiendo: marcar todas juntas, sin referencia, como registradas o como no necesarias.
 */
@Composable
private fun BottomActions(uiState: FiscalUiState, onAction: (FiscalAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(spacing.s16)
            .keepsMessagesAbove(),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        if (uiState.selecting) {
            val count = uiState.selection.size
            val working = uiState.bulkStatus == FiscalSheetStatus.WORKING
            if (uiState.bulkStatus == FiscalSheetStatus.FAILED) {
                NoticeCard(stringResource(R.string.fiscal_error_save), NoticeVariant.ERROR)
            }
            PrimaryActionButton(
                text = pluralStringResource(
                    R.plurals.fiscal_mark_many_recorded_action,
                    count,
                    count,
                ),
                onClick = { onAction(FiscalAction.MarkSelection(FiscalMarkStatus.RECORDED)) },
                variant = ActionVariant.PRIMARY,
                state = if (working) ActionState.WORKING else ActionState.IDLE,
            )
            PrimaryActionButton(
                text = pluralStringResource(
                    R.plurals.fiscal_mark_many_not_needed_action,
                    count,
                    count,
                ),
                onClick = { onAction(FiscalAction.MarkSelection(FiscalMarkStatus.NOT_NEEDED)) },
                variant = ActionVariant.OUTLINED,
                enabled = !working,
            )
        } else {
            PrimaryActionButton(
                text = stringResource(R.string.fiscal_share_summary_action),
                onClick = { onAction(FiscalAction.ShareSummary) },
                variant = ActionVariant.PRIMARY,
                enabled = uiState.content is FiscalContent.Sales,
            )
            if (uiState.portalUrl != null) {
                PrimaryActionButton(
                    text = stringResource(R.string.fiscal_open_link_action),
                    onClick = { onAction(FiscalAction.OpenLink) },
                    variant = ActionVariant.TONAL,
                )
            }
        }
    }
}

/** La hoja `Registro externo` de una venta, en una ventana estrecha. */
@Composable
private fun MarkSheet(sheet: FiscalMarkSheet, onAction: (FiscalAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(FiscalAction.CloseSheet) },
        title = stringResource(R.string.fiscal_mark_title),
        action = { MarkActions(sheet, onAction) },
    ) { MarkFields(sheet, onAction) }
}

/** La misma hoja como panel fijo a la derecha de la lista, con sus botones abajo. */
@Composable
private fun SidePanel(sheet: FiscalMarkSheet, onAction: (FiscalAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Text(
                stringResource(R.string.fiscal_mark_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            MarkFields(sheet, onAction)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(spacing.s16)
                .keepsMessagesAbove(),
        ) { MarkActions(sheet, onAction) }
    }
}

/**
 * La venta, con su fecha, su total y `Ver venta`, la ayuda si tiene una devolución, y la
 * referencia externa, opcional, de hasta 60 caracteres.
 */
@Composable
private fun ColumnScope.MarkFields(sheet: FiscalMarkSheet, onAction: (FiscalAction) -> Unit) {
    val formats = LocalAppFormats.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val sale = sheet.sale
    if (sale == null) {
        if (!sheet.unavailable) {
            SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.listRow))
        }
    } else {
        Text(saleTitle(sale.saleId), style = MaterialTheme.typography.titleMedium)
        MoneyText(sale.total, MoneySize.LARGE)
        Text(
            formats.date.longDate(sale.businessDate),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        Text(
            sale.customerName ?: stringResource(R.string.fiscal_no_customer),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        InlineTextButton(
            text = stringResource(R.string.fiscal_view_sale_action),
            onClick = { onAction(FiscalAction.ViewSale(sale.saleId)) },
        )
        if (sale.hasReturn) {
            StatusTag(stringResource(R.string.fiscal_return_tag))
            Text(
                stringResource(R.string.fiscal_return_help),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    val tooLong = sheet.referenceTooLong ||
        FiscalInputs.reference(sheet.reference) == ReferenceInput.TooLong
    AppTextField(
        value = sheet.reference,
        onValueChange = { onAction(FiscalAction.ReferenceChange(it)) },
        label = stringResource(R.string.fiscal_reference_label),
        error = if (tooLong) stringResource(R.string.fiscal_error_reference) else null,
        imeAction = ImeAction.Done,
        capitalization = KeyboardCapitalization.None,
        autoCorrect = false,
    )
    if (sheet.status == FiscalSheetStatus.FAILED || sheet.unavailable) {
        NoticeCard(stringResource(R.string.fiscal_error_save), NoticeVariant.ERROR)
    }
}

/**
 * `Registrada aparte` es la acción principal; `No aplica` la alternativa; y, si la venta ya tiene
 * otra marca, `Volver a pendiente`.
 */
@Composable
private fun MarkActions(sheet: FiscalMarkSheet, onAction: (FiscalAction) -> Unit) {
    val working = sheet.status == FiscalSheetStatus.WORKING
    val enabled = sheet.sale != null && !sheet.unavailable && !working
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        PrimaryActionButton(
            text = stringResource(R.string.fiscal_mark_recorded_action),
            onClick = { onAction(FiscalAction.MarkSale(FiscalMarkStatus.RECORDED)) },
            variant = ActionVariant.PRIMARY,
            enabled = sheet.sale != null && !sheet.unavailable,
            state = if (working) ActionState.WORKING else ActionState.IDLE,
        )
        PrimaryActionButton(
            text = stringResource(R.string.fiscal_mark_not_needed_action),
            onClick = { onAction(FiscalAction.MarkSale(FiscalMarkStatus.NOT_NEEDED)) },
            variant = ActionVariant.OUTLINED,
            enabled = enabled,
        )
        if (sheet.sale?.status != null && sheet.sale.status != FiscalMarkStatus.PENDING) {
            PrimaryActionButton(
                text = stringResource(R.string.fiscal_mark_pending_action),
                onClick = { onAction(FiscalAction.MarkSale(FiscalMarkStatus.PENDING)) },
                variant = ActionVariant.TEXT,
                enabled = enabled,
            )
        }
    }
}

/**
 * `Configurar enlace`: la dirección del sistema externo, que solo se acepta con `https`. La ayuda
 * dice que la app no la visita.
 */
@Composable
private fun LinkSheet(sheet: FiscalLinkSheet, onAction: (FiscalAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(FiscalAction.CloseLinkSheet) },
        title = stringResource(R.string.fiscal_configure_link_action),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.fiscal_link_save_action),
                onClick = { onAction(FiscalAction.SaveLink) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.status == FiscalSheetStatus.WORKING) {
                    ActionState.WORKING
                } else {
                    ActionState.IDLE
                },
            )
        },
    ) {
        AppTextField(
            value = sheet.text,
            onValueChange = { onAction(FiscalAction.LinkChange(it)) },
            label = stringResource(R.string.fiscal_link_label),
            error = when (sheet.error) {
                FiscalLinkError.NOT_HTTPS -> stringResource(R.string.fiscal_error_link)
                FiscalLinkError.TOO_LONG -> stringResource(R.string.fiscal_error_link)
                null -> null
            },
            supportingText = stringResource(
                R.string.fiscal_link_help,
                stringResource(R.string.app_name),
            ),
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Uri,
            autoCorrect = false,
            keyboardActions = KeyboardActions(onDone = { onAction(FiscalAction.SaveLink) }),
        )
        if (sheet.status == FiscalSheetStatus.FAILED) {
            NoticeCard(stringResource(saveErrorText(outOfSpace = false)), NoticeVariant.ERROR)
        }
    }
}

/** La fila de una venta: número, fecha y cliente, total, referencia y si tiene devolución. */
@Composable
private fun rowData(sale: FiscalSale): SelectableDocumentRowData {
    val date = LocalAppFormats.current.date.dayMonth(sale.businessDate)
    val customer = sale.customerName ?: stringResource(R.string.fiscal_no_customer)
    return SelectableDocumentRowData(
        title = saleTitle(sale.saleId),
        detail = stringResource(R.string.fiscal_row_detail, date, customer),
        total = sale.total,
        status = statusLabel(sale.status),
        tags = listOfNotNull(stringResource(R.string.fiscal_return_tag).takeIf { sale.hasReturn }),
        note = sale.externalReference?.let { stringResource(R.string.fiscal_reference_row, it) },
    )
}

/** `Venta 1.204`, con el número en el formato del negocio. */
@Composable
internal fun saleTitle(saleId: Long): String {
    val number = LocalAppFormats.current.quantity.format(
        QuantityMilli.ofUnits(saleId),
        allowsFraction = false,
    )
    return stringResource(R.string.fiscal_sale_row, number)
}

@Composable
private fun filterLabel(status: FiscalMarkStatus): String = stringResource(
    when (status) {
        FiscalMarkStatus.PENDING -> R.string.fiscal_filter_pending
        FiscalMarkStatus.RECORDED -> R.string.fiscal_filter_recorded
        FiscalMarkStatus.NOT_NEEDED -> R.string.fiscal_filter_not_needed
    },
)

@Composable
internal fun statusLabel(status: FiscalMarkStatus): String = stringResource(
    when (status) {
        FiscalMarkStatus.PENDING -> R.string.fiscal_status_pending
        FiscalMarkStatus.RECORDED -> R.string.fiscal_status_recorded
        FiscalMarkStatus.NOT_NEEDED -> R.string.fiscal_status_not_needed
    },
)

@Composable
private fun ListSkeleton() {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.listRow))
        }
    }
}

private const val HEADER = "header"
private const val STATE = "state"
private const val SKELETON = "skeleton"
private const val ROW = "row"
private const val SKELETON_ROWS = 6
private val EXPANDED_WIDTH = 840.dp
