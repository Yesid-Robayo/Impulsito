package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutBalance
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutProblem
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CashTenderRow
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.PaymentBalanceState
import io.github.yesidrobayo.shopledger.ui.components.PaymentFeeState
import io.github.yesidrobayo.shopledger.ui.components.PaymentQrView
import io.github.yesidrobayo.shopledger.ui.components.PaymentSplitEditor
import io.github.yesidrobayo.shopledger.ui.components.PaymentSplitRow
import io.github.yesidrobayo.shopledger.ui.components.PercentField
import io.github.yesidrobayo.shopledger.ui.components.PhotoState
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.payment.rememberStoredPhoto
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * El cobro: resuelve la venta que no es común, con varios pagos, la comisión y el neto de cada uno,
 * el ajuste de toda la venta y la imagen de cobro. Nunca se interpone en la venta común.
 *
 * - Arriba, el total a cobrar en el bloque de color, grande para que se lea de lejos: quien paga
 *   suele mirar esta pantalla. Debajo, dentro del mismo bloque, solo las filas que aplican.
 * - Luego el ajuste de la venta y los pagos, con la línea de balance siempre visible.
 * - `Registrar venta` va fijo abajo y siempre responde: si los pagos no suman, enfoca la línea de
 *   balance y no registra.
 * - En una ventana ancha, el resumen va a la izquierda y los pagos a la derecha; en una mediana, una
 *   columna a 600 dp.
 * - El precio de lo que se atiende nunca se oculta. En modo discreto se ocultan la comisión y el
 *   neto.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun CheckoutScreen(
    uiState: CheckoutUiState,
    onAction: (CheckoutAction) -> Unit,
    onBack: () -> Unit,
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
                    title = stringResource(R.string.checkout_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = onBack,
                    navigationLabel = stringResource(R.string.a11y_back),
                )
                if (uiState.content == CheckoutContent.Ready) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (wide) {
                            WideCheckout(
                                uiState,
                                onAction,
                            )
                        } else {
                            NarrowCheckout(uiState, onAction)
                        }
                    }
                    RegisterBar(uiState, onAction)
                }
            }
            uiState.qr?.let { QrOverlay(it, uiState, onAction) }
        }
    }
    CheckoutSheets(uiState, onAction)
    if (uiState.zeroDialog) {
        val zero = rememberMoneyDisplay(Money.ZERO, LocalAppFormats.current.money)
        ConfirmDialog(
            title = stringResource(R.string.dialog_zero_sale_title, zero.text.text),
            body = stringResource(R.string.dialog_zero_sale_body),
            confirmLabel = stringResource(R.string.sale_register),
            onConfirm = { onAction(CheckoutAction.ConfirmZeroTotal) },
            onDismiss = { onAction(CheckoutAction.DismissDialog) },
            dismissLabel = stringResource(R.string.action_back),
        )
    }
}

/** Un teléfono o una ventana mediana: una sola columna, a 600 dp como mucho. */
@Composable
private fun NarrowCheckout(uiState: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            TotalBlock(uiState.summary)
            AdjustmentRow(uiState, onAction)
            Payments(uiState, onAction)
            TipSection(uiState, onAction)
        }
    }
}

/** Una tableta: el resumen y el ajuste a la izquierda, los pagos a la derecha. */
@Composable
private fun WideCheckout(uiState: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    val spacing = AppTheme.spacing
    Row(
        Modifier.fillMaxSize().padding(horizontal = spacing.s24),
        horizontalArrangement = Arrangement.spacedBy(spacing.s24),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = spacing.s8),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            TotalBlock(uiState.summary)
            AdjustmentRow(uiState, onAction)
            TipSection(uiState, onAction)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = spacing.s8),
        ) {
            Payments(uiState, onAction)
        }
    }
}

/**
 * El total a cobrar en el bloque de color, con la cifra del héroe, y debajo, con filetes, las filas
 * que lo forman: subtotal, ajuste, el recargo o descuento de cada método e impuesto añadido. Así el
 * total se lee de lejos, y las filas suman lo que se ve.
 */
@Composable
private fun TotalBlock(summary: CheckoutSummary) {
    val app = AppTheme.colors
    val spacing = AppTheme.spacing
    val money = LocalAppFormats.current.money
    val totalText = summary.amountDue?.let {
        stringResource(R.string.a11y_checkout_total, rememberMoneyDisplay(it, money).description)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(app.heroContainer, MaterialTheme.shapes.extraLarge)
            .padding(
                start = spacing.s20,
                end = spacing.s20,
                top = spacing.s20,
                bottom = spacing.s8,
            ),
    ) {
        Column(Modifier.clearAndSetSemantics { totalText?.let { contentDescription = it } }) {
            Text(
                stringResource(R.string.checkout_total_due),
                style = MaterialTheme.typography.labelLarge,
                color = app.onHeroVariant,
            )
            MoneyText(summary.amountDue, MoneySize.HERO, tone = MoneyTone.ON_HERO)
        }
        Column(Modifier.padding(top = spacing.s8)) {
            summary.subtotal?.let { HeroRow(stringResource(R.string.checkout_subtotal), it) }
            if (summary.writtenAdjustment != Money.ZERO) {
                HeroRow(stringResource(R.string.checkout_adjustment), summary.writtenAdjustment)
            }
            summary.methodAdjustments.forEach { adjustment ->
                val name = methodName(adjustment.method)
                val label = if (adjustment.amount > Money.ZERO) {
                    stringResource(R.string.checkout_method_surcharge, name)
                } else {
                    stringResource(R.string.checkout_method_discount, name)
                }
                HeroRow(label, adjustment.amount)
            }
            summary.tax?.let { HeroRow(stringResource(R.string.term_tax), it) }
            if (summary.tip != Money.ZERO) {
                HeroRow(stringResource(R.string.checkout_tip), summary.tip)
            }
            if (summary.cashRounding != Money.ZERO) {
                HeroRow(stringResource(R.string.checkout_cash_rounding), summary.cashRounding)
            }
        }
    }
}

/** Una fila de libreta dentro del bloque de color, con su filete arriba. */
@Composable
private fun HeroRow(label: String, amount: Money) {
    val app = AppTheme.colors
    HorizontalDivider(thickness = AppTheme.sizes.hairline, color = app.heroDivider)
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppTheme.spacing.s40),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = app.onHeroVariant,
            modifier = Modifier.weight(1f),
        )
        MoneyText(amount, MoneySize.ROW, tone = MoneyTone.ON_HERO)
    }
}

/** `Ajuste de la venta`: abre la hoja del descuento o recargo de toda la venta. */
@Composable
private fun AdjustmentRow(uiState: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    val adjustment = uiState.adjustment
    val money = LocalAppFormats.current.money
    val shown = rememberMoneyDisplay(
        if (adjustment <
            Money.ZERO
        ) {
            -adjustment
        } else {
            adjustment
        },
        money,
    )
    val summary = when {
        adjustment < Money.ZERO ->
            stringResource(R.string.checkout_adjustment_discount_summary, shown.text.text)

        adjustment > Money.ZERO ->
            stringResource(R.string.checkout_adjustment_surcharge_summary, shown.text.text)

        else -> null
    }
    SettingsRow(
        title = stringResource(R.string.checkout_sale_adjustment),
        action = SettingsRowAction.Navigation { onAction(CheckoutAction.OpenAdjustment) },
        summary = summary,
    )
}

/**
 * `Propina`, solo con las propinas encendidas: un campo y `Sin propina`. La propina se suma a lo que
 * se cobra, y nunca a ninguna ganancia.
 */
@Composable
private fun TipSection(uiState: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    if (!uiState.tipsEnabled) return
    SectionHeader(stringResource(R.string.checkout_tip))
    MoneyField(
        value = uiState.tipText,
        onValueChange = { onAction(CheckoutAction.TipChange(it)) },
        label = stringResource(R.string.checkout_tip),
        error = fieldErrorText(uiState.tipError),
        imeAction = ImeAction.Done,
    )
    if (uiState.tipText.isNotEmpty()) {
        InlineTextButton(
            text = stringResource(R.string.checkout_no_tip),
            onClick = { onAction(CheckoutAction.NoTip) },
        )
    }
}

/** Los pagos, `Agregar pago` y la línea de balance, que recibe el foco si los pagos no suman. */
@Composable
private fun Payments(uiState: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    val money = LocalAppFormats.current.money
    val focus = remember { FocusRequester() }
    LaunchedEffect(uiState.balanceError) {
        if (uiState.balanceError) focus.requestFocus()
    }
    SectionHeader(stringResource(R.string.checkout_section_payments))
    problemText(uiState.problem)?.let {
        NoticeCard(text = it, variant = NoticeVariant.ERROR)
    }
    val balance = when (val current = uiState.balance) {
        is CheckoutBalance.Missing -> PaymentBalanceState.Missing(current.amount)
        is CheckoutBalance.Excess -> PaymentBalanceState.Excess(current.amount)
        CheckoutBalance.Complete -> PaymentBalanceState.Complete
        null -> null
    }
    val due = uiState.due
    val dueText = due?.let { rememberMoneyDisplay(it, money).text.text }
    val missing = when (val current = uiState.balance) {
        is CheckoutBalance.Missing -> current.amount
        is CheckoutBalance.Excess -> current.amount
        CheckoutBalance.Complete, null -> null
    }
    PaymentSplitEditor(
        balance = balance,
        onAddPayment =
            if (uiState.addable.isEmpty()) null else ({ onAction(CheckoutAction.OpenAddPayment) }),
        modifier = Modifier.focusRequester(focus).focusable(),
        balanceError =
            if (uiState.balanceError && dueText != null) {
                stringResource(R.string.checkout_error_sum, dueText)
            } else {
                null
            },
        errorAction =
            missing?.let { amount ->
                {
                    InlineTextButton(
                        text = stringResource(
                            R.string.checkout_use_amount_action,
                            rememberMoneyDisplay(amount, money).text.text,
                        ),
                        onClick = { onAction(CheckoutAction.UseDue) },
                    )
                }
            },
    ) {
        val count = uiState.payments.size
        uiState.payments.forEachIndexed { index, row ->
            PaymentRow(index, count, row, onAction)
        }
    }
}

@Composable
private fun PaymentRow(
    index: Int,
    count: Int,
    row: CheckoutPaymentRow,
    onAction: (CheckoutAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    val name = methodName(row.method)
    val remove = stringResource(R.string.checkout_remove_payment_action)
    val qr = stringResource(R.string.checkout_show_qr_action)
    val menu = buildList {
        if (row.method.hasQr &&
            !row.gone
        ) {
            add(OverflowAction(qr, { onAction(CheckoutAction.ShowQr(index)) }))
        }
        if (count >
            1
        ) {
            add(
                OverflowAction(remove, {
                    onAction(CheckoutAction.RemovePayment(index))
                }, destructive = true),
            )
        }
    }
    PaymentSplitRow(
        method = name,
        amountText = row.amountText,
        onAmountChange = { onAction(CheckoutAction.AmountChange(index, it)) },
        menu = menu,
        amountError = fieldErrorText(row.amountError),
        covers = row.covers,
        fee =
            if (row.showsFee) {
                PaymentFeeState(
                    text = row.feeText,
                    onTextChange = { onAction(CheckoutAction.FeeChange(index, it)) },
                    net = row.net,
                    error = fieldErrorText(row.feeError),
                    aboveAmount = row.feeAboveAmount,
                )
            } else {
                null
            },
        settles = row.settlesOn?.let {
            stringResource(R.string.checkout_settles_on, formats.date.longDate(it))
        },
        rowError = if (row.gone) stringResource(R.string.checkout_error_method_gone) else null,
        extra = {
            if (row.isCash) {
                CashTenderRow(
                    tenderedText = row.tenderedText,
                    onTenderedChange = { onAction(CheckoutAction.TenderedChange(index, it)) },
                    change = row.change?.takeIf { !row.tenderedBelow },
                    onExactAmount = { onAction(CheckoutAction.ExactAmount(index)) },
                    error = when {
                        row.tenderedBelow -> stringResource(R.string.checkout_error_tendered)
                        else -> fieldErrorText(row.tenderedError)
                    },
                )
            }
        },
    )
}

/** La barra fija de abajo: el error al registrar, si lo hubo, y `Registrar venta`. */
@Composable
private fun RegisterBar(uiState: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    val spacing = AppTheme.spacing
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider(
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = spacing.s16, vertical = spacing.s8),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                if (uiState.status == RegisterStatus.FAILED) {
                    NoticeCard(
                        text = stringResource(R.string.error_sale_register),
                        variant = NoticeVariant.ERROR,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { onAction(CheckoutAction.Register) },
                    )
                }
                PrimaryActionButton(
                    text = stringResource(R.string.sale_register),
                    onClick = { onAction(CheckoutAction.Register) },
                    variant = ActionVariant.PRIMARY,
                    state =
                        if (uiState.status == RegisterStatus.WORKING) {
                            ActionState.WORKING
                        } else {
                            ActionState.IDLE
                        },
                )
            }
        }
    }
}

/** La imagen de cobro a pantalla completa, encima de todo. */
@Composable
private fun QrOverlay(
    qr: CheckoutQr,
    uiState: CheckoutUiState,
    onAction: (CheckoutAction) -> Unit,
) {
    val name = methodName(qr.method)
    val photo = qr.image?.let { rememberStoredPhoto(it) }
    PaymentQrView(
        amount = qr.amount,
        image = (photo as? PhotoState.Ready)?.bitmap,
        imageDescription = stringResource(R.string.a11y_checkout_qr_image, name),
        instructions = qr.instructions,
        onRegister = { onAction(CheckoutAction.Register) },
        onBack = { onAction(CheckoutAction.CloseQr) },
        registering = uiState.status == RegisterStatus.WORKING,
    )
}

/** Las hojas del cobro: el ajuste de la venta y el método de un pago nuevo. */
@Composable
private fun CheckoutSheets(uiState: CheckoutUiState, onAction: (CheckoutAction) -> Unit) {
    val close = { onAction(CheckoutAction.CloseSheet) }
    when (val sheet = uiState.sheet) {
        null -> Unit

        CheckoutSheet.AddPayment -> AppBottomSheet(
            onDismissRequest = close,
            title = stringResource(R.string.checkout_add_payment_action),
        ) {
            uiState.addable.forEach { method ->
                SettingsRow(
                    title = methodName(method),
                    action = SettingsRowAction.Navigation {
                        onAction(CheckoutAction.AddPayment(method.id))
                    },
                )
            }
        }

        is CheckoutSheet.Adjustment -> AdjustmentSheet(sheet.editor, onAction, close)
    }
}

/** La hoja del ajuste: descuento o recargo, en monto o en porcentaje. */
@Composable
private fun AdjustmentSheet(
    editor: AdjustmentEditor,
    onAction: (CheckoutAction) -> Unit,
    close: () -> Unit,
) {
    val spacing = AppTheme.spacing
    val discount = stringResource(R.string.checkout_adjustment_discount)
    val surcharge = stringResource(R.string.checkout_adjustment_surcharge)
    val byAmount = stringResource(R.string.checkout_adjustment_by_amount)
    val byPercent = stringResource(R.string.checkout_adjustment_by_percent)
    AppBottomSheet(
        onDismissRequest = close,
        title = stringResource(R.string.checkout_sale_adjustment),
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                PrimaryActionButton(
                    text = stringResource(R.string.checkout_adjustment_apply_action),
                    onClick = { onAction(CheckoutAction.ApplyAdjustment) },
                    variant = ActionVariant.PRIMARY,
                )
                if (editor.canRemove) {
                    InlineTextButton(
                        text = stringResource(R.string.checkout_adjustment_remove_action),
                        onClick = { onAction(CheckoutAction.RemoveAdjustment) },
                    )
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            CompactChipRow(
                options = AdjustmentKind.entries,
                label = {
                    when (it) {
                        AdjustmentKind.DISCOUNT -> discount
                        AdjustmentKind.SURCHARGE -> surcharge
                    }
                },
                selected = setOf(editor.kind),
                onToggle = { onAction(CheckoutAction.AdjustmentKindChange(it)) },
                multiple = false,
            )
            CompactChipRow(
                options = AdjustmentInput.entries,
                label = {
                    when (it) {
                        AdjustmentInput.AMOUNT -> byAmount
                        AdjustmentInput.PERCENT -> byPercent
                    }
                },
                selected = setOf(editor.input),
                onToggle = { onAction(CheckoutAction.AdjustmentInputChange(it)) },
                multiple = false,
            )
            val label = when (editor.kind) {
                AdjustmentKind.DISCOUNT -> discount
                AdjustmentKind.SURCHARGE -> surcharge
            }
            val error = adjustmentErrorText(editor.error)
            when (editor.input) {
                AdjustmentInput.AMOUNT -> MoneyField(
                    value = editor.text,
                    onValueChange = { onAction(CheckoutAction.AdjustmentTextChange(it)) },
                    label = label,
                    error = error,
                )

                AdjustmentInput.PERCENT -> PercentField(
                    value = editor.text,
                    onValueChange = { onAction(CheckoutAction.AdjustmentTextChange(it)) },
                    label = label,
                    error = error,
                )
            }
        }
    }
}

@Composable
private fun adjustmentErrorText(error: AdjustmentError?): String? = when (error) {
    null -> null
    AdjustmentError.NOT_A_NUMBER -> stringResource(R.string.field_error_number)
    AdjustmentError.DISCOUNT_ABOVE_TOTAL -> stringResource(R.string.checkout_error_discount)
    AdjustmentError.TOO_HIGH -> stringResource(R.string.error_value_high)
}

@Composable
private fun fieldErrorText(error: SaleFieldError?): String? = when (error) {
    null -> null

    SaleFieldError.NOT_A_NUMBER -> stringResource(R.string.field_error_number)

    SaleFieldError.TOO_HIGH,
    SaleFieldError.FRACTION_NOT_ALLOWED,
    SaleFieldError.ZERO_QUANTITY,
    SaleFieldError.DISCOUNT_OVER_LINE,
    -> stringResource(R.string.error_value_high)
}

/** Lo que impide cobrar, aparte de que los pagos no sumen. */
@Composable
private fun problemText(problem: CheckoutProblem?): String? = when (problem) {
    null -> null

    // Lo recibido que no alcanza se dice en su pago, no arriba.
    is CheckoutProblem.TenderedBelowAmount -> null

    CheckoutProblem.DiscountAboveSale -> stringResource(R.string.checkout_error_discount)

    CheckoutProblem.LineIssue,
    CheckoutProblem.TooLarge,
    is CheckoutProblem.PaymentBelowZero,
    -> stringResource(R.string.error_value_high)
}

// Desde 840 dp el cobro va en dos columnas.
private val EXPANDED_WIDTH = 840.dp
