package io.github.yesidrobayo.shopledger.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadge
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadgeVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.LedgerRow
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El detalle de un cliente, sin ViewModel: su saldo con palabras, lo vencido, sus datos de contacto,
 * su extracto y sus ventas. La acción fija es `Registrar abono`, que sigue con saldo en cero porque
 * un anticipo deja saldo a favor.
 *
 * El saldo de esta persona sigue a la vista con el modo discreto: es a quien se atiende. Tocar un
 * abono sin anular ofrece anularlo, con confirmación; nada se borra.
 *
 * @param navigation `BACK` cuando ocupa la pantalla; `NONE` cuando va como panel junto a la lista.
 */
@Composable
fun CustomerDetailScreen(
    uiState: CustomerDetailUiState,
    onAction: (CustomerDetailAction) -> Unit,
    modifier: Modifier = Modifier,
    navigation: TopBarNavigation = TopBarNavigation.BACK,
) {
    val ready = (uiState.content as? CustomerDetailContent.Ready)?.view
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppTopBar(
            title = ready?.let { customerName(it) } ?: stringResource(R.string.customer_title),
            navigation = navigation,
            onNavigate = { onAction(CustomerDetailAction.Back) },
            navigationLabel = when (navigation) {
                TopBarNavigation.NONE -> null
                TopBarNavigation.BACK -> stringResource(R.string.a11y_back)
                TopBarNavigation.CLOSE -> stringResource(R.string.a11y_close)
            },
            titleStyle = MaterialTheme.typography.titleLarge,
        ) {
            ScreenHelpLink(HelpTopic.CUSTOMERS)
            if (ready != null) TopBarActions(ready, onAction)
        }
        Box(
            Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth(),
        ) {
            when (val content = uiState.content) {
                CustomerDetailContent.Loading -> LoadingSkeleton()

                CustomerDetailContent.Missing, CustomerDetailContent.ReadError -> EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.state_error_read),
                ) {
                    if (content == CustomerDetailContent.ReadError) {
                        PrimaryActionButton(
                            text = stringResource(R.string.action_retry),
                            onClick = { onAction(CustomerDetailAction.Retry) },
                            variant = ActionVariant.TONAL,
                        )
                    }
                }

                CustomerDetailContent.UnknownZone -> EmptyState(
                    icon = R.drawable.ic_error,
                    title = stringResource(R.string.stats_zone_unknown_title),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.stats_zone_unknown_action),
                        onClick = { onAction(CustomerDetailAction.ChooseZone) },
                        variant = ActionVariant.TONAL,
                    )
                }

                is CustomerDetailContent.Ready -> DetailBody(content.view, uiState, onAction)
            }
        }
        if (ready != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .keepsMessagesAbove()
                    .navigationBarsPadding()
                    .padding(AppTheme.spacing.s16),
                contentAlignment = Alignment.Center,
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.customer_payment_action),
                    onClick = { onAction(CustomerDetailAction.OpenPayment) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.widthIn(
                        max = AppTheme.sizes.maxContentWidth,
                    ).fillMaxWidth(),
                )
            }
        }
    }
}

/** El nombre del cliente, o `Cliente anónimo` si se anonimizó. */
@Composable
internal fun customerName(view: CustomerDetailView): String =
    view.customer.contact?.name ?: stringResource(R.string.customer_anonymous)

/** `Editar`, si tiene datos, y en el menú compartir, ajustar, archivar y anonimizar. */
@Composable
private fun TopBarActions(view: CustomerDetailView, onAction: (CustomerDetailAction) -> Unit) {
    val customer = view.customer
    if (!customer.isAnonymized) {
        TopBarAction(R.drawable.ic_edit, stringResource(R.string.action_edit)) {
            onAction(CustomerDetailAction.Edit)
        }
    }
    val actions = buildList {
        if (view.lines.isNotEmpty()) {
            add(
                OverflowAction(stringResource(R.string.customer_statement_share_action), {
                    onAction(CustomerDetailAction.ShareStatement)
                }),
            )
        }
        add(
            OverflowAction(stringResource(R.string.customer_adjust_balance_action), {
                onAction(CustomerDetailAction.OpenAdjust)
            }),
        )
        if (!customer.isArchived) {
            add(
                OverflowAction(stringResource(R.string.action_archive), {
                    onAction(CustomerDetailAction.AskArchive)
                }),
            )
        }
        if (!customer.isAnonymized) {
            add(
                OverflowAction(
                    stringResource(R.string.customer_anonymize_action),
                    { onAction(CustomerDetailAction.AskAnonymize) },
                    destructive = true,
                ),
            )
        }
    }
    OverflowMenu(actions)
}

@Composable
private fun LoadingSkeleton() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow * 2))
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

@Composable
private fun DetailBody(
    view: CustomerDetailView,
    uiState: CustomerDetailUiState,
    onAction: (CustomerDetailAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val customer = view.customer
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = spacing.s16)) {
        if (customer.isArchived || customer.isAnonymized) {
            item(key = "status") { StatusRow(view, onAction) }
        }
        item(key = "balance") { BalanceBlock(view) }
        // Recordar solo tiene sentido con deuda y con alguien a quien escribirle.
        if (customer.balance > Money.ZERO && !customer.isAnonymized) {
            item(key = "remind") {
                PrimaryActionButton(
                    text = stringResource(R.string.customer_remind_action),
                    onClick = { onAction(CustomerDetailAction.OpenReminder) },
                    variant = ActionVariant.TONAL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.s16, vertical = spacing.s4),
                )
            }
        }
        // Solo se le vende a un cliente vigente: archivado o anónimo ya no se elige al vender.
        if (!customer.isArchived && !customer.isAnonymized) {
            item(key = "sell") {
                PrimaryActionButton(
                    text = stringResource(R.string.customer_sell_action),
                    onClick = { onAction(CustomerDetailAction.SellToCustomer) },
                    variant = ActionVariant.TONAL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.s16, vertical = spacing.s4),
                )
            }
        }
        customer.contact?.phone?.let { phone ->
            item(key = "phone") { PhoneRow(phone) { onAction(CustomerDetailAction.Dial(phone)) } }
        }
        customer.contact?.email?.let { email ->
            item(key = "email") { ContactText(stringResource(R.string.form_email), email) }
        }
        customer.contact?.notes?.let { notes ->
            item(key = "notes") { ContactText(stringResource(R.string.form_notes), notes) }
        }
        item(key = "tabs") {
            SegmentedTabs(
                options = listOf(
                    stringResource(R.string.customer_tab_movements),
                    stringResource(R.string.customer_tab_sales),
                ),
                selectedIndex = uiState.tab.ordinal,
                onSelect = {
                    onAction(CustomerDetailAction.SelectTab(CustomerDetailTab.entries[it]))
                },
                modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
            )
        }
        when (uiState.tab) {
            CustomerDetailTab.MOVEMENTS -> movements(view, onAction)
            CustomerDetailTab.SALES -> sales(uiState, onAction)
        }
    }
}

/** Archivado, con `Reactivar`, o anonimizado, que no se deshace. */
@Composable
private fun StatusRow(view: CustomerDetailView, onAction: (CustomerDetailAction) -> Unit) {
    val spacing = AppTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        if (view.customer.isAnonymized) {
            StatusTag(stringResource(R.string.customer_anonymized_tag))
        }
        if (view.customer.isArchived) {
            StatusTag(stringResource(R.string.status_archived))
            InlineTextButton(stringResource(R.string.action_reactivate), {
                onAction(CustomerDetailAction.Reactivate)
            })
        }
    }
}

/**
 * El saldo con su insignia, y debajo el vencimiento: `Vencido desde el …` con icono, `Vence el …`,
 * `Sin vencimiento` o `Al día`. El estado nunca depende solo del color.
 */
@Composable
private fun BalanceBlock(view: CustomerDetailView) {
    val spacing = AppTheme.spacing
    val balance = view.customer.balance
    // Un foco: Saldo, el monto, la palabra de la insignia y el vencimiento.
    AppCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s16, vertical = spacing.s8)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            stringResource(R.string.customer_balance),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            // El saldo es de quien se atiende: no se oculta con el modo discreto.
            MoneyText(
                amount = if (balance < Money.ZERO) -balance else balance,
                size = MoneySize.LARGE,
                modifier = Modifier.weight(1f, fill = false),
            )
            // El monto ya está al lado, en grande: la insignia dice solo la palabra, `Debe`, `Vencido`
            // o `A favor`, como su forma corta del modo discreto.
            CompositionLocalProvider(LocalDiscreetMode provides true) {
                BalanceBadge(BalanceBadgeVariant.forCustomer(balance, view.overdue), balance)
            }
        }
        DueLine(view.due)
    }
}

@Composable
private fun DueLine(due: DueStatus) {
    val formats = LocalAppFormats.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = when (due) {
        DueStatus.Clear -> stringResource(R.string.customer_balance_clear)

        DueStatus.Credit -> return

        is DueStatus.Overdue ->
            stringResource(R.string.customer_overdue_since, formats.date.dayMonthYear(due.since))

        is DueStatus.DueOn -> stringResource(
            R.string.customer_due_on,
            formats.date.dayMonthYear(due.on),
        )

        DueStatus.NoDue -> stringResource(R.string.customer_no_due)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        if (due is DueStatus.Overdue) {
            Icon(
                painterResource(R.drawable.ic_error),
                contentDescription = null,
                tint = AppTheme.colors.loss,
                modifier = Modifier.size(AppTheme.sizes.iconTag),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (due is DueStatus.Overdue) AppTheme.colors.loss else muted,
        )
    }
}

/** El extracto, del más reciente al más antiguo, con el saldo que dejó cada fila. */
private fun LazyListScope.movements(
    view: CustomerDetailView,
    onAction: (CustomerDetailAction) -> Unit,
) {
    if (view.lines.isEmpty()) {
        item(key = "no-movements") {
            EmptyStateInline(
                title = stringResource(R.string.customer_empty_movements),
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
        }
        return
    }
    items(view.lines, key = { "entry-${it.entry.id}" }) { line ->
        val voidLabel = stringResource(R.string.customer_void_payment_action)
        LedgerRow(
            title = stringResource(typeLabel(line.entry.type)),
            detail = entryDetail(line),
            amount = line.entry.amount,
            balanceAfter = line.entry.balanceAfter,
            balanceText = R.string.customer_ledger_balance_after,
            voided = line.voided,
            onClick = if (line.voidable) {
                { onAction(CustomerDetailAction.AskVoidPayment(line.entry.id)) }
            } else {
                null
            },
            onClickLabel = voidLabel.takeIf { line.voidable },
        )
    }
}

/** La fecha de la fila y lo que la identifica: la venta de un cargo, o la nota que se escribió. */
@Composable
private fun entryDetail(line: StatementLine): String {
    val formats = LocalAppFormats.current
    val date = formats.date.dayMonthYear(line.entry.businessDate)
    val reference = when (val source = line.entry.source) {
        is CustomerEntrySource.Sale -> stringResource(
            R.string.sale_detail_title,
            formats.quantity.format(QuantityMilli.ofUnits(source.saleId), allowsFraction = false),
        )

        CustomerEntrySource.None, is CustomerEntrySource.SaleReturn,
        is CustomerEntrySource.Reverses,
        -> line.entry.note
    } ?: return date
    return stringResource(R.string.customer_ledger_row_detail, date, reference)
}

/** Las ventas del cliente, por tramos: el siguiente se pide al llegar al final. */
private fun LazyListScope.sales(
    uiState: CustomerDetailUiState,
    onAction: (CustomerDetailAction) -> Unit,
) {
    val sales = uiState.sales
    if (sales.rows.isEmpty() && !sales.loading && !sales.failed) {
        item(key = "no-sales") {
            EmptyStateInline(
                title = stringResource(R.string.customer_empty_sales),
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            )
        }
        return
    }
    items(sales.rows, key = { "sale-${it.id}" }) { row ->
        SaleRow(row) { onAction(CustomerDetailAction.OpenSale(row.id)) }
    }
    when {
        sales.failed -> item(key = "sales-failed") {
            EmptyStateInline(
                title = stringResource(R.string.state_error_read),
                modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
            ) {
                InlineTextButton(stringResource(R.string.action_retry), {
                    onAction(CustomerDetailAction.LoadMoreSales)
                })
            }
        }

        !sales.complete -> item(key = "sales-more") {
            // Al llegar aquí se pide el tramo siguiente; mientras llega, un esqueleto.
            LaunchedEffect(sales.rows.size) { onAction(CustomerDetailAction.LoadMoreSales) }
            SkeletonBlock(
                Modifier
                    .fillMaxWidth()
                    .padding(AppTheme.spacing.s16)
                    .heightIn(min = AppTheme.sizes.listRow),
            )
        }
    }
}

/**
 * Una venta: su fecha, el primer producto y cuántos más, y el total. Anulada, va tachada. Si el
 * total no cabe al lado, como con letra grande, pasa debajo del texto.
 */
@Composable
private fun SaleRow(row: SaleHistoryRow, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        LedgerLineLayout(
            gap = spacing.s12,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow)
                    .padding(horizontal = spacing.s16, vertical = spacing.s12),
            leading = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Text(
                        row.firstProductName.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (row.voided) muted else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val date = formats.date.dayMonthYear(row.businessDate)
                    Text(
                        if (row.lineCount > 1) {
                            stringResource(
                                R.string.customer_ledger_row_detail,
                                date,
                                stringResource(R.string.sale_more_items, row.lineCount - 1),
                            )
                        } else {
                            date
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            },
            trailing = {
                MoneyText(
                    row.total,
                    MoneySize.ROW,
                    tone = if (row.voided) MoneyTone.MUTED else MoneyTone.DEFAULT,
                    voided = row.voided,
                )
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** El teléfono, que al tocarlo abre el marcador del sistema. Se lee como un botón. */
@Composable
private fun PhoneRow(phone: String, onDial: () -> Unit) {
    val spacing = AppTheme.spacing
    val reading = stringResource(R.string.supplier_dial_a11y, phone)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onDial)
            .heightIn(min = AppTheme.sizes.listRowSingle)
            .padding(horizontal = spacing.s16, vertical = spacing.s8)
            .clearAndSetSemantics {
                contentDescription = reading
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Icon(
            painterResource(R.drawable.ic_call),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
        Column {
            Text(
                stringResource(R.string.form_phone),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                phone,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Un dato de contacto con su rótulo encima. Las notas se ven enteras. */
@Composable
private fun ContactText(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val SKELETON_ROWS = 5
