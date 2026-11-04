package io.github.yesidrobayo.shopledger.format

import android.icu.util.ULocale
import java.util.Locale

/**
 * El formato regional del negocio: la etiqueta BCP 47 que decide separadores, agrupación de cifras,
 * orden de las fechas y reloj de 12 o 24 horas.
 *
 * No es el idioma de la interfaz. Con la app en inglés y el formato de una región con coma
 * decimal, los montos siguen con coma: el idioma elige las palabras y este formato elige los
 * números. Por eso llega siempre por parámetro, desde los ajustes del negocio, y nunca sale del
 * idioma por defecto del proceso, que cambia con el idioma elegido para la app.
 *
 * Dos partes de la región no se respetan, a propósito. Las cifras son siempre latinas, porque la
 * fuente de la app y sus cifras tabulares solo cubren esas. Y el calendario es siempre el
 * gregoriano, porque los reportes agrupan por mes y semana gregorianos: un mes persa o lunar no
 * coincide con el período que se está mostrando.
 */
class FormatLocale private constructor(
    /** La etiqueta normalizada, por ejemplo `es-AR`. Es la que se compara y se guarda. */
    val tag: String,
    /** Locale de ICU para números, con cifras latinas. */
    internal val numberLocale: ULocale,
    /** Región del formato, o vacío si la etiqueta no trae una. */
    internal val region: String,
) {
    /**
     * Locale de ICU para fechas: el idioma de la interfaz con la región de este formato.
     *
     * Las palabras de una fecha, como el nombre del mes, salen del idioma de la interfaz; la región
     * aporta lo que ICU sabe de ella para ese idioma. El reloj de 12 o 24 horas no se deja en manos
     * de esa combinación: lo decide [DateFormatter] con este mismo formato.
     */
    internal fun dateLocale(uiLocale: Locale): ULocale {
        val builder = ULocale.Builder().setLanguage(uiLocale.language)
        if (region.isNotEmpty()) builder.setRegion(region)
        return builder
            .setUnicodeLocaleKeyword(NUMBERING_KEY, LATIN_DIGITS)
            .setUnicodeLocaleKeyword(CALENDAR_KEY, GREGORIAN)
            .build()
    }

    override fun equals(other: Any?): Boolean = other is FormatLocale && other.tag == tag

    override fun hashCode(): Int = tag.hashCode()

    override fun toString(): String = tag

    companion object {
        private const val NUMBERING_KEY = "nu"
        private const val NUMBERING_KEYWORD = "numbers"
        private const val LATIN_DIGITS = "latn"
        private const val CALENDAR_KEY = "ca"
        private const val GREGORIAN = "gregory"

        /**
         * El formato de la etiqueta [tag], o `null` si no nombra un idioma.
         *
         * Sin idioma, ICU caería en su formato raíz, que no es el de ninguna región: con monedas de
         * tres decimales, `1.234` podría leerse como mil doscientos treinta y cuatro. Quien llama
         * decide qué hacer, por ejemplo pedir a la persona que elija el formato otra vez. La
         * etiqueta puede venir de una copia restaurada, así que no se confía en ella.
         */
        fun of(tag: String): FormatLocale? {
            val parsed = ULocale.forLanguageTag(tag)
            if (parsed.language.isEmpty()) return null
            // La etiqueta ya pasó por el analizador de ICU, así que fijar las cifras no puede fallar.
            val numberLocale = parsed.setKeywordValue(NUMBERING_KEYWORD, LATIN_DIGITS)
            return FormatLocale(parsed.toLanguageTag(), numberLocale, parsed.country)
        }
    }
}
