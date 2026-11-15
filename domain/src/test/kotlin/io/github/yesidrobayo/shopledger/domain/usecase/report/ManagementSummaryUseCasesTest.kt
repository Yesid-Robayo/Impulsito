package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryAvailability
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryFile
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxTotals
import io.github.yesidrobayo.shopledger.domain.port.ManagementSummaryPackagePort
import io.github.yesidrobayo.shopledger.domain.port.ManagementSummaryRepository
import io.github.yesidrobayo.shopledger.domain.port.PeriodBreakdownRepository
import io.github.yesidrobayo.shopledger.domain.port.SummaryNaming
import io.github.yesidrobayo.shopledger.domain.port.SummaryPackageRequest
import io.github.yesidrobayo.shopledger.domain.port.TaxReportRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ObserveTaxEstimateUseCase
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Las cifras del resumen de gestión y el pedido de su paquete, sobre sumas en memoria. */
class ManagementSummaryUseCasesTest {
    private val reports = FakePeriodReportRepository()
    private val zone = requireNotNull(BusinessTimeZone.of("Etc/GMT+5"))
    private val calendar = BusinessCalendar(zone, DayOfWeek.MONDAY)
    private val october = ReportPeriod.Month(YearMonth(2026, 10))
    private val taxMode = MutableStateFlow(TaxMode.ADDED)
    private val taxSettings = object : TaxSettingsRepository {
        override fun observe(): Flow<TaxSettings> =
            taxMode.map { mode -> TaxSettings(mode, null, false) }

        override suspend fun get(): TaxSettings = TaxSettings(taxMode.value, null, false)

        override suspend fun update(taxes: TaxSettings, updatedAt: Instant) = Unit
    }
    private val taxReports = object : TaxReportRepository {
        override fun observeTotals(period: ReportPeriod): Flow<TaxTotals> =
            flowOf(TaxTotals(Money(530_000), Money(20_000), Money(120_000)))
    }
    private val breakdowns = FakeBreakdowns()
    private val availability =
        MutableStateFlow(
            SummaryAvailability(hasPurchases = true, hasInventory = true, hasParties = false),
        )
    private val summaries = ManagementSummaryRepository { availability }

    private fun observe() = ObserveManagementSummaryUseCase(
        reports,
        ObserveTaxEstimateUseCase(taxSettings, taxReports),
        breakdowns,
        summaries,
    )

    @Test
    fun `the summary reads the statistics sums, the tax, the methods and the notices`() = runTest {
        reports.totals.value = mapOf(october to totals(net = 4_250_000, cost = 2_720_000))

        val summary = requireNotNull(observe()(october, calendar).first())

        assertEquals(Money(4_250_000), summary.profit.netSales)
        assertEquals(Money(1_530_000), summary.profit.grossProfit)
        val tax = TaxEstimate(collected = Money(510_000), paid = Money(120_000))
        assertEquals(tax, summary.tax)
        assertEquals(breakdowns.methods, summary.methods)
        assertEquals(3L, summary.salesWithoutCost)
        assertEquals(
            listOf(
                SummaryContent.SALES_AND_PAYMENTS,
                SummaryContent.PURCHASES_AND_EXPENSES,
                SummaryContent.CLOSING_INVENTORY,
            ),
            summary.contents,
        )
        // Octubre termina el 1 de noviembre a las 00:00 en la zona del negocio, 05:00 en UTC.
        assertEquals(Instant.parse("2026-11-01T05:00:00Z"), breakdowns.askedEnd)
    }

    @Test
    fun `without tax in the business the summary has no tax row`() = runTest {
        taxMode.value = TaxMode.NONE

        val summary = requireNotNull(observe()(october, calendar).first())

        assertNull(summary.tax)
    }

    @Test
    fun `a sum that does not fit gives no summary instead of failing`() = runTest {
        reports.totals.value = mapOf(october to totals(net = Long.MIN_VALUE, refund = 1))

        assertNull(observe()(october, calendar).first())
    }

    @Test
    fun `the package follows the saved region and carries the chosen files of the period`() =
        runTest {
            val regional =
                regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)
                    .copy(csvSeparator = CsvSeparator.TAB)
            val settings = FakeAppSettingsRepository(appSettings(regional))
            val port = RecordingPort(CsvExport.Saved(files = 6, rows = 10L))
            val seen = mutableListOf<String>()
            val create = CreateManagementSummaryUseCase(settings, port) { tag ->
                seen += tag
                DecimalSeparator.COMMA
            }
            val progress = mutableListOf<CsvExportProgress>()

            val result = create(
                period = october,
                chosen =
                    setOf(SummaryContent.SALES_AND_PAYMENTS, SummaryContent.CLOSING_INVENTORY),
                offered = SummaryContent.entries,
                target = SummaryTarget("/cache/exports/summary.zip", "summary.pdf", "/work/s.pdf"),
                naming = NAMING,
                progress = progress::add,
            )

            assertEquals(CsvExport.Saved(files = 6, rows = 10L), result)
            assertEquals(listOf("und"), seen)
            val request = requireNotNull(port.request)
            assertEquals("/cache/exports/summary.zip", request.targetPath)
            assertEquals("summary.pdf", request.pdfName)
            assertEquals("/work/s.pdf", request.pdfPath)
            assertSame(NAMING, request.naming)
            assertEquals(date(2026, 10, 1), request.range.from)
            assertEquals(date(2026, 10, 31), request.range.to)
            assertEquals(6, request.files.size)
            assertEquals(SummaryFile.Table(CsvExportTable.SALES), request.files.first())
            assertEquals(SummaryFile.ClosingInventory, request.files.last())
            assertEquals("12,34\r\n", request.rows.row(listOf(CsvCell.Amount(Money(1234L)))))
            assertEquals(listOf(CsvExportProgress(1, 6)), progress)
        }

    @Test
    fun `an unknown business zone does not create the package`() = runTest {
        val regional = regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)
        val settings =
            FakeAppSettingsRepository(
                appSettings(regional.copy(businessTimeZoneId = "Nowhere/Unknown")),
            )
        val port = RecordingPort(CsvExport.Saved(files = 1, rows = 0L))

        val result = CreateManagementSummaryUseCase(settings, port) { DecimalSeparator.POINT }(
            october,
            SummaryContent.entries.toSet(),
            SummaryContent.entries,
            SummaryTarget("a.zip", "b.pdf", "c.pdf"),
            NAMING,
        ) {}

        assertEquals(CsvExport.Failed, result)
        assertNull(port.request)
    }

    private class FakeBreakdowns : PeriodBreakdownRepository {
        val methods =
            listOf(
                PaymentMethodTotal(
                    "Efectivo inventado",
                    PaymentMethodKind.CASH,
                    3,
                    Money(4_000_000),
                    Money.ZERO,
                ),
            )
        var askedEnd: Instant? = null

        override fun observeProductSales(period: ReportPeriod): Flow<List<ProductSales>> =
            flowOf(emptyList())

        override fun observePaymentMethods(period: ReportPeriod): Flow<List<PaymentMethodTotal>> =
            flowOf(methods)

        override fun observeExpenseCategories(
            period: ReportPeriod,
        ): Flow<List<ExpenseCategoryTotal>> = flowOf(emptyList())

        override fun observeNoticeCounts(
            period: ReportPeriod,
            periodEnd: Instant,
        ): Flow<PeriodNoticeCounts> {
            askedEnd = periodEnd
            return flowOf(PeriodNoticeCounts(3, 0, 0, 0, 0))
        }
    }

    /** Recibe el pedido, avisa un archivo hecho de seis y responde [result]. */
    private class RecordingPort(private val result: CsvExport) : ManagementSummaryPackagePort {
        var request: SummaryPackageRequest? = null

        override suspend fun write(
            request: SummaryPackageRequest,
            progress: (CsvExportProgress) -> Unit,
        ): CsvExport {
            this.request = request
            progress(CsvExportProgress(1, request.files.size))
            return result
        }
    }

    private fun totals(net: Long = 0, refund: Long = 0, cost: Long = 0) = PeriodTotals(
        ProfitTotals(
            salesNetTotal = Money(net),
            returnsNetRefund = Money(refund),
            salesTotalCost = Money(cost),
            returnsCostReturned = Money.ZERO,
            recordedExpenses = Money.ZERO,
            netShrinkage = Money.ZERO,
            paymentFees = Money.ZERO,
            cashRoundingTotal = Money.ZERO,
        ),
        salesCount = 1,
    )

    private fun date(year: Int, month: Int, day: Int) = BusinessDate(LocalDate(year, month, day))

    private companion object {
        val NAMING = object : SummaryNaming {
            override fun fileName(table: CsvExportTable): String = "${table.key}.csv"

            override fun header(column: String): String = column

            override fun seedName(table: SeedTable, systemKey: String): String = systemKey

            override fun closingInventoryFileName(): String = "closing_inventory.csv"
        }
    }
}
