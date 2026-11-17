package io.github.yesidrobayo.shopledger.ui.combo

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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La receta de un combo: sus componentes con cuánto lleva de cada uno, la tarjeta con lo que
 * cuesta, el precio, la ganancia y el margen, cuánto alcanza y, abajo, `Guardar combo`.
 *
 * Cada fila dice cuánto lleva del componente en su unidad y lo que cuesta en una unidad del combo.
 * Un servicio dice que no descuenta stock, y uno archivado lo marca. Un ciclo o un tercer nivel se
 * marcan en la fila apenas se agrega el componente.
 *
 * El costo de cada fila, el de componentes, la ganancia y el margen van con modo discreto. El
 * precio no, porque es lo que se atiende, ni las cantidades, porque se están llenando.
 */
@Composable
fun RecipeScreen(
    uiState: RecipeUiState,
    onAction: (RecipeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = uiState.comboName.ifEmpty { stringResource(R.string.combo_title) },
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(RecipeAction.Close) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = {
                    ScreenHelpLink(HelpTopic.RECIPES)
                    if (!uiState.loading && !uiState.missing && !uiState.loadFailed) {
                        TopBarAction(
                            icon = R.drawable.ic_edit,
                            label = stringResource(R.string.combo_edit_product_action),
                            onClick = { onAction(RecipeAction.EditProduct) },
                        )
                    }
                },
            )
            when {
                uiState.loadFailed -> NoticeCard(
                    text = stringResource(R.string.state_error_read),
                    variant = NoticeVariant.ERROR,
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(RecipeAction.Retry) },
                )

                uiState.missing -> EmptyState(
                    icon = R.drawable.ic_inventory_2,
                    title = stringResource(R.string.combo_error_unavailable),
                    modifier = Modifier.padding(top = AppTheme.spacing.s24),
                )

                uiState.loading -> LoadingRecipe()

                else -> RecipeForm(uiState, onAction)
            }
        }
    }
    RecipePickerSheet(uiState.picker, onAction)
    if (uiState.discardAsked) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            body = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.dialog_discard_action),
            onConfirm = { onAction(RecipeAction.ConfirmLeave) },
            onDismiss = { onAction(RecipeAction.DismissDialog) },
            dismissLabel = stringResource(R.string.dialog_discard_exit),
        )
    }
}

/** Tres filas y la tarjeta de esqueleto, solo si la lectura tarda más de lo que se nota. */
@Composable
private fun LoadingRecipe() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.listRow))
            }
            SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.listRow * 2))
        }
    }
}

@Composable
private fun ColumnScope.RecipeForm(uiState: RecipeUiState, onAction: (RecipeAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .weight(1f)
            .align(Alignment.CenterHorizontally)
            .widthIn(max = FORM_WIDTH)
            .fillMaxWidth(),
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(RECIPE_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
            contentPadding = PaddingValues(spacing.s16),
        ) {
            item(key = HEADER) { SectionHeader(stringResource(R.string.combo_components_title)) }
            if (uiState.rows.isEmpty()) {
                item(key = EMPTY) {
                    EmptyState(
                        icon = R.drawable.ic_inventory_2,
                        title = stringResource(R.string.combo_detail_empty_title),
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.combo_add_component_action),
                            onClick = { onAction(RecipeAction.OpenPicker) },
                            variant = ActionVariant.TONAL,
                        )
                    }
                }
            } else {
                items(uiState.rows, key = { it.key }) { row -> ComponentCard(row, onAction) }
                if (uiState.canAdd) {
                    item(key = ADD) {
                        InlineTextButton(
                            text = stringResource(R.string.combo_add_component_action),
                            onClick = { onAction(RecipeAction.OpenPicker) },
                        )
                    }
                }
            }
            item(key = FIGURES) { FiguresCard(uiState) }
            item(key = AVAILABLE) { Availability(uiState) }
            // Un producto con stock propio se produce con su receta guardada, no con la que se
            // está armando: con cambios sin guardar, primero se guarda.
            if (uiState.produced && !uiState.dirty && uiState.rows.isNotEmpty()) {
                item(key = PRODUCE) {
                    PrimaryActionButton(
                        text = stringResource(R.string.combo_produce_action),
                        onClick = { onAction(RecipeAction.Produce) },
                        variant = ActionVariant.TONAL,
                        modifier = Modifier.fillMaxWidth(),
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
                uiState.notice?.let { RecipeNoticeCard(it, onAction) }
                PrimaryActionButton(
                    text = if (uiState.produced) {
                        stringResource(R.string.combo_save_recipe_action)
                    } else {
                        stringResource(R.string.combo_save_action)
                    },
                    onClick = { onAction(RecipeAction.Save) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                    state = if (uiState.saving) ActionState.WORKING else ActionState.IDLE,
                )
            }
        }
    }
}

/**
 * Un componente: su nombre, cuánto lleva en su unidad, lo que cuesta en una unidad del combo y
 * `Quitar`. Un servicio dice que no descuenta stock; uno archivado lleva su marca.
 */
@Composable
private fun ComponentCard(row: RecipeRowUi, onAction: (RecipeAction) -> Unit) {
    val spacing = AppTheme.spacing
    // Una receta puede llevar media unidad, así que toda unidad admite milésimas aquí.
    val unit = row.unit?.let { unitLabel(it).copy(allowsFraction = true) }
        ?: UnitLabel("", allowsFraction = true)
    val removeLabel = stringResource(R.string.a11y_combo_remove_component, row.name)
    val remove = { onAction(RecipeAction.RemoveComponent(row.key)) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Nombre y marcas en un solo foco, con quitar también como acción de TalkBack.
            Column(
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {
                        customActions = listOf(
                            CustomAccessibilityAction(removeLabel) {
                                remove()
                                true
                            },
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(spacing.s4),
            ) {
                Text(text = row.name, style = MaterialTheme.typography.titleMedium)
                if (row.archived) StatusTag(stringResource(R.string.status_archived))
                if (!row.tracked) {
                    Text(
                        text = stringResource(R.string.combo_no_stock_component),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            InlineTextButton(
                text = stringResource(R.string.action_remove),
                onClick = remove,
                modifier = Modifier.semantics { contentDescription = removeLabel },
            )
        }
        // La cantidad ocupa todo el ancho y el costo va debajo, a la derecha: con letra grande y un
        // monto largo, ponerlos lado a lado dejaba el campo sin espacio.
        QuantityField(
            value = row.quantityText,
            onValueChange = { onAction(RecipeAction.QuantityChange(row.key, it)) },
            label = stringResource(R.string.sale_line_quantity),
            unit = unit,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s8),
            error = row.error?.let { rowErrorText(it) },
        )
        MoneyText(
            row.lineCost,
            MoneySize.ROW,
            modifier = Modifier.align(Alignment.End).padding(top = spacing.s4),
            sensitive = true,
        )
    }
}

/** Costo de componentes, precio, ganancia por unidad y margen, con el aviso de costo faltante. */
@Composable
private fun FiguresCard(uiState: RecipeUiState) {
    val spacing = AppTheme.spacing
    val money = LocalAppFormats.current.money
    val noValue = stringResource(R.string.a11y_no_value)
    // Un foco que se vuelve a leer, sin mover el foco, cada vez que cambia una cifra.
    AppCard(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            LabeledAmount(stringResource(R.string.combo_cost_label), uiState.unitCost, true)
            LabeledAmount(stringResource(R.string.combo_price_label), uiState.salePrice, false)
            // TalkBack lee la moneda con su nombre, no el símbolo, y `—` como sin dato.
            val profit = uiState.profit?.let { rememberMoneyDisplay(it, money) }
            val profitReading =
                stringResource(R.string.combo_profit_per_unit, profit?.description ?: noValue)
            DiscreetValue(style = MaterialTheme.typography.bodyLarge) {
                Text(
                    text = stringResource(
                        R.string.combo_profit_per_unit,
                        profit?.text?.text ?: NO_VALUE_TEXT,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = profitReading },
                )
            }
            val margin = uiState.margin
            val marginReading = stringResource(R.string.combo_margin, noValue)
                .takeUnless { margin is Margin.Defined }
            DiscreetValue(style = MaterialTheme.typography.bodyLarge) {
                Text(
                    text = stringResource(R.string.combo_margin, marginText(margin)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = if (marginReading == null) {
                        Modifier
                    } else {
                        Modifier.semantics { contentDescription = marginReading }
                    },
                )
            }
            if (uiState.hasUncosted) {
                NoticeCard(
                    text = stringResource(R.string.combo_warning_no_cost),
                    variant = NoticeVariant.WARNING,
                )
            }
        }
    }
}

@Composable
private fun LabeledAmount(label: String, amount: Money?, sensitive: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        MoneyText(amount, MoneySize.ROW, sensitive = sensitive)
    }
}

/** `Disponibles ahora` y el componente que limita. Sin componentes con stock, no se muestra. */
@Composable
private fun Availability(uiState: RecipeUiState) {
    val available = uiState.available ?: return
    val formats = LocalAppFormats.current
    Column(
        Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Text(
            text = stringResource(
                R.string.combo_available_now,
                formats.quantity.format(QuantityMilli.ofUnits(available), allowsFraction = false),
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        uiState.limitingName?.let {
            Text(
                text = stringResource(R.string.combo_limited_by, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecipeNoticeCard(notice: RecipeNotice, onAction: (RecipeAction) -> Unit) {
    when (notice) {
        is RecipeNotice.SaveFailed -> NoticeCard(
            text = if (notice.outOfSpace) {
                stringResource(R.string.error_device_storage)
            } else {
                stringResource(R.string.state_error_save)
            },
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(RecipeAction.Retry) },
        )
    }
}

/** La hoja para agregar un componente, con búsqueda. Sin coincidencias, lo dice. */
@Composable
private fun RecipePickerSheet(picker: RecipePicker?, onAction: (RecipeAction) -> Unit) {
    picker ?: return
    SelectionSheet(
        title = stringResource(R.string.combo_add_component_action),
        items = picker.options,
        key = { it.id },
        query = picker.query,
        onQueryChange = { onAction(RecipeAction.QueryChange(it)) },
        onDismissRequest = { onAction(RecipeAction.ClosePicker) },
        searchHint = stringResource(R.string.sale_search_hint),
        itemContent = { option ->
            SettingsRow(
                title = option.name,
                action = SettingsRowAction.Navigation {
                    onAction(RecipeAction.AddComponent(option.id))
                },
            )
        },
        resultsCount = R.plurals.a11y_search_results,
    )
}

@Composable
private fun rowErrorText(error: RecipeRowError): String = when (error) {
    RecipeRowError.NotANumber -> stringResource(R.string.field_error_number)
    RecipeRowError.TooHigh -> stringResource(R.string.error_value_high)
    is RecipeRowError.Cycle -> stringResource(R.string.combo_error_cycle, error.comboName)
    RecipeRowError.TooDeep -> stringResource(R.string.combo_error_levels)
    RecipeRowError.Unavailable -> stringResource(R.string.combo_error_unavailable)
}

@Composable
internal fun marginText(margin: Margin): String = when (margin) {
    is Margin.Defined -> LocalAppFormats.current.percent.format(margin.value)
    Margin.NoData, Margin.OutOfRange -> NO_VALUE_TEXT
}

/** La lista de la receta, para que las pruebas la desplacen. */
internal const val RECIPE_LIST_TAG = "recipe-list"

private const val HEADER = "header"
private const val EMPTY = "empty"
private const val ADD = "add"
private const val FIGURES = "figures"
private const val AVAILABLE = "available"
private const val PRODUCE = "produce"
private const val SKELETON_ROWS = 3

/** Ancho máximo de la receta en una ventana ancha. */
private val FORM_WIDTH = 600.dp
