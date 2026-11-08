package io.github.yesidrobayo.shopledger.ui.payment

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/** El texto visible de [method]: su nombre, o el de su clave inicial en el idioma de la interfaz. */
@Composable
fun paymentMethodName(method: PaymentMethodRecord): String = paymentMethodName(
    method.systemKey,
    method.details.name,
)

/** El nombre de un método por su clave inicial y su nombre propio, si lo tiene. */
@Composable
fun paymentMethodName(systemKey: String?, name: String?): String =
    SeedText.displayName(LocalResources.current, SeedTable.PAYMENT_METHODS, systemKey, name)

/** El nombre de la clase de un método, como se ofrece en los chips del formulario. */
@StringRes
fun kindLabel(kind: PaymentMethodKind): Int = when (kind) {
    PaymentMethodKind.CASH -> R.string.payment_method_kind_cash
    PaymentMethodKind.CARD -> R.string.payment_method_kind_card
    PaymentMethodKind.TRANSFER -> R.string.payment_method_kind_transfer
    PaymentMethodKind.WALLET -> R.string.payment_method_kind_wallet
    PaymentMethodKind.ON_ACCOUNT -> R.string.payment_method_kind_on_account
    PaymentMethodKind.OTHER -> R.string.payment_method_kind_other
}

/**
 * La segunda línea de la fila: el tipo y, si la tiene, la comisión, o lo que exige un cobro a
 * crédito. La comisión fija pasa por el formato de montos, con su símbolo en su propia corrida.
 */
@Composable
fun paymentMethodSummary(details: PaymentMethodDetails): AnnotatedString {
    val kind = stringResource(kindLabel(details.kind))
    val second: CharSequence? =
        when {
            details.kind == PaymentMethodKind.ON_ACCOUNT ->
                stringResource(R.string.payment_method_on_account_requires)

            details.feeFixed.minor > 0L -> {
                val fixed = rememberMoneyDisplay(details.feeFixed, LocalAppFormats.current.money)
                formatAnnotated(
                    stringResource(R.string.payment_method_fee_summary),
                    percentText(details.feeRate),
                    fixed.text,
                )
            }

            details.feeRate.bp > 0L ->
                stringResource(
                    R.string.payment_method_fee_percent_only,
                    percentText(details.feeRate),
                )

            else -> null
        }
    return if (second == null) {
        AnnotatedString(kind)
    } else {
        formatAnnotated(stringResource(R.string.payment_method_row_summary), kind, second)
    }
}

/** La línea de acreditación, o `null` si el dinero llega el mismo día. */
@Composable
fun settlementText(details: PaymentMethodDetails): String? {
    val days = details.settlementDays
    if (days <= 0 || details.kind == PaymentMethodKind.ON_ACCOUNT) return null
    return pluralStringResource(R.plurals.payment_method_settlement_days, days, days)
}

/** Un porcentaje con el formato del negocio, como `2,5 %`. */
@Composable
fun percentText(rate: BasisPoints): String = LocalAppFormats.current.percent.format(rate)
