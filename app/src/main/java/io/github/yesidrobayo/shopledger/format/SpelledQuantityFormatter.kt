package io.github.yesidrobayo.shopledger.format

import android.content.res.Resources
import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import android.icu.text.PluralFormat
import android.icu.util.ULocale
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.localization.SeedText
import java.math.BigDecimal

/**
 * Escribe una cantidad con el nombre de su unidad en plural, como la lee TalkBack: `0,250
 * kilogramos`, `1 caja`, `3 cajas`. Solo sirve para las unidades iniciales, las únicas con plural
 * conocido en cada idioma; con el símbolo, una cantidad no lleva plural (`1,500 kg`).
 *
 * El plural depende de los decimales **visibles**, no del valor: en inglés `1` es singular y
 * `1,000` es plural. Por eso el número se escribe con el mismo formato que [QuantityFormatter], tres
 * decimales en una unidad con fracciones y un entero en una de conteo, y la categoría la elige
 * [PluralFormat] sobre ese mismo número. `MessageFormat` no sirve: escribe su `#` con el formato
 * del idioma y sin decimales fijos, así que perdería la escala y los separadores de la región.
 *
 * Las palabras y la regla del plural salen del idioma de [Resources], que da quien llama, y el
 * número sale de [formatLocale], el formato regional del negocio. La regla es la de ICU del
 * teléfono, y se acepta que cambie con la versión: en portugués, Android 8 escribe `1,500
 * quilogramas` y Android 10 o posterior `1,500 quilograma`, y un millón exacto de unidades, que
 * hasta Android 12L es `1.000.000 unidades`, desde Android 13 es `1.000.000 de unidades` en español
 * y portugués. Las dos formas se entienden, y la cantidad con símbolo, la que se ve en pantalla, no
 * lleva plural.
 *
 * Es seguro usarlo desde varios hilos: cada llamada trabaja sobre su propia copia del formato.
 */
class SpelledQuantityFormatter(val formatLocale: FormatLocale) {
    private val wholeFormat: DecimalFormat = decimalFormat(formatLocale, fractionDigits = 0)
    private val fractionFormat: DecimalFormat = decimalFormat(formatLocale, fractionDigits = 3)

    /**
     * La cantidad con el plural de la unidad inicial [systemKey], o `null` si la unidad no tiene
     * plural conocido: una que creó la persona, una inicial que la persona renombró con [name], o
     * una clave que esta versión no conoce. Sin plural, la cantidad lleva el nombre de la unidad
     * sin declinar: `3 × caja`.
     *
     * Con [allowsFraction] en falso y una cantidad que no es entera, muestra igual sus tres
     * decimales, como [QuantityFormatter]: redondear escondería un dato.
     */
    fun format(
        resources: Resources,
        systemKey: String?,
        name: String?,
        quantity: QuantityMilli,
        allowsFraction: Boolean,
    ): String? {
        if (name != null || systemKey == null) return null
        val res = SeedText.unitQuantityRes(systemKey) ?: return null
        val uiLocale = ULocale.forLocale(resources.configuration.locales[0])
        val plural = PluralFormat(uiLocale, pluralBody(resources.getString(res)))
        val whole = !allowsFraction && quantity.isWholeUnits
        val number = (if (whole) wholeFormat else fractionFormat).clone() as DecimalFormat
        plural.setNumberFormat(number)
        val value =
            if (whole) {
                BigDecimal.valueOf(quantity.milli / Scale.MILLI)
            } else {
                BigDecimal.valueOf(quantity.milli, MILLI_DIGITS)
            }
        return IcuTextNormalizer.normalizeText(plural.format(value))
    }

    internal companion object {
        /** Decimales de una cantidad en milésimas. */
        private const val MILLI_DIGITS = 3

        /** El comienzo de cada patrón plural de los recursos, con su único argumento. */
        private const val PATTERN_START = "{qty, plural,"

        private const val PATTERN_END = "}"

        /**
         * Las opciones del patrón `{qty, plural, one {…} other {…}}` de un recurso, que es lo que
         * recibe [PluralFormat]. El recurso guarda el patrón entero de ICU, que reconoce quien
         * traduce, y aquí solo se le quita el envoltorio del argumento.
         *
         * Lanza [IllegalArgumentException] si el recurso no tiene esa forma: es un error de una
         * traducción, que la prueba de cada idioma encuentra antes de publicar.
         */
        fun pluralBody(pattern: String): String {
            require(pattern.startsWith(PATTERN_START) && pattern.endsWith(PATTERN_END)) {
                "El recurso no es un patrón plural de una cantidad"
            }
            return pattern.substring(PATTERN_START.length, pattern.length - PATTERN_END.length)
        }

        /**
         * El formato con [fractionDigits] decimales exactos de [formatLocale], con el signo menos
         * tipográfico: en una frase no se puede cambiar el guion después, porque las palabras
         * también podrían llevarlo.
         */
        private fun decimalFormat(formatLocale: FormatLocale, fractionDigits: Int): DecimalFormat =
            fixedDecimalFormat(formatLocale.numberLocale, NumberFormat.NUMBERSTYLE, fractionDigits)
                .apply {
                    decimalFormatSymbols =
                        decimalFormatSymbols.apply { minusSign = IcuTextNormalizer.MINUS_SIGN }
                }
    }
}
