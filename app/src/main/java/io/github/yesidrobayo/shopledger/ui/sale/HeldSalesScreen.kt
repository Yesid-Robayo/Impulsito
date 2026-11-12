package io.github.yesidrobayo.shopledger.ui.sale

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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.rule.sale.HeldSaleRules
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarHost
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Las ventas en espera: atender a otra persona a mitad de una venta sin perder lo escrito, y las
 * mesas o pedidos de mostrador con su nombre.
 *
 * - Una fila por venta, de la más antigua a la más reciente: el nombre o `Sin nombre`, cuántos
 *   productos, desde qué hora espera y su total estimado con los precios vigentes. Tocarla la
 *   retoma; su menú la renombra o la descarta.
 * - Una venta en espera no es una venta: aquí nada toca stock, dinero ni reportes.
 * - En una ventana ancha, la lista no pasa de 600 dp, centrada.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun HeldSalesScreen(
    uiState: HeldSalesUiState,
    onAction: (HeldSalesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notices = remember { SnackbarHostState() }
    val failed = stringResource(R.string.state_error_save)
    LaunchedEffect(uiState.notice) {
        if (uiState.notice == null) return@LaunchedEffect
        onAction(HeldSalesAction.NoticeShown)
        notices.showSnackbar(AppSnackbarVisuals(failed, SnackbarVariant.ERROR))
    }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.draft_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(HeldSalesAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                )
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    HeldList(
                        uiState.content,
                        onAction,
                        Modifier.widthIn(max = AppTheme.sizes.maxContentWidth),
                    )
                }
            }
            AppSnackbarHost(
                notices,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
    HeldDialogs(uiState.dialog, onAction)
    uiState.rename?.let { RenameSheet(it, onAction) }
}

@Composable
private fun HeldList(
    content: HeldSalesContent,
    onAction: (HeldSalesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (content) {
        HeldSalesContent.Loading -> DelayedSkeleton {
            Column(modifier.fillMaxWidth()) {
                repeat(SKELETON_ROWS) {
                    SkeletonBlock(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = AppTheme.spacing.s16,
                                vertical = AppTheme.spacing.s8,
                            )
                            .heightIn(min = AppTheme.sizes.listRow),
                    )
                }
            }
        }

        HeldSalesContent.Empty -> EmptyState(
            icon = R.drawable.ic_sell,
            title = stringResource(R.string.draft_empty_title),
            body = stringResource(R.string.draft_empty_body),
            modifier = modifier,
        ) {
            PrimaryActionButton(
                text = stringResource(R.string.draft_empty_action),
                onClick = { onAction(HeldSalesAction.GoSell) },
                variant = ActionVariant.TONAL,
            )
        }

        HeldSalesContent.ReadError -> EmptyState(
            icon = R.drawable.ic_error,
            title = stringResource(R.string.state_error_read),
            modifier = modifier,
        ) {
            PrimaryActionButton(
                text = stringResource(R.string.action_retry),
                onClick = { onAction(HeldSalesAction.RetryRead) },
                variant = ActionVariant.TONAL,
            )
        }

        is HeldSalesContent.Sales -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = AppTheme.spacing.s16),
        ) {
            items(content.rows, key = { it.id }) { row ->
                HeldRow(row, content.zone, onAction)
            }
        }
    }
}

/**
 * Una venta en espera: el nombre, cuántos productos y desde qué hora, y su total estimado. Tocarla
 * la retoma. El menú, también como acciones de TalkBack, la renombra o la descarta.
 */
@Composable
private fun HeldRow(
    row: HeldSaleRow,
    zone: BusinessTimeZone?,
    onAction: (HeldSalesAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val renameLabel = stringResource(R.string.action_rename)
    val discardLabel = stringResource(R.string.draft_discard_action)
    val count = row.itemCount.toInt()
    val total = row.estimatedTotal?.let { rememberMoneyDisplay(it, formats.money) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.draft_resume_action),
                ) { onAction(HeldSalesAction.Resume(row.id)) }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(renameLabel) {
                            onAction(HeldSalesAction.OpenRename(row.id))
                            true
                        },
                        CustomAccessibilityAction(discardLabel) {
                            onAction(HeldSalesAction.AskDiscard(row.id))
                            true
                        },
                    )
                }
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(start = spacing.s16, top = spacing.s12, bottom = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            // El total va a la derecha, como todo monto en una lista, con su palabra porque es
            // estimado. Si con letra grande no cabe al lado, baja bajo el texto: nunca se corta.
            LedgerLineLayout(
                gap = spacing.s8,
                modifier = Modifier.weight(1f),
                trailingWraps = true,
                leading = {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                        Text(
                            row.label ?: stringResource(R.string.draft_no_name),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (row.label ==
                                null
                            ) {
                                muted
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            pluralStringResource(R.plurals.draft_items, count, count),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                        zone?.let {
                            Text(
                                stringResource(
                                    R.string.draft_waiting_since,
                                    formats.date.time(row.heldSince, it),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = muted,
                            )
                        }
                    }
                },
            ) { alignment ->
                if (total != null) {
                    Text(
                        formatAnnotated(stringResource(R.string.draft_estimated_total), total.text),
                        style = AppTheme.amounts.row,
                        textAlign = if (alignment ==
                            Alignment.End
                        ) {
                            TextAlign.End
                        } else {
                            TextAlign.Start
                        },
                    )
                }
            }
            OverflowMenu(
                listOf(
                    OverflowAction(renameLabel, { onAction(HeldSalesAction.OpenRename(row.id)) }),
                    OverflowAction(
                        discardLabel,
                        { onAction(HeldSalesAction.AskDiscard(row.id)) },
                        destructive = true,
                    ),
                ),
            )
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun HeldDialogs(dialog: HeldSalesDialog?, onAction: (HeldSalesAction) -> Unit) {
    val dismiss = { onAction(HeldSalesAction.DismissDialog) }
    when (dialog) {
        null -> Unit

        is HeldSalesDialog.Swap -> ConfirmDialog(
            title = stringResource(R.string.draft_swap_confirm_title),
            body = stringResource(R.string.draft_swap_confirm_body),
            confirmLabel = stringResource(R.string.draft_swap_confirm_action),
            onConfirm = { onAction(HeldSalesAction.ConfirmSwap) },
            onDismiss = dismiss,
            variant = ConfirmVariant.STANDARD,
        )

        is HeldSalesDialog.Discard -> ConfirmDialog(
            title = stringResource(R.string.draft_discard_confirm_title),
            body = stringResource(R.string.draft_discard_confirm_body),
            confirmLabel = stringResource(R.string.draft_discard_confirm_action),
            onConfirm = { onAction(HeldSalesAction.ConfirmDiscard) },
            onDismiss = dismiss,
            variant = ConfirmVariant.STANDARD,
        )
    }
}

/** Renombrar: el mismo campo que al poner en espera, con lo que tenía. En blanco, sin nombre. */
@Composable
private fun RenameSheet(editor: RenameEditor, onAction: (HeldSalesAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(HeldSalesAction.CloseRename) },
        title = stringResource(R.string.action_rename),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.action_save),
                onClick = { onAction(HeldSalesAction.ConfirmRename) },
                variant = ActionVariant.TONAL,
                enabled = !editor.tooLong,
                state = if (editor.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        AppTextField(
            value = editor.text,
            onValueChange = { onAction(HeldSalesAction.RenameChange(it)) },
            label = stringResource(R.string.draft_label),
            supportingText = stringResource(R.string.draft_label_help),
            imeAction = ImeAction.Done,
            error =
                if (editor.tooLong) {
                    pluralStringResource(
                        R.plurals.error_text_long,
                        HeldSaleRules.MAX_LABEL_LENGTH,
                        HeldSaleRules.MAX_LABEL_LENGTH,
                    )
                } else {
                    null
                },
        )
    }
}

/** Filas del esqueleto mientras la lista tarda. */
private const val SKELETON_ROWS = 4
