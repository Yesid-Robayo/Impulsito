package io.github.yesidrobayo.shopledger.format

import android.icu.util.ULocale
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Los símbolos que los formateadores dan al dominio para leer lo que escribe la persona. Los
 * valores esperados están escritos a mano, con regiones que son datos de prueba de la matriz de
 * configuraciones.
 */
class NumberInputSymbolsTest {
    @get:Rule
    val foreignDevice = ForeignDeviceRule(ForeignDevice.ALL.first())

    @Test
    fun aCommaDecimalRegionAndAPointDecimalRegion() {
        assertEquals(
            NumberSymbols(',', '\u2212', DigitGrouping('.', 3, 3)),
            MoneyFormatter(locale("es-AR"), 2).inputSymbols,
        )
        assertEquals(
            NumberSymbols('.', '\u2212', DigitGrouping(',', 3, 3)),
            MoneyFormatter(locale("en-US"), 2).inputSymbols,
        )
    }

    @Test
    fun groupsOfThreeAndThenTwoDigits() {
        val formatter = MoneyFormatter(locale("en-IN"), 2)
        assertEquals(NumberSymbols('.', '\u2212', DigitGrouping(',', 3, 2)), formatter.inputSymbols)
        assertEquals("12,34,567.00", formatter.format(Money(123_456_700)))
        assertEquals(
            NumberInput.Value(Money(123_456_700)),
            NumberInputParser.money("12,34,567.00", formatter.inputSymbols, 2, Limit.UNIT_MONEY),
        )
        assertEquals(
            NumberInput.Malformed,
            NumberInputParser.money("1,234,567.00", formatter.inputSymbols, 2, Limit.UNIT_MONEY),
        )
    }

    @Test
    fun groupsWithAnApostropheReadBothApostrophes() {
        val formatter = MoneyFormatter(locale("de-CH"), 2)
        assertEquals("1'234'567.89", formatter.format(Money(123_456_789)))
        for (text in listOf("1'234'567.89", "1\u2019234\u2019567.89", "1234567.89")) {
            assertEquals(
                text,
                NumberInput.Value(Money(123_456_789)),
                NumberInputParser.money(text, formatter.inputSymbols, 2, Limit.UNIT_MONEY),
            )
        }
    }

    @Test
    fun groupsWithAThinSpaceReadAnySpace() {
        val formatter = MoneyFormatter(locale("fr-FR"), 2)
        assertEquals('\u00A0', formatter.inputSymbols.grouping?.separator)
        for (text in listOf("1\u202F234\u202F567,89", "1 234 567,89", "1234567,89")) {
            assertEquals(
                text,
                NumberInput.Value(Money(123_456_789)),
                NumberInputParser.money(text, formatter.inputSymbols, 2, Limit.UNIT_MONEY),
            )
        }
    }

    @Test
    fun aRegionWithOtherDigitsStillReadsLatinDigitsWithoutDirectionMarks() {
        val formatter = MoneyFormatter(locale("ar-EG"), 2)
        assertEquals('\u2212', formatter.inputSymbols.minusSign)
        val text = formatter.format(Money(-123_456))
        assertEquals(
            NumberInput.Value(Money(-123_456)),
            NumberInputParser.money(text, formatter.inputSymbols, 2, Limit.UNIT_MONEY),
        )
    }

    /**
     * En algunas regiones el dinero no usa el separador decimal de los demás números, y eso cambia
     * con la versión de ICU: un campo de dinero lee con los símbolos del formateador de dinero, y
     * uno de cantidad con los del de cantidades. Las cadenas no se fijan aquí, porque cambian entre
     * niveles de API; se exige la ida y vuelta.
     */
    @Test
    fun aRegionWhereMoneyAndQuantitiesUseDifferentSeparatorsReadsEachWithItsOwn() {
        for (tag in listOf("en-DE", "pt-CV", "kea-CV", "fr-CH")) {
            val money = MoneyFormatter(locale(tag), 2)
            for (minor in listOf(123_456L, -123_456L, 100L)) {
                val text = money.format(Money(minor))
                assertEquals(
                    "$tag $text",
                    NumberInput.Value(Money(minor)),
                    NumberInputParser.money(text, money.inputSymbols, 2, Limit.UNIT_MONEY),
                )
            }
            val quantities = QuantityFormatter(locale(tag))
            val text = quantities.format(QuantityMilli(1_234_500), allowsFraction = true)
            assertEquals(
                "$tag $text",
                NumberInput.Value(QuantityMilli(1_234_500)),
                NumberInputParser.quantity(text, quantities.inputSymbols, Limit.ON_HAND_MILLI),
            )
        }
    }

    @Test
    fun quantitiesAndPercentagesUseTheirOwnFormat() {
        assertEquals(
            NumberSymbols(',', '\u2212', DigitGrouping('.', 3, 3)),
            QuantityFormatter(locale("pt-BR")).inputSymbols,
        )
        assertEquals(
            NumberSymbols(',', '\u2212', DigitGrouping('.', 3, 3)),
            PercentFormatter(locale("es-ES")).inputSymbols,
        )
    }

    /**
     * Más allá de la matriz: en cada región que trae el ICU del dispositivo, lo que escriben los
     * tres formateadores vuelve a los mismos enteros. Si una región escribiera un símbolo que el
     * lector no entiende, esta prueba la nombra.
     */
    @Test
    fun everyRegionOfTheDeviceReadsBackWhatItsFormattersWrite() {
        val failures = mutableListOf<String>()
        for (available in ULocale.getAvailableLocales()) {
            val tag = available.toLanguageTag()
            val formatLocale = FormatLocale.of(tag) ?: continue
            try {
                checkRegion(formatLocale, failures)
            } catch (error: IllegalArgumentException) {
                failures += "$tag: ${error.message}"
            }
        }
        assertTrue(failures.take(MAX_REPORTED).joinToString("\n"), failures.isEmpty())
    }

    private fun checkRegion(formatLocale: FormatLocale, failures: MutableList<String>) {
        fun <T> expect(read: NumberInput<T>, expected: T, text: String) {
            if (read != NumberInput.Value(expected)) failures += "$formatLocale $text $read"
        }
        for (decimals in 0..MoneyFormatter.MAX_CURRENCY_DECIMALS) {
            val money = MoneyFormatter(formatLocale, decimals)
            for (minor in listOf(-123_456_789_012L, 0L, 7L, Limit.UNIT_MONEY.max)) {
                val text = money.format(Money(minor))
                val read = NumberInputParser.money(
                    text,
                    money.inputSymbols,
                    decimals,
                    Limit.UNIT_MONEY,
                )
                expect(read, Money(minor), text)
            }
        }
        val quantities = QuantityFormatter(formatLocale)
        for (milli in listOf(-1_234_567L, 250L, Limit.ON_HAND_MILLI.max)) {
            val text = quantities.format(QuantityMilli(milli), allowsFraction = true)
            val read = NumberInputParser.quantity(
                text,
                quantities.inputSymbols,
                Limit.ON_HAND_MILLI,
            )
            expect(read, QuantityMilli(milli), text)
        }
        val percentages = PercentFormatter(formatLocale)
        val maximum = BasisPoints.MAX_BULK_PERCENT
        for (rate in listOf(BasisPoints(-1_250L), BasisPoints(99_990L))) {
            for (text in listOf(percentages.format(rate), percentages.formatChange(rate))) {
                expect(
                    NumberInputParser.percent(text, percentages.inputSymbols, maximum),
                    rate,
                    text,
                )
            }
        }
    }

    private fun locale(tag: String): FormatLocale = requireNotNull(FormatLocale.of(tag))

    private companion object {
        /** Cuántas regiones que fallan se muestran en el mensaje, para que se pueda leer. */
        const val MAX_REPORTED = 40
    }
}
