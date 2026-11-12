package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseListDay
import io.github.yesidrobayo.shopledger.domain.usecase.expense.ExpenseListRow
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PeriodSelector
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.canGoForward
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.purchase.EXPENSES_SEGMENT
import io.github.yesidrobayo.shopledger.ui.purchase.PURCHASES_SEGMENT
import io.github.yesidrobayo.shopledger.ui.purchase.RecordsSegments
import io.github.yesidrobayo.shopledger.ui.purchase.SALES_SEGMENT
import io.github.yesidrobayo.shopledger.ui.sale.reasonLabel
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Los gastos: el segmento `Gastos` de Registros. Lo que cuesta mantener el negocio, para que la
 * ganancia sea real.
 *
 * - Arriba, el mes con sus flechas, el total del mes en gastos activos y el filtro por categoría.
 * - La lista va por día, con la categoría, la descripción y el valor. Un gasto anulado va tachado,
 *   con su etiqueta, y no suma.
 * - Tocar un gasto abre su hoja, sin campos editables, con `Anular gasto`. `Nuevo gasto` abre la
 *   hoja del gasto nuevo, con la ayuda de que la mercancía va como compra.
 * - En una ventana ancha, la hoja se abre como panel a la derecha de la lista.
 * - Un gasto no revela costo de productos ni ganancia: el modo discreto no lo cambia.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun ExpensesScreen(
    uiState: ExpensesUiState,
    onAction: (ExpensesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= EXPANDED_WIDTH
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.nav_records),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(ExpensesAction.ShowSales) },
                    navigationLabel = stringResource(R.string.a11y_back),
                    actions = {
                        OverflowMenu(
                            listOf(
                                OverflowAction(
                                    stringResource(R.string.recurring_title),
                                    { onAction(ExpensesAction.OpenRecurring) },
                                ),
                            ),
                        )
                    },
                )
                RecordsSegments(selected = EXPENSES_SEGMENT) { index ->
                    when (index) {
                        SALES_SEGMENT -> onAction(ExpensesAction.ShowSales)
                        PURCHASES_SEGMENT -> onAction(ExpensesAction.ShowPurchases)
                    }
                }
                Row(Modifier.weight(1f)) {
                    val listWidth = if (wide) {
                        Modifier.width(AppTheme.sizes.maxContentWidth)
                    } else {
                        Modifier.weight(1f)
                    }
                    Box(listWidth.fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            MonthHeader(uiState, onAction)
                            ExpenseList(uiState, onAction, Modifier.weight(1f))
                        }
                        TonalFab(
                            text = stringResource(R.string.empty_expenses_action),
                            onClick = { onAction(ExpensesAction.NewExpense) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .padding(AppTheme.spacing.s16)
                                .keepsMessagesAbove(),
                        )
                    }
                    if (wide) {
                        VerticalDivider(
                            thickness = AppTheme.sizes.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .consumeWindowInsets(WindowInsets.statusBars),
                        ) {
                            SidePanel(uiState, onAction)
                        }
                    }
                }
            }
            if (!wide) Sheets(uiState, onAction)
        }
    }
    if (uiState.categorySheet) CategoryFilterSheet(uiState, onAction)
    if (uiState.detail?.confirmCorrect == true) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_correct_title),
            body = stringResource(R.string.dialog_correct_expense_body),
            confirmLabel = stringResource(R.string.dialog_correct_action),
            onConfirm = { onAction(ExpensesAction.ConfirmCorrect) },
            onDismiss = { onAction(ExpensesAction.CloseCorrect) },
        )
    }
}

/** El mes con sus flechas, el total del mes y el filtro por categoría. */
@Composable
private fun MonthHeader(uiState: ExpensesUiState, onAction: (ExpensesAction) -> Unit) {
    val spacing = AppTheme.spacing
    val month = uiState.month
    val today = uiState.today
    Column(
        Modifier.padding(horizontal = spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        if (month != null && today != null) {
            val period = ReportPeriod.Month(month)
            PeriodSelector(
                period = period,
                onPrevious = { onAction(ExpensesAction.PreviousMonth) },
                onNext = { onAction(ExpensesAction.NextMonth) },
                showNext = canGoForward(period, today),
                today = today,
            )
        }
        StatTile(
            label = stringResource(R.string.expense_month_total),
            amount = uiState.total ?: Money.ZERO.takeIf {
                uiState.content !is ExpensesContent.Days
            },
            variant = StatTileVariant.CARD,
            loading = uiState.content == ExpensesContent.Loading,
        )
        val chosen = uiState.filterCategory
        val label = chosen?.let { categoryName(it) } ?: stringResource(R.string.filter_category)
        CompactChipRow(
            options = listOf(FILTER_CHIP),
            label = { label },
            selected = if (chosen != null) setOf(FILTER_CHIP) else emptySet(),
            onToggle = { onAction(ExpensesAction.OpenCategories) },
            multiple = true,
        )
    }
}

@Composable
private fun ExpenseList(
    uiState: ExpensesUiState,
    onAction: (ExpensesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Deja libre lo que ocupa el botón flotante, para que no tape la última fila.
    val bottom = AppTheme.sizes.touchTarget + AppTheme.spacing.s40
    Box(modifier.fillMaxWidth()) {
        when (val content = uiState.content) {
            ExpensesContent.Loading -> DelayedSkeleton { ListSkeleton() }

            ExpensesContent.Empty -> CenteredState {
                EmptyState(
                    icon = R.drawable.ic_receipt_long,
                    title = stringResource(R.string.empty_expenses_title),
                    body = stringResource(R.string.empty_expenses_body),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.empty_expenses_action),
                        onClick = { onAction(ExpensesAction.NewExpense) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            ExpensesContent.EmptyFiltered -> CenteredState {
                EmptyState(
                    icon = R.drawable.ic_search,
                    title = stringResource(R.string.empty_expenses_filtered_title),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_clear_filters),
                        onClick = { onAction(ExpensesAction.CategoryFilterChange(null)) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            ExpensesContent.ReadError -> CenteredState {
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(ExpensesAction.Retry) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            is ExpensesContent.Days -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottom),
            ) {
                for (day in content.days) {
                    stickyHeader(key = "day-${day.date}", contentType = DAY) { DayHeader(day) }
                    items(day.rows, key = { it.id }, contentType = { ROW }) { row ->
                        ExpenseRowItem(row, row.id == uiState.selectedExpenseId) {
                            onAction(ExpensesAction.OpenExpense(row.id))
                        }
                    }
                }
            }
        }
    }
}

/** El encabezado fijo de un día: la fecha y lo gastado ese día en gastos activos. */
@Composable
private fun DayHeader(day: ExpenseListDay) {
    val formats = LocalAppFormats.current
    Column(Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.touchTarget)
                    .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
                    .semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formats.date.weekdayDayMonth(day.date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                MoneyText(day.total, MoneySize.ROW, tone = MoneyTone.MUTED)
            }
        }
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Un gasto: la categoría y la descripción, y el valor a la derecha. Anulado, va apagado, tachado y
 * con su etiqueta, sin acciones en su hoja.
 */
@Composable
private fun ExpenseRowItem(row: ExpenseListRow, selected: Boolean, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    Surface(color = if (selected) scheme.secondaryContainer else scheme.surface) {
        Column(Modifier.clickable(onClick = onClick)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow)
                    .padding(horizontal = spacing.s16, vertical = spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    Text(
                        categoryName(row.category),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (row.voided) muted else scheme.onSurface,
                        textDecoration = TextDecoration.LineThrough.takeIf { row.voided },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ExpenseAmount(row, MoneySize.ROW)
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = spacing.s16),
                thickness = AppTheme.sizes.hairline,
                color = scheme.outlineVariant,
            )
        }
    }
}

/** El valor de un gasto, tachado y con «Anulado» si se anuló. */
@Composable
private fun ExpenseAmount(row: ExpenseListRow, size: MoneySize) {
    MoneyText(
        row.amount,
        size,
        tone = if (row.voided) MoneyTone.MUTED else MoneyTone.DEFAULT,
        voided = row.voided,
        voidedLabel = R.string.status_voided_expense,
        voidedDescription = R.string.a11y_voided_expense_amount,
    )
}

/** En una ventana estrecha, el detalle y el gasto nuevo van en hojas. */
@Composable
private fun Sheets(uiState: ExpensesUiState, onAction: (ExpensesAction) -> Unit) {
    val form = uiState.form
    val detail = uiState.detail
    val category = uiState.newCategory
    when {
        category != null -> NewCategorySheet(category, onAction)

        form != null -> AppBottomSheet(
            onDismissRequest = { onAction(ExpensesAction.CloseForm) },
            title = stringResource(R.string.expense_new_title),
            action = { SaveButton(form, onAction) },
        ) { ExpenseFormFields(uiState, form, onAction) }

        detail != null -> AppBottomSheet(
            onDismissRequest = { onAction(ExpensesAction.CloseDetail) },
            title = detailTitle(detail),
            action = detailAction(detail, onAction),
        ) { ExpenseDetailFields(detail, onAction) }
    }
}

/** La hoja de una categoría nueva desde el gasto nuevo: el nombre y `Guardar categoría`. */
@Composable
private fun NewCategorySheet(draft: NewExpenseCategory, onAction: (ExpensesAction) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AppBottomSheet(
        onDismissRequest = { onAction(ExpensesAction.CloseCategory) },
        title = stringResource(R.string.category_new),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.category_save),
                onClick = { onAction(ExpensesAction.SaveCategory) },
                variant = ActionVariant.PRIMARY,
                state = if (draft.saving) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        AppTextField(
            value = draft.name,
            onValueChange = { onAction(ExpensesAction.NewCategoryNameChange(it)) },
            label = stringResource(R.string.form_name),
            modifier = Modifier.focusRequester(focus),
            imeAction = ImeAction.Done,
            error = draft.error?.let {
                stringResource(
                    when (it) {
                        ExpenseCategoryNameError.EMPTY -> R.string.error_category_name_empty
                        ExpenseCategoryNameError.TOO_LONG -> R.string.error_category_name_long
                        ExpenseCategoryNameError.DUPLICATE -> R.string.error_category_name_duplicate
                    },
                )
            },
        )
        if (draft.saveFailed) {
            NoticeCard(stringResource(saveErrorText(outOfSpace = false)), NoticeVariant.ERROR)
        }
    }
}

/**
 * En una ventana ancha, el detalle o el gasto nuevo van a la derecha de la lista, con su botón fijo
 * abajo, sobre el teclado.
 */
@Composable
private fun SidePanel(uiState: ExpensesUiState, onAction: (ExpensesAction) -> Unit) {
    val form = uiState.form
    val detail = uiState.detail
    val spacing = AppTheme.spacing
    val title: String
    val action: (@Composable () -> Unit)?
    val fields: @Composable ColumnScope.() -> Unit
    when {
        form != null -> {
            title = stringResource(R.string.expense_new_title)
            action = { SaveButton(form, onAction) }
            fields = { ExpenseFormFields(uiState, form, onAction) }
        }

        detail != null -> {
            title = detailTitle(detail)
            action = detailAction(detail, onAction)
            fields = { ExpenseDetailFields(detail, onAction) }
        }

        else -> return
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            fields()
        }
        action?.let {
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(spacing.s16)
                    .keepsMessagesAbove(),
            ) { it() }
        }
    }
}

@Composable
private fun detailTitle(detail: ExpenseDetailSheet): String = if (detail.voiding != null) {
    stringResource(R.string.dialog_void_expense_title)
} else {
    categoryName(detail.row.category)
}

/** El botón de la hoja de un gasto: anular, o confirmar la anulación. Un anulado no tiene. */
private fun detailAction(
    detail: ExpenseDetailSheet,
    onAction: (ExpensesAction) -> Unit,
): (@Composable () -> Unit)? {
    if (detail.row.voided) return null
    val voiding = detail.voiding
    return {
        if (voiding == null) {
            // Corregir va sobre anular, los dos delineados: ninguno es la acción principal.
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                PrimaryActionButton(
                    text = stringResource(R.string.dialog_correct_action),
                    onClick = { onAction(ExpensesAction.StartCorrect) },
                    variant = ActionVariant.OUTLINED,
                    state = if (detail.status == ExpenseSheetStatus.WORKING) {
                        ActionState.WORKING
                    } else {
                        ActionState.IDLE
                    },
                )
                PrimaryActionButton(
                    text = stringResource(R.string.dialog_void_expense_action),
                    onClick = { onAction(ExpensesAction.StartVoid) },
                    variant = ActionVariant.OUTLINED,
                )
            }
        } else {
            PrimaryActionButton(
                text = stringResource(R.string.dialog_void_expense_action),
                onClick = { onAction(ExpensesAction.ConfirmVoid) },
                variant = ActionVariant.DESTRUCTIVE,
                enabled = !voiding.noteTooLong,
                state = if (detail.status == ExpenseSheetStatus.WORKING) {
                    ActionState.WORKING
                } else {
                    ActionState.IDLE
                },
            )
        }
    }
}

/**
 * Lo que dice la hoja de un gasto: el valor, la fecha y la descripción, sin campos editables. Al
 * anular, cuándo deja de contar, el motivo y una nota opcional.
 */
@Composable
private fun ColumnScope.ExpenseDetailFields(
    detail: ExpenseDetailSheet,
    onAction: (ExpensesAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    val row = detail.row
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val voiding = detail.voiding
    if (voiding == null) {
        ExpenseAmount(row, MoneySize.LARGE)
        Text(
            formats.date.longDate(row.date),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        row.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (detail.status == ExpenseSheetStatus.FAILED) {
            NoticeCard(stringResource(R.string.error_expense_void), NoticeVariant.ERROR)
        }
        return
    }
    Text(
        stringResource(R.string.dialog_void_expense_body, formats.date.longDate(row.date)),
        style = MaterialTheme.typography.bodyMedium,
        color = muted,
    )
    CompactChipRow(
        options = ExpenseVoidDraft.REASONS,
        label = { stringResource(reasonLabel(it)) },
        selected = setOf(voiding.reason),
        onToggle = { onAction(ExpensesAction.VoidReasonChange(it)) },
        multiple = false,
    )
    AppTextField(
        value = voiding.note,
        onValueChange = { onAction(ExpensesAction.VoidNoteChange(it)) },
        label = stringResource(R.string.form_note),
        singleLine = false,
        error = textTooLong(voiding.noteTooLong, ExpenseVoidDraft.MAX_NOTE_LENGTH),
    )
    if (detail.status == ExpenseSheetStatus.FAILED) {
        NoticeCard(stringResource(R.string.error_expense_void), NoticeVariant.ERROR)
    }
}

/** El botón de guardar el gasto nuevo, con su indicador mientras guarda. */
@Composable
private fun SaveButton(form: ExpenseForm, onAction: (ExpensesAction) -> Unit) {
    PrimaryActionButton(
        text = stringResource(R.string.expense_save),
        onClick = { onAction(ExpensesAction.Save) },
        variant = ActionVariant.PRIMARY,
        state = if (form.status == ExpenseSheetStatus.WORKING) {
            ActionState.WORKING
        } else {
            ActionState.IDLE
        },
    )
}

/**
 * Los campos del gasto nuevo, en el orden de la pantalla: valor con foco, categoría con `Otros` por
 * defecto, descripción, fecha y la ayuda de que la mercancía va como compra.
 */
@Composable
private fun ColumnScope.ExpenseFormFields(
    uiState: ExpensesUiState,
    form: ExpenseForm,
    onAction: (ExpensesAction) -> Unit,
) {
    val errors = form.errors
    // El valor tiene el foco al abrir, con el teclado: es lo único obligatorio que falta.
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { amountFocus.requestFocus() }
    MoneyField(
        value = form.amountText,
        onValueChange = { onAction(ExpensesAction.AmountChange(it)) },
        label = stringResource(R.string.form_amount),
        modifier = Modifier.focusRequester(amountFocus),
        error = amountErrorText(errors),
    )
    val categories = uiState.activeCategories
    val newCategory = stringResource(R.string.category_new)
    // `Nueva categoría` va al final de los chips, y abre su hoja sin perder lo escrito.
    CompactChipRow(
        options = categories.map { it.id } + NEW_CATEGORY_CHIP,
        label = { id ->
            if (id == NEW_CATEGORY_CHIP) {
                newCategory
            } else {
                categories.firstOrNull { it.id == id }?.let { categoryName(it) }.orEmpty()
            }
        },
        selected = setOfNotNull(form.categoryId),
        onToggle = { id ->
            if (id == NEW_CATEGORY_CHIP) {
                onAction(ExpensesAction.NewCategory)
            } else {
                onAction(ExpensesAction.FormCategoryChange(id))
            }
        },
        multiple = false,
    )
    AppTextField(
        value = form.description,
        onValueChange = { onAction(ExpensesAction.DescriptionChange(it)) },
        label = stringResource(R.string.expense_description),
        singleLine = false,
        imeAction = ImeAction.Done,
        error = textTooLong(form.descriptionTooLong, ExpenseForm.MAX_DESCRIPTION_LENGTH),
    )
    val today = uiState.today
    val weekStart = uiState.weekStartDay
    if (today != null && weekStart != null) {
        DateField(
            label = stringResource(R.string.form_date),
            date = form.date ?: today,
            onDateChange = { onAction(ExpensesAction.DateChange(it)) },
            today = today,
            weekStartDay = weekStart,
            error = stringResource(R.string.error_date_future)
                .takeIf { ExpenseFormError.DATE_FUTURE in errors },
        )
    }
    form.notice?.let { FormNotice(it, onAction) }
    NoticeCard(stringResource(R.string.notice_purchase_as_expense), NoticeVariant.INFO)
}

@Composable
private fun FormNotice(notice: ExpenseFormNotice, onAction: (ExpensesAction) -> Unit) {
    when (notice) {
        is ExpenseFormNotice.SaveFailed -> NoticeCard(
            text = stringResource(saveErrorText(notice.outOfSpace)),
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(ExpensesAction.Save) },
        )

        ExpenseFormNotice.CurrencyUnconfirmed -> NoticeCard(
            text = stringResource(R.string.region_required_body),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.region_required_action),
            onAction = { onAction(ExpensesAction.ConfirmCurrency) },
        )

        ExpenseFormNotice.CategoryUnavailable -> NoticeCard(
            text = stringResource(R.string.error_expense_category_unavailable),
            variant = NoticeVariant.WARNING,
        )

        ExpenseFormNotice.CategoryLeftOut -> NoticeCard(
            text = stringResource(R.string.notice_fix_archived_category),
            variant = NoticeVariant.WARNING,
        )
    }
}

@Composable
private fun amountErrorText(errors: Set<ExpenseFormError>): String? {
    val money = LocalAppFormats.current.money
    return when {
        ExpenseFormError.AMOUNT_ZERO in errors -> stringResource(
            R.string.error_value_zero,
            rememberMoneyDisplay(Money.ZERO, money).text.text,
        )

        ExpenseFormError.AMOUNT_NOT_A_NUMBER in errors ->
            stringResource(R.string.field_error_number)

        ExpenseFormError.AMOUNT_DECIMALS in errors -> pluralStringResource(
            R.plurals.error_money_decimals,
            money.currencyDecimals,
            money.currencyDecimals,
        )

        ExpenseFormError.AMOUNT_TOO_HIGH in errors -> stringResource(R.string.error_value_high)

        else -> null
    }
}

@Composable
private fun textTooLong(tooLong: Boolean, max: Int): String? =
    if (tooLong) pluralStringResource(R.plurals.error_text_long, max, max) else null

/** Elegir la categoría del filtro, o `Todas`. Ofrece también las archivadas: sus gastos siguen. */
@Composable
private fun CategoryFilterSheet(uiState: ExpensesUiState, onAction: (ExpensesAction) -> Unit) {
    val options: List<Long?> = listOf<Long?>(null) + uiState.categories.map { it.id }
    val all = stringResource(R.string.filter_all_categories)
    AppBottomSheet(
        onDismissRequest = { onAction(ExpensesAction.CloseCategories) },
        title = stringResource(R.string.filter_category),
    ) {
        CompactChipRow(
            options = options,
            label = { id ->
                id?.let { chosen ->
                    uiState.categories.firstOrNull { it.id == chosen }?.let { categoryName(it) }
                } ?: all
            },
            selected = setOf(uiState.categoryId),
            onToggle = { onAction(ExpensesAction.CategoryFilterChange(it)) },
            multiple = false,
        )
    }
}

/** El nombre visible de una categoría: el de la persona, o el traducido de una inicial. */
@Composable
internal fun categoryName(category: ExpenseCategory): String = SeedText.displayName(
    LocalResources.current,
    SeedTable.EXPENSE_CATEGORIES,
    category.systemKey,
    category.details.name,
)

@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Seis filas de esqueleto con la forma de la lista. */
@Composable
private fun ListSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle))
        }
    }
}

// Desde 840 dp, lista y hoja lado a lado.
private val EXPANDED_WIDTH = 840.dp

private const val FILTER_CHIP = "category"

/** El chip `Nueva categoría`, al final: ningún id de la base es negativo. */
private const val NEW_CATEGORY_CHIP = -1L
private const val SKELETON_ROWS = 6
private const val DAY = "day"
private const val ROW = "row"
