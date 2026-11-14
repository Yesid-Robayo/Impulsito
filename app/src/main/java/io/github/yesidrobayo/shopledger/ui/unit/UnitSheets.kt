package io.github.yesidrobayo.shopledger.ui.unit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.UnitCostDisplay
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CheckboxRow
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DISCREET_TEXT
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PickerField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityField
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.UnitPicker
import io.github.yesidrobayo.shopledger.ui.components.UnitPickerSheet
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.components.unitKindLabel
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.components.unitName
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Dónde va una hoja de edición: encima, como hoja inferior, o con [asPanel] al lado de la lista, en
 * una ventana `Expanded`. En los dos casos el título va arriba y la acción fija abajo, sobre el
 * teclado.
 */
@Composable
internal fun EditorContainer(
    asPanel: Boolean,
    title: String,
    onDismiss: () -> Unit,
    action: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!asPanel) {
        AppBottomSheet(
            onDismissRequest = onDismiss,
            title = title,
            action = action,
            content = content,
        )
        return
    }
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = spacing.s16,
                end = spacing.s4,
                top = spacing.s8,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TopBarAction(R.drawable.ic_close, stringResource(R.string.a11y_close), onDismiss)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
            content = content,
        )
        Box(Modifier.padding(spacing.s16)) { action() }
    }
}

/** El aviso de un guardado fallido y el botón principal, al pie de una hoja. */
@Composable
private fun SaveAction(text: String, saving: Boolean, failure: SaveFailure?, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
        failure?.let {
            NoticeCard(
                text = stringResource(saveErrorText(it.outOfSpace)),
                variant = NoticeVariant.ERROR,
                icon = R.drawable.ic_error,
            )
        }
        PrimaryActionButton(
            text = text,
            onClick = onSave,
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (saving) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

/**
 * La hoja de una unidad: `Nombre`, `Símbolo` opcional de hasta 10 caracteres, `Tipo` y la casilla
 * `Admite decimales`. Los errores van en su campo, y un guardado fallido se avisa arriba del botón
 * sin borrar nada.
 */
@Composable
fun UnitEditorSheet(
    editor: UnitEditor,
    onAction: (UnitEditorAction) -> Unit,
    asPanel: Boolean = false,
) {
    val title = if (editor.editingId == null) R.string.unit_new_action else R.string.unit_edit_title
    EditorContainer(
        asPanel = asPanel,
        title = stringResource(title),
        onDismiss = { onAction(UnitEditorAction.Dismiss) },
        action = {
            SaveAction(
                stringResource(R.string.unit_save_action),
                editor.saving,
                editor.saveFailed,
            ) {
                onAction(UnitEditorAction.Save)
            }
        },
    ) {
        AppTextField(
            value = editor.name,
            onValueChange = { onAction(UnitEditorAction.Name(it)) },
            label = stringResource(R.string.unit_name_label),
            error =
                when (editor.nameProblem) {
                    null -> null

                    UnitNameProblem.EMPTY -> stringResource(R.string.unit_error_name_empty)

                    UnitNameProblem.DUPLICATE -> stringResource(R.string.unit_error_name_repeated)

                    UnitNameProblem.TOO_LONG ->
                        pluralStringResource(
                            R.plurals.error_text_long,
                            UnitEditorModel.NAME_MAX,
                            UnitEditorModel.NAME_MAX,
                        )
                },
        )
        AppTextField(
            value = editor.symbol,
            onValueChange = { onAction(UnitEditorAction.Symbol(it)) },
            label = stringResource(R.string.unit_symbol_label),
            error =
                if (editor.symbolTooLong) {
                    pluralStringResource(
                        R.plurals.error_text_long,
                        UnitEditorModel.SYMBOL_MAX,
                        UnitEditorModel.SYMBOL_MAX,
                    )
                } else {
                    null
                },
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        )
        Text(
            stringResource(R.string.unit_kind_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AppTheme.spacing.s8),
        )
        CompactChipRow(
            options = UnitKind.entries,
            label = { stringResource(unitKindLabel(it)) },
            selected = setOf(editor.kind),
            onToggle = { onAction(UnitEditorAction.Kind(it)) },
            multiple = false,
        )
        CheckboxRow(
            text = stringResource(R.string.unit_allows_fraction),
            checked = editor.allowsFraction,
            onCheckedChange = { onAction(UnitEditorAction.AllowsFraction(it)) },
        )
    }
}

/**
 * La hoja de una conversión: el producto, la unidad alterna, `Equivale a` con la unidad base como
 * sufijo, el precio y el código propios, y dónde se ofrece. Debajo del factor, el resultado se lee en
 * vivo, con el costo por unidad base, que en modo discreto se oculta.
 *
 * Al editar, el producto y la unidad quedan fijos. Cambiar el factor de una conversión guardada pide
 * confirmación, porque solo afecta a documentos nuevos.
 *
 * @param products los productos vigentes que se pueden elegir.
 * @param units todas las unidades: la base de un producto puede estar archivada.
 */
@Composable
fun ConversionEditorSheet(
    editor: ConversionEditor,
    products: List<Product>,
    units: List<MeasureUnit>,
    onAction: (ConversionEditorAction) -> Unit,
    asPanel: Boolean = false,
) {
    val product = products.firstOrNull { it.id == editor.productId }
    val base = product?.let { chosen -> units.firstOrNull { it.id == chosen.details.baseUnitId } }
    val alternate = units.firstOrNull { it.id == editor.unitId }
    var choosingProduct by rememberSaveable { mutableStateOf(false) }
    var choosingUnit by rememberSaveable { mutableStateOf(false) }
    val title =
        if (editor.editingId == null) {
            R.string.unit_add_conversion_action
        } else {
            R.string.unit_conversion_edit_title
        }
    EditorContainer(
        asPanel = asPanel,
        title = stringResource(title),
        onDismiss = { onAction(ConversionEditorAction.Dismiss) },
        action = {
            SaveAction(
                stringResource(R.string.unit_save_conversion_action),
                editor.saving,
                editor.saveFailed,
            ) { onAction(ConversionEditorAction.Save) }
        },
    ) {
        if (editor.productLocked) {
            FixedValue(
                stringResource(R.string.unit_product_label),
                product?.details?.name.orEmpty(),
            )
        } else {
            PickerField(
                label = stringResource(R.string.unit_product_label),
                value = product?.details?.name.orEmpty(),
                onClick = { choosingProduct = true },
            )
            problemOf(editor, ConversionField.PRODUCT)?.let { FieldMessage(it) }
        }
        if (editor.unitLocked) {
            FixedValue(
                stringResource(R.string.unit_alternate_label),
                alternate?.let {
                    unitName(it)
                }.orEmpty(),
            )
        } else {
            UnitPicker(
                label = stringResource(R.string.unit_alternate_label),
                unit = alternate,
                onClick = { choosingUnit = true },
                error = problemOf(editor, ConversionField.UNIT),
            )
        }
        QuantityField(
            value = editor.factor,
            onValueChange = { onAction(ConversionEditorAction.Factor(it)) },
            label = stringResource(R.string.unit_equals_label),
            unit = base?.let { unitLabel(it) } ?: UnitLabel("", allowsFraction = true),
            error = problemOf(editor, ConversionField.FACTOR),
        )
        if (product != null && base != null && alternate != null) {
            ConversionResult(editor.factor, product, alternate, base)
        }
        MoneyField(
            value = editor.price,
            onValueChange = { onAction(ConversionEditorAction.Price(it)) },
            label = stringResource(R.string.unit_price_label),
            error = problemOf(editor, ConversionField.PRICE),
        )
        AppTextField(
            value = editor.barcode,
            onValueChange = { onAction(ConversionEditorAction.Barcode(it)) },
            label = stringResource(R.string.product_barcode),
            error = problemOf(editor, ConversionField.BARCODE),
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        )
        CheckboxRow(
            text = stringResource(R.string.unit_for_sale),
            checked = editor.forSale,
            onCheckedChange = { onAction(ConversionEditorAction.ForSale(it)) },
        )
        CheckboxRow(
            text = stringResource(R.string.unit_for_purchase),
            checked = editor.forPurchase,
            onCheckedChange = { onAction(ConversionEditorAction.ForPurchase(it)) },
        )
        if (editor.editingId != null) {
            InlineTextButton(
                text = stringResource(R.string.unit_conversion_delete_confirm_action),
                onClick = { onAction(ConversionEditorAction.AskDelete) },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (choosingProduct) {
        ProductSheet(products, { onAction(ConversionEditorAction.Product(it)) }) {
            choosingProduct = false
        }
    }
    if (choosingUnit) {
        UnitPickerSheet(
            units = units.filterNot { it.isArchived },
            onPick = { onAction(ConversionEditorAction.AlternateUnit(it.id)) },
            onDismiss = { choosingUnit = false },
            excluded = base?.id,
        )
    }
    ConversionDialogs(editor, onAction)
}

@Composable
private fun ConversionDialogs(
    editor: ConversionEditor,
    onAction: (ConversionEditorAction) -> Unit,
) {
    if (editor.confirmingFactorChange) {
        ConfirmDialog(
            title = stringResource(R.string.unit_change_factor_confirm_title),
            body = stringResource(R.string.unit_change_factor_confirm_body),
            confirmLabel = stringResource(R.string.unit_change_factor_confirm_action),
            onConfirm = { onAction(ConversionEditorAction.ConfirmFactorChange) },
            onDismiss = { onAction(ConversionEditorAction.KeepEditing) },
        )
    }
    if (editor.confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.unit_conversion_delete_confirm_title),
            body = stringResource(R.string.unit_conversion_delete_confirm_body),
            confirmLabel = stringResource(R.string.unit_conversion_delete_confirm_action),
            onConfirm = { onAction(ConversionEditorAction.ConfirmDelete) },
            onDismiss = { onAction(ConversionEditorAction.KeepEditing) },
            variant = ConfirmVariant.DESTRUCTIVE,
        )
    }
}

/** Un dato que ya no se elige, como el producto de una conversión que se edita: rótulo y valor. */
@Composable
private fun FixedValue(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth().padding(
            vertical = AppTheme.spacing.s4,
        ).semantics(mergeDescendants = true) {
        },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FieldMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ProductSheet(products: List<Product>, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val normalized = TextNormalizer.normalize(query)
    SelectionSheet(
        title = stringResource(R.string.unit_product_label),
        items = products.filter { it.details.normalizedName.contains(normalized) },
        key = { it.id },
        query = query,
        onQueryChange = { query = it },
        onDismissRequest = onDismiss,
        searchHint = stringResource(R.string.sale_search_hint),
        itemContent = { product ->
            SettingsRow(
                title = product.details.name,
                action =
                    SettingsRowAction.Navigation {
                        onPick(product.id)
                        onDismiss()
                    },
            )
        },
        resultsCount = R.plurals.a11y_search_results,
    )
}

/**
 * El resultado en vivo: `1 caja = 24 und · Costo por und ¤ 417`. El costo sale del valor del stock,
 * y si redondeado a la unidad menor fuera cero, se muestra con los decimales justos para que no
 * parezca que el producto no tiene costo. En modo discreto el costo se oculta; el factor no.
 */
@Composable
private fun ConversionResult(
    factorText: String,
    product: Product,
    unit: MeasureUnit,
    base: MeasureUnit,
) {
    val formats = LocalAppFormats.current
    val read = NumberInputParser.quantity(
        factorText,
        formats.quantity.inputSymbols,
        Limit.LINE_QUANTITY_MILLI,
    )
    val factor = (read as? NumberInput.Value)?.value?.takeIf { it.milli >= 1L } ?: return
    val alternate = unitLabel(unit).symbol
    val baseLabel = unitLabel(base)
    val quantity =
        quantityText(
            factor,
            baseLabel.copy(
                allowsFraction =
                    baseLabel.allowsFraction || !factor.isWholeUnits,
            ),
        )
    val cost = rememberUnitCost(product)
    val text: AnnotatedString =
        if (cost == null) {
            AnnotatedString(stringResource(R.string.unit_conversion_row, alternate, quantity))
        } else {
            val amount = if (LocalDiscreetMode.current) AnnotatedString(DISCREET_TEXT) else cost
            formatAnnotated(
                stringResource(R.string.unit_conversion_result),
                alternate,
                quantity,
                baseLabel.symbol,
                amount,
            )
        }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** El costo por unidad base de [product], con su símbolo, o `null` si no tiene costo que mostrar. */
@Composable
private fun rememberUnitCost(product: Product): AnnotatedString? {
    val money = LocalAppFormats.current.money
    val exact = UnitCostDisplay.costOf(product) ?: return null
    val maxExtra = MoneyFormatter.MAX_CURRENCY_DECIMALS - money.currencyDecimals
    val scaled = (UnitCostDisplay.scaled(exact, maxExtra) as? Checked.Value)?.value ?: return null
    val formatter =
        remember(money, scaled.extraDecimals) {
            if (scaled.extraDecimals == 0) {
                money
            } else {
                MoneyFormatter(
                    formatLocale = money.formatLocale,
                    currencyCode = money.currencyCode,
                    currencyDecimals = money.currencyDecimals + scaled.extraDecimals,
                    currencySymbol = money.currencySymbol,
                    uiLocale = money.uiLocale,
                )
            }
        }
    return rememberMoneyDisplay(Money(scaled.minor), formatter).text
}

/** El texto del problema de [field], si lo tiene. */
@Composable
private fun problemOf(editor: ConversionEditor, field: ConversionField): String? =
    when (val problem = editor.errors[field]) {
        null -> null

        ConversionProblem.Missing ->
            stringResource(
                if (field == ConversionField.PRODUCT) {
                    R.string.unit_error_product_missing
                } else {
                    R.string.unit_error_unit_missing
                },
            )

        ConversionProblem.Zero -> stringResource(R.string.unit_error_factor_zero)

        ConversionProblem.NotANumber -> stringResource(R.string.field_error_number)

        ConversionProblem.TooHigh -> stringResource(R.string.error_value_high)

        ConversionProblem.SameAsBase -> stringResource(R.string.unit_error_same_as_base)

        ConversionProblem.Taken -> stringResource(R.string.unit_error_conversion_taken)

        is ConversionProblem.DuplicateBarcode ->
            stringResource(R.string.error_barcode_duplicate, problem.productName)

        is ConversionProblem.TooLong ->
            pluralStringResource(R.plurals.error_text_long, problem.max, problem.max)
    }
