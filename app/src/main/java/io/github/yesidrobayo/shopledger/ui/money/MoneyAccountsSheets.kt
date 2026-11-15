package io.github.yesidrobayo.shopledger.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.money.MoneyAccountRules
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.FieldError
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PickerField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.RateField
import io.github.yesidrobayo.shopledger.ui.components.SelectionSheet
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La hoja de un retiro, un aporte u otro ingreso: qué es, en qué cuenta, el monto en la moneda de esa
 * cuenta y una nota. Si un retiro deja la cuenta en negativo lo avisa antes de guardar, sin
 * bloquear. Un error se muestra dentro de la hoja, y lo escrito se conserva.
 */
@Composable
internal fun ManualSheetHost(
    uiState: MoneyAccountsUiState,
    sheet: ManualSheet,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    val view = (uiState.content as? MoneyAccountsContent.Ready)?.view ?: return
    val (title, help) = when (sheet.kind) {
        ManualMoneyKind.OWNER_DRAW -> R.string.money_draw_title to R.string.money_draw_help

        ManualMoneyKind.OWNER_CONTRIBUTION ->
            R.string.money_contribution_title to R.string.money_contribution_help

        ManualMoneyKind.OTHER_INCOME ->
            R.string.money_other_income_title to R.string.money_other_income_help
    }
    AppBottomSheet(
        onDismissRequest = { onAction(MoneyAccountsAction.DismissSheet) },
        title = stringResource(title),
        action = { SaveButton(sheet.working, onAction) },
    ) {
        Help(stringResource(help))
        val account = sheet.accountId?.let { view.find(it)?.account }
        AccountChoice(
            label = stringResource(R.string.money_account),
            accounts = view.active,
            selectedId = sheet.accountId,
            onChoose = { onAction(MoneyAccountsAction.ChooseAccount(it)) },
        )
        AmountField(sheet.amountText, account, sheet.errors, onAction)
        sheet.negativeAfter?.let { NegativeWarning(it, account) }
        NoteField(sheet.note, sheet.errors, onAction)
        sheet.notice?.let { FormNotice(it, onAction) }
    }
}

/**
 * La hoja de transferir: `Desde`, `Hacia`, el monto en la moneda de origen y, entre monedas
 * distintas, la cotización escrita a mano y `Llegan …` en la moneda de destino. El total no cambia.
 */
@Composable
internal fun TransferSheetHost(
    uiState: MoneyAccountsUiState,
    sheet: TransferSheet,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    val view = (uiState.content as? MoneyAccountsContent.Ready)?.view ?: return
    val from = sheet.fromId?.let { view.find(it)?.account }
    val to = sheet.toId?.let { view.find(it)?.account }
    AppBottomSheet(
        onDismissRequest = { onAction(MoneyAccountsAction.DismissSheet) },
        title = stringResource(R.string.money_transfer_title),
        action = { SaveButton(sheet.working, onAction) },
    ) {
        Help(stringResource(R.string.money_transfer_help))
        AccountChoice(
            label = stringResource(R.string.money_from),
            accounts = view.active,
            selectedId = sheet.fromId,
            onChoose = { onAction(MoneyAccountsAction.ChooseFrom(it)) },
        )
        AccountChoice(
            label = stringResource(R.string.money_to),
            accounts = view.active,
            selectedId = sheet.toId,
            onChoose = { onAction(MoneyAccountsAction.ChooseTo(it)) },
            error = stringResource(R.string.money_error_same_account)
                .takeIf { MoneyFormError.SAME_ACCOUNT in sheet.errors },
        )
        AmountField(sheet.amountText, from, sheet.errors, onAction)
        if (from != null && to != null && from.currency != to.currency) {
            RateField(
                value = sheet.rateText,
                onValueChange = { onAction(MoneyAccountsAction.Rate(it)) },
                label = stringResource(R.string.money_fx_rate),
                error = rateError(sheet.errors),
            )
            sheet.arrives?.let { arrives ->
                ProvideAccountCurrency(to.currency) {
                    val amount = rememberMoneyDisplay(arrives, LocalAppFormats.current.money)
                    val description = stringResource(R.string.money_fx_arrives, amount.description)
                    Text(
                        stringResource(R.string.money_fx_arrives, amount.text.text),
                        style = AppTheme.amounts.small,
                        color = MaterialTheme.colorScheme.onSurface,
                        // Cambia al escribir el monto o la cotización: se anuncia sin mover
                        // el foco.
                        modifier = Modifier.semantics {
                            contentDescription = description
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
            }
        }
        sheet.negativeAfter?.let { NegativeWarning(it, from) }
        NoteField(sheet.note, sheet.errors, onAction)
        sheet.notice?.let { FormNotice(it, onAction) }
    }
}

/**
 * La hoja de una cuenta: `Nombre`, `Tipo` y, al crear, `Moneda` y `Saldo inicial`, que entra como
 * su primer movimiento. Al editar, la moneda se lee y no cambia. Elegir la moneda abre la lista de
 * monedas del teléfono en lugar de la hoja.
 */
@Composable
internal fun AccountEditorHost(editor: AccountEditor, onAction: (MoneyAccountsAction) -> Unit) {
    if (editor.choosingCurrency) {
        CurrencySheet(editor, onAction)
        return
    }
    val creating = editor.editingId == null
    AppBottomSheet(
        onDismissRequest = { onAction(MoneyAccountsAction.DismissSheet) },
        title = stringResource(
            if (creating) R.string.money_new_account_action else R.string.money_edit_title,
        ),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.money_account_save_action),
                onClick = { onAction(MoneyAccountsAction.Save) },
                variant = ActionVariant.PRIMARY,
                state = if (editor.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        val translated = editor.systemKey?.let { initialName(it) }
        val shown = if (!editor.nameEdited && translated != null) translated else editor.name
        AppTextField(
            value = shown,
            onValueChange = { onAction(MoneyAccountsAction.Name(it)) },
            label = stringResource(R.string.money_account_name),
            error = nameError(editor.errors),
        )
        Label(stringResource(R.string.money_account_kind))
        CompactChipRow(
            options = MoneyAccountKind.entries,
            label = { stringResource(kindLabel(it)) },
            selected = setOf(editor.kind),
            onToggle = { onAction(MoneyAccountsAction.Kind(it)) },
            multiple = false,
        )
        val currency = editor.currencyName?.let {
            stringResource(R.string.region_option_summary, it, editor.currency.code.code)
        } ?: editor.currency.code.code
        PickerField(
            label = stringResource(R.string.money_account_currency),
            value = currency,
            onClick = { onAction(MoneyAccountsAction.OpenCurrencies) },
            lockedReason = stringResource(R.string.money_currency_fixed_help).takeIf { !creating },
        )
        if (creating) {
            Help(stringResource(R.string.money_currency_fixed_help))
            ProvideAccountCurrency(editor.currency) {
                MoneyField(
                    value = editor.openingText,
                    onValueChange = { onAction(MoneyAccountsAction.OpeningBalance(it)) },
                    label = stringResource(R.string.money_opening_balance),
                    error = openingError(editor.errors, editor.currency.decimals),
                    imeAction = ImeAction.Done,
                    allowNegative = true,
                )
            }
        }
        editor.notice?.let { FormNotice(it, onAction) }
    }
}

/** La lista de monedas del teléfono, con búsqueda, para una cuenta nueva. */
@Composable
private fun CurrencySheet(editor: AccountEditor, onAction: (MoneyAccountsAction) -> Unit) {
    SelectionSheet(
        title = stringResource(R.string.money_account_currency),
        items = editor.currencyOptions,
        key = { it.key },
        query = editor.currencyQuery,
        onQueryChange = { onAction(MoneyAccountsAction.CurrencyQuery(it)) },
        onDismissRequest = { onAction(MoneyAccountsAction.CloseCurrencies) },
        searchHint = stringResource(R.string.region_currency_search_hint),
        itemContent = { option ->
            val code = CurrencyCode.of(option.key)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRowSingle)
                    .clickable(role = Role.Button) {
                        code?.let { onAction(MoneyAccountsAction.ChooseCurrency(it)) }
                    }
                    .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8),
            ) {
                Text(
                    option.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    option.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        resultsCount = R.plurals.a11y_search_results_currencies,
    )
}

/**
 * Un movimiento abierto desde el libro: su tipo, el monto con signo y el saldo que dejó. Lo
 * registrado a mano ofrece `Anular movimiento`; lo que viene de un documento dice que se anula desde
 * ese documento y lleva a él.
 */
@Composable
internal fun MovementSheetHost(
    uiState: MoneyAccountsUiState,
    sheet: MovementSheet,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    val detail = uiState.detail as? AccountDetailContent.Ready ?: return
    val line = sheet.line
    val movement = line.movement
    val voidAction: @Composable () -> Unit = {
        PrimaryActionButton(
            text = stringResource(R.string.money_void_action),
            onClick = { onAction(MoneyAccountsAction.AskVoid) },
            variant = ActionVariant.TONAL,
        )
    }
    AppBottomSheet(
        onDismissRequest = { onAction(MoneyAccountsAction.DismissSheet) },
        title = stringResource(movementLabel(movement.type)),
        action = voidAction.takeIf { line.voidable },
    ) {
        ProvideAccountCurrency(detail.row.account.currency) {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
                MoneyText(
                    movement.amount,
                    MoneySize.LARGE,
                    voided = line.voided,
                    sensitive = true,
                    voidedLabel = R.string.ledger_status_voided,
                    voidedDescription = R.string.a11y_ledger_voided_amount,
                    signed = true,
                )
                Text(
                    LocalAppFormats.current.date.dayMonthYear(movement.time.businessDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val extra = movementDetail(line, detail.zone, uiState.today)
                if (extra.isNotEmpty()) {
                    Text(
                        extra,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!movement.type.isManual) {
            val document = sheet.document
            NoticeCard(
                text = stringResource(R.string.money_automatic_notice),
                variant = NoticeVariant.INFO,
                actionLabel = stringResource(R.string.action_view).takeIf { document != null },
                onAction = { document?.let { onAction(MoneyAccountsAction.OpenDocument(it)) } },
            )
        }
    }
}

/** Anular un movimiento y archivar una cuenta, con su confirmación. */
@Composable
internal fun MoneyConfirm(dialog: MoneyDialog, onAction: (MoneyAccountsAction) -> Unit) {
    when (dialog) {
        is MoneyDialog.Void -> ConfirmDialog(
            title = stringResource(R.string.money_void_confirm_title),
            body = stringResource(
                if (dialog.isTransfer) {
                    R.string.money_void_confirm_body_transfer
                } else {
                    R.string.money_void_confirm_body
                },
            ),
            confirmLabel = stringResource(R.string.money_void_action),
            onConfirm = { onAction(MoneyAccountsAction.ConfirmDialog) },
            onDismiss = { onAction(MoneyAccountsAction.DismissDialog) },
        )

        is MoneyDialog.Archive -> ProvideAccountCurrency(dialog.account.currency) {
            val account = dialog.account
            val amount = rememberMoneyDisplay(account.balance, LocalAppFormats.current.money)
            ConfirmDialog(
                title = stringResource(R.string.money_archive_confirm_title, accountName(account)),
                body = if (account.balance == Money.ZERO) {
                    stringResource(R.string.money_archive_confirm_body)
                } else {
                    stringResource(R.string.money_archive_confirm_body_balance, amount.text.text)
                },
                confirmLabel = stringResource(R.string.money_archive_confirm_action),
                onConfirm = { onAction(MoneyAccountsAction.ConfirmDialog) },
                onDismiss = { onAction(MoneyAccountsAction.DismissDialog) },
            )
        }
    }
}

// ---------------------------------------------------------------- piezas de las hojas

@Composable
private fun SaveButton(working: Boolean, onAction: (MoneyAccountsAction) -> Unit) {
    PrimaryActionButton(
        text = stringResource(R.string.money_save_action),
        onClick = { onAction(MoneyAccountsAction.Save) },
        variant = ActionVariant.PRIMARY,
        state = if (working) ActionState.WORKING else ActionState.IDLE,
    )
}

@Composable
private fun Help(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Una cuenta por chip. Con una sola cuenta no hay qué elegir, y no se dibuja. */
@Composable
private fun AccountChoice(
    label: String,
    accounts: List<MoneyAccount>,
    selectedId: Long?,
    onChoose: (Long) -> Unit,
    error: String? = null,
) {
    if (accounts.size < 2 && error == null) return
    Label(label)
    val names = accounts.associate { it.id to accountName(it) }
    CompactChipRow(
        options = accounts.map { it.id },
        label = { names.getValue(it) },
        selected = setOfNotNull(selectedId),
        onToggle = onChoose,
        multiple = false,
    )
    error?.let { FieldError(it) }
}

/** El monto, con el símbolo y los decimales de la moneda de [account]. */
@Composable
private fun AmountField(
    text: String,
    account: MoneyAccount?,
    errors: Set<MoneyFormError>,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val field: @Composable () -> Unit = {
        MoneyField(
            value = text,
            onValueChange = { onAction(MoneyAccountsAction.Amount(it)) },
            label = stringResource(R.string.money_amount),
            modifier = Modifier.focusRequester(focus),
            error = amountError(errors),
        )
    }
    if (account == null) field() else ProvideAccountCurrency(account.currency, field)
}

/**
 * `Esta cuenta queda con saldo negativo: …`, sin bloquear. El saldo que resulta es lo que la
 * persona está escribiendo, así que se ve aunque el modo discreto esté encendido.
 */
@Composable
private fun NegativeWarning(balance: Money, account: MoneyAccount?) {
    val draw: @Composable () -> Unit = {
        val amount = rememberMoneyDisplay(balance, LocalAppFormats.current.money)
        NoticeCard(
            text = stringResource(R.string.money_negative_warning, amount.text.text),
            variant = NoticeVariant.WARNING,
        )
    }
    if (account == null) draw() else ProvideAccountCurrency(account.currency, draw)
}

@Composable
private fun NoteField(
    note: String,
    errors: Set<MoneyFormError>,
    onAction: (MoneyAccountsAction) -> Unit,
) {
    AppTextField(
        value = note,
        onValueChange = { onAction(MoneyAccountsAction.Note(it)) },
        label = stringResource(R.string.money_note),
        singleLine = false,
        imeAction = ImeAction.Done,
        error = pluralStringResource(
            R.plurals.error_text_long,
            MoneyMovementDraft.NOTE_MAX,
            MoneyMovementDraft.NOTE_MAX,
        ).takeIf { MoneyFormError.NOTE_TOO_LONG in errors },
    )
}

@Composable
private fun FormNotice(notice: MoneyFormNotice, onAction: (MoneyAccountsAction) -> Unit) {
    when (notice) {
        is MoneyFormNotice.SaveFailed -> NoticeCard(
            text = stringResource(saveErrorText(notice.outOfSpace)),
            variant = NoticeVariant.ERROR,
        )

        MoneyFormNotice.CurrencyUnconfirmed -> NoticeCard(
            text = stringResource(R.string.region_required_body),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.region_required_action),
            onAction = { onAction(MoneyAccountsAction.ConfirmCurrency) },
        )

        MoneyFormNotice.UnknownZone -> NoticeCard(
            text = stringResource(R.string.stats_zone_unknown_title),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.stats_zone_unknown_action),
            onAction = { onAction(MoneyAccountsAction.ChooseZone) },
        )
    }
}

@Composable
private fun amountError(errors: Set<MoneyFormError>): String? {
    val decimals = LocalAppFormats.current.money.currencyDecimals
    return when {
        MoneyFormError.AMOUNT_ZERO in errors -> stringResource(R.string.money_error_amount_zero)

        MoneyFormError.AMOUNT_NOT_A_NUMBER in errors -> stringResource(R.string.field_error_number)

        MoneyFormError.AMOUNT_DECIMALS in errors ->
            pluralStringResource(R.plurals.error_money_decimals, decimals, decimals)

        MoneyFormError.AMOUNT_TOO_HIGH in errors -> stringResource(R.string.error_value_high)

        else -> null
    }
}

@Composable
private fun rateError(errors: Set<MoneyFormError>): String? = when {
    MoneyFormError.RATE_REQUIRED in errors -> stringResource(R.string.money_error_fx_rate)
    MoneyFormError.RATE_NOT_A_NUMBER in errors -> stringResource(R.string.field_error_number)
    MoneyFormError.RATE_TOO_HIGH in errors -> stringResource(R.string.error_value_high)
    else -> null
}

@Composable
private fun nameError(errors: Set<MoneyFormError>): String? = when {
    MoneyFormError.NAME_EMPTY in errors -> stringResource(R.string.money_error_name_empty)

    MoneyFormError.NAME_TOO_LONG in errors -> pluralStringResource(
        R.plurals.error_text_long,
        MoneyAccountRules.NAME_MAX,
        MoneyAccountRules.NAME_MAX,
    )

    MoneyFormError.NAME_TAKEN in errors -> stringResource(R.string.money_error_name_duplicate)

    else -> null
}

@Composable
private fun openingError(errors: Set<MoneyFormError>, decimals: Int): String? = when {
    MoneyFormError.OPENING_NOT_A_NUMBER in errors -> stringResource(R.string.field_error_number)

    MoneyFormError.OPENING_DECIMALS in errors ->
        pluralStringResource(R.plurals.error_money_decimals, decimals, decimals)

    MoneyFormError.OPENING_TOO_HIGH in errors -> stringResource(R.string.error_value_high)

    else -> null
}

/** El nombre traducido de una cuenta inicial, por su clave. */
@Composable
private fun initialName(systemKey: String): String =
    SeedText.displayName(LocalResources.current, SeedTable.MONEY_ACCOUNTS, systemKey, null)
