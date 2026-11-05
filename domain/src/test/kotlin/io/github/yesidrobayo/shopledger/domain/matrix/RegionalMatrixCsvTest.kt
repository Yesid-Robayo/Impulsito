package io.github.yesidrobayo.shopledger.domain.matrix

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
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
 * El CSV de cada fila de la matriz: el separador de columnas con que nace, el separador decimal de
 * su región y los decimales de su moneda. Lo que exporta vuelve a los mismos enteros al leerlo con
 * esos símbolos, como lo leería una hoja de cálculo de la misma región.
 */
@RunWith(Parameterized::class)
class RegionalMatrixCsvTest(private val row: RegionalMatrixRow, defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    private val settings = row.regionalSettings()
    private val decimalSeparator = row.regionOr(RegionalMatrix.INVENTED_DEVICE).decimalSeparator
    private val csv = CsvFormat(settings.csvSeparator, decimalSeparator, row.currencyDecimals)

    @Test
    fun `the columns are born with semicolons where the comma is the decimal separator`() {
        val expected =
            when (decimalSeparator) {
                DecimalSeparator.COMMA -> CsvSeparator.SEMICOLON
                DecimalSeparator.POINT -> CsvSeparator.COMMA
            }
        assertEquals(expected, settings.csvSeparator)
        // Así ninguna celda numérica necesita comillas.
        assertEquals(
            "12${decimalSeparator.char}34",
            CsvFormat(expected, decimalSeparator, 2).moneyCell(Money(1_234)),
        )
    }

    @Test
    fun `the documented amount is written with the decimals of the currency`() {
        val point = decimalSeparator.char
        val expected =
            when (row.currencyDecimals) {
                0 -> "1234"

                2 -> "12${point}34"

                3 -> "1${point}234"

                4 -> "0${point}1234"

                else -> error(
                    "Falta en esta prueba el ejemplo de ${row.currencyDecimals} decimales",
                )
            }
        assertEquals(expected, csv.moneyCell(Money(1_234)))
        assertEquals("-$expected", csv.moneyCell(Money(-1_234)))
    }

    @Test
    fun `quantities always carry three decimals with the separator of the region`() {
        assertEquals("2${decimalSeparator.char}000", csv.quantityCell(QuantityMilli(2_000)))
        assertEquals("-0${decimalSeparator.char}250", csv.quantityCell(QuantityMilli(-250)))
    }

    @Test
    fun `with comma columns in a comma region the numbers with decimals go in quotes`() {
        val commaColumns = CsvFormat(CsvSeparator.COMMA, decimalSeparator, row.currencyDecimals)
        val cell = commaColumns.moneyCell(Money(1_234))
        val quoted = decimalSeparator == DecimalSeparator.COMMA && row.currencyDecimals > 0
        assertEquals("$row «$cell»", quoted, cell.startsWith('"') && cell.endsWith('"'))
    }

    @Test
    fun `what the export writes reads back to the same integers`() {
        // Una hoja de cálculo de la región lee el separador decimal, sin grupos ni símbolo.
        val symbols =
            NumberSymbols(
                decimalSeparator = decimalSeparator.char,
                minusSign = NumberSymbols.HYPHEN_MINUS,
                grouping = null,
            )
        for (minor in AMOUNTS) {
            val cell = csv.moneyCell(Money(minor)).removeSurrounding("\"")
            assertEquals(
                "$row «$cell»",
                NumberInput.Value(Money(minor)),
                NumberInputParser.money(cell, symbols, row.currencyDecimals, Limit.DOCUMENT_TOTAL),
            )
        }
        for (milli in listOf(0L, 1L, 250L, -2_000L, Limit.ON_HAND_MILLI.max)) {
            val cell = csv.quantityCell(QuantityMilli(milli)).removeSurrounding("\"")
            assertEquals(
                "$row «$cell»",
                NumberInput.Value(QuantityMilli(milli)),
                NumberInputParser.quantity(cell, symbols, Limit.ON_HAND_MILLI),
            )
        }
    }

    companion object {
        private val AMOUNTS: List<Long> =
            listOf(
                0L,
                1L,
                -1L,
                1_234L,
                -1_234L,
                12_350L,
                Limit.UNIT_MONEY.max,
                -Limit.DOCUMENT_TOTAL.max,
            )

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> = RegionalMatrix.withForeignDefaults()
    }
}
