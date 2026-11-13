package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPricePreview
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceOutcome
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceRules
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CheckboxRow
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.MoneyAmountText
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PercentField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.moneyStyle
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La actualización masiva de precios, sin ViewModel: qué productos, cuánto y cómo redondear arriba;
 * el resumen y la vista previa debajo, con lo que conviene revisar primero; y la acción fija
 * `Actualizar N precios`. Cada fila se puede quitar. Los precios se ven siempre; el margen nuevo,
 * que es una cifra del negocio, se oculta en modo discreto.
 */
@Composable
fun BulkPriceScreen(
    uiState: BulkPriceUiState,
    onAction: (BulkPriceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    // Los nombres visibles de las listas, para la etiqueta de cada fila de la vista previa.
    val resources = LocalResources.current
    val names = uiState.lists.associate {
        it.id to SeedText.displayName(resources, SeedTable.PRICE_LISTS, it.systemKey, it.name)
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.bulk_title),
            navigation = TopBarNavigation.BACK,
            onNavigate = { onAction(BulkPriceAction.Back) },
            navigationLabel = stringResource(R.string.a11y_back),
        ) {
            // Deshacer la última solo se ofrece si al menos un precio volvería.
            if (uiState.canUndo && !uiState.undoing) {
                OverflowMenu(
                    listOf(
                        OverflowAction(stringResource(R.string.bulk_undo_last), {
                            onAction(BulkPriceAction.OpenUndo)
                        }),
                    ),
                )
            }
        }
        if (uiState.content == BulkPreviewContent.NoPrices) {
            EmptyState(
                icon = R.drawable.ic_sell,
                title = stringResource(R.string.empty_bulk_title),
                body = stringResource(R.string.empty_bulk_body),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.empty_bulk_action),
                    onClick = { onAction(BulkPriceAction.Back) },
                    variant = ActionVariant.TONAL,
                )
            }
            return@Column
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = spacing.s16),
        ) {
            item(key = "options") { Options(uiState, onAction) }
            when (val content = uiState.content) {
                BulkPreviewContent.Loading -> item(key = "loading") { PreviewSkeleton() }

                BulkPreviewContent.NeedsInput -> item(key = "needs-input") {
                    EmptyStateInline(
                        title = stringResource(R.string.bulk_empty_preview),
                        modifier = Modifier.padding(horizontal = spacing.s16),
                    )
                }

                BulkPreviewContent.ReadError -> item(key = "error") {
                    EmptyStateInline(
                        title = stringResource(R.string.state_error_read),
                        modifier = Modifier.padding(horizontal = spacing.s16),
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.action_retry),
                            onClick = { onAction(BulkPriceAction.Retry) },
                            variant = ActionVariant.TONAL,
                        )
                    }
                }

                // La pantalla ya mostró el vacío arriba.
                BulkPreviewContent.NoPrices -> Unit

                is BulkPreviewContent.Ready -> {
                    val preview = content.preview
                    item(key = "summary") { Summary(preview, uiState) }
                    // Con varias listas, cada fila dice cuál cambia.
                    val many = uiState.input.listIds.size > 1
                    items(preview.changes, key = {
                        "row-${it.productId}-${it.priceListId}"
                    }) { line ->
                        PreviewRow(line, names[line.priceListId].takeIf { many }, onAction)
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .keepsMessagesAbove()
                .navigationBarsPadding()
                .imePadding()
                .padding(spacing.s16),
            contentAlignment = Alignment.Center,
        ) {
            val changing = uiState.changing
            PrimaryActionButton(
                text = pluralStringResource(R.plurals.bulk_apply, changing, changing),
                onClick = { onAction(BulkPriceAction.Apply) },
                variant = ActionVariant.PRIMARY,
                enabled = changing > 0,
                state = if (uiState.applying) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth(),
            )
        }
    }
    uiState.sheet?.let { ScopeSheet(it, uiState, onAction) }
    val undo = uiState.undo
    if (uiState.confirmingUndo && undo != null) {
        val reverts = undo.reverts.size
        ConfirmDialog(
            title = stringResource(R.string.dialog_undo_prices_title),
            body = pluralStringResource(R.plurals.dialog_undo_prices_body, reverts, reverts),
            confirmLabel = stringResource(R.string.action_undo),
            onConfirm = { onAction(BulkPriceAction.ConfirmUndo) },
            onDismiss = { onAction(BulkPriceAction.DismissUndo) },
        )
    }
}

/** `Qué productos`, `Cuánto` y `Redondear`, con sus campos. */
@Composable
private fun Options(uiState: BulkPriceUiState, onAction: (BulkPriceAction) -> Unit) {
    val spacing = AppTheme.spacing
    val input = uiState.input
    Column(
        Modifier.fillMaxWidth().padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        SectionLabel(R.string.bulk_which)
        val chosenCategory = uiState.categories.firstOrNull { it.id == input.categoryId }
        val chosenSupplier = uiState.suppliers.firstOrNull { it.id == input.supplierId }
        CompactChipRow(
            options = BulkScopeKind.entries,
            label = { kind ->
                when (kind) {
                    BulkScopeKind.ALL -> stringResource(R.string.bulk_scope_all)

                    BulkScopeKind.CATEGORY ->
                        chosenCategory?.details?.name
                            ?: stringResource(R.string.bulk_scope_category)

                    BulkScopeKind.SUPPLIER ->
                        chosenSupplier?.details?.name
                            ?: stringResource(R.string.bulk_scope_supplier)

                    BulkScopeKind.PICK -> stringResource(R.string.bulk_scope_pick)
                }
            },
            selected = setOf(input.scope),
            onToggle = { onAction(BulkPriceAction.SelectScope(it)) },
            multiple = false,
        )
        if (uiState.offeredLists.isNotEmpty()) {
            SectionLabel(R.string.price_list_title)
            val resources = LocalResources.current
            CompactChipRow(
                options = uiState.offeredLists,
                label = {
                    SeedText.displayName(resources, SeedTable.PRICE_LISTS, it.systemKey, it.name)
                },
                selected = uiState.offeredLists.filter { it.id in input.listIds }.toSet(),
                onToggle = { onAction(BulkPriceAction.ToggleList(it.id)) },
                multiple = true,
            )
        }
        SectionLabel(R.string.bulk_mode_label)
        CompactChipRow(
            options = OFFERED_MODES,
            label = { stringResource(modeLabel(it)) },
            selected = setOf(input.mode),
            onToggle = { onAction(BulkPriceAction.SelectMode(it)) },
            multiple = false,
        )
        SectionLabel(R.string.bulk_how_much)
        val formats = LocalAppFormats.current
        when (input.mode) {
            BulkPriceMode.PERCENT -> {
                PercentField(
                    value = input.percentText,
                    onValueChange = { onAction(BulkPriceAction.PercentText(it)) },
                    label = stringResource(R.string.bulk_percent_label),
                    allowNegative = true,
                    imeAction = ImeAction.Done,
                )
                CompactChipRow(
                    options = SHORTCUTS,
                    label = { formats.percent.formatChange(it) },
                    selected = emptySet(),
                    onToggle = { onAction(BulkPriceAction.PercentShortcut(it)) },
                    multiple = false,
                )
            }

            BulkPriceMode.AMOUNT -> MoneyField(
                value = input.amountText,
                onValueChange = { onAction(BulkPriceAction.AmountText(it)) },
                label = stringResource(R.string.price_list_bulk_mode_amount),
                imeAction = ImeAction.Done,
                allowNegative = true,
            )

            BulkPriceMode.TARGET_MARGIN -> PercentField(
                value = input.marginText,
                onValueChange = { onAction(BulkPriceAction.MarginText(it)) },
                label = stringResource(R.string.price_list_bulk_mode_target_margin),
                imeAction = ImeAction.Done,
            )

            // No se ofrece todavía: llega con la moneda de referencia.
            BulkPriceMode.REFERENCE_RATE -> Unit
        }
        if (uiState.highPercent) HighPercentNotice(uiState)
        SectionLabel(R.string.bulk_round)
        val savedStep = uiState.savedStep
        val roundings = buildList {
            add(BulkRounding.NONE)
            if (savedStep > Money.ZERO) add(BulkRounding.SAVED_STEP)
            add(BulkRounding.OTHER)
        }
        val stepText = rememberMoneyDisplay(savedStep, formats.money).text.text
        CompactChipRow(
            options = roundings,
            label = { rounding ->
                when (rounding) {
                    BulkRounding.NONE -> stringResource(R.string.bulk_no_round)
                    BulkRounding.SAVED_STEP -> stringResource(R.string.bulk_round_to, stepText)
                    BulkRounding.OTHER -> stringResource(R.string.bulk_round_other)
                }
            },
            selected = setOf(input.rounding),
            onToggle = { onAction(BulkPriceAction.SelectRounding(it)) },
            multiple = false,
        )
        if (input.rounding == BulkRounding.OTHER) {
            MoneyField(
                value = input.stepText,
                onValueChange = { onAction(BulkPriceAction.StepText(it)) },
                label = stringResource(R.string.bulk_step_label),
                imeAction = ImeAction.Done,
            )
        }
    }
}

@Composable
private fun SectionLabel(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = AppTheme.spacing.s8),
    )
}

/**
 * El aviso de un porcentaje grande, con un ejemplo calculado: protege de escribir `80` por `8`.
 * El ejemplo usa un precio redondo de cien unidades enteras de la moneda.
 */
@Composable
private fun HighPercentNotice(uiState: BulkPriceUiState) {
    val percent = uiState.percent ?: return
    val formats = LocalAppFormats.current
    var scale = 1L
    repeat(formats.money.currencyDecimals) { scale *= 10L }
    val example = Money(EXAMPLE_UNITS * scale)
    val after = when (val outcome = BulkPriceRules.outcome(example, null, percent, Money.ZERO)) {
        is BulkPriceOutcome.Changes -> outcome.newPrice
        BulkPriceOutcome.Same, is BulkPriceOutcome.Excluded -> return
    }
    NoticeCard(
        text = stringResource(
            R.string.bulk_high_percent,
            formats.percent.formatChange(percent),
            rememberMoneyDisplay(example, formats.money).text.text,
            rememberMoneyDisplay(after, formats.money).text.text,
        ),
        variant = NoticeVariant.WARNING,
        icon = R.drawable.ic_warning,
    )
}

/** `Cambian: 24 · Con aviso: 2`, y los que quedan fuera, con su motivo en una sola frase. */
@Composable
private fun Summary(preview: BulkPricePreview, uiState: BulkPriceUiState) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        HorizontalDivider(thickness = AppTheme.sizes.hairline, color = AppTheme.colors.cardBorder)
        Text(
            stringResource(R.string.bulk_summary, preview.changes.size, preview.flagged),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (preview.excluded.isNotEmpty()) {
            val zero = rememberMoneyDisplay(Money.ZERO, LocalAppFormats.current.money).text.text
            NoticeCard(
                text = pluralStringResource(
                    R.plurals.notice_bulk_excluded,
                    preview.excluded.size,
                    preview.excluded.size,
                    zero,
                ),
                variant = NoticeVariant.INFO,
            )
        }
        if (preview.changes.isEmpty() && uiState.input.removed.isEmpty()) {
            EmptyStateInline(title = stringResource(R.string.bulk_nothing_changed))
        }
    }
}

/**
 * Un producto que cambia: su nombre, el precio de ahora tachado y el nuevo, y el margen que deja.
 * Con margen negativo o con un redondeo que se aparta de lo pedido, su etiqueta. Se puede quitar.
 */
@Composable
private fun PreviewRow(
    line: BulkPriceLine,
    listName: String?,
    onAction: (BulkPriceAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val change = line.change
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(
                    start = spacing.s16,
                    end = spacing.s4,
                    top = spacing.s8,
                    bottom = spacing.s8,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
                Text(
                    line.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                listName?.let { StatusTag(it) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s8),
                ) {
                    // El precio de ahora, tachado y apagado: no está anulado, así que sin etiqueta.
                    val money = LocalAppFormats.current.money
                    val oldPrice = rememberMoneyDisplay(change.oldPrice, money)
                    val struck = TextDecoration.LineThrough
                    MoneyAmountText(
                        display = oldPrice,
                        style = moneyStyle(MoneySize.ROW).copy(textDecoration = struck),
                        color = muted,
                    )
                    MoneyText(change.newPrice, MoneySize.ROW)
                }
                MarginLine(change.margin)
                if (change.negativeMargin) {
                    StatusTag(
                        stringResource(R.string.notice_negative_margin),
                        tone = StatusTone.LOSS,
                    )
                }
                if (change.distorted) {
                    Text(
                        stringResource(R.string.notice_bulk_rounding),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
            TopBarAction(
                R.drawable.ic_close,
                stringResource(R.string.bulk_remove_a11y, line.name),
            ) { onAction(BulkPriceAction.RemoveRow(line.productId)) }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** El margen nuevo, oculto en modo discreto. Sin costo propio, un guion. */
@Composable
private fun MarginLine(margin: Margin) {
    val text = when (margin) {
        is Margin.Defined -> LocalAppFormats.current.percent.format(margin.value)
        Margin.NoData, Margin.OutOfRange -> NO_VALUE_TEXT
    }
    val negative = margin is Margin.Defined && margin.value < BasisPoints.ZERO
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val marginColor = if (negative) AppTheme.colors.loss else muted
    DiscreetValue(style = MaterialTheme.typography.bodySmall) {
        Text(
            stringResource(R.string.product_margin_value, text),
            style = MaterialTheme.typography.bodySmall,
            color = marginColor,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        )
    }
}

@Composable
private fun PreviewSkeleton() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

/** La hoja de elegir una categoría, un proveedor o los productos uno por uno. */
@Composable
private fun ScopeSheet(
    sheet: BulkSheet,
    uiState: BulkPriceUiState,
    onAction: (BulkPriceAction) -> Unit,
) {
    var query by rememberSaveable(sheet) { mutableStateOf("") }
    val wanted = TextNormalizer.normalize(query.trim())
    val hint = stringResource(R.string.sale_search_hint)
    val close = { onAction(BulkPriceAction.CloseSheet) }
    when (sheet) {
        BulkSheet.CATEGORY ->
            SelectionSheet(
                title = stringResource(R.string.bulk_scope_category),
                items = uiState.categories.filter { it.details.normalizedName.contains(wanted) },
                key = { it.id },
                query = query,
                onQueryChange = { query = it },
                onDismissRequest = close,
                searchHint = hint,
                itemContent = { category ->
                    SettingsRow(
                        category.details.name,
                        SettingsRowAction.Navigation {
                            onAction(BulkPriceAction.PickCategory(category.id))
                        },
                    )
                },
            )

        BulkSheet.SUPPLIER ->
            SelectionSheet(
                title = stringResource(R.string.bulk_scope_supplier),
                items = uiState.suppliers.filter { it.details.normalizedName.contains(wanted) },
                key = { it.id },
                query = query,
                onQueryChange = { query = it },
                onDismissRequest = close,
                searchHint = hint,
                itemContent = { supplier ->
                    SettingsRow(
                        supplier.details.name,
                        SettingsRowAction.Navigation {
                            onAction(BulkPriceAction.PickSupplier(supplier.id))
                        },
                    )
                },
            )

        BulkSheet.PRODUCTS ->
            SelectionSheet(
                title = stringResource(R.string.bulk_scope_pick),
                items = uiState.products.filter {
                    it.salePrice > Money.ZERO && it.details.normalizedName.contains(wanted)
                },
                key = { it.id },
                query = query,
                onQueryChange = { query = it },
                onDismissRequest = close,
                searchHint = hint,
                itemContent = { product ->
                    CheckboxRow(
                        text = product.details.name,
                        checked = product.id in uiState.input.picked,
                        onCheckedChange = { onAction(BulkPriceAction.TogglePicked(product.id)) },
                    )
                },
            )
    }
}

/** Los atajos de porcentaje: +5 %, +10 % y −10 %. */
private val SHORTCUTS = listOf(BasisPoints(500L), BasisPoints(1_000L), BasisPoints(-1_000L))

/** Cien unidades enteras de la moneda: un precio redondo para el ejemplo del aviso. */
private const val EXAMPLE_UNITS = 100L
private const val SKELETON_ROWS = 6

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun BulkPricePreview() {
    PreviewTheme { BulkPriceScreen(BulkPriceSamples.ready(), {}) }
}

@ThemePreviews
@Composable
private fun BulkPriceHighPercentPreview() {
    PreviewTheme(discreet = true) { BulkPriceScreen(BulkPriceSamples.ready("80", 8_000L), {}) }
}

@Preview(name = "Sin porcentaje")
@Composable
private fun BulkPriceNeedsInputPreview() {
    PreviewTheme { BulkPriceScreen(BulkPriceSamples.needsInput, {}) }
}

@Preview(name = "Sin precios")
@Composable
private fun BulkPriceNoPricesPreview() {
    PreviewTheme { BulkPriceScreen(BulkPriceUiState(content = BulkPreviewContent.NoPrices), {}) }
}

@Preview(name = "Deshacer la última")
@Composable
private fun BulkPriceUndoPreview() {
    PreviewTheme { BulkPriceScreen(BulkPriceSamples.confirmingUndo(), {}) }
}

/** Los modos que se ofrecen hoy: la cotización llega con la moneda de referencia. */
private val OFFERED_MODES =
    listOf(BulkPriceMode.PERCENT, BulkPriceMode.AMOUNT, BulkPriceMode.TARGET_MARGIN)

private fun modeLabel(mode: BulkPriceMode): Int = when (mode) {
    BulkPriceMode.PERCENT -> R.string.price_list_bulk_mode_percent
    BulkPriceMode.AMOUNT -> R.string.price_list_bulk_mode_amount
    BulkPriceMode.TARGET_MARGIN -> R.string.price_list_bulk_mode_target_margin
    BulkPriceMode.REFERENCE_RATE -> R.string.price_list_bulk_mode_reference_rate
}
