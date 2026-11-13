package io.github.yesidrobayo.shopledger.ui.supplierreturn

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
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.usecase.returns.SupplierReturnDetail
import io.github.yesidrobayo.shopledger.domain.usecase.returns.SupplierReturnDetailLine
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
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
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.sale.reasonLabel
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El detalle de una devolución a proveedor: el total con la fecha, el proveedor y cómo se resolvió,
 * las líneas, y al final `Anular devolución`, que pregunta antes. Una devolución no se edita.
 * Anulada, el total va tachado con el motivo y ya no se ofrece anular.
 *
 * El costo unitario y los totales van con modo discreto. Es pura: recibe el estado y avisa cada
 * acción.
 */
@Composable
fun SupplierReturnDetailScreen(
    uiState: SupplierReturnDetailUiState,
    onAction: (SupplierReturnDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notices = remember { SnackbarHostState() }
    val voidedText = stringResource(R.string.supplier_return_voided)
    val ready = uiState as? SupplierReturnDetailUiState.Ready
    LaunchedEffect(ready?.justVoided) {
        if (ready?.justVoided != true) return@LaunchedEffect
        onAction(SupplierReturnDetailAction.VoidShown)
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
                    onNavigate = { onAction(SupplierReturnDetailAction.Back) },
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
                        SupplierReturnDetailUiState.Loading -> DelayedSkeleton { DetailSkeleton() }

                        SupplierReturnDetailUiState.NotFound,
                        SupplierReturnDetailUiState.ReadError,
                        -> ReadProblem(uiState == SupplierReturnDetailUiState.ReadError, onAction)

                        is SupplierReturnDetailUiState.Ready -> DetailBody(uiState, onAction)
                    }
                }
            }
            AppSnackbarHost(
                notices,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
    if (ready?.confirmVoid == true) {
        ConfirmDialog(
            title = stringResource(R.string.supplier_return_void_confirm_title),
            body = stringResource(R.string.supplier_return_void_confirm_body),
            confirmLabel = stringResource(R.string.supplier_return_void_action),
            onConfirm = { onAction(SupplierReturnDetailAction.ConfirmVoid) },
            onDismiss = { onAction(SupplierReturnDetailAction.DismissVoid) },
        )
    }
}

/** `Devolución 12`, con el número en el formato del negocio; mientras se lee, el título general. */
@Composable
private fun titleOf(ready: SupplierReturnDetailUiState.Ready?): String {
    ready ?: return stringResource(R.string.supplier_return_title)
    val number = LocalAppFormats.current.quantity.format(
        QuantityMilli.ofUnits(ready.detail.purchaseReturn.id),
        allowsFraction = false,
    )
    return stringResource(R.string.supplier_return_detail_title, number)
}

@Composable
private fun DetailBody(
    ready: SupplierReturnDetailUiState.Ready,
    onAction: (SupplierReturnDetailAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
            contentPadding = PaddingValues(spacing.s16),
        ) {
            item(key = TOTAL) { TotalBlock(ready.detail) }
            items(ready.detail.lines, key = { it.line.id }) { LineCard(it) }
        }
        if (ready.canVoid) {
            Column(
                Modifier.keepsMessagesAbove().padding(spacing.s16).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                if (ready.voidFailed) {
                    NoticeCard(
                        stringResource(R.string.error_supplier_return_void),
                        NoticeVariant.ERROR,
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.supplier_return_void_action),
                    onClick = { onAction(SupplierReturnDetailAction.AskVoid) },
                    variant = ActionVariant.TONAL,
                    modifier = Modifier.fillMaxWidth(),
                    state = if (ready.voiding) ActionState.WORKING else ActionState.IDLE,
                )
            }
        }
    }
}

/** El total, con la fecha, el proveedor y cómo se resolvió; anulada, con su motivo. */
@Composable
private fun TotalBlock(detail: SupplierReturnDetail) {
    val formats = LocalAppFormats.current
    val app = AppTheme.colors
    val content = detail.purchaseReturn.content
    val voiding = detail.purchaseReturn.voiding
    Column(
        Modifier
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
            color = app.onHeroVariant,
        )
        Text(
            detail.supplierName ?: stringResource(R.string.purchase_no_supplier),
            style = MaterialTheme.typography.bodyLarge,
            color = app.onHero,
        )
        Text(
            stringResource(
                when (content.resolution) {
                    PurchaseReturnResolution.CASH_REFUND ->
                        R.string.supplier_return_resolution_cash_refund

                    PurchaseReturnResolution.SUPPLIER_CREDIT ->
                        R.string.supplier_return_resolution_supplier_credit
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = app.onHeroVariant,
        )
        content.note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = app.onHeroVariant)
        }
        voiding?.let {
            // El total tachado ya lleva la etiqueta `Anulada`: aquí va el motivo.
            Text(
                stringResource(reasonLabel(it.reason)),
                style = MaterialTheme.typography.bodyMedium,
                color = app.heroAccent,
            )
        }
    }
}

/** Una línea: producto, cantidad, costo unitario y total de la línea. */
@Composable
private fun LineCard(line: SupplierReturnDetailLine) {
    val unit = line.unit?.let { unitLabel(it) } ?: UnitLabel("", allowsFraction = false)
    AppCard {
        Text(line.productName, style = MaterialTheme.typography.titleMedium)
        Text(
            quantityText(line.line.content.quantity, unit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AmountRow(stringResource(R.string.supplier_return_unit_cost_label)) {
            MoneyText(line.line.content.unitCost, MoneySize.ROW, sensitive = true)
        }
        AmountRow(stringResource(R.string.supplier_return_line_total_label)) {
            MoneyText(line.line.content.lineTotal, MoneySize.ROW, sensitive = true)
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = AppTheme.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        amount()
    }
}

@Composable
private fun ReadProblem(retry: Boolean, onAction: (SupplierReturnDetailAction) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(icon = R.drawable.ic_error, title = stringResource(R.string.state_error_read)) {
            if (retry) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(SupplierReturnDetailAction.Retry) },
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
    Column(
        Modifier.padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.saleTile))
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
        }
    }
}

private const val TOTAL = "total"
private const val SKELETON_ROWS = 3
