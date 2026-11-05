package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.ui.currency.MoneyDisplay
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import java.util.Locale

/**
 * Dibuja un monto ya armado: una línea, sin elipsis y sin recorte, porque un monto cortado es un
 * monto falso. TalkBack lee solo [MoneyDisplay.description], con el nombre de la moneda, y nunca el
 * símbolo.
 *
 * Es la pieza que dibuja la cifra. El monto de las pantallas, con su tamaño, su tono, el anulado y
 * el modo discreto, la envuelve.
 *
 * @param style uno de los estilos de `AppTheme.amounts`, que traen las cifras tabulares.
 * @param color la tinta del monto. Nunca el color de marca ni el acento fuera del héroe.
 */
@Composable
fun MoneyAmountText(
    display: MoneyDisplay,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = display.text,
        modifier = modifier.clearAndSetSemantics { contentDescription = display.description },
        color = color,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

/**
 * Montos de muestra con datos inventados: el código de pruebas de ISO 4217 y símbolos escritos a
 * mano, que van antes y después de las cifras, uno de derecha a izquierda.
 */
@ThemePreviews
@Composable
private fun MoneyAmountTextPreview() {
    val samples =
        listOf(
            previewFormatter("en", "\u00A4") to Money(123_456_789_012L),
            previewFormatter("fr", "\u20B5") to Money(-120_000L),
            previewFormatter("en", "\u060B") to Money(420_000L),
            previewFormatter("es", null) to Money.ZERO,
        )
    PreviewTheme {
        Column(
            modifier = Modifier.padding(AppTheme.spacing.s16),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            MoneyAmountText(
                rememberMoneyDisplay(samples[0].second, samples[0].first),
                AppTheme.amounts.hero,
            )
            for ((formatter, amount) in samples.drop(1)) {
                MoneyAmountText(rememberMoneyDisplay(amount, formatter), AppTheme.amounts.row)
            }
        }
    }
}

private fun previewFormatter(tag: String, symbol: String?): MoneyFormatter = MoneyFormatter(
    formatLocale = requireNotNull(FormatLocale.of(tag)),
    currencyCode = requireNotNull(CurrencyCode.of("XTS")),
    currencyDecimals = 2,
    currencySymbol = symbol,
    uiLocale = Locale.forLanguageTag(tag),
)
