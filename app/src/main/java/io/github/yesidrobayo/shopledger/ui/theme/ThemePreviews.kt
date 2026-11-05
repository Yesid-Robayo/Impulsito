package io.github.yesidrobayo.shopledger.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.format.DateFormatter
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.format.PercentFormatter
import io.github.yesidrobayo.shopledger.format.QuantityFormatter
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import java.util.Locale

/** Las tres vistas previas que pide todo composable no trivial: claro, oscuro y letra al 200 %. */
@Preview(name = "Claro")
@Preview(
    name = "Oscuro",
    uiMode =
        Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Preview(name = "Letra al 200 %", fontScale = 2f)
annotation class ThemePreviews

/**
 * El tema de una vista previa: la paleta pedida, en el modo de la vista previa, sobre el papel, con
 * los formatos de muestra y el modo discreto que se pida.
 */
@Composable
fun PreviewTheme(
    palette: ColorPalette = DefaultColorPalette,
    discreet: Boolean = false,
    formats: AppFormats = previewFormats(),
    content: @Composable () -> Unit,
) {
    AppTheme(palette = palette, darkTheme = isSystemInDarkTheme()) {
        CompositionLocalProvider(
            LocalAppFormats provides formats,
            LocalDiscreetMode provides discreet,
        ) {
            Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
        }
    }
}

/**
 * Formatos de muestra para vistas previas y capturas: el código de pruebas de ISO 4217, `XTS`, con
 * el signo genérico `¤`, sin decimales y con los números de [formatTag]. No representan ningún
 * país: cambian solo para mostrar otra forma de escribir los números.
 */
fun previewFormats(
    formatTag: String = PREVIEW_FORMAT_TAG,
    uiLocale: Locale = Locale.ENGLISH,
    currencyDecimals: Int = 0,
): AppFormats {
    val formatLocale = requireNotNull(FormatLocale.of(formatTag))
    return AppFormats(
        money =
            MoneyFormatter(
                formatLocale = formatLocale,
                currencyCode = requireNotNull(CurrencyCode.of(PREVIEW_CURRENCY)),
                currencyDecimals = currencyDecimals,
                currencySymbol = PREVIEW_SYMBOL,
                uiLocale = uiLocale,
            ),
        quantity = QuantityFormatter(formatLocale),
        percent = PercentFormatter(formatLocale),
        date = DateFormatter(formatLocale, uiLocale),
    )
}

/** Números con punto de miles y coma decimal, como los ejemplos de la documentación. */
private const val PREVIEW_FORMAT_TAG = "es"

/** El código de pruebas de ISO 4217: no es la moneda de nadie. */
private const val PREVIEW_CURRENCY = "XTS"

/** El signo genérico de moneda. */
private const val PREVIEW_SYMBOL = "¤"
