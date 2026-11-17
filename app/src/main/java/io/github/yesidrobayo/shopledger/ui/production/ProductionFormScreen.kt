package io.github.yesidrobayo.shopledger.ui.production

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotRules
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.LotPicker
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PickerField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.lotOptionsOf
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El formulario de una producción: el producto, la cantidad, los insumos que pide la receta con lo
 * que hay de cada uno, el resultado en vivo, la nota y, abajo, `Registrar producción`.
 *
 * Un insumo que no alcanza dice cuánto quedaría, sin bloquear. Los costos del resultado van con
 * modo discreto; las cantidades y lo disponible no, porque se están llenando.
 */
@Composable
fun ProductionFormScreen(
    uiState: ProductionFormUiState,
    onAction: (ProductionFormAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.production_new_action),
                navigation = TopBarNavigation.CLOSE,
                onNavigate = { onAction(ProductionFormAction.Close) },
                navigationLabel = stringResource(R.string.a11y_close),
                actions = { ScreenHelpLink(HelpTopic.RECIPES) },
            )
            when {
                uiState.loadFailed -> NoticeCard(
                    text = stringResource(R.string.state_error_read),
                    variant = NoticeVariant.ERROR,
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { onAction(ProductionFormAction.Retry) },
                )

                uiState.loading -> DelayedSkeleton {
                    Column(
                        Modifier.padding(AppTheme.spacing.s16),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
                    ) {
                        repeat(SKELETON_ROWS) {
                            SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.listRow))
                        }
                    }
                }

                uiState.noRecipes -> EmptyState(
                    icon = R.drawable.ic_inventory_2,
                    title = stringResource(R.string.production_empty_title),
                    body = stringResource(R.string.production_empty_body),
                    modifier = Modifier.padding(top = AppTheme.spacing.s24),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.production_empty_no_recipe_action),
                        onClick = { onAction(ProductionFormAction.RecipeHelp) },
                        variant = ActionVariant.TONAL,
                    )
                }

                else -> ProductionForm(uiState, onAction)
            }
        }
    }
    ProductPickerSheet(uiState.picker, onAction)
    if (uiState.discardAsked) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            body = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.dialog_discard_action),
            onConfirm = { onAction(ProductionFormAction.ConfirmLeave) },
            onDismiss = { onAction(ProductionFormAction.DismissDialog) },
            dismissLabel = stringResource(R.string.dialog_discard_exit),
        )
    }
}

@Composable
private fun ColumnScope.ProductionForm(
    uiState: ProductionFormUiState,
    onAction: (ProductionFormAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .weight(1f)
            .align(Alignment.CenterHorizontally)
            .widthIn(max = FORM_WIDTH)
            .fillMaxWidth(),
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(PRODUCTION_FORM_TAG),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
            contentPadding = PaddingValues(spacing.s16),
        ) {
            item(key = PRODUCT) {
                PickerField(
                    label = stringResource(R.string.production_product_label),
                    value = uiState.product?.name.orEmpty(),
                    onClick = { onAction(ProductionFormAction.OpenPicker) },
                )
            }
            uiState.product?.let { product ->
                item(key = QUANTITY) {
                    QuantityField(
                        value = uiState.quantityText,
                        onValueChange = { onAction(ProductionFormAction.QuantityChange(it)) },
                        label = stringResource(R.string.production_qty_label),
                        unit = labelOf(product.unit),
                        modifier = Modifier.fillMaxWidth(),
                        error = uiState.quantityError?.let { fieldErrorText(it) },
                    )
                }
                uiState.outputLot?.let { lot ->
                    item(key = OUTPUT_LOT) { OutputLotFields(lot, uiState, onAction) }
                }
            }
            if (uiState.inputs.isNotEmpty()) {
                item(key = INPUTS) {
                    SectionHeader(stringResource(R.string.production_inputs_title))
                }
                items(uiState.inputs, key = { it.productId }) { input ->
                    InputCard(input, onAction)
                }
            }
            uiState.figures?.let { figures ->
                item(key = FIGURES) { FiguresCard(figures, uiState) }
            }
            item(key = NOTE) {
                AppTextField(
                    value = uiState.noteText,
                    onValueChange = { onAction(ProductionFormAction.NoteChange(it)) },
                    label = stringResource(R.string.production_note_label),
                    supportingText = stringResource(R.string.production_note_hint),
                    modifier = Modifier.fillMaxWidth(),
                    error = if (uiState.noteTooLong) {
                        stringResource(R.string.error_value_high)
                    } else {
                        null
                    },
                )
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
                uiState.notice?.let { NoticeOf(it, onAction) }
                PrimaryActionButton(
                    text = stringResource(R.string.production_register_action),
                    onClick = { onAction(ProductionFormAction.Register) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                    state = if (uiState.saving) ActionState.WORKING else ActionState.IDLE,
                )
            }
        }
    }
}

/**
 * Un insumo: su nombre, la cantidad para esta tanda y lo disponible. Si no alcanza, dice cuánto
 * quedaría, sin bloquear. Un servicio no muestra stock.
 */
@Composable
private fun InputCard(input: ProductionInputUi, onAction: (ProductionFormAction) -> Unit) {
    val spacing = AppTheme.spacing
    // Una tanda puede llevar media unidad de un insumo, así que toda unidad admite milésimas.
    val unit = labelOf(input.unit, fraction = true)
    AppCard {
        Text(input.name, style = MaterialTheme.typography.titleMedium)
        QuantityField(
            value = input.quantityText,
            onValueChange = { onAction(ProductionFormAction.InputChange(input.productId, it)) },
            label = stringResource(R.string.production_qty_label),
            unit = unit,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.s8),
            error = input.error?.let { fieldErrorText(it) },
        )
        if (input.tracked) {
            Text(
                stringResource(
                    R.string.production_available,
                    quantityText(input.available, labelOf(input.unit, fraction = true)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s4),
            )
        }
        // Sin elegir, sale primero el lote que vence antes: la persona solo interviene si quiere.
        input.lots?.let { choices ->
            LotPicker(
                options = lotOptionsOf(choices, withUnassigned = false),
                selectedId = input.chosenLotId,
                unit = unit,
                onSelect = { onAction(ProductionFormAction.ChooseInputLot(input.productId, it)) },
                modifier = Modifier.fillMaxWidth().padding(top = spacing.s8),
            )
        }
        input.leftAfter?.let { left ->
            NoticeCard(
                text = stringResource(
                    R.string.production_warning_negative,
                    quantityText(left, labelOf(input.unit, fraction = true)),
                ),
                variant = NoticeVariant.WARNING,
                modifier = Modifier.padding(top = spacing.s8),
            )
        }
    }
}

/**
 * El lote al que entra lo producido: su código y, en un lote nuevo, su vencimiento. Sin código,
 * entra al lote sin asignar. Con el código de un lote que el producto ya tiene, suma a él y su
 * vencimiento se muestra para leer, como al recibir una compra.
 */
@Composable
private fun OutputLotFields(
    lot: ProductionLotUi,
    uiState: ProductionFormUiState,
    onAction: (ProductionFormAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val help = when {
        lot.code.isBlank() -> stringResource(R.string.purchase_lot_blank_help)
        lot.existing -> stringResource(R.string.production_lot_existing_help)
        else -> null
    }
    Column {
        AppTextField(
            value = lot.code,
            onValueChange = { onAction(ProductionFormAction.LotCodeChange(it)) },
            label = stringResource(R.string.purchase_lot_code_label),
            modifier = Modifier.fillMaxWidth(),
            error = if (lot.tooLong) {
                pluralStringResource(
                    R.plurals.error_text_long,
                    LotRules.MAX_CODE_LENGTH,
                    LotRules.MAX_CODE_LENGTH,
                )
            } else {
                null
            },
            supportingText = help,
            capitalization = KeyboardCapitalization.Characters,
            autoCorrect = false,
        )
        val today = uiState.today
        val weekStartDay = uiState.weekStartDay
        when {
            lot.code.isBlank() -> Unit

            lot.existing ->
                Text(
                    text = lot.existingExpiry?.let {
                        val date = LocalAppFormats.current.date.dayMonthYear(it)
                        stringResource(R.string.lot_expires_on, date)
                    } ?: stringResource(R.string.lot_no_expiry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.s4),
                )

            today != null && weekStartDay != null -> {
                DateField(
                    label = stringResource(R.string.purchase_lot_expiry_label),
                    date = lot.expiry,
                    onDateChange = { onAction(ProductionFormAction.LotExpiryChange(it)) },
                    today = today,
                    weekStartDay = weekStartDay,
                    modifier = Modifier.padding(top = spacing.s8),
                    latest = null,
                )
                if (lot.expiry != null) {
                    InlineTextButton(
                        text = stringResource(R.string.lot_no_expiry),
                        onClick = { onAction(ProductionFormAction.LotExpiryChange(null)) },
                    )
                }
            }

            // Sin la zona del negocio no hay hoy desde el que elegir: el lote entra sin
            // vencimiento.
            else -> Unit
        }
    }
}

/**
 * Costo total, costo por unidad y cómo cambia el costo promedio, con el aviso de insumos sin
 * costo. Los dos costos son un solo foco que TalkBack anuncia al cambiar, y los montos se leen con
 * el nombre de la moneda.
 */
@Composable
private fun FiguresCard(figures: ProductionFiguresUi, uiState: ProductionFormUiState) {
    val spacing = AppTheme.spacing
    val money = LocalAppFormats.current.money
    val unitName = uiState.product?.unit?.let { labelOf(it).symbol }.orEmpty()
    val before = rememberMoneyDisplay(figures.averageBefore, money)
    val after = rememberMoneyDisplay(figures.averageAfter, money)
    val effectSpoken =
        stringResource(R.string.production_cost_effect, before.description, after.description)
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            Column(
                Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                LabeledAmount(
                    stringResource(R.string.production_total_cost_label),
                    figures.totalCost,
                )
                LabeledAmount(
                    stringResource(R.string.production_cost_per, unitName),
                    figures.unitCost,
                )
            }
            DiscreetValue(style = MaterialTheme.typography.bodyMedium) {
                Text(
                    text = stringResource(
                        R.string.production_cost_effect,
                        before.text.text,
                        after.text.text,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = effectSpoken },
                )
            }
            if (figures.hasUncosted) {
                NoticeCard(
                    text = stringResource(R.string.production_warning_no_cost),
                    variant = NoticeVariant.WARNING,
                )
            }
        }
    }
}

/** Un costo con su nombre. Con letra grande, el monto que no cabe al lado baja bajo el nombre. */
@Composable
private fun LabeledAmount(label: String, amount: Money) {
    LedgerLineLayout(
        gap = AppTheme.spacing.s12,
        modifier = Modifier.fillMaxWidth(),
        leading = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = { MoneyText(amount, MoneySize.ROW, sensitive = true) },
    )
}

@Composable
private fun NoticeOf(notice: ProductionNotice, onAction: (ProductionFormAction) -> Unit) {
    when (notice) {
        is ProductionNotice.RegisterFailed -> NoticeCard(
            text = if (notice.outOfSpace) {
                stringResource(R.string.error_device_storage)
            } else {
                stringResource(R.string.production_error_register)
            },
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(ProductionFormAction.Retry) },
        )

        is ProductionNotice.StockCap -> NoticeCard(
            text = stringResource(R.string.production_error_stock_cap, notice.productName),
            variant = NoticeVariant.ERROR,
        )
    }
}

/** La hoja para elegir lo que se produce, con búsqueda. */
@Composable
private fun ProductPickerSheet(
    picker: ProductionPicker?,
    onAction: (ProductionFormAction) -> Unit,
) {
    picker ?: return
    SelectionSheet(
        title = stringResource(R.string.production_product_label),
        items = picker.options,
        key = { it.id },
        query = picker.query,
        onQueryChange = { onAction(ProductionFormAction.QueryChange(it)) },
        onDismissRequest = { onAction(ProductionFormAction.ClosePicker) },
        searchHint = stringResource(R.string.sale_search_hint),
        itemContent = { option ->
            SettingsRow(
                title = option.name,
                action = SettingsRowAction.Navigation {
                    onAction(ProductionFormAction.ChooseProduct(option.id))
                },
            )
        },
        resultsCount = R.plurals.a11y_search_results,
    )
}

@Composable
private fun fieldErrorText(error: ProductionFieldError): String = when (error) {
    ProductionFieldError.Missing -> stringResource(R.string.error_quantity_zero)
    ProductionFieldError.NotANumber -> stringResource(R.string.field_error_number)
    ProductionFieldError.TooHigh -> stringResource(R.string.error_value_high)
    ProductionFieldError.FractionNotAllowed -> stringResource(R.string.error_unit_decimals)
}

/** El formulario de producción, para que las pruebas lo desplacen. */
internal const val PRODUCTION_FORM_TAG = "production-form"

private const val PRODUCT = "product"
private const val QUANTITY = "quantity"
private const val OUTPUT_LOT = "output-lot"
private const val INPUTS = "inputs"
private const val FIGURES = "figures"
private const val NOTE = "note"
private const val SKELETON_ROWS = 4

/** Ancho máximo del formulario en una ventana ancha. */
private val FORM_WIDTH = 600.dp
