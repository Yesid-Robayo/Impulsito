package io.github.yesidrobayo.shopledger.ui.expense

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.rule.expense.RecurrenceRules
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
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.WholeNumberField
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.money.PayingAccountChips
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.datetime.DayOfWeek

/**
 * Los gastos recurrentes: tener presentes los que se repiten. La app propone cada uno en su fecha
 * y la persona lo confirma: nunca se registra solo.
 *
 * - Arriba, `Por confirmar`, solo si hay alguno vencido, con `Registrar` y `Omitir` en cada uno.
 *   Debajo, `Programados`, con cada cuánto se repite y la próxima fecha.
 * - Tocar uno abre su hoja para editarlo, con `Archivar`. El menú lleva a los archivados, que se
 *   pueden reactivar.
 * - En una ventana ancha, la hoja va como panel fijo a la derecha de la lista.
 * - Muestra gastos previstos, no costo de productos ni ganancia: el modo discreto no lo cambia.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun RecurringExpensesScreen(
    uiState: RecurringExpensesUiState,
    onAction: (RecurringExpensesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val archivedView = uiState.showArchived
    BackHandler(enabled = archivedView) {
        onAction(RecurringExpensesAction.ShowArchived(false))
    }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // El ancho del bloque, leído aquí: dentro de una fila o columna no se alcanza.
            val boxWidth = maxWidth
            val wide = maxWidth >= EXPANDED_WIDTH
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(
                        if (archivedView) {
                            R.string.recurring_archived_filter
                        } else {
                            R.string.recurring_title
                        },
                    ),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = {
                        if (archivedView) {
                            onAction(RecurringExpensesAction.ShowArchived(false))
                        } else {
                            onAction(RecurringExpensesAction.Back)
                        }
                    },
                    navigationLabel = stringResource(R.string.a11y_back),
                    titleStyle = MaterialTheme.typography.titleLarge,
                    actions = {
                        if (!archivedView) {
                            OverflowMenu(
                                listOf(
                                    OverflowAction(
                                        stringResource(R.string.recurring_archived_filter),
                                        { onAction(RecurringExpensesAction.ShowArchived(true)) },
                                    ),
                                ),
                            )
                        }
                    },
                )
                Row(Modifier.weight(1f)) {
                    val listWidth = if (wide) {
                        Modifier.width(minOf(AppTheme.sizes.maxContentWidth, boxWidth * 2 / 5))
                    } else {
                        Modifier.weight(1f)
                    }
                    Box(listWidth.fillMaxHeight()) {
                        RecurringList(uiState, onAction, Modifier.fillMaxSize())
                        if (!archivedView && uiState.content.offersNew) {
                            TonalFab(
                                text = stringResource(R.string.recurring_new_action),
                                onClick = { onAction(RecurringExpensesAction.New) },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .keepsMessagesAbove(),
                            )
                        }
                    }
                    val form = uiState.form
                    if (wide && form != null) {
                        VerticalDivider(
                            thickness = AppTheme.sizes.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .consumeWindowInsets(WindowInsets.statusBars),
                        ) { FormPanel(uiState, form, onAction) }
                    }
                }
            }
            val form = uiState.form
            if (!wide && form != null) {
                AppBottomSheet(
                    onDismissRequest = { onAction(RecurringExpensesAction.CloseForm) },
                    title = stringResource(formTitle(form)),
                    action = { SaveButton(form, onAction) },
                ) { FormFields(uiState, form, onAction) }
            }
        }
    }
    Dialogs(uiState, onAction)
}

/** Si la pantalla ofrece crear uno: con la lista leída, aunque esté vacía. */
private val RecurringContent.offersNew: Boolean
    get() = this is RecurringContent.Active || this == RecurringContent.Empty

@Composable
private fun RecurringList(
    uiState: RecurringExpensesUiState,
    onAction: (RecurringExpensesAction) -> Unit,
    modifier: Modifier,
) {
    // Deja libre lo que ocupa el botón flotante, para que no tape la última fila.
    val bottom = AppTheme.sizes.touchTarget + AppTheme.spacing.s40
    Box(modifier) {
        when (val content = uiState.content) {
            RecurringContent.Loading -> DelayedSkeleton { ListSkeleton() }

            RecurringContent.Empty -> Centered {
                EmptyState(
                    icon = R.drawable.ic_receipt_long,
                    title = stringResource(R.string.recurring_empty_title),
                    body = stringResource(R.string.recurring_empty_body),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.recurring_new_action),
                        onClick = { onAction(RecurringExpensesAction.New) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            RecurringContent.ReadError -> Centered {
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(RecurringExpensesAction.Retry) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            RecurringContent.UnknownZone -> Centered {
                EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.expense_zone_unknown_title),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.stats_zone_unknown_action),
                        onClick = { onAction(RecurringExpensesAction.ChooseZone) },
                        variant = ActionVariant.TONAL,
                    )
                }
            }

            is RecurringContent.Active -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottom),
            ) {
                if (content.due.isNotEmpty()) {
                    header(R.string.recurring_section_due)
                    items(content.due, key = { "due-${it.id}" }, contentType = { DUE }) { item ->
                        DueRow(item, uiState, onAction)
                    }
                }
                if (content.scheduled.isNotEmpty()) {
                    header(R.string.recurring_section_scheduled)
                    items(content.scheduled, key = { "next-${it.id}" }, contentType = { ROW }) {
                        ScheduledRow(it, uiState, onAction)
                    }
                }
            }

            is RecurringContent.Archived -> if (content.items.isEmpty()) {
                Centered {
                    EmptyState(
                        icon = R.drawable.ic_receipt_long,
                        title = stringResource(R.string.recurring_archived_empty),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(content.items, key = { it.id }, contentType = { ROW }) {
                        ArchivedRow(it, uiState, onAction)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.header(@StringRes title: Int) {
    item(key = "header-$title", contentType = HEADER) { SectionHeader(stringResource(title)) }
}

/**
 * Uno que espera confirmación: su nombre, desde cuándo, el valor, y las dos acciones. `Registrar`
 * va primero: es lo que la persona suele hacer.
 */
@Composable
private fun DueRow(
    item: RecurringExpense,
    uiState: RecurringExpensesUiState,
    onAction: (RecurringExpensesAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    val today = uiState.today
    val due = item.details.nextDueDate
    val since = if (today == null) {
        formats.date.dayMonthYear(due)
    } else {
        formats.date.shortDate(due, today)
    }
    val registerLabel = stringResource(R.string.recurring_register_action)
    val skipLabel = stringResource(R.string.recurring_skip_action)
    Column {
        // TalkBack recorre la fila como un foco y ofrece `Registrar` y `Omitir` como acciones,
        // sin tener que llegar a los botones.
        RowBody(
            name = recurringName(item, uiState),
            detail = stringResource(R.string.recurring_due_since, since),
            amount = item.details.amount,
            onClick = { onAction(RecurringExpensesAction.Edit(item.id)) },
            actions = listOf(
                CustomAccessibilityAction(registerLabel) {
                    onAction(RecurringExpensesAction.Register(item.id))
                    true
                },
                CustomAccessibilityAction(skipLabel) {
                    onAction(RecurringExpensesAction.StartSkip(item.id))
                    true
                },
            ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.s16)
                .padding(bottom = AppTheme.spacing.s12),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            PrimaryActionButton(
                text = registerLabel,
                onClick = { onAction(RecurringExpensesAction.Register(item.id)) },
                variant = ActionVariant.TONAL,
                modifier = Modifier.weight(1f),
            )
            PrimaryActionButton(
                text = skipLabel,
                onClick = { onAction(RecurringExpensesAction.StartSkip(item.id)) },
                variant = ActionVariant.OUTLINED,
                modifier = Modifier.weight(1f),
            )
        }
        RowDivider()
    }
}

/** Uno programado: cada cuánto se repite, la próxima fecha y el valor. Tocarlo lo edita. */
@Composable
private fun ScheduledRow(
    item: RecurringExpense,
    uiState: RecurringExpensesUiState,
    onAction: (RecurringExpensesAction) -> Unit,
) {
    Column {
        RowBody(
            name = recurringName(item, uiState),
            detail = scheduleText(item, uiState.today),
            amount = item.details.amount,
            onClick = { onAction(RecurringExpensesAction.Edit(item.id)) },
        )
        RowDivider()
    }
}

/** Uno archivado: ya no se propone, y se puede reactivar. */
@Composable
private fun ArchivedRow(
    item: RecurringExpense,
    uiState: RecurringExpensesUiState,
    onAction: (RecurringExpensesAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            // El nombre y su estado son un foco; `Reactivar` es otro, porque es un botón.
            Column(
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.s4),
            ) {
                Text(
                    recurringName(item, uiState),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusTag(stringResource(R.string.status_archived))
            }
            // Un ancho mínimo y no fijo: con letra grande, la palabra no se corta.
            PrimaryActionButton(
                text = stringResource(R.string.action_reactivate),
                onClick = { onAction(RecurringExpensesAction.Reactivate(item.id)) },
                variant = ActionVariant.TEXT,
                state = if (uiState.working) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier.widthIn(min = REACTIVATE_WIDTH),
            )
        }
        RowDivider()
    }
}

/**
 * El nombre, una línea debajo y el valor a la derecha. Toda la fila es un solo toque y un solo
 * foco. Si con letra grande el valor no cabe al lado, baja debajo del nombre: nunca se corta.
 *
 * @param actions las acciones que TalkBack ofrece en la fila, además de tocarla.
 */
@Composable
private fun RowBody(
    name: String,
    detail: String,
    amount: Money,
    onClick: () -> Unit,
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    val spacing = AppTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { if (actions.isNotEmpty()) customActions = actions }
            .heightIn(min = AppTheme.sizes.listRow)
            .padding(horizontal = spacing.s16, vertical = spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedgerLineLayout(
            gap = spacing.s12,
            modifier = Modifier.weight(1f),
            leading = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailing = { MoneyText(amount, MoneySize.ROW) },
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = AppTheme.spacing.s16),
        thickness = AppTheme.sizes.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** La descripción, o el nombre de la categoría si no tiene. */
@Composable
internal fun recurringName(item: RecurringExpense, uiState: RecurringExpensesUiState): String {
    item.details.description?.let { return it }
    val category = uiState.categories.firstOrNull { it.id == item.details.expenseCategoryId }
    return category?.let { categoryName(it) }.orEmpty()
}

/** `Cada mes, día 5 · Próximo: 5 dic`. */
@Composable
private fun scheduleText(item: RecurringExpense, today: BusinessDate?): String {
    val details = item.details
    val formats = LocalAppFormats.current.date
    val next = details.nextDueDate
    val every = when (details.recurrence) {
        Recurrence.WEEKLY -> stringResource(
            R.string.recurring_every_weekly,
            formats.weekdayLong(next.date.dayOfWeek),
        )

        Recurrence.MONTHLY ->
            stringResource(R.string.recurring_every_monthly, details.dayOfPeriod)

        Recurrence.YEARLY ->
            stringResource(R.string.recurring_every_yearly, formats.dayMonth(next))
    }
    val date = if (today == null) formats.dayMonthYear(next) else formats.shortDate(next, today)
    val upcoming = stringResource(R.string.recurring_next, date)
    return stringResource(R.string.recurring_schedule_line, every, upcoming)
}

/** El panel de la derecha en una ventana ancha, con el botón fijo abajo. */
@Composable
private fun FormPanel(
    uiState: RecurringExpensesUiState,
    form: RecurringForm,
    onAction: (RecurringExpensesAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Text(
                stringResource(formTitle(form)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            FormFields(uiState, form, onAction)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(spacing.s16)
                .keepsMessagesAbove(),
        ) { SaveButton(form, onAction) }
    }
}

@StringRes
private fun formTitle(form: RecurringForm): Int =
    if (form.editingId == null) R.string.recurring_new_title else R.string.recurring_edit_title

@Composable
private fun SaveButton(form: RecurringForm, onAction: (RecurringExpensesAction) -> Unit) {
    PrimaryActionButton(
        text = stringResource(R.string.recurring_save_action),
        onClick = { onAction(RecurringExpensesAction.Save) },
        variant = ActionVariant.PRIMARY,
        state = if (form.status == ExpenseSheetStatus.WORKING) {
            ActionState.WORKING
        } else {
            ActionState.IDLE
        },
    )
}

/**
 * Los campos, en el orden de la pantalla: valor con foco, categoría, descripción, cada cuánto se
 * repite, el día según la repetición, la próxima fecha, la cuenta si la persona lleva cuentas y el
 * recordatorio. La ayuda fija dice que solo se registra al confirmarlo.
 */
@Composable
private fun ColumnScope.FormFields(
    uiState: RecurringExpensesUiState,
    form: RecurringForm,
    onAction: (RecurringExpensesAction) -> Unit,
) {
    val errors = form.errors
    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (form.editingId == null) amountFocus.requestFocus() }
    MoneyField(
        value = form.amountText,
        onValueChange = { onAction(RecurringExpensesAction.AmountChange(it)) },
        label = stringResource(R.string.recurring_field_amount),
        modifier = Modifier.focusRequester(amountFocus),
        error = stringResource(R.string.recurring_error_value).takeIf {
            errors.any { it in AMOUNT_ERRORS }
        },
    )
    FieldLabel(R.string.recurring_field_category)
    val categories = uiState.formCategories
    CompactChipRow(
        options = categories.map { it.id },
        label = { id ->
            categories.firstOrNull { it.id == id }?.let { categoryName(it) }.orEmpty()
        },
        selected = setOfNotNull(form.categoryId),
        onToggle = { onAction(RecurringExpensesAction.CategoryChange(it)) },
        multiple = false,
    )
    AppTextField(
        value = form.description,
        onValueChange = { onAction(RecurringExpensesAction.DescriptionChange(it)) },
        label = stringResource(R.string.recurring_field_description),
        singleLine = false,
        imeAction = ImeAction.Done,
        error = if (form.descriptionTooLong) {
            pluralStringResource(
                R.plurals.error_text_long,
                ExpenseForm.MAX_DESCRIPTION_LENGTH,
                ExpenseForm.MAX_DESCRIPTION_LENGTH,
            )
        } else {
            null
        },
    )
    FieldLabel(R.string.recurring_field_repeats)
    CompactChipRow(
        options = Recurrence.entries,
        label = { stringResource(recurrenceLabel(it)) },
        selected = setOf(form.recurrence),
        onToggle = { onAction(RecurringExpensesAction.RecurrenceChange(it)) },
        multiple = false,
    )
    val dayError = stringResource(R.string.recurring_error_day)
        .takeIf { RecurringFormError.DAY_INVALID in errors }
    when (form.recurrence) {
        Recurrence.WEEKLY -> {
            FieldLabel(R.string.recurring_field_weekday)
            val formats = LocalAppFormats.current.date
            CompactChipRow(
                options = (1..RecurrenceRules.DAYS_IN_WEEK).toList(),
                label = { formats.weekdayLong(DayOfWeek(it)) },
                selected = setOf(form.day),
                onToggle = { onAction(RecurringExpensesAction.WeekdayChange(it)) },
                multiple = false,
            )
        }

        Recurrence.MONTHLY -> WholeNumberField(
            value = form.dayText,
            onValueChange = { onAction(RecurringExpensesAction.DayOfMonthChange(it)) },
            label = stringResource(R.string.recurring_field_day_of_month),
            error = dayError,
        )

        // Cada año, el día es el de la próxima fecha.
        Recurrence.YEARLY -> Unit
    }
    val today = uiState.today
    val weekStart = uiState.weekStartDay
    if (today != null && weekStart != null) {
        DateField(
            label = stringResource(R.string.recurring_field_next_date),
            date = form.nextDate,
            onDateChange = { onAction(RecurringExpensesAction.NextDateChange(it)) },
            today = today,
            weekStartDay = weekStart,
            latest = null,
            error = dayError.takeIf { form.recurrence != Recurrence.MONTHLY },
        )
    }
    if (form.mayFallOnMonthEnd) {
        Text(
            stringResource(R.string.recurring_month_end_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    PayingAccountChips(uiState.accounts, form.accountId, R.string.recurring_field_account) {
        onAction(RecurringExpensesAction.AccountChange(it))
    }
    SettingsRow(
        title = stringResource(R.string.recurring_field_reminder),
        action = SettingsRowAction.Toggle(form.reminder) {
            onAction(RecurringExpensesAction.ReminderChange(it))
        },
    )
    if (RecurringFormError.CATEGORY_MISSING in errors) {
        NoticeCard(
            stringResource(R.string.error_expense_category_unavailable),
            NoticeVariant.WARNING,
        )
    }
    form.notice?.let { FormNotice(it, onAction) }
    NoticeCard(
        stringResource(R.string.recurring_help, stringResource(R.string.app_name)),
        NoticeVariant.INFO,
    )
    if (form.editingId != null) {
        PrimaryActionButton(
            text = stringResource(R.string.action_archive),
            onClick = { onAction(RecurringExpensesAction.StartArchive) },
            variant = ActionVariant.OUTLINED,
        )
    }
}

@Composable
private fun FieldLabel(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FormNotice(notice: RecurringFormNotice, onAction: (RecurringExpensesAction) -> Unit) {
    when (notice) {
        is RecurringFormNotice.SaveFailed -> NoticeCard(
            text = stringResource(saveErrorText(notice.outOfSpace)),
            variant = NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(RecurringExpensesAction.Save) },
        )

        RecurringFormNotice.CategoryUnavailable -> NoticeCard(
            text = stringResource(R.string.error_expense_category_unavailable),
            variant = NoticeVariant.WARNING,
        )
    }
}

/** Las confirmaciones de omitir un vencimiento y de archivar. */
@Composable
private fun Dialogs(
    uiState: RecurringExpensesUiState,
    onAction: (RecurringExpensesAction) -> Unit,
) {
    val formats = LocalAppFormats.current.date
    uiState.skipping?.let { item ->
        val details = item.details
        val next =
            RecurrenceRules.next(details.recurrence, details.dayOfPeriod, details.nextDueDate)
        ConfirmDialog(
            title = stringResource(R.string.recurring_skip_confirm_title),
            body = stringResource(R.string.recurring_skip_confirm_body, formats.longDate(next)),
            confirmLabel = stringResource(R.string.recurring_skip_action),
            onConfirm = { onAction(RecurringExpensesAction.ConfirmSkip) },
            onDismiss = { onAction(RecurringExpensesAction.DismissSkip) },
        )
    }
    uiState.archiving?.let { item ->
        ConfirmDialog(
            title = stringResource(
                R.string.recurring_archive_confirm_title,
                recurringName(item, uiState),
            ),
            body = stringResource(R.string.recurring_archive_confirm_body),
            confirmLabel = stringResource(R.string.action_archive),
            onConfirm = { onAction(RecurringExpensesAction.ConfirmArchive) },
            onDismiss = { onAction(RecurringExpensesAction.DismissArchive) },
        )
    }
}

@StringRes
private fun recurrenceLabel(recurrence: Recurrence): Int = when (recurrence) {
    Recurrence.WEEKLY -> R.string.recurring_recurrence_weekly
    Recurrence.MONTHLY -> R.string.recurring_recurrence_monthly
    Recurrence.YEARLY -> R.string.recurring_recurrence_yearly
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Cuatro filas de esqueleto con la forma de la lista. */
@Composable
private fun ListSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
        }
    }
}

private val AMOUNT_ERRORS = setOf(
    RecurringFormError.AMOUNT_ZERO,
    RecurringFormError.AMOUNT_NOT_A_NUMBER,
    RecurringFormError.AMOUNT_DECIMALS,
    RecurringFormError.AMOUNT_TOO_HIGH,
)

// Desde 840 dp, lista y hoja lado a lado.
private val EXPANDED_WIDTH = 840.dp

private val REACTIVATE_WIDTH = 140.dp
private const val SKELETON_ROWS = 4
private const val DUE = "due"
private const val ROW = "row"
private const val HEADER = "header"
