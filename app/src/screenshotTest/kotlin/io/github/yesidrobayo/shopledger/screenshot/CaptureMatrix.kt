package io.github.yesidrobayo.shopledger.screenshot

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.DefaultColorPalette
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats

// La matriz de las capturas: cada función con @PreviewTest sale en los tres idiomas de la app, y
// en cada uno en claro, en oscuro y con la letra al 200 %. La paleta y la forma de escribir los
// números las elige cada función. Las referencias se generan y se comparan siempre en el mismo
// sistema operativo, para que la fuente no cambie entre una corrida y otra.

private const val NIGHT = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL

/** El ancho de un teléfono común. Los componentes se dibujan a ese ancho, sin el alto fijo. */
private const val PHONE_WIDTH = 360

/** Un teléfono común entero, para las pantallas. */
private const val SCREEN_WIDTH = 411
private const val SCREEN_HEIGHT = 891

/** Los componentes: tres idiomas, en claro, en oscuro y con la letra al 200 %. */
@Preview(name = "es_light", locale = "es", widthDp = PHONE_WIDTH, showBackground = true)
@Preview(
    name = "es_dark",
    locale = "es",
    uiMode = NIGHT,
    widthDp = PHONE_WIDTH,
    showBackground = true,
)
@Preview(
    name = "es_font200",
    locale = "es",
    fontScale = 2f,
    widthDp = PHONE_WIDTH,
    showBackground = true,
)
@Preview(name = "en_light", locale = "en-rUS", widthDp = PHONE_WIDTH, showBackground = true)
@Preview(
    name = "en_dark",
    locale = "en-rUS",
    uiMode = NIGHT,
    widthDp = PHONE_WIDTH,
    showBackground = true,
)
@Preview(
    name = "en_font200",
    locale = "en-rUS",
    fontScale = 2f,
    widthDp = PHONE_WIDTH,
    showBackground = true,
)
@Preview(name = "pt_light", locale = "pt", widthDp = PHONE_WIDTH, showBackground = true)
@Preview(
    name = "pt_dark",
    locale = "pt",
    uiMode = NIGHT,
    widthDp = PHONE_WIDTH,
    showBackground = true,
)
@Preview(
    name = "pt_font200",
    locale = "pt",
    fontScale = 2f,
    widthDp = PHONE_WIDTH,
    showBackground = true,
)
annotation class ComponentMatrix

/** Las pantallas, con el tamaño de un teléfono: las mismas nueve combinaciones. */
@Preview(name = "es_light", locale = "es", widthDp = SCREEN_WIDTH, heightDp = SCREEN_HEIGHT)
@Preview(
    name = "es_dark",
    locale = "es",
    uiMode = NIGHT,
    widthDp = SCREEN_WIDTH,
    heightDp = SCREEN_HEIGHT,
)
@Preview(
    name = "es_font200",
    locale = "es",
    fontScale = 2f,
    widthDp = SCREEN_WIDTH,
    heightDp = SCREEN_HEIGHT,
)
@Preview(name = "en_light", locale = "en-rUS", widthDp = SCREEN_WIDTH, heightDp = SCREEN_HEIGHT)
@Preview(
    name = "en_dark",
    locale = "en-rUS",
    uiMode = NIGHT,
    widthDp = SCREEN_WIDTH,
    heightDp = SCREEN_HEIGHT,
)
@Preview(
    name = "en_font200",
    locale = "en-rUS",
    fontScale = 2f,
    widthDp = SCREEN_WIDTH,
    heightDp = SCREEN_HEIGHT,
)
@Preview(name = "pt_light", locale = "pt", widthDp = SCREEN_WIDTH, heightDp = SCREEN_HEIGHT)
@Preview(
    name = "pt_dark",
    locale = "pt",
    uiMode = NIGHT,
    widthDp = SCREEN_WIDTH,
    heightDp = SCREEN_HEIGHT,
)
@Preview(
    name = "pt_font200",
    locale = "pt",
    fontScale = 2f,
    widthDp = SCREEN_WIDTH,
    heightDp = SCREEN_HEIGHT,
)
annotation class ScreenMatrix

/**
 * Cómo se escriben los números en una captura: dos filas de la matriz de configuraciones
 * regionales, una sin decimales y otra con tres, con el código de pruebas de ISO 4217 y el signo
 * genérico, que no son la moneda de nadie.
 */
enum class CaptureFormat(val formatTag: String, val decimals: Int) {
    /** Sin decimales, con los números de la primera fila de la matriz. */
    NO_DECIMALS("ja-JP", 0),

    /** Tres decimales, con los números de la fila que los prueba. */
    THREE_DECIMALS("en-KW", 3),
}

/**
 * El tema de una captura: la paleta pedida, en el modo y el idioma de la vista previa, con los
 * números de [format]. Las fechas y los nombres de las monedas salen en el idioma de la vista
 * previa, como en la app.
 */
@Composable
fun CaptureTheme(
    palette: ColorPalette = DefaultColorPalette,
    format: CaptureFormat = CaptureFormat.NO_DECIMALS,
    content: @Composable () -> Unit,
) {
    val uiLocale = LocalConfiguration.current.locales[0]
    PreviewTheme(
        palette = palette,
        formats = previewFormats(format.formatTag, uiLocale, format.decimals),
        content = content,
    )
}
