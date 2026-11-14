package io.github.yesidrobayo.shopledger.ui.label

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.label.LabelTemplate
import io.github.yesidrobayo.shopledger.domain.model.print.PairedPrinter
import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.label.LabelItem
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CheckboxRow
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.QuantityStepper
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.UnitLabel
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Dibuja la vista previa de una etiqueta con el mismo motor que imprime. La pantalla es pura: la
 * ruta le pasa el dibujo real, y las vistas previas de Compose, el que tengan a mano.
 */
typealias LabelPreviewSlot = @Composable (LabelItem, LabelTemplate, LabelSize, Modifier) -> Unit

/**
 * `Etiquetas imprimibles`: qué productos y cuántas copias, la plantilla, el tamaño, la vista
 * previa del primero y los avisos, con `Imprimir {n} etiquetas` fijo abajo. Desde `Medium`, la
 * lista va a la izquierda, y la plantilla y la vista previa a la derecha.
 */
@Composable
fun LabelsScreen(
    uiState: LabelsUiState,
    onAction: (LabelsAction) -> Unit,
    preview: LabelPreviewSlot,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = stringResource(R.string.label_title),
            navigation = TopBarNavigation.CLOSE,
            onNavigate = { onAction(LabelsAction.Back) },
            navigationLabel = stringResource(R.string.a11y_close),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= MEDIUM_WIDTH) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxSize(),
                        contentPadding = PaddingValues(bottom = AppTheme.spacing.s16),
                    ) {
                        scopeItems(uiState, onAction)
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxSize(),
                        contentPadding = PaddingValues(bottom = AppTheme.spacing.s16),
                    ) {
                        outputItems(uiState, onAction, preview)
                    }
                }
            } else {
                LazyColumn(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(bottom = AppTheme.spacing.s16),
                ) {
                    scopeItems(uiState, onAction)
                    outputItems(uiState, onAction, preview)
                }
            }
        }
        ActionBar(uiState, onAction)
    }
    uiState.sheet?.let { Sheet(it, uiState, onAction) }
    if (uiState.confirmingGenerate) {
        val count = uiState.plan.withoutCode.size
        ConfirmDialog(
            title = stringResource(R.string.label_generate_confirm_title),
            body = pluralStringResource(R.plurals.label_generate_confirm_body, count, count),
            confirmLabel = stringResource(R.string.label_generate_codes_action),
            onConfirm = { onAction(LabelsAction.ConfirmGenerate) },
            onDismiss = { onAction(LabelsAction.DismissGenerate) },
        )
    }
}

/** `Qué productos`, la fecha de `Solo lo que cambió de precio` y la lista con sus copias. */
private fun LazyListScope.scopeItems(uiState: LabelsUiState, onAction: (LabelsAction) -> Unit) {
    item(key = "scope") { ScopeOptions(uiState, onAction) }
    when {
        uiState.readError -> item(key = "error") {
            EmptyStateInline(
                title = stringResource(R.string.state_error_read),
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(LabelsAction.Retry) },
                    variant = ActionVariant.TONAL,
                )
            }
        }

        uiState.waiting -> item(key = "loading") { ListSkeleton() }

        uiState.chosen.isEmpty() -> item(key = "empty") { EmptyList(uiState, onAction) }

        else -> items(uiState.chosen, key = { "row-${it.productId}" }) { item ->
            val tooLong = item.productId in uiState.tooLongIds
            ChosenRow(item, tooLong, onAction)
        }
    }
}

/** `Plantilla`, `Tamaño`, la vista previa y los avisos de lo que queda por fuera. */
private fun LazyListScope.outputItems(
    uiState: LabelsUiState,
    onAction: (LabelsAction) -> Unit,
    preview: LabelPreviewSlot,
) {
    item(key = "output") { OutputOptions(uiState, onAction) }
    uiState.printable.firstOrNull()?.let { first ->
        item(key = "preview-${first.productId}") {
            preview(first, uiState.template, uiState.size, Modifier.padding(AppTheme.spacing.s16))
        }
    }
    item(key = "notices") { Notices(uiState, onAction) }
}

@Composable
private fun ScopeOptions(uiState: LabelsUiState, onAction: (LabelsAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        SectionLabel(R.string.label_which_title)
        val category = uiState.categories.firstOrNull { it.id == uiState.categoryId }
        CompactChipRow(
            options = LabelScope.entries,
            label = { scope ->
                when (scope) {
                    LabelScope.PICK -> stringResource(R.string.label_scope_choose)

                    LabelScope.CATEGORY ->
                        category?.details?.name ?: stringResource(R.string.label_scope_category)

                    LabelScope.CHANGED -> stringResource(R.string.label_scope_changed)
                }
            },
            selected = setOf(uiState.scope),
            onToggle = { onAction(LabelsAction.SelectScope(it)) },
            multiple = false,
        )
        val today = uiState.today
        val weekStart = uiState.weekStartDay
        if (uiState.scope == LabelScope.CHANGED && today != null && weekStart != null) {
            DateField(
                label = stringResource(R.string.label_since),
                date = uiState.since,
                onDateChange = { onAction(LabelsAction.SetSince(it)) },
                today = today,
                weekStartDay = weekStart,
            )
        }
    }
}

@Composable
private fun EmptyList(uiState: LabelsUiState, onAction: (LabelsAction) -> Unit) {
    val modifier = Modifier.padding(horizontal = AppTheme.spacing.s16)
    val since = uiState.since
    if (uiState.scope == LabelScope.CHANGED && since != null) {
        val date = LocalAppFormats.current.date.longDate(since)
        EmptyStateInline(
            title = stringResource(R.string.label_no_changes_since, date),
            modifier = modifier,
        )
        return
    }
    EmptyStateInline(title = stringResource(R.string.label_empty_title), modifier = modifier) {
        PrimaryActionButton(
            text = stringResource(R.string.label_scope_choose),
            onClick = { onAction(LabelsAction.OpenPick) },
            variant = ActionVariant.TONAL,
        )
    }
}

/** Una fila de la lista: el nombre y sus copias. Con una copia, el menos la quita. */
@Composable
private fun ChosenRow(item: LabelItem, tooLong: Boolean, onAction: (LabelsAction) -> Unit) {
    val spacing = AppTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.listRow)
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (tooLong) {
                StatusTag(
                    stringResource(R.string.label_code_too_long),
                    icon = R.drawable.ic_warning,
                    tone = StatusTone.WARNING,
                )
            }
        }
        QuantityStepper(
            quantity = QuantityMilli.ofUnits(item.copies.toLong()),
            unit = COPIES,
            productName = item.name,
            onDecrement = { onAction(LabelsAction.Decrement(item.productId)) },
            onIncrement = { onAction(LabelsAction.Increment(item.productId)) },
            removesLine = item.copies == 1,
            valueText = LocalAppFormats.current.quantity.format(
                QuantityMilli.ofUnits(item.copies.toLong()),
                allowsFraction = false,
            ),
            valueDescription = pluralStringResource(
                R.plurals.a11y_label_copies,
                item.copies,
                item.copies,
                item.name,
            ),
        )
    }
}

@Composable
private fun OutputOptions(uiState: LabelsUiState, onAction: (LabelsAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        SectionLabel(R.string.label_template_title)
        CompactChipRow(
            options = LabelTemplate.entries,
            label = { stringResource(templateLabel(it)) },
            selected = setOf(uiState.template),
            onToggle = { onAction(LabelsAction.SelectTemplate(it)) },
            multiple = false,
        )
        SectionLabel(R.string.label_size_title)
        CompactChipRow(
            options = LabelSize.entries,
            label = { stringResource(sizeLabel(it)) },
            selected = setOf(uiState.size),
            onToggle = { onAction(LabelsAction.SelectSize(it)) },
            multiple = false,
        )
    }
}

/** Lo que queda por fuera y por qué, cada aviso con su salida. */
@Composable
private fun Notices(uiState: LabelsUiState, onAction: (LabelsAction) -> Unit) {
    if (uiState.waiting || uiState.readError) return
    val spacing = AppTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        val plan = uiState.plan
        val withoutCode = plan.withoutCode.size
        if (uiState.template.showsCode && withoutCode > 0) {
            NoticeCard(
                text =
                    pluralStringResource(R.plurals.label_no_code_notice, withoutCode, withoutCode),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.label_generate_codes_action)
                    .takeUnless { uiState.generating },
                onAction = { onAction(LabelsAction.GenerateCodes) },
            )
        }
        val withoutPrice = plan.withoutPrice.size
        if (uiState.template.showsPrice && withoutPrice > 0) {
            NoticeCard(
                text = pluralStringResource(
                    R.plurals.label_excluded_no_price,
                    withoutPrice,
                    withoutPrice,
                ),
                variant = NoticeVariant.INFO,
            )
        }
        if (uiState.tooLong.isNotEmpty()) {
            NoticeCard(
                text = stringResource(R.string.label_code_too_long),
                variant = NoticeVariant.WARNING,
                actionLabel =
                    uiState.wider?.let { stringResource(R.string.label_use_wider_action) },
                onAction = { onAction(LabelsAction.UseWider) },
            )
        }
        if (uiState.needsPrinter && uiState.notice == null) {
            NoticeCard(
                text = stringResource(R.string.label_no_printer_notice),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.label_configure_printer_action),
                onAction = { onAction(LabelsAction.ConfigurePrinter) },
            )
        }
    }
}

/**
 * Lo fijo de abajo: un error al imprimir, con `Reintentar` y `Crear PDF`, y la acción principal.
 * Los mensajes se apoyan encima, nunca sobre el botón.
 */
@Composable
private fun ActionBar(uiState: LabelsUiState, onAction: (LabelsAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .keepsMessagesAbove()
            .navigationBarsPadding()
            .imePadding()
            .padding(spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        val wide = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth()
        uiState.notice?.let { notice ->
            NoticeCard(
                text = noticeText(notice, uiState.printer),
                variant = NoticeVariant.ERROR,
                actionLabel = when (notice) {
                    is LabelNotice.PrintFailed -> stringResource(R.string.action_retry)
                    LabelNotice.PermissionDenied, is LabelNotice.PrintersUnavailable -> null
                },
                onAction = { onAction(LabelsAction.Print) },
                secondaryLabel = stringResource(R.string.label_create_pdf_action),
                onSecondary = { onAction(LabelsAction.CreatePdf) },
                modifier = wide,
            )
        }
        val count = uiState.labelCount
        val text = when {
            uiState.printing -> stringResource(R.string.printer_status_printing)
            uiState.size.paper == null -> stringResource(R.string.label_create_pdf_action)
            else -> pluralStringResource(R.plurals.label_print_count_action, count, count)
        }
        PrimaryActionButton(
            text = text,
            onClick = { onAction(LabelsAction.Print) },
            variant = ActionVariant.PRIMARY,
            enabled = count > 0 && !uiState.waiting,
            state = if (uiState.printing) ActionState.WORKING else ActionState.IDLE,
            modifier = wide,
        )
    }
}

@Composable
private fun noticeText(notice: LabelNotice, printer: PairedPrinter?): String {
    val name = printer?.name ?: stringResource(R.string.printer_section_bluetooth)
    return when (notice) {
        LabelNotice.PermissionDenied -> stringResource(R.string.printer_error_permission)
        is LabelNotice.PrintersUnavailable -> failureText(notice.reason, name)
        is LabelNotice.PrintFailed -> failureText(notice.reason, name)
    }
}

@Composable
private fun failureText(reason: PrintFailure, printer: String): String = when (reason) {
    PrintFailure.NO_ADAPTER -> stringResource(R.string.printer_error_no_bluetooth)
    PrintFailure.PERMISSION_MISSING -> stringResource(R.string.printer_error_permission)
    PrintFailure.BLUETOOTH_OFF -> stringResource(R.string.printer_error_bluetooth_off)
    PrintFailure.NOT_BONDED -> stringResource(R.string.printer_error_unpaired, printer)
    PrintFailure.CONNECT_FAILED -> stringResource(R.string.printer_error_connect, printer)
    PrintFailure.WRITE_FAILED -> stringResource(R.string.label_error_print)
}

/** La hoja de productos, la de categorías o la de impresoras emparejadas. */
@Composable
private fun Sheet(sheet: LabelSheet, uiState: LabelsUiState, onAction: (LabelsAction) -> Unit) {
    var query by rememberSaveable(sheet) { mutableStateOf("") }
    val wanted = TextNormalizer.normalize(query.trim())
    val hint = stringResource(R.string.sale_search_hint)
    val close = { onAction(LabelsAction.CloseSheet) }
    when (sheet) {
        LabelSheet.PRODUCTS -> SelectionSheet(
            title = stringResource(R.string.label_scope_choose),
            items = uiState.products.filter {
                !it.isArchived && it.details.normalizedName.contains(wanted)
            },
            key = { it.id },
            query = query,
            onQueryChange = { query = it },
            onDismissRequest = close,
            searchHint = hint,
            itemContent = { product ->
                CheckboxRow(
                    text = product.details.name,
                    checked = product.id in uiState.picked,
                    onCheckedChange = { onAction(LabelsAction.TogglePicked(product.id)) },
                )
            },
        )

        LabelSheet.CATEGORY -> SelectionSheet(
            title = stringResource(R.string.label_scope_category),
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
                        onAction(LabelsAction.PickCategory(category.id))
                    },
                )
            },
        )

        LabelSheet.PRINTERS -> AppBottomSheet(
            onDismissRequest = close,
            title = stringResource(R.string.printer_choose_paired_title),
        ) {
            if (uiState.printers.isEmpty()) {
                Text(
                    stringResource(R.string.printer_paired_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
                )
            }
            val fallback = stringResource(R.string.printer_section_bluetooth)
            uiState.printers.forEach { printer ->
                SettingsRow(
                    printer.name ?: fallback,
                    SettingsRowAction.Navigation { onAction(LabelsAction.ChoosePrinter(printer)) },
                )
            }
        }
    }
}

@Composable
private fun ListSkeleton() {
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

@Composable
private fun SectionLabel(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = AppTheme.spacing.s8),
    )
}

private fun templateLabel(template: LabelTemplate): Int = when (template) {
    LabelTemplate.NAME_PRICE -> R.string.label_template_name_price
    LabelTemplate.NAME_PRICE_CODE -> R.string.label_template_name_price_code
    LabelTemplate.CODE_ONLY -> R.string.label_template_code_only
}

private fun sizeLabel(size: LabelSize): Int = when (size) {
    LabelSize.MM_58 -> R.string.label_size_58
    LabelSize.MM_80 -> R.string.label_size_80
    LabelSize.SHEET -> R.string.label_size_sheet
}

/** Las copias se cuentan enteras: el selector no ofrece fracciones. */
private val COPIES = UnitLabel(symbol = "", allowsFraction = false)

/** Desde aquí, la lista y la vista previa van lado a lado. */
private val MEDIUM_WIDTH = 600.dp
private const val SKELETON_ROWS = 4
