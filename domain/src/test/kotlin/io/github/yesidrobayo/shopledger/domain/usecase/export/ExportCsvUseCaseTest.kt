package io.github.yesidrobayo.shopledger.domain.usecase.export

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.port.CsvExportPort
import io.github.yesidrobayo.shopledger.domain.port.CsvExportRequest
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** El formato de la exportación sale de la configuración regional guardada, nunca del teléfono. */
class ExportCsvUseCaseTest {
    private val naming =
        object : CsvNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String = systemKey
        }

    @Test
    fun `the cells follow the saved separator, decimals and zone`() = runTest {
        val regional =
            regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY).copy(csvSeparator = CsvSeparator.TAB)
        val settings = FakeAppSettingsRepository(appSettings(regional))
        val port = RecordingPort(CsvExport.Saved(files = 9, rows = 3L))
        val seen = mutableListOf<String>()
        val useCase =
            ExportCsvUseCase(settings, port) { tag ->
                seen += tag
                DecimalSeparator.COMMA
            }
        val progress = mutableListOf<CsvExportProgress>()

        val result = useCase("content://doc/1", naming) { progress += it }

        assertEquals(CsvExport.Saved(files = 9, rows = 3L), result)
        assertEquals(listOf("und"), seen)
        val request = requireNotNull(port.request)
        assertEquals("content://doc/1", request.documentUri)
        assertSame(naming, request.naming)
        val moment = Instant.parse("2026-11-12T15:00:00Z").toEpochMilliseconds()
        assertEquals(
            "12,34\t2026-11-12 10:00:00\r\n",
            request.rows.row(listOf(CsvCell.Amount(Money(1234L)), CsvCell.Moment(moment))),
        )
        assertEquals(listOf(CsvExportProgress(1, 9)), progress)
        assertNull(request.range)
    }

    @Test
    fun `a date range reaches the export`() = runTest {
        val settings =
            FakeAppSettingsRepository(appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)))
        val port = RecordingPort(CsvExport.Saved(files = 9, rows = 1L))
        val range =
            requireNotNull(
                CsvDateRange.of(
                    requireNotNull(BusinessDate.parse("2026-11-01")),
                    requireNotNull(BusinessDate.parse("2026-11-30")),
                ),
            )

        ExportCsvUseCase(settings, port) { DecimalSeparator.POINT }("uri", naming, range) {}

        assertEquals(range, requireNotNull(port.request).range)
    }

    @Test
    fun `a spreadsheet export carries its cells with the currency decimals`() = runTest {
        val settings =
            FakeAppSettingsRepository(appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)))
        val port = RecordingPort(CsvExport.Saved(files = 9, rows = 1L))

        ExportCsvUseCase(settings, port) { DecimalSeparator.COMMA }(
            "uri",
            naming,
            spreadsheet = true,
        ) {}

        val sheet = requireNotNull(requireNotNull(port.request).spreadsheet)
        // Con coma decimal en la región, la hoja recibe el número con punto.
        assertEquals(
            io.github.yesidrobayo.shopledger.domain.rule.export.SheetValue.Number("12.34"),
            sheet.cell(CsvCell.Amount(Money(1234L))),
        )
    }

    @Test
    fun `an unknown business zone does not export`() = runTest {
        val regional = regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)
        val settings =
            FakeAppSettingsRepository(
                appSettings(regional.copy(businessTimeZoneId = "Nowhere/Unknown")),
            )
        val port = RecordingPort(CsvExport.Saved(files = 9, rows = 0L))

        val result = ExportCsvUseCase(settings, port) { DecimalSeparator.POINT }("uri", naming) {}

        assertEquals(CsvExport.Failed, result)
        assertNull(port.request)
    }

    /** Recibe el pedido, avisa un archivo hecho y responde [result]. */
    private class RecordingPort(private val result: CsvExport) : CsvExportPort {
        var request: CsvExportRequest? = null

        override suspend fun export(
            request: CsvExportRequest,
            progress: (CsvExportProgress) -> Unit,
        ): CsvExport {
            this.request = request
            progress(CsvExportProgress(1, CsvExportTable.entries.size))
            return result
        }
    }
}
