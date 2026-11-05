package io.github.yesidrobayo.shopledger.domain.matrix

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Lo que la persona escribe en un campo, con los separadores y los decimales de cada fila de la
 * matriz, vuelve a los mismos enteros, y un decimal de más o un valor sobre su tope se rechaza sin
 * redondear.
 *
 * Los textos los arma [written] desde el entero, con los símbolos que la fila dice que muestra la
 * app. La ida y vuelta contra los formateadores de verdad, con ICU, corre en el dispositivo con las
 * mismas filas.
 */
@RunWith(Parameterized::class)
class RegionalMatrixInputTest(
    private val row: RegionalMatrixRow,
    defaults: ForeignSystemDefaults,
) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    private val decimals = row.currencyDecimals
    private val symbols = row.regionOr(RegionalMatrix.INVENTED_DEVICE).numberSymbols

    @Test
    fun `amounts come back to the same minor units with or without groups and either minus`() {
        for (minor in AMOUNTS) {
            val texts =
                listOf(
                    written(minor, decimals, symbols),
                    written(minor, decimals, symbols, grouped = false),
                    written(minor, decimals, symbols, minus = NumberSymbols.HYPHEN_MINUS),
                    " ${written(minor, decimals, symbols)} ",
                )
            for (text in texts) {
                assertEquals(
                    "$row «$text»",
                    NumberInput.Value(Money(minor)),
                    money(text, Limit.DOCUMENT_TOTAL),
                )
            }
        }
    }

    @Test
    fun `one decimal more than the currency has is rejected without rounding`() {
        val text = written(123_451, decimals + 1, symbols)

        assertEquals(
            "$row «$text»",
            NumberInput.TooManyDecimals(decimals),
            money(text, Limit.UNIT_MONEY),
        )
    }

    @Test
    fun `the documented typing examples give the minor units of the row decimals`() {
        // 12,5 y 12,345, escritos con el separador decimal de la fila.
        val half = written(125, 1, symbols)
        val three = written(12_345, 3, symbols)

        assertEquals(
            "$row «$half»",
            if (decimals >= 1) value(125 * pow10(decimals - 1)) else NumberInput.TooManyDecimals(0),
            money(half, Limit.UNIT_MONEY),
        )
        assertEquals(
            "$row «$three»",
            if (decimals >= 3) {
                value(12_345 * pow10(decimals - 3))
            } else {
                NumberInput.TooManyDecimals(decimals)
            },
            money(three, Limit.UNIT_MONEY),
        )
    }

    @Test
    fun `an amount over its limit is rejected and the limit itself is not`() {
        for (limit in listOf(Limit.UNIT_MONEY, Limit.DOCUMENT_TOTAL)) {
            val cap = Money(limit.max)
            assertEquals(value(limit.max), money(written(limit.max, decimals, symbols), limit))
            for (over in listOf(limit.max + 1, -limit.max - 1)) {
                val text = written(over, decimals, symbols)
                assertEquals("$row «$text»", NumberInput.TooLarge(cap), money(text, limit))
            }
        }
    }

    @Test
    fun `groups in the wrong place and the decimal separator of other regions are malformed`() {
        val grouping = requireNotNull(symbols.grouping) { "Toda fila de la matriz agrupa cifras" }
        // Con grupos de tres, 1.5 no es 15; con tres y luego dos, 1,234,567 no es un número.
        val otherSizes =
            symbols.copy(
                grouping = grouping.copy(secondarySize = if (grouping.secondarySize == 2) 3 else 2),
            )
        val texts =
            listOf(
                "1${grouping.separator}5",
                written(1_234_567 * pow10(decimals), decimals, otherSizes),
                "12${otherDecimal(symbols)}5",
            )
        for (text in texts) {
            assertEquals("$row «$text»", NumberInput.Malformed, money(text, Limit.UNIT_MONEY))
        }
    }

    @Test
    fun `quantities come back to the same thousandths and a fourth decimal is rejected`() {
        for (milli in QUANTITIES) {
            val text = written(milli, 3, symbols)
            assertEquals(
                "$row «$text»",
                NumberInput.Value(QuantityMilli(milli)),
                NumberInputParser.quantity(text, symbols, Limit.ON_HAND_MILLI),
            )
        }
        val fourDecimals = written(12_345, 4, symbols)
        assertEquals(
            NumberInput.TooManyDecimals(3),
            NumberInputParser.quantity(fourDecimals, symbols, Limit.LINE_QUANTITY_MILLI),
        )
        val over = written(Limit.LINE_QUANTITY_MILLI.max + 1, 3, symbols)
        assertEquals(
            NumberInput.TooLarge(QuantityMilli(Limit.LINE_QUANTITY_MILLI.max)),
            NumberInputParser.quantity(over, symbols, Limit.LINE_QUANTITY_MILLI),
        )
    }

    @Test
    fun `percentages come back to the same basis points and a third decimal is rejected`() {
        for (bp in PERCENTAGES) {
            val number = written(bp, 2, symbols)
            for (text in listOf("$number %", "$number%", "%$number")) {
                assertEquals(
                    "$row «$text»",
                    NumberInput.Value(BasisPoints(bp)),
                    NumberInputParser.percent(text, symbols, BasisPoints.MAX_BULK_PERCENT),
                )
            }
        }
        assertEquals(
            NumberInput.TooManyDecimals(2),
            NumberInputParser.percent(
                written(12_345, 3, symbols) + "%",
                symbols,
                BasisPoints.MAX_BULK_PERCENT,
            ),
        )
    }

    private fun money(text: String, limit: Limit): NumberInput<Money> =
        NumberInputParser.money(text, symbols, decimals, limit)

    private fun value(minor: Long): NumberInput<Money> = NumberInput.Value(Money(minor))

    companion object {
        private val AMOUNTS: List<Long> =
            listOf(
                0L,
                1L,
                -1L,
                5L,
                1_234L,
                -1_234L,
                12_350L,
                123_456_789L,
                -123_456_789L,
                1_000_000_007L,
                Limit.UNIT_MONEY.max,
                -Limit.UNIT_MONEY.max,
                Limit.DOCUMENT_TOTAL.max,
                -Limit.DOCUMENT_TOTAL.max,
            )

        private val QUANTITIES: List<Long> =
            listOf(
                0L,
                1L,
                250L,
                333L,
                1_500L,
                -2_000L,
                Limit.LINE_QUANTITY_MILLI.max,
                Limit.ON_HAND_MILLI.max,
            )

        private val PERCENTAGES: List<Long> = listOf(0L, 5L, 1_250L, 2_100L, -2_000L, 99_999L)

        /** 10 elevado a [exponent], de 0 a 4: los decimales posibles de una moneda. */
        private fun pow10(exponent: Int): Long = (1..exponent).fold(1L) { power, _ -> power * 10 }

        /** El separador decimal que la región de [symbols] no usa. */
        private fun otherDecimal(symbols: NumberSymbols): Char =
            if (symbols.decimalSeparator == DecimalSeparator.COMMA.char) {
                DecimalSeparator.POINT.char
            } else {
                DecimalSeparator.COMMA.char
            }

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> = RegionalMatrix.withForeignDefaults()
    }
}
