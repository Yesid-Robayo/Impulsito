package io.github.yesidrobayo.shopledger.ui.production

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La lista de producciones por fecha, con `Nueva producción` abajo, y el detalle de una en una
 * hoja con `Anular producción`.
 *
 * El costo total va con modo discreto; la cantidad no. Una producción anulada va tachada y con su
 * etiqueta. Sin producciones, el vacío explica para qué sirve, y sin recetas ofrece crear una.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProductionsScreen(
    uiState: ProductionsUiState,
    onAction: (ProductionsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.production_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(ProductionsAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                )
                when {
                    uiState.loadFailed -> EmptyState(
                        icon = R.drawable.ic_error,
                        title = stringResource(R.string.state_error_read),
                        modifier = Modifier.padding(top = AppTheme.spacing.s24),
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.action_retry),
                            onClick = { onAction(ProductionsAction.Retry) },
                            variant = ActionVariant.TONAL,
                        )
                    }

                    uiState.loading -> LoadingList()

                    uiState.days.isEmpty() -> EmptyProductions(uiState.hasRecipes, onAction)

                    else -> ProductionList(uiState, onAction)
                }
            }
            if (!uiState.loading && !uiState.loadFailed && uiState.days.isNotEmpty()) {
                TonalFab(
                    text = stringResource(R.string.production_new_action),
                    onClick = { onAction(ProductionsAction.NewProduction) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .keepsMessagesAbove()
                        .navigationBarsPadding()
                        .padding(AppTheme.spacing.s16),
                )
            }
        }
    }
    uiState.detail?.let { ProductionDetailSheet(it, uiState, onAction) }
}

@Composable
private fun LoadingList() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.listRow))
            }
        }
    }
}

@Composable
private fun EmptyProductions(hasRecipes: Boolean, onAction: (ProductionsAction) -> Unit) {
    EmptyState(
        icon = R.drawable.ic_inventory_2,
        title = stringResource(R.string.production_empty_title),
        body = stringResource(R.string.production_empty_body),
        modifier = Modifier.padding(top = AppTheme.spacing.s24),
    ) {
        if (hasRecipes) {
            PrimaryActionButton(
                text = stringResource(R.string.production_new_action),
                onClick = { onAction(ProductionsAction.NewProduction) },
                variant = ActionVariant.TONAL,
            )
        } else {
            PrimaryActionButton(
                text = stringResource(R.string.production_empty_no_recipe_action),
                onClick = { onAction(ProductionsAction.CreateRecipe) },
                variant = ActionVariant.TONAL,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProductionList(uiState: ProductionsUiState, onAction: (ProductionsAction) -> Unit) {
    val formats = LocalAppFormats.current
    LazyColumn(
        // En una ventana ancha, la lista se centra a 600 dp, como las demás.
        Modifier
            .fillMaxSize()
            .wrapContentWidth()
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .testTag(PRODUCTION_LIST_TAG),
        contentPadding = PaddingValues(bottom = AppTheme.sizes.listRow * 2),
    ) {
        for (day in uiState.days) {
            stickyHeader(key = "day-${day.date}", contentType = DAY) {
                Column(Modifier.fillMaxWidth()) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Text(
                            formats.date.weekdayDayMonth(day.date),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = AppTheme.sizes.touchTarget)
                                .padding(
                                    horizontal = AppTheme.spacing.s16,
                                    vertical = AppTheme.spacing.s8,
                                )
                                .semantics { heading() },
                        )
                    }
                    HorizontalDivider(
                        thickness = AppTheme.sizes.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            items(day.items, key = { it.id }, contentType = { ROW }) { item ->
                ProductionRow(item) { onAction(ProductionsAction.Open(item.id)) }
            }
        }
        if (uiState.canLoadMore) {
            item(key = MORE, contentType = MORE) {
                // Llegar al final pide el tramo siguiente, sin botón: la lista no tiene límite.
                LaunchedEffect(uiState.days.sumOf { it.items.size }) {
                    onAction(ProductionsAction.LoadMore)
                }
                SkeletonBlock(
                    Modifier
                        .fillMaxWidth()
                        .padding(AppTheme.spacing.s16)
                        .height(AppTheme.sizes.listRow),
                )
            }
        }
    }
}

/** Una producción: el producto y la cantidad a la izquierda, el costo total a la derecha. */
@Composable
private fun ProductionRow(item: ProductionItemUi, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.listRow)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
            Text(
                text = item.productName,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (item.voided) TextDecoration.LineThrough else null,
                color = if (item.voided) muted else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = quantityText(item.quantity, labelOf(item.unit)),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            if (item.voided) StatusTag(stringResource(R.string.status_voided))
        }
        MoneyText(
            item.totalCost,
            MoneySize.ROW,
            sensitive = true,
            tone = if (item.voided) MoneyTone.MUTED else MoneyTone.DEFAULT,
        )
    }
}

/**
 * El detalle de una producción, en solo lectura: lo producido, el costo total, los insumos y la
 * nota. Activa, ofrece `Anular producción`, que pregunta antes y avisa si lo producido quedaría
 * negativo.
 */
@Composable
private fun ProductionDetailSheet(
    detail: ProductionDetailUi,
    uiState: ProductionsUiState,
    onAction: (ProductionsAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    val spacing = AppTheme.spacing
    AppBottomSheet(
        onDismissRequest = { onAction(ProductionsAction.CloseDetail) },
        title = stringResource(
            R.string.production_detail_title,
            formats.date.longDate(detail.date),
        ),
        action = if (detail.voided) {
            null
        } else {
            {
                PrimaryActionButton(
                    text = stringResource(R.string.production_void_action),
                    onClick = { onAction(ProductionsAction.AskVoid) },
                    variant = ActionVariant.TONAL,
                    modifier = Modifier.fillMaxWidth(),
                    state = if (uiState.voiding) ActionState.WORKING else ActionState.IDLE,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            // Lo producido es un solo foco: el producto, la cantidad y si se anuló.
            Column(
                Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                Text(detail.productName, style = MaterialTheme.typography.titleMedium)
                Text(
                    quantityText(detail.quantity, labelOf(detail.unit)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (detail.voided) StatusTag(stringResource(R.string.status_voided))
            }
            // Con letra grande, el costo que no cabe al lado baja bajo su nombre: nunca se corta.
            LedgerLineLayout(
                gap = spacing.s12,
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                leading = {
                    Text(
                        stringResource(R.string.production_total_cost_label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailing = { MoneyText(detail.totalCost, MoneySize.ROW, sensitive = true) },
            )
            Text(
                stringResource(R.string.production_inputs_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = spacing.s8).semantics { heading() },
            )
            detail.lines.forEach { line ->
                // Un insumo es un solo foco: su nombre, lo que se usó y su costo.
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            quantityText(line.quantity, labelOf(line.unit, fraction = true)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MoneyText(line.lineCost, MoneySize.ROW, sensitive = true)
                }
            }
            detail.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Anular no se bloquea porque lo producido ya salió: se avisa antes de tocar el botón.
            detail.leftIfVoided?.let {
                NoticeCard(
                    text = stringResource(
                        R.string.production_void_negative,
                        quantityText(it, labelOf(detail.unit)),
                        detail.productName,
                    ),
                    variant = NoticeVariant.WARNING,
                )
            }
            if (uiState.voidFailed) {
                NoticeCard(
                    text = stringResource(R.string.state_error_save),
                    variant = NoticeVariant.ERROR,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(ProductionsAction.ConfirmVoid) },
                )
            }
            if (uiState.voidLotUsed) {
                NoticeCard(
                    text = stringResource(R.string.error_void_lot_already_used),
                    variant = NoticeVariant.ERROR,
                )
            }
        }
    }
    if (uiState.voidAsked) {
        ConfirmDialog(
            title = stringResource(R.string.production_void_confirm_title),
            body = stringResource(R.string.production_void_confirm_body),
            confirmLabel = stringResource(R.string.production_void_action),
            onConfirm = { onAction(ProductionsAction.ConfirmVoid) },
            onDismiss = { onAction(ProductionsAction.DismissVoid) },
            variant = ConfirmVariant.DESTRUCTIVE,
        )
    }
}

/** La unidad de una cantidad, o ninguna si ya no se lee. */
@Composable
internal fun labelOf(unit: MeasureUnit?, fraction: Boolean = false): UnitLabel {
    val label = unit?.let { unitLabel(it) } ?: UnitLabel("", allowsFraction = true)
    return if (fraction) label.copy(allowsFraction = true) else label
}

/** La lista de producciones, para que las pruebas la desplacen. */
internal const val PRODUCTION_LIST_TAG = "production-list"

private const val DAY = "day"
private const val ROW = "row"
private const val MORE = "more"
private const val SKELETON_ROWS = 6
