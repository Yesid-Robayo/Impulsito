package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La región con que nacen los ajustes: la etiqueta sale limpia y corta, la semana y el separador de
 * columnas siguen a la región, y una zona que no cabe en la base no la rompe.
 *
 * Corre en el dispositivo porque usa el ICU de Android.
 */
@RunWith(AndroidJUnit4::class)
internal class InitialRegionTest {
    @Test
    fun theWeekAndTheCsvColumnsFollowTheRegion() {
        // Una región con coma decimal y semana desde el lunes, y otra con punto y desde el domingo.
        assertEquals(
            InitialRegion("de-DE", "Etc/GMT-1", 1, CsvSeparator.SEMICOLON),
            InitialRegion.of(Locale.forLanguageTag("de-DE"), "Etc/GMT-1"),
        )
        assertEquals(
            InitialRegion("en-US", "Etc/GMT+5", 7, CsvSeparator.COMMA),
            InitialRegion.of(Locale.forLanguageTag("en-US"), "Etc/GMT+5"),
        )
    }

    @Test
    fun theFormatKeepsLanguageScriptAndRegionOnly() {
        // Las extensiones y variantes no son del formato, y podrían pasar de 35 caracteres.
        val long = Locale.forLanguageTag("de-DE-u-ca-buddhist-nu-thai-x-mucho-texto-privado")
        assertEquals("de-DE", InitialRegion.of(long, "UTC").formatLocale)
        assertEquals(
            "zh-Hant-TW",
            InitialRegion.of(Locale.forLanguageTag("zh-Hant-TW"), "UTC").formatLocale,
        )
        assertEquals("fr", InitialRegion.of(Locale.forLanguageTag("fr"), "UTC").formatLocale)
    }

    @Test
    fun withoutALanguageTheFormatIsUndeterminedAndAsksToBeChosen() {
        for (locale in listOf(null, Locale.ROOT)) {
            val region = InitialRegion.of(locale, "UTC")
            assertEquals("und", region.formatLocale)
            // Sin región, el punto decimal: las columnas van con coma.
            assertEquals(CsvSeparator.COMMA, region.csvSeparator)
        }
    }

    @Test
    fun aZoneThatDoesNotFitTheDatabaseFallsBackToUtc() {
        val locale = Locale.forLanguageTag("de-DE")
        assertEquals("UTC", InitialRegion.of(locale, "").businessTimeZone)
        assertEquals("UTC", InitialRegion.of(locale, "Z".repeat(65)).businessTimeZone)
        assertEquals("Z".repeat(64), InitialRegion.of(locale, "Z".repeat(64)).businessTimeZone)
    }

    @Test
    fun theSystemRegionFitsTheDatabase() {
        val system = InitialRegion.ofSystem()

        assertTrue(system.formatLocale.length in 1..35)
        assertTrue(system.businessTimeZone.length in 1..64)
        assertTrue(system.weekStartDay in 1..7)
    }
}
