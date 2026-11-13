package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.currency.MoneyDisplay
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una fila de un libro: el tipo y la fecha a la izquierda, y a la derecha el monto con signo y el
 * saldo que dejó, `Saldo ¤ 4.000`. Sirve igual para el libro de un cliente, de un proveedor o de una
 * cuenta de dinero: cada pantalla pone sus textos.
 *
 * Una fila compensada no se borra ni se edita: se muestra tachada y apagada, con su etiqueta, y la
 * fila que la compensa aparece aparte en el mismo libro. El saldo de cada fila es el que guardó al
 * escribirse: la fila nunca lo recalcula.
 *
 * Con letra grande o un monto muy largo, las cifras pasan debajo del texto, sin cortarse.
 *
 * @param title el tipo de movimiento, como `Abono`.
 * @param detail la fecha y lo que identifica la fila, como `20 oct, Venta 1.180`.
 * @param amount el monto con su signo: positivo lleva `+`.
 * @param balanceAfter el saldo después de la fila, o `null` si ese libro no lo guarda.
 * @param balanceText la frase del saldo, con `%1$s` donde va el monto.
 * @param voided si otra fila ya la compensó.
 * @param sensitive si las cifras se ocultan con el modo discreto: el saldo de una cuenta sí, el de la
 *   persona que se atiende no.
 * @param onClick lo que hace tocar la fila, con [onClickLabel] para TalkBack, o `null` si no se toca.
 */
@Composable
fun LedgerRow(
    title: String,
    detail: String,
    amount: Money,
    balanceAfter: Money?,
    @StringRes balanceText: Int,
    modifier: Modifier = Modifier,
    voided: Boolean = false,
    sensitive: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val spacing = AppTheme.spacing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val touch =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
        }
    Column(modifier.fillMaxWidth().then(touch)) {
        LedgerLineLayout(
            gap = spacing.s12,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.listRow)
                    .padding(horizontal = spacing.s16, vertical = spacing.s12)
                    // Una fila es un foco: TalkBack lee tipo, fecha, monto y saldo juntos.
                    .semantics(mergeDescendants = true) {},
            leading = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                    Text(
                        text = title,
                        style =
                            MaterialTheme.typography.titleSmall.let {
                                if (voided) {
                                    it.copy(
                                        textDecoration = TextDecoration.LineThrough,
                                    )
                                } else {
                                    it
                                }
                            },
                        color = if (voided) muted else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(text = detail, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            },
            trailing = { alignment ->
                Column(
                    horizontalAlignment = alignment,
                    verticalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    MoneyText(
                        amount = amount,
                        size = MoneySize.ROW,
                        tone = if (voided) MoneyTone.MUTED else MoneyTone.DEFAULT,
                        voided = voided,
                        sensitive = sensitive,
                        voidedLabel = R.string.ledger_status_voided,
                        voidedDescription = R.string.a11y_ledger_voided_amount,
                        signed = true,
                    )
                    balanceAfter?.let { BalanceAfter(it, balanceText, sensitive) }
                }
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = spacing.s16),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * El saldo que dejó la fila, dentro de su frase traducida: el monto conserva sus corridas, con el
 * símbolo aparte, y TalkBack lee el nombre de la moneda.
 */
@Composable
private fun BalanceAfter(balance: Money, @StringRes text: Int, sensitive: Boolean) {
    val style = AppTheme.amounts.small
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val display = rememberMoneyDisplay(balance, LocalAppFormats.current.money)
    val pattern = stringResource(text)
    val description = stringResource(text, display.description)
    val phrase =
        remember(pattern, display, description) {
            MoneyDisplay(formatAnnotated(pattern, display.text), description)
        }
    val shown =
        @Composable {
            MoneyAmountText(
                display = phrase,
                style = style,
                color = muted,
                modifier =
                    Modifier.clearAndSetSemantics {
                        contentDescription = phrase.description
                        if (sensitive) isSensitiveData = true
                    },
            )
        }
    if (sensitive) DiscreetValue(style = style, color = muted) { shown() } else shown()
}

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun LedgerRowPreview() {
    PreviewTheme {
        Column(horizontalAlignment = Alignment.Start) {
            LedgerRow(
                title = PreviewSamples.LEDGER_PAYMENT,
                detail = PreviewSamples.LEDGER_DETAIL,
                amount = -PreviewSamples.PRICE,
                balanceAfter = PreviewSamples.SECOND_PRICE,
                balanceText = R.string.customer_ledger_balance_after,
                onClick = {},
            )
            LedgerRow(
                title = PreviewSamples.LEDGER_CREDIT_SALE,
                detail = PreviewSamples.LONG_NAME,
                amount = PreviewSamples.LONG_AMOUNT,
                balanceAfter = PreviewSamples.LONG_AMOUNT,
                balanceText = R.string.customer_ledger_balance_after,
            )
            LedgerRow(
                title = PreviewSamples.LEDGER_PAYMENT,
                detail = PreviewSamples.LEDGER_DETAIL,
                amount = -PreviewSamples.PRICE,
                balanceAfter = PreviewSamples.SECOND_PRICE,
                balanceText = R.string.customer_ledger_balance_after,
                voided = true,
            )
        }
    }
}

@ThemePreviews
@Composable
private fun LedgerRowDiscreetPreview() {
    PreviewTheme(discreet = true) {
        LedgerRow(
            title = PreviewSamples.LEDGER_PAYMENT,
            detail = PreviewSamples.LEDGER_DETAIL,
            amount = PreviewSamples.PRICE,
            balanceAfter = PreviewSamples.SECOND_PRICE,
            balanceText = R.string.customer_ledger_balance_after,
            sensitive = true,
        )
    }
}
