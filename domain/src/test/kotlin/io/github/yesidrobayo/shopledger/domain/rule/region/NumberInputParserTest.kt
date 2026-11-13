package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Lo que la persona escribe en un campo de monto, cantidad o porcentaje, leído con los símbolos de
 * un formato regional. Los símbolos son estilos inventados, no regiones: cuáles usa cada región lo
 * prueba la matriz de configuraciones en el dispositivo, contra los formateadores. Los valores
 * esperados están escritos a mano, y cada prueba corre con idiomas por defecto de la JVM que, si el
 * lector los leyera, cambiarían el resultado.
 */
@RunWith(Parameterized::class)
class NumberInputParserTest(private val defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    @Test
    fun `the test really runs with a foreign default language`() {
        assertEquals(Locale.forLanguageTag(defaults.languageTag), Locale.getDefault())
    }

    @Test
    fun `comma decimal and point grouping`() {
        assertEquals(money(123_456), money("1.234,56", COMMA_DECIMAL, 2))
        assertEquals(money(123_456), money("1234,56", COMMA_DECIMAL, 2))
        assertEquals(money(1_234_567_800), money("12.345.678,00", COMMA_DECIMAL, 2))
    }

    @Test
    fun `a count reads whole numbers with the grouping of the region and nothing else`() {
        assertEquals(NumberInput.Value(1_250L), count("1.250", COMMA_DECIMAL))
        assertEquals(NumberInput.Value(1_250L), count("1,250", POINT_DECIMAL))
        assertEquals(NumberInput.Value(100L), count("100", COMMA_DECIMAL))
        assertEquals(NumberInput.Blank, count(" ", COMMA_DECIMAL))
        assertEquals(NumberInput.Malformed, count("1x", COMMA_DECIMAL))
        assertEquals(NumberInput.TooManyDecimals(0), count("1,5", COMMA_DECIMAL))
        assertEquals(NumberInput.TooLarge(Limit.POINTS.max), count("1000000000001", COMMA_DECIMAL))
    }

    private fun count(text: String, symbols: NumberSymbols): NumberInput<Long> =
        NumberInputParser.count(text, symbols, Limit.POINTS)

    @Test
    fun `an exchange rate reads up to six decimals as millionths`() {
        // El ejemplo de la transferencia entre monedas: 4150 por unidad.
        assertEquals(NumberInput.Value(4_150_000_000L), rate("4.150", COMMA_DECIMAL))
        assertEquals(NumberInput.Value(4_150_500_000L), rate("4.150,5", COMMA_DECIMAL))
        assertEquals(NumberInput.Value(1L), rate("0.000001", POINT_DECIMAL))
        assertEquals(NumberInput.TooManyDecimals(6), rate("0,0000001", COMMA_DECIMAL))
        assertEquals(NumberInput.Blank, rate("", COMMA_DECIMAL))
        assertEquals(NumberInput.Malformed, rate("4x", COMMA_DECIMAL))
        assertEquals(
            NumberInput.TooLarge(Limit.DOCUMENT_TOTAL.max),
            rate("1.000.000.000.001", COMMA_DECIMAL),
        )
    }

    private fun rate(text: String, symbols: NumberSymbols): NumberInput<Long> =
        NumberInputParser.rateMicro(text, symbols, Limit.DOCUMENT_TOTAL)

    @Test
    fun `point decimal and comma grouping`() {
        assertEquals(money(123_456), money("1,234.56", POINT_DECIMAL, 2))
        assertEquals(money(123_456), money("1234.56", POINT_DECIMAL, 2))
    }

    @Test
    fun `the same minor units read from the documented example with each number of decimals`() {
        // La línea de 0,333 kg del ejemplo de unidad menor: el mismo precio se escribe distinto
        // según los decimales de la moneda, y se guarda como el entero de la tabla.
        assertEquals(money(18_500), money("18500", COMMA_DECIMAL, 0))
        assertEquals(money(1_850), money("18,50", COMMA_DECIMAL, 2))
        assertEquals(money(18_500), money("18,500", COMMA_DECIMAL, 3))
        assertEquals(money(6_161), money("6161", COMMA_DECIMAL, 0))
        assertEquals(money(616), money("6,16", COMMA_DECIMAL, 2))
        assertEquals(money(6_161), money("6,161", COMMA_DECIMAL, 3))
    }

    @Test
    fun `currencies with zero, two, three and four decimals`() {
        assertEquals(money(1_235), money("1.235", COMMA_DECIMAL, 0))
        assertEquals(money(123_456), money("1.234,56", COMMA_DECIMAL, 2))
        assertEquals(money(1_234_567), money("1,234.567", POINT_DECIMAL, 3))
        assertEquals(money(12_345_678), money("1.234,5678", COMMA_DECIMAL, 4))
        assertEquals(money(1), money("0,0001", COMMA_DECIMAL, 4))
    }

    @Test
    fun `missing decimals are zeros and are never guessed`() {
        assertEquals(money(1_200), money("12", COMMA_DECIMAL, 2))
        assertEquals(money(12_500), money("12,5", COMMA_DECIMAL, 3))
        assertEquals(money(50), money(",5", COMMA_DECIMAL, 2))
        // Mientras se escribe, el separador decimal puede quedar sin cifras detrás.
        assertEquals(money(1_200), money("12,", COMMA_DECIMAL, 2))
        assertEquals(money(12), money("12,", COMMA_DECIMAL, 0))
        assertEquals(money(7), money("0007", POINT_DECIMAL, 0))
        assertEquals(money(0), money("0", POINT_DECIMAL, 2))
    }

    @Test
    fun `one decimal more than the currency has is rejected, not rounded`() {
        assertEquals(NumberInput.TooManyDecimals(2), money("12,345", COMMA_DECIMAL, 2))
        assertEquals(NumberInput.TooManyDecimals(0), money("12,5", COMMA_DECIMAL, 0))
        assertEquals(NumberInput.TooManyDecimals(3), money("1.234,5670", COMMA_DECIMAL, 3))
        assertEquals(NumberInput.TooManyDecimals(4), money("0,00001", COMMA_DECIMAL, 4))
        // Ni siquiera un cero de más: el campo guarda lo que se escribió, y no se escribió eso.
        assertEquals(NumberInput.TooManyDecimals(2), money("12.500", POINT_DECIMAL, 2))
    }

    @Test
    fun `groups of three and then two digits`() {
        assertEquals(money(123_456_700), money("12,34,567.00", THREE_THEN_TWO, 2))
        assertEquals(money(123_456_789_000), money("1,23,45,67,890.00", THREE_THEN_TWO, 2))
        assertEquals(money(123_400), money("1,234.00", THREE_THEN_TWO, 2))
        assertEquals(money(123_456_700), money("1234567.00", THREE_THEN_TWO, 2))
        // Grupos de tres en todo el número no son los de este formato.
        assertEquals(NumberInput.Malformed, money("1,234,567.00", THREE_THEN_TWO, 2))
        assertEquals(NumberInput.Malformed, money("123,456.00", THREE_THEN_TWO, 2))
    }

    @Test
    fun `apostrophe grouping accepts the keyboard and the typographic apostrophe`() {
        assertEquals(money(123_456_789), money("1'234'567.89", APOSTROPHE, 2))
        assertEquals(money(123_456_789), money("1\u2019234\u2019567.89", APOSTROPHE, 2))
        assertEquals(money(123_456_789), money("1'234\u2019567.89", APOSTROPHE, 2))
    }

    @Test
    fun `space grouping accepts any space the person can type or paste`() {
        val expected = money(123_456_789)
        assertEquals(expected, money("1\u00A0234\u00A0567,89", SPACE, 2))
        assertEquals(expected, money("1 234 567,89", SPACE, 2))
        assertEquals(expected, money("1\u202F234\u2009567,89", SPACE, 2))
        assertEquals(NumberInput.Malformed, money("1  234,89", SPACE, 2))
    }

    @Test
    fun `a format without grouping only takes digits and the decimal separator`() {
        assertEquals(money(123_456), money("1234.56", UNGROUPED, 2))
        assertEquals(NumberInput.Malformed, money("1,234.56", UNGROUPED, 2))
    }

    @Test
    fun `groups out of place are rejected instead of guessed`() {
        // Con punto de miles, 1.5 no es ni uno y medio ni quince: es un error de digitación.
        for (text in listOf(
            "1.5",
            ".5",
            "1..234",
            "1.234.",
            "1.234,5.6",
            "12.34",
            "1.2345",
            "1234.567",
        )) {
            assertEquals(text, NumberInput.Malformed, money(text, COMMA_DECIMAL, 2))
        }
        // Un número escrito con los separadores de otra región tampoco se lee al revés.
        assertEquals(NumberInput.Malformed, money("1,5", POINT_DECIMAL, 2))
        assertEquals(NumberInput.Malformed, money("1,234,56", COMMA_DECIMAL, 2))
    }

    @Test
    fun `a negative with the typographic minus, the keyboard hyphen or the region minus`() {
        val expected = money(-120_000)
        assertEquals(expected, money("\u22121.200,00", COMMA_DECIMAL, 2))
        assertEquals(expected, money("-1.200,00", COMMA_DECIMAL, 2))
        assertEquals(expected, money("\u20121.200,00", OWN_MINUS, 2))
        assertEquals(expected, money("1.200,00\u2212", COMMA_DECIMAL, 2))
        assertEquals(expected, money(" \u2212 1.200,00 ", COMMA_DECIMAL, 2))
        assertEquals(money(0), money("\u22120", COMMA_DECIMAL, 2))
    }

    @Test
    fun `an explicit plus sign keeps the value positive`() {
        assertEquals(money(120_000), money("+1.200,00", COMMA_DECIMAL, 2))
        assertEquals(money(120_000), money("1.200,00+", COMMA_DECIMAL, 2))
    }

    @Test
    fun `more than one sign, or a sign inside the number, is malformed`() {
        for (text in listOf("--5", "\u22125\u2212", "+-5", "5\u22125", "\u2212", "+", " - ")) {
            assertEquals(text, NumberInput.Malformed, money(text, COMMA_DECIMAL, 2))
        }
    }

    @Test
    fun `letters, separators alone and other symbols are malformed`() {
        val texts =
            listOf("12a", "a", ",", "1,2,3", "1.234,5,6", "%5", "5%", "1e3", "0x10", "\u00B2")
        for (text in texts) {
            assertEquals(text, NumberInput.Malformed, money(text, COMMA_DECIMAL, 2))
        }
    }

    @Test
    fun `an empty field is blank`() {
        for (text in listOf("", "   ", "\u00A0", "\u200E")) {
            assertEquals(NumberInput.Blank, money(text, COMMA_DECIMAL, 2))
        }
    }

    @Test
    fun `direction marks pasted from another app are ignored`() {
        assertEquals(money(-123_456), money("\u200F\u22121.234,56\u200E", COMMA_DECIMAL, 2))
        assertEquals(money(5), money("\u061C5", COMMA_DECIMAL, 0))
    }

    @Test
    fun `digits of other scripts and full width signs mean the same digits`() {
        // Cifras de ancho completo con su coma, punto y menos, como las deja un teclado de Asia
        // oriental.
        val fullWidth = "\uFF0D\uFF11\uFF0C\uFF12\uFF13\uFF14\uFF0E\uFF15\uFF16"
        assertEquals(money(-123_456), money(fullWidth, POINT_DECIMAL, 2))
        // Cifras arábigo-índicas y devanagari.
        assertEquals(money(123), money("\u0661\u0662\u0663", POINT_DECIMAL, 0))
        assertEquals(money(4_050), money("\u0664\u0660.\u0665", POINT_DECIMAL, 2))
        assertEquals(money(9), money("\u096F", POINT_DECIMAL, 0))
        // Fuera del bloque de ancho completo, un signo parecido no es un signo del número.
        assertEquals(NumberInput.Malformed, money("5\uFF5F", POINT_DECIMAL, 0))
    }

    @Test
    fun `a unit price at its limit is valid and one minor unit more is rejected`() {
        val limit = Money(Limit.UNIT_MONEY.max)
        assertEquals(NumberInput.Value(limit), money("1.000.000.000.000", COMMA_DECIMAL, 0))
        assertEquals(NumberInput.Value(limit), money("10.000.000.000,00", COMMA_DECIMAL, 2))
        assertEquals(NumberInput.Value(limit), money("100.000.000,0000", COMMA_DECIMAL, 4))
        assertEquals(NumberInput.TooLarge(limit), money("1.000.000.000.001", COMMA_DECIMAL, 0))
        assertEquals(NumberInput.TooLarge(limit), money("10.000.000.000,01", COMMA_DECIMAL, 2))
        assertEquals(NumberInput.TooLarge(limit), money("100.000.000,0001", COMMA_DECIMAL, 4))
    }

    @Test
    fun `the limit also bounds a negative value`() {
        val limit = Money(Limit.UNIT_MONEY.max)
        assertEquals(NumberInput.Value(-limit), money("\u22121.000.000.000.000", COMMA_DECIMAL, 0))
        assertEquals(
            NumberInput.TooLarge(limit),
            money("\u22121.000.000.000.001", COMMA_DECIMAL, 0),
        )
    }

    @Test
    fun `the document total limit, exactly and one more`() {
        // Un millón de unidades a mil millones por unidad alcanza justo el tope de un documento.
        val limit = Money(Limit.DOCUMENT_TOTAL.max)
        val atLimit = "1.000.000.000.000.000"
        assertEquals(
            NumberInput.Value(limit),
            money(atLimit, COMMA_DECIMAL, 0, Limit.DOCUMENT_TOTAL),
        )
        assertEquals(
            NumberInput.TooLarge(limit),
            money("1.000.000.000.000.001", COMMA_DECIMAL, 0, Limit.DOCUMENT_TOTAL),
        )
    }

    @Test
    fun `numbers far beyond Long are rejected by their limit without overflowing`() {
        val limit = Money(Limit.STOCK_VALUE.max)
        for (text in listOf(
            "9223372036854775808",
            "99999999999999999999999999",
            "9.000.000.000.000.001",
        )) {
            assertEquals(
                text,
                NumberInput.TooLarge(limit),
                money(text, COMMA_DECIMAL, 0, Limit.STOCK_VALUE),
            )
        }
        assertEquals(
            NumberInput.Value(limit),
            money("90.000.000.000.000,00", COMMA_DECIMAL, 2, Limit.STOCK_VALUE),
        )
        // Muchos ceros a la izquierda no suman nada.
        assertEquals(money(1), money("0000000000000000000000000001", COMMA_DECIMAL, 0))
    }

    @Test
    fun `the number is checked for form, then decimals, then its limit`() {
        // Con todo mal a la vez, el motivo es lo primero que hay que corregir.
        assertEquals(NumberInput.Malformed, money("99.999.999.999.999,999a", COMMA_DECIMAL, 2))
        assertEquals(
            NumberInput.TooManyDecimals(2),
            money("99.999.999.999.999,999", COMMA_DECIMAL, 2),
        )
    }

    @Test
    fun `currency decimals go from zero to four`() {
        assertThrows(IllegalArgumentException::class.java) { money("1", COMMA_DECIMAL, -1) }
        assertThrows(IllegalArgumentException::class.java) { money("1", COMMA_DECIMAL, 5) }
    }

    @Test
    fun `quantities in thousandths`() {
        assertEquals(quantity(250), quantity("0,250", COMMA_DECIMAL))
        assertEquals(quantity(250), quantity("0,25", COMMA_DECIMAL))
        assertEquals(quantity(12_000), quantity("12", COMMA_DECIMAL))
        assertEquals(quantity(1_500), quantity("1.500", POINT_DECIMAL))
        assertEquals(quantity(-2_000), quantity("\u22122", COMMA_DECIMAL))
        assertEquals(
            quantity(8_000_000_000),
            quantity("80,00,000", THREE_THEN_TWO, Limit.ON_HAND_MILLI),
        )
        assertEquals(NumberInput.TooManyDecimals(3), quantity("0,0005", COMMA_DECIMAL))
        assertEquals(NumberInput.Malformed, quantity("0,2.5", COMMA_DECIMAL))
        assertEquals(NumberInput.Blank, quantity(" ", COMMA_DECIMAL))
    }

    @Test
    fun `quantity limits, exactly and one thousandth more`() {
        val line = QuantityMilli(Limit.LINE_QUANTITY_MILLI.max)
        assertEquals(NumberInput.Value(line), quantity("1.000.000", COMMA_DECIMAL))
        assertEquals(NumberInput.TooLarge(line), quantity("1.000.000,001", COMMA_DECIMAL))
        val onHand = QuantityMilli(Limit.ON_HAND_MILLI.max)
        assertEquals(
            NumberInput.Value(onHand),
            quantity("8.000.000,000", COMMA_DECIMAL, Limit.ON_HAND_MILLI),
        )
        assertEquals(
            NumberInput.TooLarge(onHand),
            quantity("8.000.000,001", COMMA_DECIMAL, Limit.ON_HAND_MILLI),
        )
    }

    @Test
    fun `percentages in basis points with up to two decimals`() {
        // El ejemplo de los puntos básicos: 12,5 % son 1250.
        assertEquals(percent(1_250), percent("12,5", COMMA_DECIMAL))
        assertEquals(percent(1_225), percent("12,25", COMMA_DECIMAL))
        assertEquals(percent(3_000), percent("30", COMMA_DECIMAL))
        assertEquals(percent(1), percent("0.01", POINT_DECIMAL))
        assertEquals(NumberInput.TooManyDecimals(2), percent("12,255", COMMA_DECIMAL))
    }

    @Test
    fun `the percent sign is accepted before or after, with or without a space`() {
        for (text in listOf("12,5 %", "12,5%", "12,5\u00A0%", "%12,5", "% 12,5", "12,5\uFF05")) {
            assertEquals(text, percent(1_250), percent(text, COMMA_DECIMAL))
        }
        for (text in listOf(
            "\u221212,5 %",
            "\u2212%12,5",
            "%\u221212,5",
            "12,5 %\u2212",
            "\u221212,5%",
        )) {
            assertEquals(text, percent(-1_250), percent(text, COMMA_DECIMAL))
        }
        assertEquals(percent(800), percent("+8,0 %", COMMA_DECIMAL))
    }

    @Test
    fun `the region percent sign is read as well as the keyboard one`() {
        for (text in listOf("12.5\u066A", "\u066A12.5", "12.5 %", "\u221212.5\u066A")) {
            val expected = if (text.startsWith("\u2212")) percent(-1_250) else percent(1_250)
            assertEquals(text, expected, percent(text, OWN_PERCENT))
        }
        assertEquals(NumberInput.Malformed, percent("12.5\u066A%", OWN_PERCENT))
    }

    @Test
    fun `a percent sign alone, twice or inside the number is malformed`() {
        for (text in listOf("%", "%%5", "5%%", "%5%", "1%2", "\u2212%", "% \u2212")) {
            assertEquals(text, NumberInput.Malformed, percent(text, COMMA_DECIMAL))
        }
    }

    @Test
    fun `percent limits come from the field`() {
        val targetMargin = BasisPoints.MAX_TARGET_MARGIN
        assertEquals(NumberInput.Value(targetMargin), percent("99", COMMA_DECIMAL, targetMargin))
        assertEquals(
            NumberInput.TooLarge(targetMargin),
            percent("99,01", COMMA_DECIMAL, targetMargin),
        )
        val bulk = BasisPoints.MAX_BULK_PERCENT
        assertEquals(NumberInput.Value(bulk), percent("999,99", COMMA_DECIMAL, bulk))
        assertEquals(NumberInput.TooLarge(bulk), percent("1.000", COMMA_DECIMAL, bulk))
        // Un tope menor que una cifra: cinco puntos básicos no admiten siete.
        assertEquals(
            NumberInput.TooLarge(BasisPoints(5)),
            percent("0,07", COMMA_DECIMAL, BasisPoints(5)),
        )
        assertEquals(
            NumberInput.Value(BasisPoints(5)),
            percent("0,05", COMMA_DECIMAL, BasisPoints(5)),
        )
        assertEquals(
            NumberInput.Value(BasisPoints.ZERO),
            percent("0", COMMA_DECIMAL, BasisPoints.ZERO),
        )
    }

    @Test
    fun `a percent limit cannot be negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            percent("1", COMMA_DECIMAL, BasisPoints(-1))
        }
    }

    private fun money(minor: Long): NumberInput<Money> = NumberInput.Value(Money(minor))

    private fun money(
        text: String,
        symbols: NumberSymbols,
        decimals: Int,
        limit: Limit = Limit.UNIT_MONEY,
    ): NumberInput<Money> = NumberInputParser.money(text, symbols, decimals, limit)

    private fun quantity(milli: Long): NumberInput<QuantityMilli> =
        NumberInput.Value(QuantityMilli(milli))

    private fun quantity(
        text: String,
        symbols: NumberSymbols,
        limit: Limit = Limit.LINE_QUANTITY_MILLI,
    ): NumberInput<QuantityMilli> = NumberInputParser.quantity(text, symbols, limit)

    private fun percent(bp: Long): NumberInput<BasisPoints> = NumberInput.Value(BasisPoints(bp))

    private fun percent(
        text: String,
        symbols: NumberSymbols,
        maximum: BasisPoints = BasisPoints.MAX_BULK_PERCENT,
    ): NumberInput<BasisPoints> = NumberInputParser.percent(text, symbols, maximum)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> = ForeignSystemDefaults.ALL

        private const val MINUS = '\u2212'

        val COMMA_DECIMAL = NumberSymbols(',', MINUS, DigitGrouping('.', 3, 3))
        val POINT_DECIMAL = NumberSymbols('.', MINUS, DigitGrouping(',', 3, 3))
        val THREE_THEN_TWO = NumberSymbols('.', MINUS, DigitGrouping(',', 3, 2))
        val APOSTROPHE = NumberSymbols('.', MINUS, DigitGrouping('\u2019', 3, 3))
        val SPACE = NumberSymbols(',', MINUS, DigitGrouping('\u00A0', 3, 3))
        val UNGROUPED = NumberSymbols('.', MINUS, null)

        /** Un signo de porcentaje propio de la región, distinto del teclado. */
        val OWN_PERCENT =
            NumberSymbols('.', MINUS, DigitGrouping(',', 3, 3), percentSign = '\u066A')

        /** Un signo menos propio de la región, distinto del guion y del tipográfico. */
        val OWN_MINUS = NumberSymbols(',', '\u2012', DigitGrouping('.', 3, 3))
    }
}
