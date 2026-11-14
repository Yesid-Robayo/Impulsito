package io.github.yesidrobayo.shopledger.domain.rule.export

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Las líneas del CSV exportado, con la tabla de fórmulas de los requisitos de seguridad y los valores
 * esperados escritos a mano. Corre con un idioma y una zona del equipo que no son los de ningún caso.
 */
@RunWith(Parameterized::class)
class CsvRowWriterTest(defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val semicolons =
        CsvRowWriter(CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.COMMA, 2), zone)

    @Test
    fun `text that starts like a formula becomes text`() {
        // La tabla de la neutralización de fórmulas, entrada y celda.
        assertText("=1+1", "\"'=1+1\"")
        assertText("@SUM(A1:A9)", "\"'@SUM(A1:A9)\"")
        assertText("-2+3", "\"'-2+3\"")
        assertText("＝1+1", "\"'＝1+1\"")
        assertText("  =1+1", "\"'  =1+1\"")
        assertText("=HYPERLINK(\"http://x\",\"y\")", "\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"")
        assertText("Arroz; 5 kg", "\"Arroz; 5 kg\"")
    }

    @Test
    fun `every formula start is neutralized`() {
        listOf('+', '\t', '\r', '\n', '＋', '－', '＠').forEach { start ->
            assertEquals("'${start}x", CsvText.neutralized("${start}x"))
        }
    }

    @Test
    fun `ordinary text and blanks are left as they are`() {
        assertEquals("Maçã ñandú", CsvText.neutralized("Maçã ñandú"))
        assertEquals("a=1", CsvText.neutralized("a=1"))
        assertEquals("", CsvText.neutralized(""))
        assertEquals("   ", CsvText.neutralized("   "))
        assertText("", "\"\"")
    }

    @Test
    fun `a line break inside a name stays inside its quotes`() {
        assertText("Pan\nde maíz", "\"Pan\nde maíz\"")
    }

    @Test
    fun `numbers are never neutralized nor quoted`() {
        assertEquals("-5000,00", semicolons.cell(CsvCell.Amount(Money(-500_000L))))
        assertEquals("-0,250", semicolons.cell(CsvCell.Quantity(QuantityMilli(-250L))))
        assertEquals("-7", semicolons.cell(CsvCell.Count(-7L)))
        assertEquals("19,00", semicolons.cell(CsvCell.Percent(BasisPoints(1_900L))))
    }

    @Test
    fun `a number with its own decimals comes from the stored integer`() {
        // El saldo de una cuenta en una moneda sin decimales y una cotización en millonésimas.
        assertEquals("1234", semicolons.cell(CsvCell.Scaled(1_234L, 0)))
        assertEquals("-12,340", semicolons.cell(CsvCell.Scaled(-12_340L, 3)))
        assertEquals("4000,500000", semicolons.cell(CsvCell.Scaled(4_000_500_000L, 6)))
        assertEquals("0,000001", semicolons.cell(CsvCell.Scaled(1L, 6)))
        val commas = CsvRowWriter(CsvFormat(CsvSeparator.COMMA, DecimalSeparator.COMMA, 2), zone)
        assertEquals("\"0,5\"", commas.cell(CsvCell.Scaled(5L, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a number cannot have more decimals than a rate`() {
        CsvCell.Scaled(1L, CsvCell.Scaled.MAX_DECIMALS + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a scaled cell cannot have negative decimals`() {
        CsvCell.Scaled(1L, -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a number cannot have negative decimals`() {
        CsvFormat(CsvSeparator.SEMICOLON, DecimalSeparator.COMMA, 2).scaledCell(1L, -1)
    }

    @Test
    fun `dates and times are iso in the business zone`() {
        val afternoon = Instant.parse("2026-11-12T15:04:05Z").toEpochMilliseconds()
        assertEquals("2026-11-12 10:04:05", semicolons.cell(CsvCell.Moment(afternoon)))
        assertEquals("10:04:05", semicolons.cell(CsvCell.TimeOfDay(afternoon)))
        // A las 3 de la madrugada en UTC, el negocio sigue en el día anterior.
        val early = Instant.parse("2026-11-12T03:00:00Z").toEpochMilliseconds()
        assertEquals("2026-11-11 22:00:00", semicolons.cell(CsvCell.Moment(early)))
        assertEquals("2026-11-12", semicolons.cell(CsvCell.Day("2026-11-12")))
    }

    @Test
    fun `a stored day without its form is written as text`() {
        assertEquals("\"'=1+1\"", semicolons.cell(CsvCell.Day("=1+1")))
        assertEquals("\"12/11/2026\"", semicolons.cell(CsvCell.Day("12/11/2026")))
    }

    @Test
    fun `a code made of digits stays text in a spreadsheet`() {
        assertEquals("\"'0123456789012\"", semicolons.cell(CsvCell.Code("0123456789012")))
        assertEquals("\"'2100000000001\"", semicolons.cell(CsvCell.Code("2100000000001")))
        // Con letras ya es texto para la hoja: sin marca, pero con la neutralización de fórmulas.
        assertEquals("\"ABC-123\"", semicolons.cell(CsvCell.Code("ABC-123")))
        assertEquals("\"(01)234\"", semicolons.cell(CsvCell.Code("(01)234")))
        assertEquals("\"'=1+1\"", semicolons.cell(CsvCell.Code("=1+1")))
        assertEquals("\"\"", semicolons.cell(CsvCell.Code("")))
    }

    @Test
    fun `an empty cell is empty`() {
        assertEquals("", semicolons.cell(CsvCell.Empty))
    }

    @Test
    fun `a row joins its cells with the separator and ends with crlf`() {
        val row =
            semicolons.row(
                listOf(
                    CsvCell.Count(1L),
                    CsvCell.Text("=1+1"),
                    CsvCell.Empty,
                    CsvCell.Amount(Money(1234L)),
                ),
            )
        assertEquals("1;\"'=1+1\";;12,34\r\n", row)
    }

    @Test
    fun `comma columns quote decimal comma numbers and keep text quoted`() {
        val commas = CsvRowWriter(CsvFormat(CsvSeparator.COMMA, DecimalSeparator.COMMA, 2), zone)
        val row = commas.row(listOf(CsvCell.Text("Arroz, 5 kg"), CsvCell.Amount(Money(1234L))))
        assertEquals("\"Arroz, 5 kg\",\"12,34\"\r\n", row)
    }

    @Test
    fun `tab columns use the tab`() {
        val tabs = CsvRowWriter(CsvFormat(CsvSeparator.TAB, DecimalSeparator.POINT, 0), zone)
        assertEquals("\"a\"\t5\r\n", tabs.row(listOf(CsvCell.Text("a"), CsvCell.Amount(Money(5L)))))
    }

    @Test
    fun `the header quotes and neutralizes its names`() {
        assertEquals("\"sale_id\";\"date\"\r\n", semicolons.header(listOf("sale_id", "date")))
        assertEquals("\"'-x\"\r\n", semicolons.header(listOf("-x")))
    }

    @Test
    fun `the byte order mark is the utf-8 one`() {
        assertEquals(listOf(0xFEFF), CsvRowWriter.BYTE_ORDER_MARK.map { it.code })
        assertEquals("\r\n", CsvRowWriter.LINE_END)
    }

    private fun assertText(input: String, expected: String) {
        assertEquals(expected, semicolons.cell(CsvCell.Text(input)))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> = ForeignSystemDefaults.ALL
    }
}
