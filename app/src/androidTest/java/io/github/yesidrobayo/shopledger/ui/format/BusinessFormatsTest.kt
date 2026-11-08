package io.github.yesidrobayo.shopledger.ui.format

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import java.util.Locale
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los formatos que reciben las pantallas salen de los ajustes del negocio: los números del formato
 * regional y las palabras del idioma de la interfaz, nunca del teléfono. Las regiones son datos de
 * prueba, y la moneda es la de pruebas de ISO 4217.
 */
@RunWith(AndroidJUnit4::class)
class BusinessFormatsTest {
    @Test
    fun theNumbersFollowTheBusinessFormatAndNotTheInterfaceLanguage() {
        val formats = businessFormatsOf(regional("es-AR", decimals = 2), Locale.ENGLISH)

        assertEquals("1.234,56", formats.money.format(Money(123_456)))
        assertEquals("es-AR", formats.money.formatLocale.tag)
        assertEquals(Locale.ENGLISH, formats.uiLocale)
    }

    @Test
    fun theDecimalsAreTheOnesOfTheBusiness() {
        val formats = businessFormatsOf(regional("en-US", decimals = 0), Locale.ENGLISH)

        assertEquals("123,456", formats.money.format(Money(123_456)))
    }

    @Test
    fun aFormatWithoutLanguageFallsBackToTheInterfaceLanguage() {
        val formats = businessFormatsOf(regional("und", decimals = 2), Locale.forLanguageTag("pt"))

        assertEquals("pt", formats.money.formatLocale.tag)
        assertEquals("1.234,56", formats.money.format(Money(123_456)))
    }

    private fun regional(formatTag: String, decimals: Int): RegionalSettings = RegionalSettings(
        currency =
            BusinessCurrency(
                code = requireNotNull(CurrencyCode.of("XTS")),
                decimals = decimals,
                symbol = null,
                lockedAt = null,
            ),
        cashRounding = Money.ZERO,
        formatLocaleTag = formatTag,
        businessTimeZoneId = "UTC",
        weekStartDay = DayOfWeek.MONDAY,
        csvSeparator = CsvSeparator.SEMICOLON,
        referenceCurrency = null,
    )
}
