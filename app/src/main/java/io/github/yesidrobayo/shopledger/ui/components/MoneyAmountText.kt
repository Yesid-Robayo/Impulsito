package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
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
import kotlin.math.roundToInt

/**
 * Dibuja un monto ya armado: una línea, sin elipsis y sin recorte, porque un monto cortado es un
 * monto falso. TalkBack lee solo [MoneyDisplay.description], con el nombre de la moneda, y nunca el
 * símbolo.
 *
 * Con [autoSize], un monto que no cabe se achica por pasos en vez de salirse: lo usan las cifras
 * grandes, como la del héroe, que con 13 cifras no caben en un teléfono angosto.
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
    autoSize: TextAutoSize? = null,
) {
    // Una etiqueta que solo tiene la fuente del sistema, como una en otro alfabeto, agranda la línea en
    // Android 15 o superior aunque el estilo fije su alto: el monto conserva la caja y la línea base
    // de sus cifras, para que una fila no crezca ni se corra.
    val systemRun = display.text.spanStyles.any { it.item.fontFamily == FontFamily.Default }
    BasicText(
        text = display.text,
        modifier =
            modifier
                .then(if (systemRun && autoSize == null) Modifier.digitLineBox(style) else Modifier)
                .clearAndSetSemantics { contentDescription = display.description },
        style = style.copy(color = color),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        autoSize = autoSize,
    )
}

/**
 * Deja el monto con el alto y la línea base que tendrían sus cifras solas en [style]. El texto se
 * dibuja igual; si una corrida es más alta, sobresale un poco sin mover nada alrededor.
 */
@Composable
private fun Modifier.digitLineBox(style: TextStyle): Modifier {
    val measurer = rememberTextMeasurer()
    val reference = remember(style, measurer) { measurer.measure(REFERENCE_DIGITS, style) }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val baseline = reference.firstBaseline.roundToInt()
        val shift = baseline - placeable[FirstBaseline]
        layout(
            placeable.width,
            reference.size.height,
            mapOf(
                FirstBaseline to baseline,
                LastBaseline to baseline,
            ),
        ) {
            placeable.place(0, shift)
        }
    }
}

/** Cifras de referencia para medir el alto y la línea base de un estilo de montos. */
private const val REFERENCE_DIGITS = "0123456789"

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
