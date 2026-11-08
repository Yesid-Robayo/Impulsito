package io.github.yesidrobayo.shopledger.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.format.DateFormatter
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.format.PercentFormatter
import io.github.yesidrobayo.shopledger.format.QuantityFormatter
import java.util.Locale

/**
 * Lo que las pantallas necesitan de los ajustes para dibujar cifras: el formato regional y la
 * moneda del negocio, y si el modo discreto está encendido.
 */
@Immutable
data class BusinessDisplay(val regional: RegionalSettings, val discreet: Boolean) {
    companion object {
        fun of(settings: AppSettings): BusinessDisplay =
            BusinessDisplay(settings.regional, settings.preferences.discreetModeEnabled)
    }
}

/**
 * Los formatos del negocio: los números y la moneda salen de [regional], y las palabras, como el
 * nombre de la moneda o de un mes, del idioma de la interfaz [uiLocale].
 *
 * El formato regional es una etiqueta guardada en la base, y puede venir de una copia de otro
 * teléfono. Si no nombra un idioma, no hay formato que respetar sin adivinar: se usa el del idioma
 * de la interfaz, para que la app no se caiga, hasta que la persona elija el formato en la pantalla
 * de región.
 */
fun businessFormatsOf(regional: RegionalSettings, uiLocale: Locale): AppFormats {
    val formatLocale =
        FormatLocale.of(regional.formatLocaleTag)
            ?: requireNotNull(FormatLocale.of(uiLocale.toLanguageTag())) {
                "El idioma de la interfaz siempre nombra un idioma"
            }
    val currency = regional.currency
    return AppFormats(
        money =
            MoneyFormatter(
                formatLocale = formatLocale,
                currencyCode = currency.code,
                currencyDecimals = currency.decimals,
                currencySymbol = currency.symbol,
                uiLocale = uiLocale,
            ),
        quantity = QuantityFormatter(formatLocale),
        percent = PercentFormatter(formatLocale),
        date = DateFormatter(formatLocale, uiLocale),
    )
}

/**
 * Da a [content] los formatos del negocio y el modo discreto de [display].
 *
 * Sin [display], porque la base no abrió y la app va al rescate, no da nada: ninguna pantalla con
 * cifras se dibuja sin la base, y dibujarlas con los formatos del teléfono sería justo lo que la
 * app nunca hace.
 */
@Composable
fun ProvideBusinessFormats(display: BusinessDisplay?, content: @Composable () -> Unit) {
    if (display == null) {
        content()
        return
    }
    // El idioma elegido para la app llega en la configuración de la actividad, no en el del proceso.
    val uiLocale = LocalConfiguration.current.locales[0]
    val formats =
        remember(display.regional, uiLocale) { businessFormatsOf(display.regional, uiLocale) }
    CompositionLocalProvider(
        LocalAppFormats provides formats,
        LocalDiscreetMode provides display.discreet,
        content = content,
    )
}
