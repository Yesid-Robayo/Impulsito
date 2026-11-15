package io.github.yesidrobayo.shopledger.ui.extra

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Los extras de un producto: los que se ofrecen en su línea al venderlo, cada uno con su precio y
 * `Quitar`, y abajo `Agregar extra`. Cada cambio se guarda al momento, así que no hay botón de
 * guardar ni nada que descartar al salir.
 *
 * El precio de cada extra es el de venta, lo que cobra su línea: no es dato sensible. Un extra
 * archivado lleva su marca, porque al vender ya no se ofrece.
 */
@Composable
fun ExtrasScreen(
    uiState: ExtrasUiState,
    onAction: (ExtrasAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = uiState.productName.ifEmpty { stringResource(R.string.extras_title) },
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(ExtrasAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            when {
                uiState.loadFailed -> NoticeCard(
                    text = stringResource(R.string.state_error_read),
                    variant = NoticeVariant.ERROR,
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(ExtrasAction.Retry) },
                )

                uiState.missing -> EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                    modifier = Modifier.padding(top = AppTheme.spacing.s24),
                )

                uiState.loading -> LoadingExtras()

                else -> ExtrasList(uiState, onAction)
            }
        }
    }
    ExtraPickerSheet(uiState.picker, onAction)
}

/** Tres filas de esqueleto, solo si la lectura tarda más de lo que se nota. */
@Composable
private fun LoadingExtras() {
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
private fun ColumnScope.ExtrasList(uiState: ExtrasUiState, onAction: (ExtrasAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .weight(1f)
            .align(Alignment.CenterHorizontally)
            .widthIn(max = FORM_WIDTH)
            .fillMaxWidth(),
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(EXTRAS_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
            contentPadding = PaddingValues(spacing.s16),
        ) {
            item(key = HEADER) { SectionHeader(stringResource(R.string.extras_title)) }
            if (uiState.extras.isEmpty()) {
                item(key = EMPTY) {
                    EmptyState(
                        icon = R.drawable.ic_inventory_2,
                        title = stringResource(R.string.extras_empty_title),
                        body = stringResource(R.string.extras_empty_body),
                    )
                }
            } else {
                items(uiState.extras, key = { it.productId }) { row ->
                    ExtraRow(row, enabled = !uiState.saving, onAction = onAction)
                    HorizontalDivider(
                        thickness = AppTheme.sizes.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
        Column(Modifier.keepsMessagesAbove().imePadding().navigationBarsPadding()) {
            HorizontalDivider(
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(
                Modifier.padding(spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                // Se va con el siguiente cambio que sí se guarde.
                if (uiState.saveFailed) {
                    NoticeCard(
                        text = stringResource(R.string.extras_error_save),
                        variant = NoticeVariant.ERROR,
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.extras_add_action),
                    onClick = { onAction(ExtrasAction.OpenPicker) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.canAdd,
                    state = if (uiState.saving) ActionState.WORKING else ActionState.IDLE,
                )
            }
        }
    }
}

/** Un extra: su nombre, su marca si está archivado, su precio y `Quitar`. */
@Composable
private fun ExtraRow(row: ExtraRowUi, enabled: Boolean, onAction: (ExtrasAction) -> Unit) {
    val spacing = AppTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(vertical = spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            if (row.archived) StatusTag(stringResource(R.string.status_archived))
            MoneyText(row.price, MoneySize.ROW)
        }
        InlineTextButton(
            text = stringResource(R.string.action_remove),
            onClick = { if (enabled) onAction(ExtrasAction.Remove(row.productId)) },
        )
    }
}

/** La hoja para agregar un extra, con búsqueda. Sin coincidencias, la lista queda vacía. */
@Composable
private fun ExtraPickerSheet(picker: ExtraPicker?, onAction: (ExtrasAction) -> Unit) {
    picker ?: return
    SelectionSheet(
        title = stringResource(R.string.extras_add_action),
        items = picker.options,
        key = { it.id },
        query = picker.query,
        onQueryChange = { onAction(ExtrasAction.QueryChange(it)) },
        onDismissRequest = { onAction(ExtrasAction.ClosePicker) },
        searchHint = stringResource(R.string.sale_search_hint),
        itemContent = { option ->
            SettingsRow(
                title = option.name,
                action = SettingsRowAction.Navigation { onAction(ExtrasAction.Add(option.id)) },
            )
        },
        resultsCount = R.plurals.a11y_search_results,
    )
}

/** La lista de extras, para que las pruebas la desplacen. */
internal const val EXTRAS_LIST_TAG = "extras-list"

private const val HEADER = "header"
private const val EMPTY = "empty"
private const val SKELETON_ROWS = 3

/** Ancho máximo de la lista en una ventana ancha. */
private val FORM_WIDTH = 600.dp
