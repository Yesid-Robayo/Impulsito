package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Las celdas numéricas del CSV, con los valores esperados escritos a mano. Corre con un idioma y una
 * zona del equipo que no son los de ningún caso: el resultado no puede depender de ellos.
 */
@RunWith(Parameterized::class)
class CsvFormatTest(defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    @Test
    fun `the regional csv table for 1234 in minor units`() {
        // Decimales de la moneda, región con coma decimal y región con punto decimal.
        assertRegionalCell(decimals = 0, withComma = "1234", withPoint = "1234")
        assertRegionalCell(decimals = 2, withComma = "12,34", withPoint = "12.34")
        assertRegionalCell(decimals = 3, withComma = "1,234", withPoint = "1.234")
    }

    @Test
    fun `a currency with four decimals keeps all of them`() {
        assertEquals("0,1234", commaRegion(decimals = 4).moneyCell(Money(1234L)))
        assertEquals("0.1234", pointRegion(decimals = 4).moneyCell(Money(1234L)))
    }

    @Test
    fun `amounts smaller than one unit get leading zeros`() {
        assertEquals("0,05", commaRegion(decimals = 2).moneyCell(Money(5L)))
        assertEquals("0.005", pointRegion(decimals = 3).moneyCell(Money(5L)))
        assertEquals("0,0001", commaRegion(decimals = 4).moneyCell(Money(1L)))
    }

    @Test
    fun `zero keeps its decimals`() {
        assertEquals("0", commaRegion(decimals = 0).moneyCell(Money.ZERO))
        assertEquals("0,00", commaRegion(decimals = 2).moneyCell(Money.ZERO))
        assertEquals("0.000", pointRegion(decimals = 3).moneyCell(Money.ZERO))
    }

    @Test
    fun `whole amounts still show their decimals`() {
        assertEquals("150,00", commaRegion(decimals = 2).moneyCell(Money(15_000L)))
        assertEquals("15.000", pointRegion(decimals = 3).moneyCell(Money(15_000L)))
    }

    @Test
    fun `there are no thousands separators`() {
        assertEquals("1234567,89", commaRegion(decimals = 2).moneyCell(Money(123_456_789L)))
        assertEquals("123456789", pointRegion(decimals = 0).moneyCell(Money(123_456_789L)))
    }

    @Test
    fun `a negative amount uses the ascii hyphen`() {
        assertEquals("-5000", commaRegion(decimals = 0).moneyCell(Money(-5000L)))
        assertEquals("-12,34", commaRegion(decimals = 2).moneyCell(Money(-1234L)))
        assertEquals("-1.234", pointRegion(decimals = 3).moneyCell(Money(-1234L)))
    }

    @Test
    fun `a negative amount smaller than one unit keeps the sign before the zero`() {
        assertEquals("-0,05", commaRegion(decimals = 2).moneyCell(Money(-5L)))
        assertEquals("-0.001", pointRegion(decimals = 3).moneyCell(Money(-1L)))
    }

    @Test
    fun `the extremes of long are exact`() {
        assertEquals(
            "92233720368547758,07",
            commaRegion(decimals = 2).moneyCell(Money(Long.MAX_VALUE)),
        )
        assertEquals(
            "-922337203685477.5808",
            pointRegion(decimals = 4).moneyCell(Money(Long.MIN_VALUE)),
        )
        assertEquals(
            "-9223372036854775808",
            pointRegion(decimals = 0).moneyCell(Money(Long.MIN_VALUE)),
        )
    }

    @Test
    fun `the largest document total is exact`() {
        assertEquals(
            "1000000000000,000",
            commaRegion(decimals = 3).moneyCell(Money(Limit.DOCUMENT_TOTAL.max)),
        )
    }

    @Test
    fun `a quantity always has three decimals`() {
        val comma = commaRegion(decimals = 0)
        assertEquals("0,250", comma.quantityCell(QuantityMilli(250L)))
        assertEquals("2,000", comma.quantityCell(QuantityMilli(2000L)))
        assertEquals("0,001", comma.quantityCell(QuantityMilli(1L)))
        assertEquals("0.000", pointRegion(decimals = 2).quantityCell(QuantityMilli.ZERO))
    }

    @Test
    fun `a negative quantity uses the ascii hyphen`() {
        assertEquals("-0,250", commaRegion(decimals = 2).quantityCell(QuantityMilli(-250L)))
        assertEquals("-12.500", pointRegion(decimals = 2).quantityCell(QuantityMilli(-12_500L)))
    }

    @Test
    fun `the largest stock is exact`() {
        assertEquals(
            "8000000.000",
            pointRegion(decimals = 0).quantityCell(QuantityMilli(Limit.ON_HAND_MILLI.max)),
        )
    }

    @Test
    fun `the quantity decimals do not follow the currency`() {
        // Una moneda sin decimales no le quita las milésimas a una cantidad.
        assertEquals("1,500", commaRegion(decimals = 0).quantityCell(QuantityMilli(1500L)))
        assertEquals("1,500", commaRegion(decimals = 4).quantityCell(QuantityMilli(1500L)))
    }

    @Test
    fun `comma columns in a decimal comma region quote numbers with decimals`() {
        val format = CsvFormat(CsvSeparator.COMMA, DecimalSeparator.COMMA, currencyDecimals = 2)
        assertEquals("\"12,34\"", format.moneyCell(Money(1234L)))
        assertEquals("\"-12,34\"", format.moneyCell(Money(-1234L)))
        assertEquals("\"0,250\"", format.quantityCell(QuantityMilli(250L)))
    }

    @Test
    fun `comma columns in a decimal comma region leave whole amounts unquoted`() {
        // Sin decimales no hay coma que parta la columna.
        val format = CsvFormat(CsvSeparator.COMMA, DecimalSeparator.COMMA, currencyDecimals = 0)
        assertEquals("1234", format.moneyCell(Money(1234L)))
        assertEquals("-1234", format.moneyCell(Money(-1234L)))
        // La cantidad sí lleva sus tres decimales, y con ellos las comillas.
        assertEquals("\"2,000\"", format.quantityCell(QuantityMilli(2000L)))
    }

    @Test
    fun `other combinations never quote a number`() {
        val pointWithComma = CsvFormat(CsvSeparator.COMMA, DecimalSeparator.POINT, 2)
        assertEquals("12.34", pointWithComma.moneyCell(Money(1234L)))
        val pointWithSemicolon = CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.POINT, 2)
        assertEquals("12.34", pointWithSemicolon.moneyCell(Money(1234L)))
        val commaWithTab = CsvFormat(CsvSeparator.TAB, DecimalSeparator.COMMA, 3)
        assertEquals("1,234", commaWithTab.moneyCell(Money(1234L)))
        assertEquals("0,250", commaWithTab.quantityCell(QuantityMilli(250L)))
        val pointWithTab = CsvFormat(CsvSeparator.TAB, DecimalSeparator.POINT, 3)
        assertEquals("1.234", pointWithTab.moneyCell(Money(1234L)))
    }

    @Test
    fun `a percentage has two decimals and no sign`() {
        assertEquals("19,00", commaRegion(decimals = 0).percentCell(BasisPoints(1_900L)))
        assertEquals("12.50", pointRegion(decimals = 3).percentCell(BasisPoints(1_250L)))
        assertEquals("0,00", commaRegion(decimals = 2).percentCell(BasisPoints.ZERO))
        assertEquals("-0.05", pointRegion(decimals = 2).percentCell(BasisPoints(-5L)))
        // Con columnas por coma y coma decimal, va entre comillas como cualquier número con decimales.
        val quoted = CsvFormat(CsvSeparator.COMMA, DecimalSeparator.COMMA, currencyDecimals = 0)
        assertEquals("\"8,25\"", quoted.percentCell(BasisPoints(825L)))
    }

    @Test
    fun `the format keeps the settings it was given`() {
        val format = CsvFormat(CsvSeparator.TAB, DecimalSeparator.COMMA, currencyDecimals = 3)
        assertEquals(CsvSeparator.TAB, format.separator)
        assertEquals(DecimalSeparator.COMMA, format.decimalSeparator)
        assertEquals(3, format.currencyDecimals)
    }

    @Test
    fun `currency decimals outside the settings range are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.COMMA, currencyDecimals = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.COMMA, currencyDecimals = 5)
        }
    }

    @Test
    fun `the limits of the settings range are accepted`() {
        assertEquals("1234", commaRegion(CsvFormat.MIN_CURRENCY_DECIMALS).moneyCell(Money(1234L)))
        assertEquals("0,1234", commaRegion(CsvFormat.MAX_CURRENCY_DECIMALS).moneyCell(Money(1234L)))
    }

    private fun assertRegionalCell(decimals: Int, withComma: String, withPoint: String) {
        assertEquals(withComma, commaRegion(decimals).moneyCell(Money(1234L)))
        assertEquals(withPoint, pointRegion(decimals).moneyCell(Money(1234L)))
    }

    /** Una región con coma decimal, con el separador de columnas con el que nace el ajuste. */
    private fun commaRegion(decimals: Int): CsvFormat =
        CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.COMMA, decimals)

    /** Una región con punto decimal, con el separador de columnas con el que nace el ajuste. */
    private fun pointRegion(decimals: Int): CsvFormat =
        CsvFormat(CsvSeparator.COMMA, DecimalSeparator.POINT, decimals)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> = ForeignSystemDefaults.ALL
    }
}
