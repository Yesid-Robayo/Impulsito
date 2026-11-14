package io.github.yesidrobayo.shopledger.domain.rule.export

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportFileName
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las celdas de la hoja exportada: números con punto desde el entero guardado, texto neutralizado y
 * fechas ISO en la zona del negocio. Los valores esperados están escritos a mano.
 */
class SheetCellWriterTest {
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val writer = SheetCellWriter(currencyDecimals = 2, zone = zone)

    @Test
    fun `numbers are numeric cells written from the stored integer`() {
        assertEquals(SheetValue.Number("12.34"), writer.cell(CsvCell.Amount(Money(1_234L))))
        assertEquals(SheetValue.Number("-50.00"), writer.cell(CsvCell.Amount(Money(-5_000L))))
        val quantity = CsvCell.Quantity(QuantityMilli(2_500L))
        assertEquals(SheetValue.Number("2.500"), writer.cell(quantity))
        assertEquals(SheetValue.Number("12.50"), writer.cell(CsvCell.Percent(BasisPoints(1_250L))))
        assertEquals(SheetValue.Number("30"), writer.cell(CsvCell.Count(30L)))
    }

    @Test
    fun `text never becomes a formula and a numeric code stays text`() {
        assertEquals(SheetValue.Text("'=1+1"), writer.cell(CsvCell.Text("=1+1")))
        assertEquals(SheetValue.Text("Arroz"), writer.cell(CsvCell.Text("Arroz")))
        assertEquals(SheetValue.Text("0012"), writer.cell(CsvCell.Code("0012")))
        assertEquals(SheetValue.Empty, writer.cell(CsvCell.Empty))
    }

    @Test
    fun `dates and times are iso text in the business zone`() {
        // 2026-11-12 15:00 UTC son las 10:00 a cinco horas al oeste.
        val moment = Instant.parse("2026-11-12T15:00:00Z").toEpochMilliseconds()

        assertEquals(SheetValue.Text("2026-11-12"), writer.cell(CsvCell.Day("2026-11-12")))
        assertEquals(
            SheetValue.Text("2026-11-12 10:00:00"),
            writer.cell(CsvCell.Moment(moment)),
        )
        assertEquals(SheetValue.Text("10:00:00"), writer.cell(CsvCell.TimeOfDay(moment)))
    }

    @Test
    fun `the spreadsheet file name ends in xlsx`() {
        val at = Instant.parse("2026-11-12T15:00:00Z")

        assertEquals(
            "App-export-20261112-1000.xlsx",
            CsvExportFileName.spreadsheetOf("App", at, zone),
        )
    }
}
