package io.github.yesidrobayo.shopledger.ui.receipt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ReceiptPreview
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.document.ReceiptAssets
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El comprobante de una venta a pantalla completa, tal como sale, con `Compartir PDF`, `Compartir
 * imagen` e `Imprimir` abajo y `Editar plantilla` en el menú. Una venta anulada se muestra y se
 * comparte igual: el papel dice que se anuló.
 *
 * Si no se pudo crear el archivo, lo dice sobre los botones con `Reintentar`: la venta sigue
 * registrada. Es pura: todo llega en [uiState] y sale por [onAction].
 */
@Composable
fun SaleReceiptScreen(
    uiState: SaleReceiptUiState,
    onAction: (SaleReceiptAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.receipt_view_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = { onAction(SaleReceiptAction.Back) },
            navigationLabel = stringResource(R.string.a11y_back),
            actions = {
                OverflowMenu(
                    listOf(
                        OverflowAction(stringResource(R.string.receipt_edit_template_action), {
                            onAction(SaleReceiptAction.EditTemplate)
                        }),
                    ),
                )
            },
        )
        when (uiState) {
            SaleReceiptUiState.Loading ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    DelayedSkeleton {
                        ReceiptPreview(
                            image = null,
                            description = stringResource(R.string.receipt_view_title),
                            modifier = Modifier.padding(AppTheme.spacing.s16),
                        )
                    }
                }

            SaleReceiptUiState.NotFound ->
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                    modifier = Modifier.weight(1f),
                )

            SaleReceiptUiState.ReadFailed ->
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                    modifier = Modifier.weight(1f),
                ) {
                    InlineTextButton(
                        stringResource(R.string.action_retry),
                        { onAction(SaleReceiptAction.Retry) },
                    )
                }

            is SaleReceiptUiState.Ready -> {
                Paper(uiState, Modifier.weight(1f))
                ShareBar(uiState, onAction)
            }
        }
    }
}

@Composable
private fun Paper(ready: SaleReceiptUiState.Ready, modifier: Modifier) {
    val assets = remember(ready.units, ready.zone) {
        ReceiptAssets(logo = null, units = ready.units, zone = ready.zone)
    }
    val image = rememberReceiptImage(ready.document, ready.logo, assets)
    Box(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        ReceiptPreview(
            image = image,
            description = previewDescription(ready.document),
            modifier = Modifier
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .padding(AppTheme.spacing.s16),
        )
    }
}

/**
 * `Compartir PDF`, la acción principal, `Compartir imagen` e `Imprimir`. Mientras se crea un
 * archivo, su botón dice `Generando…` y ninguno atiende otro toque. `Imprimir` usa la térmica por
 * defecto; sin ninguna, abre las impresoras.
 */
@Composable
private fun ShareBar(ready: SaleReceiptUiState.Ready, onAction: (SaleReceiptAction) -> Unit) {
    val spacing = AppTheme.spacing
    val width = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth()
    Column(
        Modifier
            .fillMaxWidth()
            .keepsMessagesAbove()
            .navigationBarsPadding()
            .padding(spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        ready.shareFailed?.let { failed ->
            NoticeCard(
                text = stringResource(R.string.receipt_error_generate),
                variant = NoticeVariant.ERROR,
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onAction(SaleReceiptAction.Share(failed)) },
                modifier = width,
            )
        }
        ShareButton(ready, ReceiptShare.PDF, ActionVariant.PRIMARY, onAction, width)
        ShareButton(ready, ReceiptShare.IMAGE, ActionVariant.OUTLINED, onAction, width)
        val print = if (ready.printing) {
            R.string.printer_status_printing
        } else {
            R.string.receipt_print_action
        }
        PrimaryActionButton(
            text = stringResource(print),
            onClick = { onAction(SaleReceiptAction.Print) },
            variant = ActionVariant.TEXT,
            state = if (ready.printing) ActionState.WORKING else ActionState.IDLE,
            modifier = width,
        )
    }
}

@Composable
private fun ShareButton(
    ready: SaleReceiptUiState.Ready,
    kind: ReceiptShare,
    variant: ActionVariant,
    onAction: (SaleReceiptAction) -> Unit,
    modifier: Modifier,
) {
    val working = ready.sharing == kind
    val label = when {
        working -> stringResource(R.string.receipt_generating)
        kind == ReceiptShare.PDF -> stringResource(R.string.receipt_share_pdf_action)
        else -> stringResource(R.string.receipt_share_image_action)
    }
    PrimaryActionButton(
        text = label,
        onClick = { onAction(SaleReceiptAction.Share(kind)) },
        variant = variant,
        state = if (working) ActionState.WORKING else ActionState.IDLE,
        enabled = ready.sharing == null || working,
        modifier = modifier,
    )
}
