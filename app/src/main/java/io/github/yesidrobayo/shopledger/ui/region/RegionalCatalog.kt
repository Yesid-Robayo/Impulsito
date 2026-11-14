package io.github.yesidrobayo.shopledger.ui.region

import android.content.res.Resources
import android.icu.text.Collator
import android.icu.text.TimeZoneFormat
import android.icu.text.TimeZoneNames
import android.icu.util.Calendar
import android.icu.util.Currency
import android.icu.util.TimeZone
import android.icu.util.ULocale
import android.os.Build
import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.format.CsvFormatFactory
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import java.util.Date
import java.util.Locale
import kotlinx.datetime.DayOfWeek

/** Una opción de una lista de la configuración regional, con su clave de búsqueda ya plegada. */
@Immutable
data class RegionalOption(
    /** Lo que se guarda: un código de moneda, una etiqueta de formato o una zona. */
    val key: String,
    /** El nombre en el idioma de la interfaz. */
    val title: String,
    /** El código, la etiqueta o la zona y su desfase, que ayudan a distinguir dos parecidas. */
    val detail: String,
    /** [title] y [detail] sin mayúsculas ni tildes, para buscar como escribe la persona. */
    val searchKey: String,
)

/**
 * Lo que la pantalla regional sabe del teléfono: las monedas, los formatos y las zonas que conoce,
 * y lo que propone a partir de su región.
 *
 * Todo sale de ICU y de la base de zonas que vienen en el teléfono: nada se descarga, y ninguna
 * lista está escrita en el código, salvo los códigos de moneda que la norma reserva para pruebas,
 * metales y unidades de cuenta, que no se ofrecen (`OfferedCurrencies`). Las listas solo ofrecen;
 * lo guardado se respeta aunque no esté en ellas, porque una copia de otro teléfono puede traer
 * una moneda o una zona que este no lista.
 */
interface RegionalCatalog {
    /** Las monedas en uso hoy, por nombre en [uiLocale], sin los códigos especiales de ISO. */
    fun currencies(uiLocale: Locale): List<RegionalOption>

    /** Los formatos regionales, por nombre en [uiLocale]. */
    fun formats(uiLocale: Locale): List<RegionalOption>

    /** Las zonas horarias que este teléfono conoce, por ciudad en [uiLocale]. */
    fun zones(uiLocale: Locale): List<RegionalOption>

    /**
     * Si la opción de zona [optionKey] es la misma zona que [zoneId]. Una zona tiene a veces dos
     * nombres, uno antiguo y uno nuevo, y la lista usa uno solo: lo guardado puede traer el otro.
     */
    fun sameZone(optionKey: String, zoneId: String): Boolean

    /** Los decimales que da la norma a [code], o `null` si este teléfono no la conoce. */
    fun isoDecimals(code: CurrencyCode): Int?

    /**
     * La moneda de la región del teléfono, o `null` si su región no tiene una: entonces la moneda
     * queda sin confirmar y la persona la elige.
     */
    fun deviceCurrency(): CurrencyCode?

    /** El primer día de la semana de [formatTag], o `null` si no es un formato usable. */
    fun proposedWeekStart(formatTag: String): DayOfWeek?

    /** El separador de CSV que no choca con el decimal de [formatTag], o `null` si no es usable. */
    fun proposedCsvSeparator(formatTag: String): CsvSeparator?

    /**
     * Los símbolos con que se escribe un monto en [formatTag] con [decimals] decimales, para leer el
     * redondeo de efectivo que escribe la persona. `null` si el formato no es usable.
     */
    fun moneyInputSymbols(formatTag: String, decimals: Int): NumberSymbols?
}

/** El catálogo de ICU y de la base de zonas del teléfono. */
class IcuRegionalCatalog(
    /** El instante de hoy, para dejar fuera las monedas que ya no circulan. */
    private val now: () -> Date = ::Date,
    /** La región del sistema: la del teléfono, nunca la del idioma elegido para la app. */
    private val systemLocale: () -> Locale? = {
        Resources.getSystem().configuration.locales.get(0)
    },
) : RegionalCatalog {
    override fun currencies(uiLocale: Locale): List<RegionalOption> {
        val today = now()
        return Currency
            .getAvailableCurrencies()
            .filter { Currency.isAvailable(it.currencyCode, today, null) }
            // Solo códigos de tres letras que la base acepta, y nunca el de sin moneda.
            .filter { CurrencyCode.of(it.currencyCode)?.isConfirmed == true }
            // Ni el de pruebas, ni metales, ni unidades de cuenta: no son dinero de un negocio.
            .filter { OfferedCurrencies.isOffered(it.currencyCode) }
            .map {
                option(it.currencyCode, it.getDisplayName(uiLocale), it.currencyCode)
            }.sortedFor(uiLocale)
    }

    override fun formats(uiLocale: Locale): List<RegionalOption> {
        val ui = ULocale.forLocale(uiLocale)
        return ULocale
            .getAvailableLocales()
            .asSequence()
            .filter { it.language.isNotEmpty() && it.variant.isEmpty() }
            .map { it.toLanguageTag() }
            .distinct()
            .filter { FormatLocale.of(it) != null }
            .map { tag -> option(tag, ULocale.forLanguageTag(tag).getDisplayName(ui), tag) }
            .toList()
            .sortedFor(uiLocale)
    }

    override fun zones(uiLocale: Locale): List<RegionalOption> {
        val ui = ULocale.forLocale(uiLocale)
        val names = TimeZoneNames.getInstance(ui)
        val offsets = TimeZoneFormat.getInstance(ui)
        val instant = now().time
        val ids = locationZoneIds() + UNIVERSAL_ZONE
        return ids
            .filter { BusinessTimeZone.of(it) != null }
            .map { id ->
                val offset = TimeZone.getTimeZone(id).getOffset(instant)
                val city = names.getExemplarLocationName(id)?.takeIf { it.isNotEmpty() } ?: id
                option(id, city, "$id · ${offsets.formatOffsetLocalizedGMT(offset)}")
            }.sortedFor(uiLocale)
    }

    override fun sameZone(optionKey: String, zoneId: String): Boolean =
        optionKey == zoneId || TimeZone.getCanonicalID(optionKey) == TimeZone.getCanonicalID(zoneId)

    /**
     * Las zonas de ciudad, una por zona: sin los nombres viejos que solo apuntan a otra. Desde
     * Android 9, ICU las da tal cual; antes se toman las que son su propio nombre canónico y tienen
     * una ciudad, sin las zonas técnicas de `Etc/` ni `SystemV/`.
     */
    private fun locationZoneIds(): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, null, null)
        } else {
            TimeZone
                .getAvailableIDs()
                .filter { id ->
                    '/' in id &&
                        !id.startsWith(TECHNICAL_ZONES) &&
                        !id.startsWith(SYSTEM_V_ZONES) &&
                        TimeZone.getCanonicalID(id) == id
                }.toSet()
        }

    override fun isoDecimals(code: CurrencyCode): Int? =
        if (code.isConfirmed && code.code in knownCodes) {
            Currency.getInstance(code.code).defaultFractionDigits
        } else {
            null
        }

    override fun deviceCurrency(): CurrencyCode? {
        val locale = systemLocale() ?: return null
        // Sin región, ICU no sabe qué moneda circula: no se adivina una por el idioma.
        if (locale.country.isEmpty()) return null
        val code = Currency.getInstance(ULocale.forLocale(locale))?.currencyCode ?: return null
        return CurrencyCode.of(code)?.takeIf { it.isConfirmed }
    }

    override fun proposedWeekStart(formatTag: String): DayOfWeek? {
        if (FormatLocale.of(formatTag) == null) return null
        val first = Calendar.getInstance(ULocale.forLanguageTag(formatTag)).firstDayOfWeek
        // ICU cuenta del domingo, 1, al sábado, 7; ISO, del lunes, 1, al domingo, 7.
        return DayOfWeek.entries[(first + ISO_SHIFT) % DAYS_IN_WEEK]
    }

    override fun proposedCsvSeparator(formatTag: String): CsvSeparator? =
        FormatLocale.of(formatTag)?.let {
            CsvSeparator.defaultFor(CsvFormatFactory.decimalSeparator(it))
        }

    override fun moneyInputSymbols(formatTag: String, decimals: Int): NumberSymbols? =
        FormatLocale.of(formatTag)?.let {
            MoneyFormatter(it, CurrencyCode.UNCONFIRMED, decimals, null, Locale.ROOT).inputSymbols
        }

    private val knownCodes: Set<String> by lazy {
        Currency.getAvailableCurrencies().mapTo(HashSet()) { it.currencyCode }
    }

    private fun option(key: String, title: String, detail: String) =
        RegionalOption(key, title, detail, TextNormalizer.normalize("$title $detail"))

    private fun List<RegionalOption>.sortedFor(uiLocale: Locale): List<RegionalOption> {
        val collator = Collator.getInstance(ULocale.forLocale(uiLocale))
        return sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    private companion object {
        /** La hora universal, para un negocio que prefiere no atarse a una ciudad. */
        const val UNIVERSAL_ZONE = "UTC"
        const val TECHNICAL_ZONES = "Etc/"
        const val SYSTEM_V_ZONES = "SystemV/"
        const val DAYS_IN_WEEK = 7

        // Del domingo de ICU, 1, al índice de DayOfWeek, 6: (1 + 5) % 7.
        const val ISO_SHIFT = 5
    }
}
