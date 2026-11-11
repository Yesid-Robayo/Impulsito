package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Si lo escrito en los pagos cubre lo que se cobra: lo dice la línea de balance. */
sealed interface PaymentBalanceState {
    /** Falta escribir [amount]. */
    data class Missing(val amount: Money) : PaymentBalanceState

    /** Los pagos pasan [amount] de lo que se cobra. */
    data class Excess(val amount: Money) : PaymentBalanceState

    /** Los pagos suman exactamente lo que se cobra. */
    data object Complete : PaymentBalanceState
}

/**
 * La lista de pagos del cobro: una [PaymentSplitRow] por pago, `Agregar pago` y la línea de balance,
 * siempre visible al final.
 *
 * @param onAddPayment `null` si ya no queda otro método para agregar.
 */
@Composable
fun PaymentSplitEditor(
    balance: PaymentBalanceState?,
    onAddPayment: (() -> Unit)?,
    modifier: Modifier = Modifier,
    balanceError: String? = null,
    errorAction: (@Composable () -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        rows()
        if (onAddPayment != null) {
            InlineTextButton(
                text = stringResource(R.string.checkout_add_payment_action),
                onClick = onAddPayment,
            )
        }
        PaymentBalanceLine(balance, balanceError, errorAction)
    }
}

/**
 * La línea de balance: `Falta cobrar`, `Sobra` o `Pagos completos`, con texto e icono, para que el
 * estado no dependa del color. Se anuncia sola al cambiar. Con [error], tras intentar registrar con
 * pagos que no suman, lo dice en rojo y ofrece [errorAction], que completa la diferencia.
 */
@Composable
fun PaymentBalanceLine(
    balance: PaymentBalanceState?,
    error: String? = null,
    errorAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    balance ?: return
    val money = LocalAppFormats.current.money
    val colors = AppTheme.colors
    val (text, icon, tint) = when (balance) {
        is PaymentBalanceState.Missing -> Triple(
            stringResource(
                R.string.checkout_missing,
                rememberMoneyDisplay(balance.amount, money).text.text,
            ),
            R.drawable.ic_warning,
            colors.warning,
        )

        is PaymentBalanceState.Excess -> Triple(
            stringResource(
                R.string.checkout_excess,
                rememberMoneyDisplay(balance.amount, money).text.text,
            ),
            R.drawable.ic_warning,
            colors.warning,
        )

        PaymentBalanceState.Complete -> Triple(
            stringResource(R.string.checkout_complete),
            R.drawable.ic_check,
            colors.profit,
        )
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .semantics {
                    liveRegion =
                        if (error != null) LiveRegionMode.Assertive else LiveRegionMode.Polite
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(AppTheme.sizes.iconInline),
            )
            Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            errorAction?.invoke()
        }
    }
}

/**
 * Un pago del cobro: una tarjeta con el método en `titleSmall`, su menú, y el monto en [MoneyField].
 *
 * - [covers] es lo que termina cubriendo con el recargo o descuento de su método, si es distinto de
 *   lo escrito.
 * - Con comisión ([fee] no nulo): la comisión, editable en esta venta, y el neto recibido. Las dos son
 *   sensibles: en modo discreto se ocultan, y tocar la comisión la deja escribir sin ocultar el campo.
 * - [settles] es `Acredita el {date}`, si el dinero tarda.
 * - [extra] es lo propio de su tipo, como lo recibido y el cambio en efectivo.
 *
 * @param menu las opciones del pago; vacío si no hay ninguna.
 */
@Composable
fun PaymentSplitRow(
    method: String,
    amountText: String,
    onAmountChange: (String) -> Unit,
    menu: List<OverflowAction>,
    modifier: Modifier = Modifier,
    amountError: String? = null,
    covers: Money? = null,
    fee: PaymentFeeState? = null,
    settles: String? = null,
    rowError: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val spacing = AppTheme.spacing
    AppCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                method,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (menu.isNotEmpty()) OverflowMenu(menu)
        }
        MoneyField(
            value = amountText,
            onValueChange = onAmountChange,
            label = stringResource(R.string.checkout_payment_amount),
            error = amountError,
        )
        covers?.let { CoversLine(it) }
        rowError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.s8),
            )
        }
        fee?.let { FeeLines(it) }
        settles?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s8),
            )
        }
        extra()
    }
}

/**
 * La comisión de un pago: lo escrito o lo calculado en [text], su error, si supera el pago, y el
 * neto que llega.
 */
data class PaymentFeeState(
    val text: String,
    val onTextChange: (String) -> Unit,
    val net: Money?,
    val error: String? = null,
    val aboveAmount: Boolean = false,
)

/** `Se cobra {amount}`: lo que cubre el pago con el recargo o descuento de su método. */
@Composable
private fun CoversLine(amount: Money) {
    val money = LocalAppFormats.current.money
    Text(
        stringResource(R.string.checkout_covers, rememberMoneyDisplay(amount, money).text.text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = AppTheme.spacing.s8),
    )
}

@Composable
private fun FeeLines(fee: PaymentFeeState) {
    val spacing = AppTheme.spacing
    HorizontalDivider(
        Modifier.padding(top = spacing.s12),
        thickness = AppTheme.sizes.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    FeeField(fee)
    if (fee.aboveAmount) {
        Text(
            stringResource(R.string.checkout_warning_fee),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.warning,
        )
    }
    LabeledAmount(stringResource(R.string.checkout_net_received), fee.net, sensitive = true)
}

/**
 * La comisión, editable. En modo discreto se ve oculta; tocarla abre el campo, que no se oculta
 * mientras se escribe, y vuelve a ocultarse al salir de él.
 */
@Composable
private fun FeeField(fee: PaymentFeeState) {
    val discreet = LocalDiscreetMode.current
    var editing by remember { mutableStateOf(false) }
    val label = stringResource(R.string.checkout_fee)
    if (discreet && !editing) {
        val hidden = stringResource(R.string.a11y_checkout_discreet_fee)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .clickable(role = Role.Button) { editing = true }
                .clearAndSetSemantics { contentDescription = "$label, $hidden" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            DiscreetValue { }
        }
        return
    }
    val focus = remember { FocusRequester() }
    MoneyField(
        value = fee.text,
        onValueChange = fee.onTextChange,
        label = label,
        error = fee.error,
        modifier =
            Modifier
                .padding(top = AppTheme.spacing.s8)
                .focusRequester(focus)
                .onFocusChanged { if (!it.isFocused && editing) editing = false },
    )
    if (discreet && editing) {
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

/**
 * Lo recibido en un pago en efectivo, junto al cambio que se devuelve, con el acceso `Monto exacto`.
 * El cambio es solo una ayuda: sin lo recibido no se muestra, y no impide registrar. Lo recibido que
 * no alcanza se dice en el campo, con [error].
 *
 * @param change el cambio, o `null` si no se escribió lo recibido o no alcanza.
 */
@Composable
fun CashTenderRow(
    tenderedText: String,
    onTenderedChange: (String) -> Unit,
    change: Money?,
    onExactAmount: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val spacing = AppTheme.spacing
    Column(modifier.fillMaxWidth().padding(top = spacing.s8)) {
        AdaptiveFieldRow(
            firstLabel = stringResource(R.string.checkout_tendered),
            secondLabel = stringResource(R.string.checkout_change),
            first = { fieldModifier ->
                MoneyField(
                    value = tenderedText,
                    onValueChange = onTenderedChange,
                    label = stringResource(R.string.checkout_tendered),
                    error = error,
                    modifier = fieldModifier,
                )
            },
            second = { valueModifier ->
                Column(valueModifier, horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.checkout_change),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(change, MoneySize.ROW)
                }
            },
        )
        InlineTextButton(
            text = stringResource(R.string.checkout_exact_amount_action),
            onClick = onExactAmount,
        )
    }
}

/** Una fila de libreta: etiqueta a la izquierda y monto a la derecha. */
@Composable
private fun LabeledAmount(label: String, amount: Money?, sensitive: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        MoneyText(amount, MoneySize.ROW, sensitive = sensitive)
    }
}

@ThemePreviews
@Composable
private fun PaymentSplitEditorPreview() {
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            PaymentSplitEditor(
                balance = PaymentBalanceState.Missing(Money(1_234_567_890L)),
                onAddPayment = {},
            ) {
                PaymentSplitRow(
                    method = PreviewSamples.METHOD_CASH,
                    amountText = "15000",
                    onAmountChange = {},
                    menu = listOf(
                        OverflowAction(stringResource(R.string.checkout_remove_payment_action), {
                        }),
                    ),
                )
                PaymentSplitRow(
                    method = PreviewSamples.LONG_NAME,
                    amountText = "10200",
                    onAmountChange = {},
                    menu = emptyList(),
                    covers = Money(10_557L),
                    fee = PaymentFeeState("355", {}, Money(10_202L), aboveAmount = false),

                )
            }
            PaymentBalanceLine(PaymentBalanceState.Complete)
        }
    }
}
