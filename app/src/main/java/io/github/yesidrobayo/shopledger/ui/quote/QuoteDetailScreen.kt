package io.github.yesidrobayo.shopledger.ui.quote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.sale.unitLabelOf
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El detalle de una cotización: total, cliente, fecha y vigencia, sus líneas tal como se
 * congelaron, y `Convertir en venta` abajo mientras está abierta.
 *
 * - Convertida, no tiene barra: `Ver venta` abre la venta que salió de ella.
 * - Cancelada, queda sin acciones.
 * - Vencida, sigue abierta con su etiqueta: convertirla avisa primero.
 * - Nunca muestra costo, margen ni ganancia: el modo discreto no cambia nada.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun QuoteDetailScreen(
    uiState: QuoteDetailUiState,
    onAction: (QuoteDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notices = remember { SnackbarHostState() }
    val convertFailed = stringResource(R.string.quote_error_convert)
    val cancelFailed = stringResource(R.string.state_error_save)
    val cancelled = stringResource(R.string.quote_cancelled)
    val shareFailed = stringResource(R.string.document_share_failed)
    LaunchedEffect(uiState.notice) {
        val notice = uiState.notice ?: return@LaunchedEffect
        onAction(QuoteDetailAction.NoticeShown)
        val (text, variant) = when (notice) {
            QuoteDetailNotice.CONVERT_FAILED -> convertFailed to SnackbarVariant.ERROR
            QuoteDetailNotice.CANCEL_FAILED -> cancelFailed to SnackbarVariant.ERROR
            QuoteDetailNotice.CANCELLED -> cancelled to SnackbarVariant.SUCCESS
            QuoteDetailNotice.SHARE_FAILED -> shareFailed to SnackbarVariant.ERROR
        }
        notices.showSnackbar(AppSnackbarVisuals(text, variant))
    }
    val quote = (uiState.content as? QuoteDetailContent.Ready)?.quote
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title =
                        quote?.let { quoteTitle(it.id) } ?: stringResource(R.string.quote_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(QuoteDetailAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                    actions = { if (quote != null) DetailMenu(quote, onAction) },
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    when (val content = uiState.content) {
                        QuoteDetailContent.Loading -> DelayedSkeleton { DetailSkeleton() }

                        QuoteDetailContent.Missing, QuoteDetailContent.Failed -> EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.state_error_read),
                        ) {
                            if (content == QuoteDetailContent.Failed) {
                                PrimaryActionButton(
                                    text = stringResource(R.string.action_retry),
                                    onClick = { onAction(QuoteDetailAction.Retry) },
                                    variant = ActionVariant.TONAL,
                                )
                            }
                        }

                        is QuoteDetailContent.Ready -> DetailBody(content.quote, onAction)
                    }
                }
                if (quote?.status == QuoteStatus.OPEN) ConvertBar(uiState.working, onAction)
            }
            AppSnackbarHost(
                notices,
                Modifier.align(Alignment.TopCenter).padding(top = AppTheme.sizes.topBar),
            )
        }
    }
    Dialogs(uiState, onAction)
}

/**
 * `Editar`, `Compartir cotización` y `Cancelar cotización`: editar y cancelar, solo abierta. Una
 * cancelada no se comparte: ya no es una oferta.
 */
@Composable
private fun DetailMenu(quote: QuoteDetail, onAction: (QuoteDetailAction) -> Unit) {
    val open = quote.status == QuoteStatus.OPEN
    val edit = stringResource(R.string.action_edit)
    val share = stringResource(R.string.quote_share_action)
    val cancel = stringResource(R.string.quote_cancel_action)
    OverflowMenu(
        buildList {
            if (open) add(OverflowAction(edit, { onAction(QuoteDetailAction.Edit) }))
            if (quote.status != QuoteStatus.CANCELLED) {
                add(OverflowAction(share, { onAction(QuoteDetailAction.Share) }))
            }
            if (open) add(OverflowAction(cancel, { onAction(QuoteDetailAction.AskCancel) }))
        },
    )
}

@Composable
private fun DetailBody(quote: QuoteDetail, onAction: (QuoteDetailAction) -> Unit) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    Column(
        Modifier
            .fillMaxSize()
            .wrapContentWidth()
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        Column(
            Modifier.padding(horizontal = spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            MoneyText(quote.totals.total, MoneySize.LARGE)
            Text(
                text = quote.customerName ?: stringResource(R.string.quote_no_customer),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formats.date.dayMonthYear(quote.businessDate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            quote.validUntil?.let {
                Text(
                    text = stringResource(
                        R.string.quote_valid_until_row,
                        formats.date.dayMonth(it),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            statusOf(quote)?.let { (text, tone) -> StatusTag(text = text, tone = tone) }
            if (quote.status == QuoteStatus.CONVERTED && quote.convertedSaleId != null) {
                InlineTextButton(
                    text = stringResource(R.string.quote_view_sale_action),
                    onClick = { onAction(QuoteDetailAction.ViewSale) },
                )
            }
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        quote.lines.forEach { line -> LineRow(line) }
        if (quote.totals.tax != Money.ZERO) {
            // Un foco: `Impuesto` y su monto.
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = spacing.s16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.term_tax),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                MoneyText(quote.totals.tax, MoneySize.ROW)
            }
        }
        quote.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = spacing.s16),
            )
        }
        Text(
            text = stringResource(R.string.quote_no_effect_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.s16),
        )
    }
}

/** Una línea cotizada: `cantidad × precio` y su total, con lo que se escribió al cotizar. */
@Composable
private fun LineRow(line: QuoteLineUi) {
    ProductRow(
        data = ProductRowData(
            name = line.name,
            stock = null,
            trailing = ProductRowTrailing.SaleLine(
                line.total,
                line.quantity,
                line.unitPrice,
                unitLabelOf(line.unit),
            ),
        ),
    )
}

/** La etiqueta del estado, si no está en su estado normal. */
@Composable
private fun statusOf(quote: QuoteDetail): Pair<String, StatusTone>? = when {
    quote.expired -> stringResource(R.string.quote_status_expired) to StatusTone.WARNING

    quote.status == QuoteStatus.CONVERTED ->
        stringResource(R.string.quote_status_converted) to StatusTone.NEUTRAL

    quote.status == QuoteStatus.CANCELLED ->
        stringResource(R.string.quote_status_cancelled) to StatusTone.NEUTRAL

    else -> null
}

/** La barra fija de abajo: `Convertir en venta`. Los mensajes se apoyan encima. */
@Composable
private fun ConvertBar(working: Boolean, onAction: (QuoteDetailAction) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().keepsMessagesAbove(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider(
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            PrimaryActionButton(
                text = stringResource(R.string.quote_convert_action),
                onClick = { onAction(QuoteDetailAction.Convert) },
                variant = ActionVariant.PRIMARY,
                state = if (working) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(AppTheme.spacing.s16),
            )
        }
    }
}

@Composable
private fun Dialogs(uiState: QuoteDetailUiState, onAction: (QuoteDetailAction) -> Unit) {
    val dismiss = { onAction(QuoteDetailAction.DismissDialog) }
    when (val dialog = uiState.dialog) {
        null -> Unit

        QuoteDetailDialog.ConfirmCancel -> ConfirmDialog(
            title = stringResource(R.string.quote_cancel_confirm_title),
            body = stringResource(R.string.quote_cancel_confirm_body),
            confirmLabel = stringResource(R.string.quote_cancel_action),
            onConfirm = { onAction(QuoteDetailAction.ConfirmCancel) },
            onDismiss = dismiss,
            variant = ConfirmVariant.DESTRUCTIVE,
        )

        is QuoteDetailDialog.Expired -> AppBottomSheet(
            onDismissRequest = dismiss,
            title = stringResource(
                R.string.quote_expired_notice,
                LocalAppFormats.current.date.dayMonth(dialog.date),
            ),
            action = {
                PrimaryActionButton(
                    text = stringResource(R.string.quote_convert_action),
                    onClick = { onAction(QuoteDetailAction.ConfirmExpired) },
                    variant = ActionVariant.TONAL,
                )
            },
        ) {
            Text(
                stringResource(R.string.quote_no_effect_note),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is QuoteDetailDialog.PriceChanged -> AppBottomSheet(
            onDismissRequest = dismiss,
            title = pluralStringResource(
                R.plurals.quote_price_changed_title,
                dialog.count,
                dialog.count,
            ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                PrimaryActionButton(
                    text = stringResource(R.string.quote_use_saved_price_action),
                    onClick = { onAction(QuoteDetailAction.ChoosePrices(false)) },
                    variant = ActionVariant.TONAL,
                )
                PrimaryActionButton(
                    text = stringResource(R.string.quote_use_current_price_action),
                    onClick = { onAction(QuoteDetailAction.ChoosePrices(true)) },
                    variant = ActionVariant.OUTLINED,
                )
            }
        }
    }
}

/** Tres bloques de esqueleto con la forma del detalle. */
@Composable
private fun DetailSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
        }
    }
}

private const val SKELETON_ROWS = 3
