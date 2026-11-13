package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.money.ProvideAccountCurrency
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Lo que se recibió en otra moneda por un pago, debajo de él: `Entregado en {currency}` con el monto
 * en esa moneda, con su símbolo y sus decimales, y la cotización escrita, `1 XXX = 4.150,5`. El monto
 * del pago sigue en la moneda del negocio, arriba: esto solo dice qué se entregó.
 *
 * Lo usa la tarjeta de cada pago del cobro. Cada fila es un solo foco para TalkBack, que lee el
 * rótulo y el valor juntos.
 */
@Composable
internal fun ForeignTenderLines(tender: ForeignTender, modifier: Modifier = Modifier) {
    val spacing = AppTheme.spacing
    val formats = LocalAppFormats.current
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(
            Modifier.padding(top = spacing.s12),
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        LabeledValue(
            stringResource(R.string.checkout_fx_tendered, tender.currency.code),
        ) {
            ProvideAccountCurrency(AccountCurrency(tender.currency, tender.decimals)) {
                MoneyText(Money(tender.amountMinor), MoneySize.ROW)
            }
        }
        LabeledValue(stringResource(R.string.checkout_fx_rate)) {
            Text(
                stringResource(
                    R.string.reference_rate_current,
                    tender.currency.code,
                    formats.quantity.formatMicro(tender.rateMicro),
                ),
                style = AppTheme.amounts.row,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Un rótulo a la izquierda y su valor a la derecha, en una fila de 48 dp y un solo foco. */
@Composable
private fun LabeledValue(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}

@ThemePreviews
@Composable
private fun ForeignTenderLinesPreview() {
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            ForeignTenderLines(
                ForeignTender(
                    currency = requireNotNull(CurrencyCode.of("XTS")),
                    decimals = 2,
                    amountMinor = 987_654_321L,
                    rateMicro = 4_150_500_000L,
                ),
            )
        }
    }
}
