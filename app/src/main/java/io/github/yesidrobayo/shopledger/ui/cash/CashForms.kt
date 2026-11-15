package io.github.yesidrobayo.shopledger.ui.cash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CheckboxRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DenominationCounter
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.FigureRow
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * La hoja de `Abrir caja`: la cuenta de efectivo, solo si hay más de una; lo que hay al abrir, con
 * el saldo de la cuenta como sugerencia; y `Contar por denominación`. Si lo contado difiere del
 * saldo, abrir lo corrige con una diferencia de caja, que no cuenta en lo esperado.
 */
@Composable
internal fun OpenCashSheetHost(
    uiState: CashRegisterUiState,
    sheet: OpenCashSheet,
    onAction: (CashRegisterAction) -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = { onAction(CashRegisterAction.DismissSheet) },
        title = stringResource(R.string.cash_open_action),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.cash_open_action),
                onClick = { onAction(CashRegisterAction.ConfirmOpen) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        if (uiState.accounts.size > 1) {
            Text(
                stringResource(R.string.cash_account),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccountChips(uiState.accounts, sheet.accountId) {
                onAction(CashRegisterAction.OpenAccount(it))
            }
        }
        CountFields(
            label = stringResource(R.string.cash_opening_amount),
            count = sheet.count,
            errors = sheet.errors,
            denominations = uiState.activeDenominations,
            onAction = onAction,
        )
        sheet.notice?.let { FormNotice(it, onAction) }
    }
}

/**
 * Lo contado: un monto, o pieza por pieza con `Contar por denominación`. Sin denominaciones, la
 * casilla lleva a escribirlas; escribir solo el total siempre funciona.
 */
@Composable
private fun ColumnScope.CountFields(
    label: String,
    count: CashCountInput,
    errors: Set<CashFormError>,
    denominations: List<CashDenomination>,
    onAction: (CashRegisterAction) -> Unit,
    columns: Int = 1,
) {
    CheckboxRow(
        text = stringResource(R.string.cash_count_by_denomination),
        checked = count.byDenomination,
        onCheckedChange = { checked ->
            if (checked && denominations.isEmpty()) {
                onAction(CashRegisterAction.OpenDenominations)
            } else {
                onAction(CashRegisterAction.CountByDenomination(checked))
            }
        },
    )
    if (count.byDenomination && denominations.isNotEmpty()) {
        DenominationCounter(
            denominations = denominations,
            counts = count.counts,
            total = count.counted,
            onCountChange = { id, text -> onAction(CashRegisterAction.CountPieces(id, text)) },
            columns = columns,
        )
        countError(errors)?.let { NoticeCard(it, NoticeVariant.ERROR) }
    } else {
        MoneyField(
            value = count.amountText,
            onValueChange = { onAction(CashRegisterAction.CountAmount(it)) },
            label = label,
            error = countError(errors),
        )
    }
}

@Composable
private fun countError(errors: Set<CashFormError>): String? {
    val money = LocalAppFormats.current.money
    return when {
        CashFormError.AMOUNT_REQUIRED in errors -> stringResource(
            R.string.cash_error_counted_required,
        )

        CashFormError.AMOUNT_NOT_A_NUMBER in errors -> stringResource(R.string.field_error_number)

        CashFormError.AMOUNT_DECIMALS in errors -> pluralStringResource(
            R.plurals.error_money_decimals,
            money.currencyDecimals,
            money.currencyDecimals,
        )

        CashFormError.AMOUNT_TOO_HIGH in errors -> stringResource(R.string.error_value_high)

        else -> null
    }
}

@Composable
private fun FormNotice(notice: CashFormNotice, onAction: (CashRegisterAction) -> Unit) {
    when (notice) {
        is CashFormNotice.SaveFailed -> NoticeCard(
            text = stringResource(saveErrorText(notice.outOfSpace)),
            variant = NoticeVariant.ERROR,
        )

        CashFormNotice.AlreadyOpen -> NoticeCard(
            text = stringResource(R.string.cash_error_already_open),
            variant = NoticeVariant.WARNING,
        )

        CashFormNotice.CurrencyUnconfirmed -> NoticeCard(
            text = stringResource(R.string.region_required_body),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.region_required_action),
            onAction = { onAction(CashRegisterAction.ConfirmCurrency) },
        )

        CashFormNotice.DenominationChanged -> NoticeCard(
            text = stringResource(R.string.cash_error_denomination_changed),
            variant = NoticeVariant.WARNING,
        )

        CashFormNotice.UnknownZone -> NoticeCard(
            text = stringResource(R.string.stats_zone_unknown_title),
            variant = NoticeVariant.WARNING,
            actionLabel = stringResource(R.string.stats_zone_unknown_action),
            onAction = { onAction(CashRegisterAction.ChooseZone) },
        )
    }
}

/**
 * `Cerrar caja`, en pantalla completa: lo contado, `Esperado`, `Contado` y `Diferencia` en vivo,
 * una nota y la barra fija. Lo que se está llenando sigue a la vista con el modo discreto; lo
 * esperado y la diferencia no, porque dicen cuánto hay en la caja.
 */
@Composable
fun CloseCashScreen(
    uiState: CashRegisterUiState,
    form: CloseCashForm,
    onAction: (CashRegisterAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.cash_close_action),
                navigation = TopBarNavigation.CLOSE,
                onNavigate = { onAction(CashRegisterAction.DismissSheet) },
                navigationLabel = stringResource(R.string.a11y_close),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columns = if (maxWidth >= EXPANDED_WIDTH) 2 else 1
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(
                            max = if (columns ==
                                2
                            ) {
                                maxWidth
                            } else {
                                AppTheme.sizes.maxContentWidth
                            },
                        )
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    CountFields(
                        label = stringResource(R.string.cash_counted),
                        count = form.count,
                        errors = form.errors,
                        denominations = uiState.activeDenominations,
                        onAction = onAction,
                        columns = columns,
                    )
                    CloseFigures(form)
                    AppTextField(
                        value = form.note,
                        onValueChange = { onAction(CashRegisterAction.CloseNote(it)) },
                        label = stringResource(R.string.cash_note),
                        singleLine = false,
                        imeAction = ImeAction.Done,
                        error = if (CashFormError.NOTE_TOO_LONG in form.errors) {
                            pluralStringResource(R.plurals.error_text_long, NOTE_MAX, NOTE_MAX)
                        } else {
                            null
                        },
                    )
                    form.notice?.let { FormNotice(it, onAction) }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .keepsMessagesAbove()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(spacing.s16),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.cash_close_action),
                    onClick = { onAction(CashRegisterAction.ConfirmClose) },
                    variant = ActionVariant.PRIMARY,
                    state = if (form.working) ActionState.WORKING else ActionState.IDLE,
                    modifier = Modifier.widthIn(
                        max = AppTheme.sizes.maxContentWidth,
                    ).fillMaxWidth(),
                )
            }
        }
    }
    if (form.confirmDifference) DifferenceConfirm(form, onAction)
}

/** `Esperado`, `Contado` y `Diferencia`, con la diferencia dicha con palabras y signo. */
@Composable
private fun CloseFigures(form: CloseCashForm) {
    val spacing = AppTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        StatTile(
            label = stringResource(R.string.cash_expected),
            amount = form.expected,
            variant = StatTileVariant.PLAIN,
            sensitive = true,
        )
        StatTile(
            label = stringResource(R.string.cash_counted),
            amount = form.count.counted,
            variant = StatTileVariant.PLAIN,
        )
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                stringResource(R.string.cash_difference),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            form.difference?.let { DifferenceText(it, sensitive = true) }
        }
    }
}

/** Cerrar con diferencia pregunta antes, con el monto: queda en el historial y no se edita. */
@Composable
private fun DifferenceConfirm(form: CloseCashForm, onAction: (CashRegisterAction) -> Unit) {
    val difference = form.difference ?: return
    // El monto va con su signo: negativo es faltante, y el texto lo dice con la diferencia.
    val signed = rememberMoneyDisplay(difference, LocalAppFormats.current.money).text.text
    ConfirmDialog(
        title = stringResource(R.string.cash_close_confirm_title),
        body = stringResource(R.string.cash_close_confirm_body, signed),
        confirmLabel = stringResource(R.string.cash_close_confirm_action),
        onConfirm = { onAction(CashRegisterAction.ConfirmDifference) },
        onDismiss = { onAction(CashRegisterAction.DismissDifference) },
    )
}

/** Un cierre anterior: lo esperado, lo contado, la diferencia, la nota y el conteo, si lo hubo. */
@Composable
internal fun ClosedDetailSheet(
    uiState: CashRegisterUiState,
    detail: ClosedCashDetail,
    onAction: (CashRegisterAction) -> Unit,
) {
    val closing = detail.session.closing ?: return
    val formats = LocalAppFormats.current
    val values = uiState.denominations.associateBy { it.id }
    AppBottomSheet(
        onDismissRequest = { onAction(CashRegisterAction.DismissSheet) },
        title = stringResource(
            R.string.cash_detail_title,
            formats.date.dayMonthYear(detail.session.opening.businessDate),
        ),
    ) {
        FigureRow(
            stringResource(R.string.cash_expected),
            closing.expected,
        )
        FigureRow(
            stringResource(R.string.cash_counted),
            closing.counted,
        )
        DifferenceText(closing.difference, sensitive = true)
        closing.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (detail.counts.isNotEmpty()) {
            Text(
                stringResource(R.string.cash_detail_counts),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            detail.counts.forEach { count ->
                val value = values[count.denominationId]?.value ?: return@forEach
                Text(
                    stringResource(
                        R.string.cash_denomination_count,
                        formats.quantity.formatCount(count.count),
                        rememberMoneyDisplay(value, formats.money).text.text,
                    ),
                    style = AppTheme.amounts.small,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * `Denominaciones`: los billetes y monedas de la persona, con su valor y si siguen activas. No hay
 * ninguna inicial. Una denominación no se borra, porque un cierre pasado la puede nombrar: se
 * desactiva, y deja de ofrecerse al contar.
 */
@Composable
fun DenominationsScreen(
    uiState: CashRegisterUiState,
    onAction: (CashRegisterAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.cash_denominations_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(CashRegisterAction.CloseDenominations) },
                    navigationLabel = stringResource(R.string.a11y_back),
                )
                if (uiState.denominations.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyState(
                            icon = R.drawable.ic_savings,
                            title = stringResource(R.string.cash_denominations_empty_title),
                            body = stringResource(R.string.cash_denominations_empty_body),
                        ) {
                            PrimaryActionButton(
                                text = stringResource(R.string.cash_denomination_add_action),
                                onClick = { onAction(CashRegisterAction.NewDenomination) },
                                variant = ActionVariant.TONAL,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = AppTheme.sizes.maxContentWidth)
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
                    ) {
                        items(uiState.denominations, key = { it.id }) { denomination ->
                            DenominationRow(denomination) {
                                onAction(CashRegisterAction.EditDenomination(denomination.id))
                            }
                        }
                    }
                }
            }
            if (uiState.denominations.isNotEmpty()) {
                TonalFab(
                    text = stringResource(R.string.cash_denomination_new_action),
                    onClick = { onAction(CashRegisterAction.NewDenomination) },
                    icon = R.drawable.ic_add,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(AppTheme.spacing.s16)
                        .keepsMessagesAbove(),
                )
            }
        }
    }
}

/** Una denominación: su valor y, si ya no se ofrece al contar, `Inactiva`. */
@Composable
private fun DenominationRow(denomination: CashDenomination, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    Column(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRow)
                .padding(horizontal = spacing.s16, vertical = spacing.s12)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            // El valor de un billete no es un dato del negocio: se ve con el modo discreto.
            MoneyText(denomination.value, MoneySize.ROW, modifier = Modifier.weight(1f))
            if (!denomination.isActive) {
                StatusTag(stringResource(R.string.cash_denomination_inactive))
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** La hoja de una denominación: `Valor` y, si ya existe, `Activa`. */
@Composable
internal fun DenominationEditorSheet(
    editor: DenominationEditor,
    onAction: (CashRegisterAction) -> Unit,
) {
    val money = LocalAppFormats.current.money
    AppBottomSheet(
        onDismissRequest = { onAction(CashRegisterAction.DismissSheet) },
        title = stringResource(
            if (editor.id ==
                null
            ) {
                R.string.cash_denomination_new_action
            } else {
                R.string.cash_denominations_title
            },
        ),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.cash_denomination_save_action),
                onClick = { onAction(CashRegisterAction.SaveDenomination) },
                variant = ActionVariant.PRIMARY,
                state = if (editor.working) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        MoneyField(
            value = editor.valueText,
            onValueChange = { onAction(CashRegisterAction.DenominationValue(it)) },
            label = stringResource(R.string.cash_denomination_value),
            error = when (editor.error) {
                null -> null

                DenominationError.VALUE_ZERO -> stringResource(
                    R.string.error_value_zero,
                    rememberMoneyDisplay(Money.ZERO, money).text.text,
                )

                DenominationError.NOT_A_NUMBER -> stringResource(R.string.field_error_number)

                DenominationError.TOO_HIGH -> stringResource(R.string.error_value_high)

                DenominationError.DUPLICATE -> stringResource(
                    R.string.cash_error_denomination_duplicate,
                )
            },
        )
        if (editor.id != null) {
            CheckboxRow(
                text = stringResource(R.string.cash_denomination_active),
                checked = editor.isActive,
                onCheckedChange = { onAction(CashRegisterAction.DenominationActive(it)) },
            )
        }
        editor.failed?.let { FormNotice(it, onAction) }
    }
}

private const val NOTE_MAX = 200
