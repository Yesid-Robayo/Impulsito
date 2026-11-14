package io.github.yesidrobayo.shopledger.ui.region

import android.icu.util.Calendar
import android.icu.util.ULocale
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import java.util.Locale
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que la pantalla regional sabe del teléfono, con el ICU y la base de zonas de cada versión de
 * Android. Los casos con nombre de país son las filas de la matriz regional, y ningún otro.
 */
@RunWith(AndroidJUnit4::class)
class RegionalCatalogDeviceTest {
    private fun catalog(systemLocale: Locale?) = IcuRegionalCatalog(systemLocale = { systemLocale })

    @Test
    fun aPhoneWithoutARegionHasNoCurrencyAndMustChoose() {
        assertNull(catalog(null).deviceCurrency())
        assertNull(catalog(Locale.forLanguageTag("und")).deviceCurrency())
        assertNull(catalog(Locale.forLanguageTag("es")).deviceCurrency())
        // La región «mundo» no tiene una moneda que circule.
        assertNull(catalog(Locale.forLanguageTag("en-001")).deviceCurrency())
    }

    @Test
    fun eachMatrixRegionProposesItsCurrencyAndSeparator() {
        for (row in RegionalMatrix.ROWS) {
            val region = row.region ?: continue
            val catalog = catalog(Locale.forLanguageTag(region.formatLocaleTag))
            // La moneda de la matriz es la que eligió cada negocio, que puede no ser la que circula en
            // su región: se pide solo que la región proponga una moneda confirmada y que esté en la lista.
            // Un formato sin región, como el de un idioma solo, no propone ninguna.
            val proposed = catalog.deviceCurrency()
            if (Locale.forLanguageTag(region.formatLocaleTag).country.isEmpty()) {
                assertNull(region.formatLocaleTag, proposed)
            } else {
                assertTrue(region.formatLocaleTag, proposed?.isConfirmed == true)
                assertTrue(
                    region.formatLocaleTag,
                    catalog.currencies(Locale.ENGLISH).any { it.key == proposed?.code },
                )
            }
            assertEquals(
                region.formatLocaleTag,
                region.csvSeparator,
                catalog.proposedCsvSeparator(region.formatLocaleTag),
            )
        }
    }

    @Test
    fun theProposedWeekIsTheFirstDayOfIcuCountedAsIso() {
        // La semana de la matriz es la que eligió cada negocio, no la que propone la región: aquí
        // se compara con el primer día de ICU para esa región, contado de lunes a domingo.
        val iso =
            mapOf(
                Calendar.MONDAY to DayOfWeek.MONDAY,
                Calendar.TUESDAY to DayOfWeek.TUESDAY,
                Calendar.WEDNESDAY to DayOfWeek.WEDNESDAY,
                Calendar.THURSDAY to DayOfWeek.THURSDAY,
                Calendar.FRIDAY to DayOfWeek.FRIDAY,
                Calendar.SATURDAY to DayOfWeek.SATURDAY,
                Calendar.SUNDAY to DayOfWeek.SUNDAY,
            )
        val catalog = catalog(null)
        for (row in RegionalMatrix.ROWS) {
            val tag = row.region?.formatLocaleTag ?: continue
            val icu = Calendar.getInstance(ULocale.forLanguageTag(tag)).firstDayOfWeek
            assertEquals(tag, iso.getValue(icu), catalog.proposedWeekStart(tag))
        }
    }

    @Test
    fun aFormatWithoutALanguageProposesNothing() {
        val catalog = catalog(null)
        assertNull(catalog.proposedWeekStart("und"))
        assertNull(catalog.proposedCsvSeparator("und"))
        assertNull(catalog.moneyInputSymbols("und", 2))
        assertNull(catalog.isoDecimals(CurrencyCode.UNCONFIRMED))
    }

    @Test
    fun theListsComeFromThePhoneAndOnlyOfferWhatItKnows() {
        val catalog = catalog(null)
        val currencies = catalog.currencies(Locale.ENGLISH)
        val formats = catalog.formats(Locale.ENGLISH)
        val zones = catalog.zones(Locale.ENGLISH)

        assertTrue(currencies.size > MIN_CURRENCIES)
        assertTrue(formats.size > MIN_FORMATS)
        assertTrue(zones.size > MIN_ZONES)
        for (list in listOf(currencies, formats, zones)) {
            assertEquals(list.size, list.map { it.key }.toSet().size)
        }
        assertTrue(currencies.all { CurrencyCode.of(it.key)?.isConfirmed == true })
        // Ni el código de pruebas ni los metales, aunque ICU los liste como monedas.
        for (special in listOf("XTS", "XXX", "XAU", "XAG", "XDR")) {
            assertTrue(special, currencies.none { it.key == special })
        }
        assertTrue(zones.all { BusinessTimeZone.of(it.key) != null })
        assertTrue(zones.any { it.key == "UTC" })
        for (row in RegionalMatrix.ROWS) {
            val zone = row.region?.businessTimeZoneId ?: continue
            assertTrue(zone, zones.any { catalog.sameZone(it.key, zone) })
        }
    }

    private companion object {
        // Mínimos holgados: ICU trae cientos de monedas, regiones y zonas en cualquier versión.
        const val MIN_CURRENCIES = 100
        const val MIN_FORMATS = 100
        const val MIN_ZONES = 200
    }
}
