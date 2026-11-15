package io.github.yesidrobayo.shopledger.ui.imports

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportExistingPolicy
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.localization.CsvExportNaming
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ColumnMapRow
import io.github.yesidrobayo.shopledger.ui.components.ColumnMapState
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ImportIssueRow
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * `Importar datos`: un asistente de cuatro pasos con la acción principal fija abajo, sobre el
 * teclado. Elegir qué importar y el archivo; relacionar sus columnas, con las desconocidas primero;
 * revisar las filas, con sus errores y una vista previa; y el resumen de lo importado.
 *
 * - El archivo es solo texto: sus celdas se muestran tal cual y nada se ejecuta ni se abre.
 * - En una ventana ancha, el paso 2 y el 3 ponen la lista a la izquierda y la vista previa viva a
 *   la derecha.
 * - Atrás en el paso 3 vuelve al 2; en el 2 pregunta antes de salir, porque el archivo leído se
 *   pierde.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun ImportScreen(
    uiState: ImportUiState,
    onAction: (ImportAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = uiState.hasFile) { onAction(ImportAction.Back) }
    if (uiState.confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.import_leave_confirm_title),
            body = stringResource(R.string.import_leave_confirm_body),
            confirmLabel = stringResource(R.string.import_leave_confirm_action),
            onConfirm = { onAction(ImportAction.ConfirmLeave) },
            onDismiss = { onAction(ImportAction.DismissLeave) },
        )
    }
    uiState.choosingColumn?.let { index -> FieldSheet(uiState, index, onAction) }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(uiState, onAction)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val expanded = maxWidth >= EXPANDED_WIDTH
                when (uiState.step) {
                    ImportStep.FILE -> Centered { FileStep(uiState, onAction) }

                    ImportStep.MAP, ImportStep.REVIEW ->
                        if (expanded) {
                            Row(Modifier.fillMaxSize()) {
                                StepList(uiState, onAction, Modifier.weight(1f))
                                PreviewPane(uiState, Modifier.weight(1f))
                            }
                        } else {
                            Centered { StepList(uiState, onAction, Modifier.fillMaxSize()) }
                        }

                    ImportStep.DONE -> Centered { DoneStep(uiState) }
                }
            }
            BottomBar(uiState, onAction)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun TopBar(uiState: ImportUiState, onAction: (ImportAction) -> Unit) {
    val back = uiState.step == ImportStep.REVIEW
    AppTopBar(
        title = stringResource(R.string.import_title),
        navigation = if (back) TopBarNavigation.BACK else TopBarNavigation.CLOSE,
        onNavigate = { onAction(if (back) ImportAction.Back else ImportAction.Close) },
        navigationLabel = stringResource(if (back) R.string.a11y_back else R.string.a11y_close),
        titleStyle = MaterialTheme.typography.titleLarge,
    ) {
        ScreenHelpLink(HelpTopic.IMPORT)
        Text(
            stringResource(R.string.import_step, uiState.step.number, ImportStep.TOTAL),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = AppTheme.spacing.s16)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Paso 1: qué importar, el formato, la moneda y la promesa de que nada sale del teléfono. */
@Composable
private fun FileStep(uiState: ImportUiState, onAction: (ImportAction) -> Unit) {
    val spacing = AppTheme.spacing
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        item(key = "title") { StepTitle(R.string.import_step_file_title) }
        item(key = "what") {
            Column(
                Modifier.padding(horizontal = spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                Text(
                    stringResource(R.string.import_what),
                    style = MaterialTheme.typography.titleSmall,
                )
                CompactChipRow(
                    options = ImportTarget.entries,
                    label = { targetLabel(it) },
                    selected = setOf(uiState.target),
                    onToggle = { onAction(ImportAction.ChooseTarget(it)) },
                    multiple = false,
                )
                Text(
                    stringResource(R.string.import_format_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        uiState.currency?.let { currency ->
            item(key = "currency") { CurrencyBlock(uiState, currency, onAction) }
        }
        item(key = "local") {
            NoticeCard(
                text = stringResource(R.string.import_local_notice),
                variant = NoticeVariant.INFO,
                modifier = Modifier.padding(horizontal = spacing.s16),
            )
        }
        uiState.fileProblem?.let { problem ->
            item(key = "problem") {
                NoticeCard(
                    text = problemText(problem),
                    variant = NoticeVariant.ERROR,
                    modifier = Modifier.padding(horizontal = spacing.s16),
                )
            }
        }
        if (uiState.reading) {
            item(key = "reading") {
                Text(
                    stringResource(R.string.import_reading_rows, uiState.rowsRead),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = spacing.s16)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun CurrencyBlock(
    uiState: ImportUiState,
    currency: ImportCurrencyUi,
    onAction: (ImportAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        if (currency.confirmed) {
            Text(
                stringResource(R.string.import_currency_label, currency.code),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when {
            uiState.needsCurrency -> NoticeCard(
                text = stringResource(R.string.import_error_currency),
                variant = NoticeVariant.WARNING,
                actionLabel = stringResource(R.string.import_confirm_currency_action),
                onAction = { onAction(ImportAction.ConfirmCurrency) },
            )

            uiState.target == ImportTarget.PRODUCTS && !currency.locked -> NoticeCard(
                text = stringResource(R.string.import_currency_lock_notice),
                variant = NoticeVariant.INFO,
            )
        }
    }
}

/** Paso 2 o 3 en su lista: lo que va a la izquierda en una ventana ancha. */
@Composable
private fun StepList(
    uiState: ImportUiState,
    onAction: (ImportAction) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        when (uiState.step) {
            ImportStep.MAP -> mapItems(uiState, onAction)
            ImportStep.REVIEW -> reviewItems(uiState, onAction)
            ImportStep.FILE, ImportStep.DONE -> Unit
        }
    }
}

/** Paso 2: la ayuda, el formato del archivo y una fila por columna, las sin relacionar primero. */
private fun LazyListScope.mapItems(uiState: ImportUiState, onAction: (ImportAction) -> Unit) {
    item(key = "title") { StepTitle(R.string.import_step_map_title) }
    item(key = "help") {
        Column(
            Modifier.padding(horizontal = AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
        ) {
            Text(
                stringResource(R.string.import_map_help),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.import_file_rows,
                    uiState.fileName ?: NO_VALUE_TEXT,
                    uiState.rowCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (uiState.separator != null) {
        item(key = "format") { FormatBlock(uiState, onAction) }
    }
    if (uiState.reading) {
        item(key = "reading") {
            Text(
                stringResource(R.string.import_reading_rows, uiState.rowsRead),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = AppTheme.spacing.s16)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    items(uiState.missing, key = { "missing-$it" }) { key ->
        NoticeCard(
            text = stringResource(R.string.import_error_required, fieldLabel(key)),
            variant = NoticeVariant.ERROR,
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        )
    }
    val ordered = uiState.columns.sortedBy { if (it.unmatched) 0 else 1 }
    items(ordered, key = { "column-${it.index}" }) { column ->
        val field = column.field
        val duplicated = field != null && field in uiState.duplicated
        ColumnMapRow(
            header = column.header.ifEmpty { NO_VALUE_TEXT },
            examples = column.examples,
            field = when {
                field != null -> fieldLabel(field)
                column.unmatched -> stringResource(R.string.import_column_choose)
                else -> stringResource(R.string.import_column_skip)
            },
            state = when {
                column.unmatched -> ColumnMapState.UNMATCHED
                column.recognized && !column.chosen -> ColumnMapState.RECOGNIZED
                else -> ColumnMapState.CHOSEN
            },
            onClick = { onAction(ImportAction.OpenColumn(column.index)) },
            error = if (duplicated && field != null) {
                stringResource(R.string.import_error_duplicate_column, fieldLabel(field))
            } else {
                null
            },
        )
    }
}

/** `Formato del archivo`, plegado: los separadores detectados, que la persona puede cambiar. */
@Composable
private fun FormatBlock(uiState: ImportUiState, onAction: (ImportAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .clickable(role = Role.Button) { onAction(ImportAction.ToggleFormat) }
                .padding(horizontal = spacing.s16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.import_file_format),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(AppTheme.sizes.icon)
                    .rotate(if (uiState.formatExpanded) HALF_TURN else 0f),
            )
        }
        if (uiState.formatExpanded) {
            Column(
                Modifier.padding(horizontal = spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                Text(
                    stringResource(R.string.import_column_separator),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CompactChipRow(
                    options = CsvSeparator.entries,
                    label = { stringResource(separatorLabel(it)) },
                    selected = setOfNotNull(uiState.separator),
                    onToggle = { onAction(ImportAction.ChangeSeparator(it)) },
                    multiple = false,
                )
                Text(
                    stringResource(R.string.import_decimal_separator),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CompactChipRow(
                    options = DecimalSeparator.entries,
                    label = { stringResource(decimalLabel(it)) },
                    selected = setOf(uiState.decimal),
                    onToggle = { onAction(ImportAction.ChangeDecimal(it)) },
                    multiple = false,
                )
            }
        }
    }
}

/** Paso 3: resumen, qué hacer con lo que ya existe, avisos, filas con error y vista previa. */
private fun LazyListScope.reviewItems(uiState: ImportUiState, onAction: (ImportAction) -> Unit) {
    val preview = uiState.preview ?: return
    item(key = "title") { StepTitle(R.string.import_step_review_title) }
    item(key = "summary") { Summary(uiState) }
    uiState.notice?.let { notice -> item(key = "notice") { NoticeView(notice, onAction) } }
    if (preview.existingRows > 0) {
        item(key = "policy") {
            Column(
                Modifier.padding(horizontal = AppTheme.spacing.s16),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
            ) {
                Text(
                    stringResource(R.string.import_if_exists),
                    style = MaterialTheme.typography.titleSmall,
                )
                CompactChipRow(
                    options = ImportExistingPolicy.entries,
                    label = { stringResource(policyLabel(it)) },
                    selected = setOf(uiState.policy),
                    onToggle = { onAction(ImportAction.ChoosePolicy(it)) },
                    multiple = false,
                )
                Text(
                    stringResource(R.string.import_if_exists_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val warnings = listOf(
        R.string.import_new_categories_notice to preview.newCategories,
        R.string.import_new_suppliers_notice to preview.newSuppliers,
        R.string.import_stock_no_cost_notice to preview.stockWithoutCost,
    ).filter { (_, count) -> count > 0 }
    items(warnings, key = { "warning-${it.first}" }) { (text, count) ->
        NoticeCard(
            text = stringResource(text, count),
            variant = NoticeVariant.WARNING,
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        )
    }
    if (preview.issues.isNotEmpty()) {
        item(key = "issues-title") { SectionTitle(R.string.import_rows_with_errors) }
        items(preview.issues, key = { "issue-${it.row}-${it.field}" }) { issue ->
            ImportIssueRow(
                row = issue.row,
                column = fieldLabel(issue.field),
                reason = stringResource(issueText(issue.issue)),
            )
        }
        item(key = "save-errors") {
            InlineTextButton(
                stringResource(R.string.import_save_errors_action),
                { onAction(ImportAction.SaveErrors) },
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
        }
    }
    previewItems(uiState)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(uiState: ImportUiState) {
    val preview = uiState.preview ?: return
    val spacing = AppTheme.spacing
    // Un solo foco: `Listas: 406. Con error: 6. Ya existen: 18`.
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s16)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        val style = MaterialTheme.typography.titleSmall
        Text(stringResource(R.string.import_summary_ready, preview.ready.size), style = style)
        Text(stringResource(R.string.import_summary_errors, preview.errorRows), style = style)
        Text(
            stringResource(R.string.import_summary_existing, preview.existingRows),
            style = style,
        )
    }
}

/** La vista previa, a la derecha en una ventana ancha. */
@Composable
private fun PreviewPane(uiState: ImportUiState, modifier: Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        if (uiState.preview == null && uiState.preparing) {
            item(key = "preparing") {
                Text(
                    stringResource(R.string.import_reading),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(AppTheme.spacing.s16),
                )
            }
        }
        if (uiState.step == ImportStep.REVIEW || uiState.preview != null) previewItems(uiState)
    }
}

/** Las primeras filas como quedarían en la app, y el aviso de que el resto no se dibuja. */
private fun LazyListScope.previewItems(uiState: ImportUiState) {
    val rows = uiState.previewRows
    if (rows.isEmpty()) return
    item(key = "preview-title") { SectionTitle(R.string.import_preview) }
    items(rows, key = { "preview-${it.row}" }) { draft -> PreviewRow(draft) }
    val total = uiState.preview?.let { it.ready.size + it.existing.size + it.locked.size } ?: 0
    if (total > ImportUiState.PREVIEW_ROWS) {
        item(key = "preview-limited") {
            Text(
                stringResource(R.string.import_preview_limited, ImportUiState.PREVIEW_ROWS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
        }
    }
}

@Composable
private fun PreviewRow(draft: ImportDraft) {
    val spacing = AppTheme.spacing
    when (draft) {
        is ImportDraft.Product -> {
            val price = draft.salePrice
            val cost = draft.cost
            val margin = if (price != null && cost != null && cost != Money.ZERO) {
                MarginCalculator.margin(price, cost)
            } else {
                Margin.NoData
            }
            ProductRow(
                ProductRowData(
                    name = draft.name,
                    stock = null,
                    trailing = ProductRowTrailing.Catalog(
                        price = price,
                        margin = margin,
                        hasCost = cost != null && cost != Money.ZERO,
                    ),
                ),
            )
        }

        is ImportDraft.Contact -> TwoLines(
            draft.name,
            listOfNotNull(draft.phone, draft.email).joinToString(LIST_GLUE),
        )

        is ImportDraft.PriceIndex -> Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
        ) {
            Text(
                draft.month,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                LocalAppFormats.current.percent.formatChange(draft.change),
                style = AppTheme.amounts.row,
            )
        }
    }
}

@Composable
private fun TwoLines(title: String, detail: String) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Paso 4: cuánto se creó, actualizó, omitió y quedó con error. */
@Composable
private fun DoneStep(uiState: ImportUiState) {
    val done = uiState.done ?: return
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(bottom = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        StepTitle(R.string.import_step_done_title)
        Column(
            Modifier
                .padding(horizontal = spacing.s16)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            val style = MaterialTheme.typography.bodyLarge
            Text(stringResource(R.string.import_done_created, done.created), style = style)
            Text(stringResource(R.string.import_done_updated, done.updated), style = style)
            Text(stringResource(R.string.import_done_skipped, done.skipped), style = style)
            Text(stringResource(R.string.import_done_errors, done.errors), style = style)
        }
    }
}

@Composable
private fun NoticeView(notice: ImportNotice, onAction: (ImportAction) -> Unit) {
    val padding = Modifier.padding(horizontal = AppTheme.spacing.s16)
    when (notice) {
        ImportNotice.APPLY_FAILED -> NoticeCard(
            text = stringResource(R.string.import_error_apply),
            variant = NoticeVariant.ERROR,
            modifier = padding,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(ImportAction.Apply) },
        )

        ImportNotice.APPLY_OUT_OF_SPACE -> NoticeCard(
            text = stringResource(R.string.error_device_storage),
            variant = NoticeVariant.ERROR,
            modifier = padding,
        )

        ImportNotice.PREPARE_FAILED -> NoticeCard(
            text = stringResource(R.string.import_error_prepare),
            variant = NoticeVariant.ERROR,
            modifier = padding,
        )

        ImportNotice.OUTDATED -> NoticeCard(
            text = stringResource(R.string.import_error_outdated),
            variant = NoticeVariant.WARNING,
            modifier = padding,
        )

        ImportNotice.CURRENCY_UNCONFIRMED -> NoticeCard(
            text = stringResource(R.string.import_error_currency),
            variant = NoticeVariant.WARNING,
            modifier = padding,
            actionLabel = stringResource(R.string.import_confirm_currency_action),
            onAction = { onAction(ImportAction.ConfirmCurrency) },
        )

        ImportNotice.SAVE_ERRORS_FAILED -> NoticeCard(
            text = stringResource(R.string.import_error_save_errors),
            variant = NoticeVariant.ERROR,
            modifier = padding,
        )
    }
}

/** La acción principal de cada paso, fija abajo y sobre el teclado. */
@Composable
private fun BottomBar(uiState: ImportUiState, onAction: (ImportAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(Modifier.keepsMessagesAbove().imePadding().navigationBarsPadding()) {
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            when (uiState.step) {
                ImportStep.FILE -> PrimaryActionButton(
                    text = stringResource(R.string.import_choose_file_action),
                    onClick = { onAction(ImportAction.ChooseFile) },
                    variant = ActionVariant.PRIMARY,
                    enabled = !uiState.needsCurrency,
                    state = if (uiState.reading) ActionState.WORKING else ActionState.IDLE,
                )

                ImportStep.MAP -> PrimaryActionButton(
                    text = stringResource(R.string.import_review_action),
                    onClick = { onAction(ImportAction.Review) },
                    variant = ActionVariant.PRIMARY,
                    enabled = !uiState.reading,
                    state = if (uiState.preparing) ActionState.WORKING else ActionState.IDLE,
                )

                ImportStep.REVIEW -> {
                    val count = uiState.toApply
                    if (count == 0) {
                        Text(
                            stringResource(R.string.import_apply_disabled_reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PrimaryActionButton(
                        text = pluralStringResource(R.plurals.import_apply_action, count, count),
                        onClick = { onAction(ImportAction.Apply) },
                        variant = ActionVariant.PRIMARY,
                        enabled = count > 0 && !uiState.preparing,
                        state = if (uiState.applying) ActionState.WORKING else ActionState.IDLE,
                    )
                }

                ImportStep.DONE -> {
                    if (uiState.target == ImportTarget.PRODUCTS) {
                        PrimaryActionButton(
                            text = stringResource(R.string.import_view_products_action),
                            onClick = { onAction(ImportAction.ViewProducts) },
                            variant = ActionVariant.PRIMARY,
                        )
                    }
                    PrimaryActionButton(
                        text = stringResource(R.string.import_another_action),
                        onClick = { onAction(ImportAction.ImportAnother) },
                        variant = if (uiState.target == ImportTarget.PRODUCTS) {
                            ActionVariant.TONAL
                        } else {
                            ActionVariant.PRIMARY
                        },
                    )
                }
            }
        }
    }
}

/** Qué dato trae una columna: los del tipo elegido y `No importar`. */
@Composable
private fun FieldSheet(uiState: ImportUiState, index: Int, onAction: (ImportAction) -> Unit) {
    val column = uiState.columns.firstOrNull { it.index == index } ?: return
    AppBottomSheet(
        onDismissRequest = { onAction(ImportAction.DismissColumn) },
        title = column.header.ifEmpty { NO_VALUE_TEXT },
    ) {
        (uiState.fields + listOf(null)).forEach { field ->
            val label = field?.let { fieldLabel(it) } ?: stringResource(R.string.import_column_skip)
            val selected = column.field == field && (field != null || !column.unmatched)
            FieldOption(label, selected) { onAction(ImportAction.ChooseField(index, field)) }
        }
    }
}

@Composable
private fun FieldOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = AppTheme.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        Icon(
            painter = painterResource(
                if (selected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked,
            ),
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(AppTheme.sizes.iconTag),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StepTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier
            .padding(horizontal = AppTheme.spacing.s16)
            .padding(top = AppTheme.spacing.s8)
            .semantics { heading() },
    )
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .padding(horizontal = AppTheme.spacing.s16)
            .padding(top = AppTheme.spacing.s8)
            .semantics { heading() },
    )
}

/** El nombre de un dato en el idioma de la app: el mismo encabezado de la exportación. */
@Composable
internal fun fieldLabel(key: String): String =
    CsvExportNaming.headerRes(key)?.let { stringResource(it) } ?: key

@Composable
private fun targetLabel(target: ImportTarget): String = stringResource(
    when (target) {
        ImportTarget.PRODUCTS -> R.string.import_type_products
        ImportTarget.CUSTOMERS -> R.string.import_type_customers
        ImportTarget.SUPPLIERS -> R.string.import_type_suppliers
        ImportTarget.PRICE_INDEX -> R.string.import_type_price_index
    },
)

private fun separatorLabel(separator: CsvSeparator): Int = when (separator) {
    CsvSeparator.SEMICOLON -> R.string.region_csv_semicolon
    CsvSeparator.COMMA -> R.string.region_csv_comma
    CsvSeparator.TAB -> R.string.region_csv_tab
}

private fun decimalLabel(decimal: DecimalSeparator): Int = when (decimal) {
    DecimalSeparator.POINT -> R.string.import_decimal_point
    DecimalSeparator.COMMA -> R.string.import_decimal_comma
}

private fun policyLabel(policy: ImportExistingPolicy): Int = when (policy) {
    ImportExistingPolicy.SKIP -> R.string.import_if_exists_skip
    ImportExistingPolicy.UPDATE -> R.string.import_if_exists_update
}

/** Por qué no se pudo usar el archivo. */
@Composable
private fun problemText(problem: ImportFileProblem): String = when (problem) {
    ImportFileProblem.NoHeader -> stringResource(R.string.import_error_no_header)

    is ImportFileProblem.Rejected -> when (problem.reason) {
        ImportRejection.TOO_LARGE -> stringResource(
            R.string.import_error_too_large,
            Formatter.formatShortFileSize(LocalContext.current, ImportLimits.MAX_IMPORT_FILE_BYTES),
        )

        ImportRejection.TOO_MANY_ROWS ->
            stringResource(R.string.import_error_too_many_rows, ImportLimits.MAX_IMPORT_ROWS)

        ImportRejection.EMPTY -> stringResource(R.string.import_error_empty)

        ImportRejection.ENCODING -> stringResource(R.string.import_error_encoding)

        ImportRejection.UNSUPPORTED_TYPE -> stringResource(R.string.import_error_type)

        // Una fila o una celda fuera de tope no es un dato de negocio: el archivo no se lee.
        ImportRejection.TOO_MANY_COLUMNS,
        ImportRejection.CELL_TOO_LARGE,
        ImportRejection.MALFORMED,
        ImportRejection.UNREADABLE,
        -> stringResource(R.string.import_error_read)
    }
}

/** El motivo de una celda que no se importa. */
internal fun issueText(issue: ImportIssue): Int = when (issue) {
    ImportIssue.MISSING -> R.string.import_issue_missing
    ImportIssue.AMOUNT -> R.string.import_issue_amount
    ImportIssue.QUANTITY -> R.string.import_issue_quantity
    ImportIssue.DECIMALS -> R.string.import_issue_decimals
    ImportIssue.LENGTH -> R.string.import_issue_length
    ImportIssue.DUPLICATE -> R.string.import_issue_duplicate
    ImportIssue.BARCODE_USED -> R.string.import_issue_barcode_used
    ImportIssue.UNIT_UNKNOWN -> R.string.import_issue_unit_unknown
    ImportIssue.UNIT_NO_FRACTION -> R.string.import_issue_unit_no_fraction
    ImportIssue.LIMIT -> R.string.import_issue_limit
    ImportIssue.EMAIL -> R.string.import_issue_email
    ImportIssue.PHONE -> R.string.import_issue_phone
    ImportIssue.MONTH -> R.string.import_issue_month
    ImportIssue.INVALID_VALUE -> R.string.import_issue_invalid_value
}

private const val HALF_TURN = 180f
private const val LIST_GLUE = " · "

// Desde aquí la ventana es Expanded: lista a la izquierda y vista previa a la derecha.
private val EXPANDED_WIDTH = 840.dp
