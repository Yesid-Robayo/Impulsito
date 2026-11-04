package io.github.yesidrobayo.shopledger.data.local

import android.content.res.Resources
import android.icu.text.DecimalFormatSymbols
import android.icu.util.Calendar
import android.icu.util.ULocale
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.TimeZone

/**
 * El formato regional, la zona y la semana con que nacen los ajustes de un negocio nuevo. Son solo
 * el punto de partida: la persona los confirma o los cambia en el primer uso.
 *
 * - [formatLocale] es la etiqueta BCP 47 de la región del sistema, con idioma, escritura y región,
 *   sin extensiones: así nunca pasa de los 35 caracteres que admite la base. Sin idioma conocido,
 *   es `und`, que obliga a elegir el formato.
 * - [businessTimeZone] es el identificador IANA de la zona del teléfono, o `UTC` si no cabe en la
 *   base.
 * - [weekStartDay] es el primer día de la semana de esa región, de 1, lunes, a 7, domingo, como en
 *   ISO 8601.
 * - [csvSeparator] separa las columnas sin chocar con el separador decimal de esa región.
 */
internal data class InitialRegion(
    val formatLocale: String,
    val businessTimeZone: String,
    val weekStartDay: Int,
    val csvSeparator: CsvSeparator,
) {
    companion object {
        /** Sin idioma conocido: BCP 47 llama así a un idioma sin determinar. */
        private const val UNDETERMINED = "und"
        private const val FALLBACK_ZONE = "UTC"
        private const val MAX_ZONE_LENGTH = 64
        private const val NUMBERS_KEYWORD = "numbers"
        private const val LATIN_DIGITS = "latn"
        private const val ISO_SUNDAY = 7

        /**
         * Lo que dice el sistema del teléfono.
         *
         * La región sale de la configuración del sistema y nunca del idioma por defecto del
         * proceso, que cambia con el idioma elegido para la app: con la app en otro idioma, los
         * montos siguen el formato de la región de la persona. La zona es la del teléfono al crear
         * la base; después, la del negocio no cambia al viajar.
         */
        fun ofSystem(): InitialRegion =
            of(Resources.getSystem().configuration.locales.get(0), TimeZone.getDefault().id)

        /**
         * Lo que corresponde a la región [systemLocale], o a ninguna si es nula, y a la zona
         * [zoneId]. La semana se lee de [systemLocale] entero, con sus extensiones, porque ahí
         * guarda el sistema el primer día que eligió la persona, si eligió uno.
         */
        fun of(systemLocale: Locale?, zoneId: String): InitialRegion {
            val tag = systemLocale?.let(::plainTag) ?: UNDETERMINED
            val numberLocale = ULocale.forLanguageTag(
                tag,
            ).setKeywordValue(NUMBERS_KEYWORD, LATIN_DIGITS)
            val decimal =
                if (DecimalFormatSymbols.getInstance(numberLocale).decimalSeparator == ',') {
                    DecimalSeparator.COMMA
                } else {
                    DecimalSeparator.POINT
                }
            val weekLocale = systemLocale?.let { ULocale.forLocale(it) } ?: ULocale.ROOT
            return InitialRegion(
                formatLocale = tag,
                businessTimeZone =
                    zoneId.takeIf { it.isNotEmpty() && it.length <= MAX_ZONE_LENGTH }
                        ?: FALLBACK_ZONE,
                weekStartDay = isoDay(Calendar.getInstance(weekLocale).firstDayOfWeek),
                csvSeparator = CsvSeparator.defaultFor(decimal),
            )
        }

        /** Idioma, escritura y región de [locale], sin extensiones ni variantes. */
        private fun plainTag(locale: Locale): String = try {
            Locale
                .Builder()
                .setLanguage(locale.language)
                .setScript(locale.script)
                .setRegion(locale.country)
                .build()
                .toLanguageTag()
        } catch (_: IllformedLocaleException) {
            UNDETERMINED
        }

        /** El día de ICU, que empieza en domingo con 1, como lo cuenta ISO 8601. */
        private fun isoDay(icuDay: Int): Int = if (icuDay ==
            Calendar.SUNDAY
        ) {
            ISO_SUNDAY
        } else {
            icuDay - 1
        }
    }
}
