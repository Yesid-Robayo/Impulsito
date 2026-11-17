package io.github.yesidrobayo.shopledger.ui.payable

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadge
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadgeVariant
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetValue
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.LedgerRow
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TopBarAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El libro de un proveedor en cuentas por pagar, sin ViewModel: lo que se le debe en grande con su
 * insignia y su vencimiento, y cada fila del libro con el saldo que dejó, del más reciente al más
 * antiguo. La acción fija es `Registrar pago`.
 *
 * Tocar un cargo de compra abre esa compra, y tocar un pago vigente ofrece anularlo, con
 * confirmación: nada se borra. Con el modo discreto, toda cifra de la pantalla se oculta: lo que se
 * debe, cada fila del libro y lo que dejaría el pago; la insignia dice solo la palabra.
 *
 * @param navigation `BACK` cuando ocupa la pantalla; `NONE` cuando va como panel junto a la lista.
 */
@Composable
fun PayableDetailScreen(
    uiState: PayableDetailUiState,
    onAction: (PayableDetailAction) -> Unit,
    modifier: Modifier = Modifier,
    navigation: TopBarNavigation = TopBarNavigation.BACK,
) {
    val ready = (uiState.content as? PayableDetailContent.Ready)?.view
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = ready?.supplier?.details?.name ?: stringResource(R.string.payable_title),
                navigation = navigation,
                onNavigate = { onAction(PayableDetailAction.Back) },
                navigationLabel = when (navigation) {
                    TopBarNavigation.NONE -> null
                    TopBarNavigation.BACK -> stringResource(R.string.a11y_back)
                    TopBarNavigation.CLOSE -> stringResource(R.string.a11y_close)
                },
                titleStyle = MaterialTheme.typography.titleLarge,
            ) {
                ScreenHelpLink(HelpTopic.PAYABLES)
                if (ready != null) {
                    TopBarAction(
                        R.drawable.ic_storefront,
                        stringResource(R.string.payable_open_supplier),
                    ) {
                        onAction(PayableDetailAction.OpenSupplier)
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth(),
            ) {
                when (val content = uiState.content) {
                    PayableDetailContent.Loading -> LoadingSkeleton()

                    PayableDetailContent.Missing, PayableDetailContent.ReadError -> CenteredState {
                        EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.state_error_read),
                        ) {
                            if (content == PayableDetailContent.ReadError) {
                                PrimaryActionButton(
                                    text = stringResource(R.string.action_retry),
                                    onClick = { onAction(PayableDetailAction.Retry) },
                                    variant = ActionVariant.TONAL,
                                )
                            }
                        }
                    }

                    PayableDetailContent.UnknownZone -> CenteredState {
                        EmptyState(
                            icon = R.drawable.ic_error,
                            title = stringResource(R.string.stats_zone_unknown_title),
                        ) {
                            PrimaryActionButton(
                                text = stringResource(R.string.stats_zone_unknown_action),
                                onClick = { onAction(PayableDetailAction.ChooseZone) },
                                variant = ActionVariant.TONAL,
                            )
                        }
                    }

                    is PayableDetailContent.Ready -> DetailBody(content.view, onAction)
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
                        text = stringResource(R.string.payable_payment_action),
                        onClick = { onAction(PayableDetailAction.OpenPayment) },
                        variant = ActionVariant.PRIMARY,
                        modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
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
private fun DetailBody(view: PayableDetailView, onAction: (PayableDetailAction) -> Unit) {
    val spacing = AppTheme.spacing
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = spacing.s16)) {
        if (view.supplier.isArchived) {
            item(key = "archived") {
                StatusTag(
                    stringResource(R.string.payable_archived_tag),
                    Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                )
            }
        }
        item(key = "balance") { BalanceBlock(view) }
        if (view.voidedPurchaseLeftCredit) {
            item(key = "voided-credit") {
                NoticeCard(
                    text = stringResource(R.string.payable_void_purchase_credit),
                    variant = NoticeVariant.INFO,
                    modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                )
            }
        }
        if (view.lines.isEmpty()) {
            item(key = "no-movements") {
                EmptyStateInline(
                    title = stringResource(R.string.customer_empty_movements),
                    modifier = Modifier.padding(horizontal = spacing.s16),
                )
            }
        }
        items(view.lines, key = {
            "entry-${it.entry.id}"
        }) { line -> PayableLedgerRow(line, onAction) }
    }
}

/**
 * `Por pagar` en grande con su insignia, y debajo el vencimiento: `Vencido desde el …` con icono,
 * `Vence el …`, `Sin vencimiento` o `Al día`. El estado nunca depende solo del color.
 */
@Composable
private fun BalanceBlock(view: PayableDetailView) {
    val spacing = AppTheme.spacing
    val balance = view.supplier.balance
    // Un foco: lo que se debe, la insignia y el vencimiento se leen juntos.
    AppCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s16, vertical = spacing.s8)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            stringResource(R.string.payable_total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            MoneyText(
                amount = if (balance < Money.ZERO) -balance else balance,
                size = MoneySize.LARGE,
                modifier = Modifier.weight(1f, fill = false),
                sensitive = true,
            )
            // El monto ya está al lado, en grande: la insignia dice solo la palabra.
            CompositionLocalProvider(LocalDiscreetMode provides true) {
                BalanceBadge(BalanceBadgeVariant.forSupplier(balance, view.overdue), balance)
            }
        }
        DueLine(view.due)
    }
}

@Composable
private fun DueLine(due: PayableDue) {
    val formats = LocalAppFormats.current
    val text = when (due) {
        PayableDue.Clear -> stringResource(R.string.customer_balance_clear)

        PayableDue.Credit -> return

        is PayableDue.Overdue ->
            stringResource(R.string.payable_overdue_since, formats.date.dayMonthYear(due.since))

        is PayableDue.DueOn ->
            stringResource(R.string.purchase_due_status, formats.date.dayMonthYear(due.on))

        PayableDue.NoDue -> stringResource(R.string.customer_no_due)
    }
    val overdue = due is PayableDue.Overdue
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        if (overdue) {
            Icon(
                painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = AppTheme.colors.loss,
                modifier = Modifier.size(AppTheme.sizes.iconTag),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (overdue) {
                AppTheme.colors.loss
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Una fila del libro: un cargo abre su compra, y un pago vigente ofrece anularse. */
@Composable
private fun PayableLedgerRow(line: PayableLine, onAction: (PayableDetailAction) -> Unit) {
    val formats = LocalAppFormats.current
    val content = line.entry.content
    val date = formats.date.dayMonthYear(content.businessDate)
    val purchaseId = content.purchaseId
    val purchaseLabel = purchaseId?.let {
        stringResource(
            R.string.purchase_detail_title,
            formats.quantity.format(QuantityMilli.ofUnits(it), allowsFraction = false),
        )
    }
    val due = content.dueDate?.let {
        stringResource(R.string.purchase_due_status, formats.date.dayMonthYear(it))
    }
    // Un cargo dice su compra y su vencimiento; las demás filas, la nota que se escribió.
    val withPurchase = purchaseLabel?.let { label ->
        due?.let { stringResource(R.string.customer_ledger_row_detail, label, it) } ?: label
    }
    val detail = (withPurchase ?: content.note)
        ?.let { stringResource(R.string.customer_ledger_row_detail, date, it) }
        ?: date
    val voidLabel = stringResource(R.string.payable_void_action)
    val onClick: (() -> Unit)? = when {
        line.voidable -> {
            { onAction(PayableDetailAction.AskVoidPayment(line.entry.id)) }
        }

        purchaseId != null -> {
            { onAction(PayableDetailAction.OpenPurchase(purchaseId)) }
        }

        else -> null
    }
    val clickLabel = if (line.voidable) voidLabel else purchaseLabel
    LedgerRow(
        title = stringResource(typeLabel(content.type)),
        detail = detail,
        amount = content.amount,
        balanceAfter = content.balanceAfter,
        balanceText = R.string.customer_ledger_balance_after,
        voided = line.voided,
        sensitive = true,
        onClick = onClick,
        onClickLabel = clickLabel,
    )
}

/** El nombre de cada tipo de fila del libro del proveedor. */
internal fun typeLabel(type: SupplierLedgerEntryType): Int = when (type) {
    SupplierLedgerEntryType.OPENING_BALANCE -> R.string.payable_ledger_opening_balance
    SupplierLedgerEntryType.CREDIT_PURCHASE -> R.string.payable_ledger_credit_purchase
    SupplierLedgerEntryType.PAYMENT -> R.string.payable_ledger_payment
    SupplierLedgerEntryType.RETURN_CREDIT -> R.string.payable_ledger_return_credit
    SupplierLedgerEntryType.ADJUSTMENT -> R.string.payable_ledger_adjustment
    SupplierLedgerEntryType.ENTRY_VOID -> R.string.payable_ledger_entry_void
}

/**
 * La hoja de `Registrar pago`: monto, con la deuda como sugerencia; cuenta de dinero solo si la
 * persona lleva cuentas; fecha, hoy o pasada; nota; y en vivo lo que queda debiendo, o el saldo a
 * favor que deja pagar de más. Un error se muestra dentro de la hoja y lo escrito se conserva.
 */
@Composable
internal fun SupplierPaymentSheetHost(
    uiState: PayableDetailUiState,
    sheet: SupplierPaymentSheet,
    onAction: (PayableDetailAction) -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = { onAction(PayableDetailAction.DismissSheet) },
        title = stringResource(R.string.payable_payment_action),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.payable_payment_action),
                onClick = { onAction(PayableDetailAction.SavePayment) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        MoneyField(
            value = sheet.amountText,
            onValueChange = { onAction(PayableDetailAction.PaymentAmount(it)) },
            label = stringResource(R.string.payable_payment_amount),
            modifier = Modifier.focusRequester(focus),
            error = amountErrorText(sheet.errors),
        )
        PreviewLine(sheet.preview)
        val choices = uiState.choices
        if (choices.accountsInUse && choices.accounts.isNotEmpty()) {
            Text(
                stringResource(R.string.payable_payment_account),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val resources = LocalResources.current
            CompactChipRow(
                options = choices.accounts.map { it.id },
                label = { id ->
                    val account = choices.accounts.first { it.id == id }
                    SeedText.displayName(
                        resources,
                        SeedTable.MONEY_ACCOUNTS,
                        account.systemKey,
                        account.details.name,
                    )
                },
                selected = setOfNotNull(sheet.accountId),
                // Tocar la elegida la quita: el pago queda solo en el libro del proveedor.
                onToggle = { id ->
                    onAction(
                        PayableDetailAction.PaymentAccount(
                            id.takeUnless {
                                it ==
                                    sheet.accountId
                            },
                        ),
                    )
                },
                multiple = false,
            )
        }
        val today = uiState.today
        val weekStart = uiState.weekStartDay
        if (today != null && weekStart != null) {
            DateField(
                label = stringResource(R.string.payable_payment_date),
                date = sheet.date ?: today,
                onDateChange = { onAction(PayableDetailAction.PaymentDate(it)) },
                today = today,
                weekStartDay = weekStart,
                error = stringResource(R.string.error_date_future)
                    .takeIf { PayableFormError.DATE_FUTURE in sheet.errors },
            )
        }
        AppTextField(
            value = sheet.note,
            onValueChange = { onAction(PayableDetailAction.PaymentNote(it)) },
            label = stringResource(R.string.payable_payment_note),
            singleLine = false,
            imeAction = ImeAction.Done,
            error = if (PayableFormError.NOTE_TOO_LONG in sheet.errors) {
                pluralStringResource(R.plurals.error_text_long, NOTE_MAX, NOTE_MAX)
            } else {
                null
            },
        )
        sheet.notice?.let { FormNotice(it, onAction) }
    }
}

/** `Saldo después del pago: …` o `Queda saldo a favor de …`, mientras se escribe el monto. */
@Composable
private fun PreviewLine(preview: PayablePreview?) {
    val money = LocalAppFormats.current.money
    val (phrase, display) = when (preview) {
        null -> return

        is PayablePreview.Remaining ->
            R.string.payable_payment_balance_after to rememberMoneyDisplay(preview.balance, money)

        is PayablePreview.CreditLeft ->
            R.string.payable_credit_left to rememberMoneyDisplay(preview.credit, money)
    }
    val text = stringResource(phrase, display.text.text)
    // TalkBack lee el nombre de la moneda, nunca el símbolo.
    val description = stringResource(phrase, display.description)
    // Dice lo que se debe: con el modo discreto se oculta, aunque el monto escrito siga a la vista.
    DiscreetValue(style = AppTheme.amounts.small) {
        Text(
            text,
            style = AppTheme.amounts.small,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics {
                contentDescription = description
                isSensitiveData = true
            },
        )
    }
}

@Composable
private fun FormNotice(notice: PayableFormNotice, onAction: (PayableDetailAction) -> Unit) {
    when (notice) {
        is PayableFormNotice.SaveFailed -> NoticeCard(
            text = stringResource(saveErrorText(notice.outOfSpace)),
            variant = NoticeVariant.ERROR,
        )

        PayableFormNotice.CurrencyUnconfirmed -> NoticeCard(
            text = stringResource(R.string.region_required_body),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.region_required_action),
            onAction = { onAction(PayableDetailAction.ConfirmCurrency) },
        )

        PayableFormNotice.AccountUnavailable -> NoticeCard(
            text = stringResource(R.string.payable_payment_account_unavailable),
            variant = NoticeVariant.WARNING,
        )

        PayableFormNotice.UnknownZone -> NoticeCard(
            text = stringResource(R.string.stats_zone_unknown_title),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.stats_zone_unknown_action),
            onAction = { onAction(PayableDetailAction.ChooseZone) },
        )
    }
}

@Composable
private fun amountErrorText(errors: Set<PayableFormError>): String? {
    val money = LocalAppFormats.current.money
    return when {
        PayableFormError.AMOUNT_ZERO in errors -> stringResource(R.string.payable_error_amount_zero)

        PayableFormError.AMOUNT_NOT_A_NUMBER in errors -> stringResource(
            R.string.field_error_number,
        )

        PayableFormError.AMOUNT_DECIMALS in errors -> pluralStringResource(
            R.plurals.error_money_decimals,
            money.currencyDecimals,
            money.currencyDecimals,
        )

        PayableFormError.AMOUNT_TOO_HIGH in errors -> stringResource(R.string.error_value_high)

        else -> null
    }
}

/** La confirmación de anular un pago: el saldo vuelve a subir y el pago queda marcado. */
@Composable
internal fun VoidPaymentConfirm(onAction: (PayableDetailAction) -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.payable_void_confirm_title),
        body = stringResource(R.string.payable_void_confirm_body),
        confirmLabel = stringResource(R.string.payable_void_action),
        onConfirm = { onAction(PayableDetailAction.ConfirmVoid) },
        onDismiss = { onAction(PayableDetailAction.DismissDialog) },
    )
}

/** Un estado que ocupa la pantalla: centrado y desplazable con letra grande. */
@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private const val SKELETON_ROWS = 6
private const val NOTE_MAX = 200
