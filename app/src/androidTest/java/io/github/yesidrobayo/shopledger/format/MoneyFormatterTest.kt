package io.github.yesidrobayo.shopledger.format

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Montos con el formato regional que se pide, sea cual sea el idioma del teléfono. Los valores
 * esperados están escritos a mano. Las regiones son datos de prueba, no supuestos, y las mismas
 * cadenas salen en API 26 y en API 37.
 */
@RunWith(Parameterized::class)
class MoneyFormatterTest(private val device: ForeignDevice) {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(device)

    @Test
    fun theTestReallyRunsWithAForeignDeviceLanguage() {
        assertEquals(device.languageTag, Locale.getDefault().toLanguageTag())
    }

    @Test
    fun twoDecimalsInACommaDecimalRegion() {
        assertEquals("1.234,56", money("es-AR", 2).format(Money(123_456)))
        assertEquals("1.234,56", money("pt-BR", 2).format(Money(123_456)))
    }

    @Test
    fun twoDecimalsInAPointDecimalRegion() {
        assertEquals("1,234.56", money("en-US", 2).format(Money(123_456)))
        assertEquals("1,234.56", money("es-MX", 2).format(Money(123_456)))
    }

    @Test
    fun zeroDecimalsShowNoDecimalPart() {
        assertEquals("1.235", money("es-AR", 0).format(Money(1_235)))
        assertEquals("1,235", money("ja-JP", 0).format(Money(1_235)))
    }

    @Test
    fun threeDecimals() {
        assertEquals("1.234,567", money("es-AR", 3).format(Money(1_234_567)))
        assertEquals("1,234.567", money("en-KW", 3).format(Money(1_234_567)))
    }

    @Test
    fun fourDecimalsAreTheMaximum() {
        assertEquals("1.234,5678", money("es-CL", 4).format(Money(12_345_678)))
    }

    @Test
    fun theSameMinorUnitsReadDifferentlyWithEachNumberOfDecimals() {
        assertEquals("12,350", money("en-US", 0).format(Money(12_350)))
        assertEquals("1,235.0", money("en-US", 1).format(Money(12_350)))
        assertEquals("123.50", money("en-US", 2).format(Money(12_350)))
        assertEquals("12.350", money("en-US", 3).format(Money(12_350)))
        assertEquals("1.2350", money("en-US", 4).format(Money(12_350)))
    }

    @Test
    fun aNegativeAmountUsesTheTypographicMinus() {
        assertEquals("\u22121.200,00", money("es-AR", 2).format(Money(-120_000)))
        assertEquals("\u22121,200.00", money("en-US", 2).format(Money(-120_000)))
        assertEquals("\u22121.200", money("es-AR", 0).format(Money(-1_200)))
    }

    @Test
    fun partsSplitTheSignFromTheDigits() {
        assertEquals(
            MoneyParts(negative = true, number = "1.200,00"),
            money("es-AR", 2).parts(Money(-120_000)),
        )
        assertEquals(
            MoneyParts(negative = false, number = "1,200.00"),
            money("en-US", 2).parts(Money(120_000)),
        )
    }

    @Test
    fun zeroIsNeverBlankAndKeepsTheDecimalsOfTheCurrency() {
        assertEquals("0,00", money("es-AR", 2).format(Money.ZERO))
        assertEquals("0", money("ja-JP", 0).format(Money.ZERO))
        assertEquals(
            MoneyParts(negative = false, number = "0.000"),
            money("en-KW", 3).parts(Money.ZERO),
        )
    }

    @Test
    fun theLargestAmountsAreExactBecauseNoDoubleIsInvolved() {
        val pointRegion = money("en-US", 2)
        assertEquals("92,233,720,368,547,758.07", pointRegion.format(Money(Long.MAX_VALUE)))
        assertEquals("\u221292,233,720,368,547,758.08", pointRegion.format(Money(Long.MIN_VALUE)))
        val documentTotal = Money(Limit.DOCUMENT_TOTAL.max)
        assertEquals("1.000.000.000.000.000", money("vi-VN", 0).format(documentTotal))
        assertEquals("999.999.999.999,999", money("es-AR", 3).format(documentTotal - Money(1)))
    }

    @Test
    fun groupsOfThreeAndTwoDigits() {
        assertEquals("12,34,567.00", money("en-IN", 2).format(Money(123_456_700)))
    }

    @Test
    fun groupsWithASpaceUseANoBreakSpaceTheFontHas() {
        assertEquals("1\u00A0234\u00A0567,89", money("fr-FR", 2).format(Money(123_456_789)))
        assertEquals("\u22121\u00A0234,56", money("sv-SE", 2).format(Money(-123_456)))
    }

    @Test
    fun groupsWithAnApostrophe() {
        assertEquals("1'234'567.89", money("de-CH", 2).format(Money(123_456_789)))
    }

    @Test
    fun theRegionDecidesTheNumbersEvenWithTheAppInEnglish() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        try {
            assertEquals("\u221212.345,67", money("es-AR", 2).format(Money(-1_234_567)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun aRegionWithOtherDigitsStillGetsLatinDigitsAndNoDirectionMarks() {
        assertEquals("\u22121,234.56", money("ar-EG", 2).format(Money(-123_456)))
        assertEquals("\u22121,234.56", money("he-IL", 2).format(Money(-123_456)))
        assertEquals("1,234.56", money("th-TH-u-nu-thai", 2).format(Money(123_456)))
        assertEquals("1,234.56", money("fa-IR", 2).format(Money(123_456)))
    }

    @Test
    fun noCurrencySymbolNorSpaceLeaksFromTheRegion() {
        for (tag in listOf(
            "es-ES",
            "en-US",
            "ja-JP",
            "pt-BR",
            "nl-NL",
            "de-CH",
            "en-IN",
            "tr-TR",
        )) {
            val text = money(tag, 2).format(Money(-123_456_789))
            assertTrue("$tag: $text", text.all { it.isDigit() || it in ".,'\u2212" })
            assertTrue("$tag: $text", text.startsWith("\u2212"))
        }
    }

    @Test
    fun decimalsOutsideZeroToFourAreAProgrammingError() {
        val formatLocale = requireNotNull(FormatLocale.of("en-US"))
        assertThrows(IllegalArgumentException::class.java) { MoneyFormatter(formatLocale, -1) }
        assertThrows(IllegalArgumentException::class.java) { MoneyFormatter(formatLocale, 5) }
    }

    @Test
    fun manyThreadsGetTheSameTextAsOneThread() {
        val formatter = money("es-AR", 2)
        val amounts = (1..ROUNDS).map { Money(it * 1_000_003L * if (it % 2 == 0) 1 else -1) }
        val expected = amounts.map { formatter.format(it) }
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val tasks = (1..THREADS).map { Callable { amounts.map { formatter.format(it) } } }
            for (result in pool.invokeAll(tasks)) {
                assertEquals(expected, result.get(1, TimeUnit.MINUTES))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun money(tag: String, decimals: Int): MoneyFormatter =
        MoneyFormatter(requireNotNull(FormatLocale.of(tag)), decimals)

    companion object {
        private const val THREADS = 8
        private const val ROUNDS = 500

        @JvmStatic
        @Parameterized.Parameters(name = "device {0}")
        fun devices(): List<ForeignDevice> = ForeignDevice.ALL
    }
}
