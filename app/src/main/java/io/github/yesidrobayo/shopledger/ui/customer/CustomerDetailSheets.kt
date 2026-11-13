package io.github.yesidrobayo.shopledger.ui.customer

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerLedgerRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.PaymentPreview
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.payment.paymentMethodName
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La hoja de `Registrar abono`: monto, con la deuda como sugerencia; método y cuenta solo si la
 * persona lleva cuentas de dinero; fecha, hoy o pasada; nota; y en vivo cómo queda el saldo, o el
 * saldo a favor que deja. Un error se muestra dentro de la hoja y lo escrito se conserva.
 */
@Composable
internal fun PaymentSheetHost(
    uiState: CustomerDetailUiState,
    sheet: PaymentSheet,
    onAction: (CustomerDetailAction) -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = { onAction(CustomerDetailAction.DismissSheet) },
        title = stringResource(R.string.customer_payment_action),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.customer_payment_action),
                onClick = { onAction(CustomerDetailAction.SavePayment) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        PaymentFields(uiState, sheet, onAction)
    }
}

@Composable
private fun ColumnScope.PaymentFields(
    uiState: CustomerDetailUiState,
    sheet: PaymentSheet,
    onAction: (CustomerDetailAction) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    MoneyField(
        value = sheet.amountText,
        onValueChange = { onAction(CustomerDetailAction.PaymentAmount(it)) },
        label = stringResource(R.string.customer_payment_amount),
        modifier = Modifier.focusRequester(focus),
        error = amountErrorText(sheet.errors, positiveOnly = true),
    )
    PreviewLine(sheet.preview)
    val choices = uiState.choices
    if (choices.accountsInUse) {
        if (choices.methods.isNotEmpty()) {
            Text(
                stringResource(R.string.customer_payment_method),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompactChipRow(
                options = choices.methods.map { it.id },
                label = { id ->
                    choices.methods.first { it.id == id }.let { paymentMethodName(it) }
                },
                selected = setOfNotNull(sheet.methodId),
                onToggle = { onAction(CustomerDetailAction.ChooseMethod(it)) },
                multiple = false,
            )
        }
        val method = choices.methods.firstOrNull { it.id == sheet.methodId }
        // La cuenta se pregunta solo si el método no trae una: con una, el dinero va ahí.
        if (method?.details?.moneyAccountId == null && choices.accounts.isNotEmpty()) {
            Text(
                stringResource(R.string.customer_payment_account),
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
                // Tocar la elegida la quita: el abono queda solo en el libro del cliente.
                onToggle = { id ->
                    onAction(
                        CustomerDetailAction.PaymentAccount(
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
    }
    val today = uiState.today
    val weekStart = uiState.weekStartDay
    if (today != null && weekStart != null) {
        DateField(
            label = stringResource(R.string.customer_payment_date),
            date = sheet.date ?: today,
            onDateChange = { onAction(CustomerDetailAction.PaymentDate(it)) },
            today = today,
            weekStartDay = weekStart,
            error = stringResource(R.string.error_date_future)
                .takeIf { CustomerFormError.DATE_FUTURE in sheet.errors },
        )
    }
    AppTextField(
        value = sheet.note,
        onValueChange = { onAction(CustomerDetailAction.PaymentNote(it)) },
        label = stringResource(R.string.customer_payment_note),
        singleLine = false,
        imeAction = ImeAction.Done,
        error = noteTooLong(CustomerFormError.NOTE_TOO_LONG in sheet.errors),
    )
    sheet.notice?.let { FormNotice(it, onAction) }
}

/** `Saldo después del abono: …` o `Queda saldo a favor de …`, mientras se escribe el monto. */
@Composable
private fun PreviewLine(preview: PaymentPreview?) {
    val money = LocalAppFormats.current.money
    val text = when (preview) {
        null, PaymentPreview.Overflow -> return

        is PaymentPreview.RemainingDebt -> stringResource(
            R.string.customer_payment_balance_after,
            rememberMoneyDisplay(preview.balance, money).text.text,
        )

        is PaymentPreview.CreditLeft -> stringResource(
            R.string.customer_payment_credit_left,
            rememberMoneyDisplay(preview.credit, money).text.text,
        )
    }
    Text(text, style = AppTheme.amounts.small, color = MaterialTheme.colorScheme.onSurface)
}

/** La hoja de `Ajustar saldo`: monto con signo y una nota obligatoria. No mueve dinero. */
@Composable
internal fun AdjustSheetHost(sheet: AdjustSheet, onAction: (CustomerDetailAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(CustomerDetailAction.DismissSheet) },
        title = stringResource(R.string.customer_adjust_balance_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.customer_adjust_save_action),
                onClick = { onAction(CustomerDetailAction.SaveAdjust) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        MoneyField(
            value = sheet.amountText,
            onValueChange = { onAction(CustomerDetailAction.AdjustAmount(it)) },
            label = stringResource(R.string.customer_adjust_amount),
            modifier = Modifier.focusRequester(focus),
            error = amountErrorText(sheet.errors, positiveOnly = false),
            allowNegative = true,
        )
        AppTextField(
            value = sheet.note,
            onValueChange = { onAction(CustomerDetailAction.AdjustNote(it)) },
            label = stringResource(R.string.customer_adjust_note),
            singleLine = false,
            imeAction = ImeAction.Done,
            error = when {
                CustomerFormError.NOTE_REQUIRED in sheet.errors ->
                    stringResource(R.string.customer_error_note_required)

                else -> noteTooLong(CustomerFormError.NOTE_TOO_LONG in sheet.errors)
            },
        )
        sheet.notice?.let { FormNotice(it, onAction) }
    }
}

@Composable
private fun FormNotice(notice: CustomerFormNotice, onAction: (CustomerDetailAction) -> Unit) {
    when (notice) {
        is CustomerFormNotice.SaveFailed -> NoticeCard(
            text = stringResource(saveErrorText(notice.outOfSpace)),
            variant = NoticeVariant.ERROR,
        )

        CustomerFormNotice.CurrencyUnconfirmed -> NoticeCard(
            text = stringResource(R.string.region_required_body),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.region_required_action),
            onAction = { onAction(CustomerDetailAction.ConfirmCurrency) },
        )

        CustomerFormNotice.AccountUnavailable -> NoticeCard(
            text = stringResource(R.string.customer_payment_account_unavailable),
            variant = NoticeVariant.WARNING,
        )

        CustomerFormNotice.UnknownZone -> NoticeCard(
            text = stringResource(R.string.stats_zone_unknown_title),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.stats_zone_unknown_action),
            onAction = { onAction(CustomerDetailAction.ChooseZone) },
        )
    }
}

@Composable
private fun amountErrorText(errors: Set<CustomerFormError>, positiveOnly: Boolean): String? {
    val money = LocalAppFormats.current.money
    return when {
        CustomerFormError.AMOUNT_ZERO in errors -> if (positiveOnly) {
            stringResource(R.string.customer_error_amount_zero)
        } else {
            stringResource(
                R.string.error_value_zero,
                rememberMoneyDisplay(Money.ZERO, money).text.text,
            )
        }

        CustomerFormError.AMOUNT_NOT_A_NUMBER in errors -> stringResource(
            R.string.field_error_number,
        )

        CustomerFormError.AMOUNT_DECIMALS in errors -> pluralStringResource(
            R.plurals.error_money_decimals,
            money.currencyDecimals,
            money.currencyDecimals,
        )

        CustomerFormError.AMOUNT_TOO_HIGH in errors -> stringResource(R.string.error_value_high)

        else -> null
    }
}

@Composable
private fun noteTooLong(tooLong: Boolean): String? = if (tooLong) {
    pluralStringResource(
        R.plurals.error_text_long,
        CustomerLedgerRules.NOTE_MAX,
        CustomerLedgerRules.NOTE_MAX,
    )
} else {
    null
}

/**
 * Las confirmaciones del detalle. Archivar y anonimizar dicen antes si queda saldo, que sigue
 * contando en `Por cobrar`; anonimizar es destructiva y no se deshace.
 */
@Composable
internal fun CustomerDetailConfirm(
    dialog: CustomerDetailDialog,
    onAction: (CustomerDetailAction) -> Unit,
) {
    val money = LocalAppFormats.current.money
    when (dialog) {
        is CustomerDetailDialog.VoidPayment -> ConfirmDialog(
            title = stringResource(R.string.customer_void_confirm_title),
            body = stringResource(R.string.customer_void_confirm_body),
            confirmLabel = stringResource(R.string.customer_void_payment_action),
            onConfirm = { onAction(CustomerDetailAction.ConfirmDialog) },
            onDismiss = { onAction(CustomerDetailAction.DismissDialog) },
        )

        is CustomerDetailDialog.Archive -> {
            val amount = rememberMoneyDisplay(dialog.balance, money).text.text
            ConfirmDialog(
                title = stringResource(R.string.customer_archive_confirm_title, dialog.name),
                body = if (dialog.balance == Money.ZERO) {
                    stringResource(R.string.customer_archive_confirm_body)
                } else {
                    stringResource(R.string.customer_archive_confirm_body_balance, amount)
                },
                confirmLabel = stringResource(R.string.customer_archive_confirm_action),
                onConfirm = { onAction(CustomerDetailAction.ConfirmDialog) },
                onDismiss = { onAction(CustomerDetailAction.DismissDialog) },
            )
        }

        is CustomerDetailDialog.Anonymize -> {
            val amount = rememberMoneyDisplay(dialog.balance, money).text.text
            ConfirmDialog(
                title = stringResource(R.string.customer_anonymize_confirm_title, dialog.name),
                body = if (dialog.balance == Money.ZERO) {
                    stringResource(R.string.customer_anonymize_confirm_body)
                } else {
                    stringResource(R.string.customer_anonymize_confirm_body_balance, amount)
                },
                confirmLabel = stringResource(R.string.customer_anonymize_confirm_action),
                onConfirm = { onAction(CustomerDetailAction.ConfirmDialog) },
                onDismiss = { onAction(CustomerDetailAction.DismissDialog) },
                variant = ConfirmVariant.DESTRUCTIVE,
            )
        }
    }
}
