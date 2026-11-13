package io.github.yesidrobayo.shopledger.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.currency.MoneyDisplay
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Qué dice un saldo: quién le debe a quién, y si ya venció. */
enum class BalanceBadgeVariant(
    @param:StringRes val text: Int,
    @param:StringRes val shortText: Int,
    @param:DrawableRes val icon: Int,
) {
    /** Un cliente debe. */
    OWES(
        R.string.customer_balance_owes,
        R.string.customer_balance_owes_short,
        R.drawable.ic_schedule,
    ),

    /** Lo que se debe ya pasó su vencimiento. */
    OVERDUE(
        R.string.customer_balance_overdue,
        R.string.customer_balance_overdue_short,
        R.drawable.ic_error,
    ),

    /** Hay saldo a favor: de un cliente o con un proveedor. */
    CREDIT(
        R.string.customer_balance_credit,
        R.string.customer_balance_credit_short,
        R.drawable.ic_savings,
    ),

    /** El negocio le debe a un proveedor. */
    SUPPLIER_OWED(
        R.string.payable_balance_owed,
        R.string.payable_balance_owed_short,
        R.drawable.ic_schedule,
    ),
    ;

    companion object {
        /**
         * La variante del saldo con signo de un cliente: positivo debe, negativo es saldo a favor, y
         * en cero no hay insignia. [overdue] solo cuenta si debe.
         */
        fun forCustomer(balance: Money, overdue: Boolean = false): BalanceBadgeVariant? = when {
            balance > Money.ZERO -> if (overdue) OVERDUE else OWES
            balance < Money.ZERO -> CREDIT
            else -> null
        }

        /**
         * La variante del saldo con signo con un proveedor: positivo es lo que el negocio le debe, y
         * negativo, saldo a favor del negocio. En cero no hay insignia.
         */
        fun forSupplier(balance: Money, overdue: Boolean = false): BalanceBadgeVariant? = when {
            balance > Money.ZERO -> if (overdue) OVERDUE else SUPPLIER_OWED
            balance < Money.ZERO -> CREDIT
            else -> null
        }
    }
}

/**
 * Un saldo dicho con palabras, `Debe ¤ 4.000` o `A favor ¤ 1.000`, en una caja de 24 dp con el color
 * de su estado y un icono: el estado nunca depende solo del color.
 *
 * Recibe el saldo con signo y lo muestra sin él, porque la palabra ya dice hacia dónde va. Un saldo
 * en cero no dibuja nada. Con el modo discreto deja la palabra sola, sin el monto: quien está en el
 * mostrador no debe ver la deuda de otra persona.
 *
 * Es el mismo para clientes, proveedores y cuentas: cada pantalla elige la variante con
 * [BalanceBadgeVariant.forCustomer] o [BalanceBadgeVariant.forSupplier].
 */
@Composable
fun BalanceBadge(variant: BalanceBadgeVariant?, balance: Money, modifier: Modifier = Modifier) {
    if (variant == null || balance == Money.ZERO) return
    val (container, content) = badgeColors(variant)
    val discreet = LocalDiscreetMode.current
    Row(
        modifier =
            modifier
                .heightIn(min = AppTheme.sizes.statusTag)
                .background(container, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = AppTheme.spacing.s8, vertical = AppTheme.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Icon(
            painter = painterResource(variant.icon),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(AppTheme.sizes.iconTag),
        )
        if (discreet) {
            Text(
                text = stringResource(variant.shortText),
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1,
            )
        } else {
            val amount = if (balance < Money.ZERO) -balance else balance
            val display = rememberMoneyDisplay(amount, LocalAppFormats.current.money)
            val phrase = badgePhrase(variant.text, display)
            MoneyAmountText(
                display = phrase,
                style = AppTheme.amounts.caption,
                color = content,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = phrase.description
                    isSensitiveData = true
                },
            )
        }
    }
}

/**
 * La frase del saldo con el monto en su lugar, en el orden que pide cada idioma. El monto conserva
 * sus corridas, con el símbolo aparte, y TalkBack lee el nombre de la moneda.
 */
@Composable
private fun badgePhrase(@StringRes text: Int, display: MoneyDisplay): MoneyDisplay {
    val template = stringResource(text, AMOUNT_MARK)
    val description = stringResource(text, display.description)
    val phrase =
        remember(template, display) {
            val at = template.indexOf(AMOUNT_MARK)
            if (at < 0) {
                AnnotatedString(template)
            } else {
                buildAnnotatedString {
                    append(template.substring(0, at))
                    append(display.text)
                    append(template.substring(at + AMOUNT_MARK.length))
                }
            }
        }
    return MoneyDisplay(phrase, description)
}

/** El fondo y la tinta de cada variante, de la paleta vigente. */
@Composable
private fun badgeColors(variant: BalanceBadgeVariant): Pair<Color, Color> {
    val app = AppTheme.colors
    return when (variant) {
        BalanceBadgeVariant.OWES, BalanceBadgeVariant.SUPPLIER_OWED ->
            app.warningContainer to app.onWarningContainer

        BalanceBadgeVariant.OVERDUE -> app.lossContainer to app.onLossContainer

        BalanceBadgeVariant.CREDIT -> app.profitContainer to app.onProfitContainer
    }
}

/** Un marcador que ningún texto trae, para ubicar el monto dentro de la frase traducida. */
private const val AMOUNT_MARK = ""

@ThemePreviews
@Composable
private fun BalanceBadgePreview() {
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            BalanceBadge(BalanceBadgeVariant.OWES, PreviewSamples.PRICE)
            BalanceBadge(BalanceBadgeVariant.OVERDUE, PreviewSamples.LONG_AMOUNT)
            BalanceBadge(BalanceBadgeVariant.CREDIT, PreviewSamples.NEGATIVE_AMOUNT)
            BalanceBadge(BalanceBadgeVariant.SUPPLIER_OWED, PreviewSamples.SECOND_PRICE)
        }
    }
}

@ThemePreviews
@Composable
private fun BalanceBadgeDiscreetPreview() {
    PreviewTheme(discreet = true) {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            BalanceBadge(BalanceBadgeVariant.OWES, PreviewSamples.PRICE)
            BalanceBadge(BalanceBadgeVariant.CREDIT, PreviewSamples.NEGATIVE_AMOUNT)
        }
    }
}
