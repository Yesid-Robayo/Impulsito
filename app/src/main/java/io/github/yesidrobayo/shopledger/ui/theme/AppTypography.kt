package io.github.yesidrobayo.shopledger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.yesidrobayo.shopledger.R

// La instancia por defecto del archivo variable es el peso 300: sin variationSettings, todo se vería
// flaco y gris aunque se pida 600.
private fun figtree(weight: FontWeight) = Font(
    resId = R.font.figtree_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Figtree, empaquetada en el APK, en los tres pesos que usa la app. */
val AppFontFamily: FontFamily =
    FontFamily(
        figtree(FontWeight.Normal),
        figtree(FontWeight.Medium),
        figtree(FontWeight.SemiBold),
    )

/**
 * Los símbolos de moneda que Figtree no trae, solo en los dos pesos de los montos. Se dibujan en su
 * propia corrida de texto, fuera de las cifras tabulares: así funciona igual en todas las versiones
 * de Android, y un símbolo más ancho nunca desalinea los dígitos.
 */
val CurrencySymbolFamily: FontFamily =
    FontFamily(
        Font(R.font.currency_symbols_500, FontWeight.Medium),
        Font(R.font.currency_symbols_600, FontWeight.SemiBold),
    )

/** Activa las cifras tabulares de Figtree: todas miden lo mismo, y una columna de montos alinea. */
const val TABULAR_FIGURES = "tnum"

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: TextUnit,
    features: String? = null,
) = TextStyle(
    fontFamily = AppFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking,
    fontFeatureSettings = features,
)

/**
 * Los quince roles de Material 3, todos con Figtree: un rol sin definir caería en la fuente del
 * sistema y rompería la identidad. Títulos en tipo oración, nunca en mayúsculas sostenidas.
 */
val AppTypography: Typography =
    Typography(
        displayLarge = style(48, 56, FontWeight.SemiBold, (-0.5).sp),
        displayMedium = style(40, 48, FontWeight.SemiBold, (-0.4).sp),
        displaySmall = style(34, 42, FontWeight.SemiBold, (-0.25).sp),
        headlineLarge = style(30, 38, FontWeight.SemiBold, (-0.2).sp),
        headlineMedium = style(26, 34, FontWeight.SemiBold, (-0.1).sp),
        headlineSmall = style(22, 30, FontWeight.SemiBold, 0.sp),
        titleLarge = style(20, 28, FontWeight.SemiBold, 0.sp),
        titleMedium = style(17, 24, FontWeight.SemiBold, 0.sp),
        titleSmall = style(15, 22, FontWeight.SemiBold, 0.sp),
        bodyLarge = style(16, 24, FontWeight.Normal, 0.sp),
        bodyMedium = style(14, 20, FontWeight.Normal, 0.sp),
        bodySmall = style(13, 18, FontWeight.Normal, 0.1.sp),
        labelLarge = style(15, 20, FontWeight.SemiBold, 0.1.sp),
        labelMedium = style(13, 18, FontWeight.SemiBold, 0.2.sp),
        labelSmall = style(12, 16, FontWeight.SemiBold, 0.3.sp),
    )

/**
 * Los estilos de todo monto y toda cantidad, con cifras tabulares. Una cifra con otro estilo es un
 * error: dejaría de alinear con las de arriba y abajo.
 *
 * Las cifras tabulares cambian un poco de ancho con el peso, así que en una columna todas usan el
 * mismo estilo.
 */
@Immutable
class AppAmounts internal constructor() {
    /** La cifra del héroe. */
    val hero: TextStyle = style(40, 48, FontWeight.SemiBold, (-0.5).sp, TABULAR_FIGURES)

    /** El stock de un producto o el total de un período. */
    val large: TextStyle = style(28, 36, FontWeight.SemiBold, (-0.25).sp, TABULAR_FIGURES)

    /** El total de la venta. */
    val title: TextStyle = style(24, 32, FontWeight.SemiBold, 0.sp, TABULAR_FIGURES)

    /** El valor de una tarjeta de cifra, o la ganancia dentro del héroe. */
    val tile: TextStyle = style(20, 28, FontWeight.SemiBold, 0.sp, TABULAR_FIGURES)

    /** El monto principal de una fila. */
    val row: TextStyle = style(16, 24, FontWeight.SemiBold, 0.sp, TABULAR_FIGURES)

    /** La segunda línea numérica de una fila. */
    val small: TextStyle = style(14, 20, FontWeight.Medium, 0.sp, TABULAR_FIGURES)

    /** Ejes de gráficas y variaciones. */
    val caption: TextStyle = style(12, 16, FontWeight.Medium, 0.1.sp, TABULAR_FIGURES)

    /** Los siete estilos con su nombre, del más grande al más chico. */
    val all: List<Pair<String, TextStyle>>
        get() =
            listOf(
                "hero" to hero,
                "large" to large,
                "title" to title,
                "tile" to tile,
                "row" to row,
                "small" to small,
                "caption" to caption,
            )
}
