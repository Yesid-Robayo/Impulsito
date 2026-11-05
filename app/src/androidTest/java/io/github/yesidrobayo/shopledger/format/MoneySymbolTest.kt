package io.github.yesidrobayo.shopledger.format

import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import android.icu.util.Currency
import android.icu.util.ULocale
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.ui.currency.LEFT_TO_RIGHT_ISOLATE
import io.github.yesidrobayo.shopledger.ui.currency.POP_DIRECTIONAL_ISOLATE
import io.github.yesidrobayo.shopledger.ui.currency.ResolvedSymbol
import io.github.yesidrobayo.shopledger.ui.currency.SymbolFont
import io.github.yesidrobayo.shopledger.ui.currency.SymbolRun
import io.github.yesidrobayo.shopledger.ui.currency.moneyText
import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * El símbolo, su lugar, su espacio y el signo de un monto, como los pone ICU con el formato
 * regional, y el nombre de la moneda en el idioma de la interfaz. Las regiones y las monedas son
 * datos de prueba, y las cadenas esperadas salen iguales en API 26 y en API 37.
 */
@RunWith(Parameterized::class)
class MoneySymbolTest(private val device: ForeignDevice) {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(device)

    @Test
    fun theSymbolAndTheSignGoWhereTheRegionPutsThem() {
        assertEquals("\u2212$1,234.50", shown("en-US", "USD", -123_450))
        assertEquals("$1,234.50", shown("en-US", "USD", 123_450))
        assertEquals("\u22121\u00A0234,50\u00A0\u20AC", shown("fr-FR", "EUR", -123_450))
        // El signo entre el símbolo y las cifras, con el espacio del lado del símbolo.
        assertEquals("\u20AC\u00A0\u22121.234,50", shown("nl-NL", "EUR", -123_450))
        // El patrón negativo de la región no lleva el espacio del positivo. Sin grupos, porque esta región
        // agrupa con un apóstrofo distinto según la versión de ICU.
        assertEquals("CHF\u00A0234.50", shown("de-CH", "CHF", 23_450))
        assertEquals("CHF\u2212234.50", shown("de-CH", "CHF", -23_450))
    }

    @Test
    fun theSameCurrencyHasTheSymbolOfEachRegion() {
        assertEquals("\u2212$\u00A01.234,50", shown("es-AR", "ARS", -123_450))
        assertEquals("\u22121\u00A0234,50\u00A0kr", shown("sv-SE", "SEK", -123_450))
        assertEquals("\u221212.345,00\u00A0\u20AC", shown("de-DE", "EUR", -1_234_500))
    }

    @Test
    fun aSymbolWrittenByThePersonGoesWhereTheRegionPutsTheSymbol() {
        assertEquals("\u2212Bs\u00A01,234.50", shown("en-US", "XTS", -123_450, symbol = "Bs"))
        assertEquals("\u22121\u00A0234,50\u00A0Bs", shown("fr-FR", "XTS", -123_450, symbol = "Bs"))
        assertEquals("Bs.\u00A0\u22121.234,50", shown("nl-NL", "XTS", -123_450, symbol = "Bs."))
        // Un signo no se separa de las cifras; unas letras, sí, aunque el patrón no lleve espacio.
        assertEquals("\u2212\u20B51,234.50", shown("en-US", "XTS", -123_450, symbol = "\u20B5"))
        assertEquals("GH\u20B51,234.50", shown("en-US", "XTS", 123_450, symbol = "GH\u20B5"))
        assertEquals("\u20B5\u2212234.50", shown("de-CH", "XTS", -23_450, symbol = "\u20B5"))
        // Un signo que Android 8 no conoce, porque es de Unicode 14, tampoco se separa.
        assertEquals("\u20C01,234.50", shown("en-US", "XTS", 123_450, symbol = "\u20C0"))
    }

    @Test
    fun aSymbolWrittenByThePersonLosesOnlyItsBordersAndInvisibleMarks() {
        val parts = formatter("en-US", "XTS", symbol = " \u200FBs\u00A0").parts(Money(100))
        assertEquals("Bs", parts.symbol)
        val blank = formatter("en-US", "USD", symbol = " \u200F ").parts(Money(100))
        assertEquals("$", blank.symbol)
    }

    @Test
    fun lettersFromIcuAreSeparatedFromTheDigitsTheSameInEveryVersion() {
        // ICU de API 26 escribe XTS1,234.50 y el de API 37, XTS 1,234.50: la regla de CLDR iguala los dos.
        assertEquals("XTS\u00A01,234.50", shown("en-US", "XTS", 123_450))
        assertEquals("\u2212XTS\u00A01,234.50", shown("en-US", "XTS", -123_450))
    }

    @Test
    fun anUnknownCurrencyShowsAndReadsItsCode() {
        val parts = formatter("en-US", "QQQ").parts(Money(-100))
        assertEquals("QQQ", parts.symbol)
        assertEquals("QQQ", parts.currencyName)
        assertEquals("QQQ", parts.currencyCode)
    }

    @Test
    fun theNameOfTheCurrencyIsInTheLanguageOfTheInterfaceWithItsPlural() {
        val spanish = Locale.forLanguageTag("es")
        assertEquals(
            "dólares estadounidenses",
            formatter("en-US", "USD", ui = spanish).parts(Money(-120_000)).currencyName,
        )
        assertEquals(
            "US dollars",
            formatter("es-AR", "USD", ui = Locale.ENGLISH).parts(Money(-120_000)).currencyName,
        )
        // El plural sale de las cifras que se ven: `1.00` es plural en inglés, y `1,00` es singular en español.
        assertEquals(
            "US dollars",
            formatter("en-US", "USD", ui = Locale.ENGLISH).parts(Money(100)).currencyName,
        )
        assertEquals(
            "dólar estadounidense",
            formatter("en-US", "USD", ui = spanish).parts(Money(100)).currencyName,
        )
        assertEquals(
            "US dollar",
            formatter(
                "en-US",
                "USD",
                decimals = 0,
                ui = Locale.ENGLISH,
            ).parts(Money(1)).currencyName,
        )
        val portuguese = formatter(
            "en-US",
            "USD",
            ui = Locale.forLanguageTag("pt"),
        ).parts(Money(200)).currencyName
        assertTrue(portuguese, portuguese.lowercase(Locale.ROOT).contains("americanos"))
    }

    @Test
    fun theSymbolWrittenByThePersonDoesNotChangeTheNameReadAloud() {
        val parts = formatter("en-US", "EUR", symbol = "Bs", ui = Locale.ENGLISH).parts(Money(200))
        assertEquals("Bs", parts.symbol)
        assertEquals("euros", parts.currencyName)
    }

    /**
     * En todas las regiones de ICU del teléfono, el signo y el símbolo quedan en el mismo orden que
     * en ICU. Solo se ignoran los espacios, que la regla de CLDR iguala entre versiones, y las
     * regiones que marcan la moneda con el separador decimal, sin símbolo visible, donde va el código.
     */
    @Test
    fun everyRegionOfThePhoneKeepsTheOrderOfIcu() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (available in ULocale.getAvailableLocales()) {
            val tag = available.toLanguageTag()
            val formatLocale = FormatLocale.of(tag) ?: continue
            for (code in codesFor(available)) {
                val icuNegative = icu(formatLocale, code, -123_450)
                if (icuNegative.none {
                        !it.isDigit() && it !in NUMBER_CHARS &&
                            !Character.isSpaceChar(it)
                    }
                ) {
                    continue
                }
                val formatter =
                    MoneyFormatter(
                        formatLocale,
                        requireNotNull(CurrencyCode.of(code)),
                        2,
                        null,
                        Locale.ENGLISH,
                    )
                for (minor in listOf(123_450L, -123_450L)) {
                    val ours = skeleton(plain(formatter.parts(Money(minor))))
                    val theirs = skeleton(icu(formatLocale, code, minor))
                    if (ours != theirs) failures += "$tag $code: $ours, ICU $theirs"
                    checked++
                }
                val same =
                    MoneyFormatter(
                        formatLocale,
                        requireNotNull(CurrencyCode.of(code)),
                        2,
                        formatter.parts(Money.ZERO).symbol,
                        Locale.ENGLISH,
                    )
                if (same.parts(Money(-1)) !=
                    formatter.parts(Money(-1))
                ) {
                    failures +=
                        "$tag $code: el mismo símbolo escrito cambia el monto"
                }
            }
        }
        assertTrue("Regiones revisadas: $checked", checked > 1_000)
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun theSignIsAlwaysTheTypographicMinus() {
        for (available in ULocale.getAvailableLocales()) {
            val formatLocale = FormatLocale.of(available.toLanguageTag()) ?: continue
            val parts = moneyFormatter(formatLocale, 2).parts(Money(-123_450))
            val text = plain(parts)
            assertTrue(text, '\u2212' in text && '-' !in text)
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun formatter(
        tag: String,
        code: String,
        decimals: Int = 2,
        symbol: String? = null,
        ui: Locale = Locale.ENGLISH,
    ): MoneyFormatter = MoneyFormatter(
        requireNotNull(FormatLocale.of(tag)),
        requireNotNull(CurrencyCode.of(code)),
        decimals,
        symbol,
        ui,
    )

    private fun shown(tag: String, code: String, minor: Long, symbol: String? = null): String =
        plain(formatter(tag, code, symbol = symbol).parts(Money(minor)))

    /** El texto que se dibuja, sin el aislamiento de dirección, con el símbolo tal cual. */
    private fun plain(parts: MoneyParts): String = moneyText(
        parts,
        ResolvedSymbol(listOf(SymbolRun(parts.symbol, SymbolFont.APP)), usesIsoCode = false),
    )
        .text
        .filterNot { it == LEFT_TO_RIGHT_ISOLATE || it == POP_DIRECTIONAL_ISOLATE }

    /** Lo que ICU escribe con la moneda real, normalizado como cualquier cifra en pantalla. */
    private fun icu(formatLocale: FormatLocale, code: String, minor: Long): String {
        val format =
            (
                NumberFormat.getInstance(
                    formatLocale.numberLocale,
                    NumberFormat.CURRENCYSTYLE,
                ) as DecimalFormat
                ).apply {
                currency = Currency.getInstance(code)
                minimumFractionDigits = 2
                maximumFractionDigits = 2
            }
        return IcuTextNormalizer.normalizeNumber(format.format(BigDecimal.valueOf(minor, 2)))
            .filterNot { Character.getType(it) == Character.FORMAT.toInt() }
    }

    /** El orden de las partes: las cifras como `N`, sin espacios. */
    private fun skeleton(text: String): String =
        text.replace(DIGITS, "N").filterNot { Character.isSpaceChar(it) }

    /** Dólar, euro, la moneda de pruebas y la de la región, si la región tiene una. */
    private fun codesFor(locale: ULocale): List<String> {
        val own = if (locale.country.isEmpty()) {
            null
        } else {
            runCatching {
                Currency.getInstance(locale)?.currencyCode
            }.getOrNull()
        }
        return listOfNotNull("USD", "EUR", "XTS", own).distinct()
    }

    companion object {
        /** Las cifras con sus separadores, incluido el signo de moneda que algunas regiones usan como coma. */
        private val DIGITS = Regex("[0-9][0-9.,'\u00A0\u202F $\u066B\u066C\u2019]*[0-9]")
        private const val NUMBER_CHARS = "0123456789.,'$\u2212\u066B\u066C\u2019"

        @JvmStatic
        @Parameterized.Parameters(name = "device {0}")
        fun devices(): List<ForeignDevice> = ForeignDevice.ALL
    }
}
