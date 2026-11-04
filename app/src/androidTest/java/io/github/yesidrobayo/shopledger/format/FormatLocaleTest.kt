package io.github.yesidrobayo.shopledger.format

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Qué etiquetas acepta el formato regional, que puede venir de una copia restaurada. */
@RunWith(AndroidJUnit4::class)
class FormatLocaleTest {
    @Test
    fun aTagIsNormalizedSoTheSameRegionCompares() {
        val lower = requireNotNull(FormatLocale.of("es-ar"))
        val upper = requireNotNull(FormatLocale.of("es-AR"))
        assertEquals("es-AR", lower.tag)
        assertEquals(upper, lower)
        assertEquals(upper.hashCode(), lower.hashCode())
        assertEquals("es-AR", lower.toString())
    }

    @Test
    fun differentRegionsAreDifferentFormats() {
        assertNotEquals(FormatLocale.of("es-AR"), FormatLocale.of("es-MX"))
        assertNotEquals(FormatLocale.of("es-AR"), "es-AR")
    }

    @Test
    fun aTagWithoutLanguageIsRejected() {
        assertNull(FormatLocale.of(""))
        assertNull(FormatLocale.of("und"))
        assertNull(FormatLocale.of("und-AR"))
        assertNull(FormatLocale.of("12345"))
    }

    @Test
    fun aTagWithUnderscoresIsNotBcp47AndIsRejected() {
        assertNull(FormatLocale.of("es_AR"))
    }

    @Test
    fun aLanguageWithoutRegionIsAccepted() {
        assertEquals("en", requireNotNull(FormatLocale.of("en")).tag)
    }

    @Test
    fun numbersAlwaysUseLatinDigitsEvenIfTheTagAsksForOthers() {
        val formatLocale = requireNotNull(FormatLocale.of("ar-EG-u-nu-arab"))
        assertEquals("latn", formatLocale.numberLocale.getUnicodeLocaleType("nu"))
    }

    @Test
    fun datesTakeTheInterfaceLanguageAndTheRegionOfTheFormat() {
        val formatLocale = requireNotNull(FormatLocale.of("es-AR"))
        val dateLocale = formatLocale.dateLocale(Locale.forLanguageTag("en-US"))
        assertEquals("en", dateLocale.language)
        assertEquals("AR", dateLocale.country)
        assertEquals("latn", dateLocale.getUnicodeLocaleType("nu"))
        assertEquals("gregory", dateLocale.getUnicodeLocaleType("ca"))
    }

    @Test
    fun datesWithoutRegionTakeOnlyTheInterfaceLanguage() {
        val formatLocale = requireNotNull(FormatLocale.of("en"))
        val dateLocale = formatLocale.dateLocale(Locale.forLanguageTag("pt-BR"))
        assertEquals("pt", dateLocale.language)
        assertEquals("", dateLocale.country)
    }
}
