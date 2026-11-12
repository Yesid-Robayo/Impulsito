package io.github.yesidrobayo.shopledger.ui.purchase

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
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetail
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetailLine
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.sale.reasonLabel
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El detalle de una compra: el total con la fecha, el proveedor y la referencia, las líneas, y al
 * final `Anular y corregir` y `Anular compra`. Una compra no se edita. Anulada, el total va tachado con el motivo y la
 * fecha, y ya no se ofrece anular.
 *
 * Los costos y el total van con modo discreto. Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun PurchaseDetailScreen(
    uiState: PurchaseDetailUiState,
    onAction: (PurchaseDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notices = remember { SnackbarHostState() }
    val voidedText = stringResource(R.string.confirm_purchase_voided)
    val ready = uiState as? PurchaseDetailUiState.Ready
    LaunchedEffect(ready?.justVoided) {
        if (ready?.justVoided != true) return@LaunchedEffect
        onAction(PurchaseDetailAction.VoidShown)
        notices.showSnackbar(AppSnackbarVisuals(voidedText, SnackbarVariant.SUCCESS))
    }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = titleOf(ready),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(PurchaseDetailAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                    titleStyle = MaterialTheme.typography.titleLarge,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = AppTheme.sizes.maxContentWidth),
                ) {
                    when (uiState) {
                        PurchaseDetailUiState.Loading -> DelayedSkeleton { DetailSkeleton() }

                        PurchaseDetailUiState.NotFound, PurchaseDetailUiState.ReadError ->
                            ReadProblem(uiState == PurchaseDetailUiState.ReadError, onAction)

                        is PurchaseDetailUiState.Ready -> DetailBody(uiState, onAction)
                    }
                }
            }
            AppSnackbarHost(
                notices,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
    if (ready?.sheet != null) VoidSheet(ready, onAction)
    if (ready?.confirmCorrect == true) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_correct_title),
            body = stringResource(R.string.dialog_correct_purchase_body),
            confirmLabel = stringResource(R.string.dialog_correct_action),
            onConfirm = { onAction(PurchaseDetailAction.ConfirmCorrect) },
            onDismiss = { onAction(PurchaseDetailAction.CloseCorrect) },
        )
    }
}

/** `Compra 318`, con el número en el formato del negocio; mientras se lee, `Compras`. */
@Composable
private fun titleOf(ready: PurchaseDetailUiState.Ready?): String {
    ready ?: return stringResource(R.string.records_tab_purchases)
    val number = LocalAppFormats.current.quantity.format(
        QuantityMilli.ofUnits(ready.detail.purchase.id),
        allowsFraction = false,
    )
    return stringResource(R.string.purchase_detail_title, number)
}

@Composable
private fun DetailBody(
    ready: PurchaseDetailUiState.Ready,
    onAction: (PurchaseDetailAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = spacing.s24),
    ) {
        item(key = TOTAL) {
            TotalBlock(ready.detail, Modifier.padding(spacing.s16))
        }
        items(ready.detail.lines, key = { it.line.id }) { line -> LineRow(line) }
        if (ready.canVoid) {
            item(key = ACTIONS) {
                Column(
                    Modifier.padding(spacing.s16).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(spacing.s8),
                ) {
                    // Corregir va sobre anular: es lo que se busca casi siempre tras un error.
                    PrimaryActionButton(
                        text = stringResource(R.string.dialog_correct_action),
                        onClick = { onAction(PurchaseDetailAction.StartCorrect) },
                        variant = ActionVariant.OUTLINED,
                        modifier = Modifier.fillMaxWidth(),
                        state = if (ready.status == PurchaseVoidStatus.WORKING &&
                            ready.sheet == null
                        ) {
                            ActionState.WORKING
                        } else {
                            ActionState.IDLE
                        },
                    )
                    PrimaryActionButton(
                        text = stringResource(R.string.dialog_void_purchase_action),
                        onClick = { onAction(PurchaseDetailAction.OpenVoid) },
                        variant = ActionVariant.TONAL,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ready.status == PurchaseVoidStatus.FAILED && ready.sheet == null) {
                        NoticeCard(
                            stringResource(R.string.error_purchase_void),
                            NoticeVariant.ERROR,
                        )
                    }
                }
            }
        }
    }
}

/** El total, con la fecha, el proveedor y la referencia; anulada, con el motivo y su fecha. */
@Composable
private fun TotalBlock(detail: PurchaseDetail, modifier: Modifier = Modifier) {
    val formats = LocalAppFormats.current
    val app = AppTheme.colors
    val content = detail.purchase.content
    val voiding = detail.purchase.voiding
    val muted = app.onHeroVariant
    Column(
        modifier
            .fillMaxWidth()
            .background(app.heroContainer, MaterialTheme.shapes.extraLarge)
            .padding(AppTheme.spacing.s20),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        MoneyText(
            amount = content.total,
            size = MoneySize.HERO,
            tone = MoneyTone.ON_HERO,
            voided = voiding != null,
            sensitive = true,
        )
        Text(
            formats.date.longDate(content.businessDate),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        Text(
            detail.supplierName ?: stringResource(R.string.purchase_no_supplier),
            style = MaterialTheme.typography.bodyLarge,
            color = app.onHero,
        )
        // La referencia es el número del documento del proveedor: se muestra tal cual.
        content.invoiceReference?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        voiding?.let {
            // El total tachado ya lleva la etiqueta `Anulada`: aquí van la fecha y el motivo.
            val reason = stringResource(reasonLabel(it.reason))
            val text = detail.voidedOn?.let { date ->
                stringResource(R.string.sale_voided_on, formats.date.longDate(date), reason)
            } ?: reason
            Text(text, style = MaterialTheme.typography.bodyMedium, color = app.heroAccent)
        }
    }
}

/** Una línea: el producto, cantidad por costo unitario, y el total de la línea a la derecha. */
@Composable
private fun LineRow(line: PurchaseDetailLine) {
    val spacing = AppTheme.spacing
    val content = line.line.content
    val unit = line.unit?.let { unitLabel(it) }
    val money = LocalAppFormats.current.money
    val quantity = unit?.let { quantityText(content.enteredQuantity, it) }
    val cost = rememberMoneyDisplay(content.unitCost, money).text.text
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                Text(line.productName, style = MaterialTheme.typography.titleSmall)
                quantity?.let {
                    Text(
                        stringResource(R.string.format_line_quantity_price, it, cost),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (line.productArchived) StatusTag(stringResource(R.string.status_archived))
            }
            MoneyText(content.lineTotal, MoneySize.ROW, sensitive = true)
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * La hoja de anular: el motivo, una nota opcional y lo que pasará: las unidades salen y el costo
 * promedio se recalcula. Si un producto quedaría con stock negativo, se dice antes de confirmar.
 */
@Composable
private fun VoidSheet(
    ready: PurchaseDetailUiState.Ready,
    onAction: (PurchaseDetailAction) -> Unit,
) {
    val sheet = requireNotNull(ready.sheet)
    AppBottomSheet(
        onDismissRequest = { onAction(PurchaseDetailAction.CloseSheet) },
        title = stringResource(R.string.dialog_void_purchase_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.dialog_void_purchase_action),
                onClick = { onAction(PurchaseDetailAction.ConfirmVoid) },
                variant = ActionVariant.DESTRUCTIVE,
                enabled = !sheet.noteTooLong,
                state = if (ready.status == PurchaseVoidStatus.WORKING) {
                    ActionState.WORKING
                } else {
                    ActionState.IDLE
                },
            )
        },
    ) {
        Text(
            stringResource(R.string.dialog_void_purchase_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (effect in ready.detail.voidEffects.filter { it.leavesNegative }) {
            val unit = effect.unit?.let { unitLabel(it) }
            val left = unit?.let { quantityText(effect.onHandAfter, it) }.orEmpty()
            NoticeCard(
                stringResource(
                    R.string.dialog_void_purchase_negative_body,
                    left,
                    effect.productName,
                ),
                NoticeVariant.WARNING,
            )
        }
        CompactChipRow(
            options = PurchaseVoidSheet.REASONS,
            label = { stringResource(reasonLabel(it)) },
            selected = setOf(sheet.reason),
            onToggle = { onAction(PurchaseDetailAction.ReasonChange(it)) },
            multiple = false,
        )
        AppTextField(
            value = sheet.note,
            onValueChange = { onAction(PurchaseDetailAction.NoteChange(it)) },
            label = stringResource(R.string.form_note),
            singleLine = false,
            error = if (sheet.noteTooLong) {
                pluralStringResource(
                    R.plurals.error_text_long,
                    PurchaseVoidSheet.MAX_VOID_NOTE_LENGTH,
                    PurchaseVoidSheet.MAX_VOID_NOTE_LENGTH,
                )
            } else {
                null
            },
        )
        if (ready.status == PurchaseVoidStatus.FAILED) {
            NoticeCard(stringResource(R.string.error_purchase_void), NoticeVariant.ERROR)
        }
    }
}

@Composable
private fun ReadProblem(retry: Boolean, onAction: (PurchaseDetailAction) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(icon = R.drawable.ic_error, title = stringResource(R.string.state_error_read)) {
            if (retry) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(PurchaseDetailAction.Retry) },
                    variant = ActionVariant.TONAL,
                )
            }
        }
    }
}

/** El bloque del total y tres filas de esqueleto. */
@Composable
private fun DetailSkeleton() {
    val spacing = AppTheme.spacing
    Column(Modifier.padding(spacing.s16), verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
        SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.saleTile))
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
        }
    }
}

private const val SKELETON_ROWS = 3
private const val TOTAL = "total"
private const val ACTIONS = "actions"
