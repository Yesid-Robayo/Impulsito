package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodTotals
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryAvailability
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryFile
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las cuentas del resumen de gestión, con los números del boceto de la pantalla: ventas 4.250.000,
 * ganancia en ventas 1.530.000 y ganancia final 805.000. Los montos son inventados.
 */
class ManagementSummaryRulesTest {
    private val october = ReportPeriod.Month(YearMonth(2026, 10))
    private val tax = TaxEstimate(collected = Money(510_000), paid = Money(120_000))
    private val methods =
        listOf(
            method("Efectivo inventado", PaymentMethodKind.CASH, 3_000_000),
            method("Tarjeta inventada", PaymentMethodKind.CARD, 1_760_000),
        )

    @Test
    fun `the figures are the same ones the statistics show for the period`() {
        val totals = sketchTotals()

        val summary = requireNotNull(
            ManagementSummaryRules.summaryOf(
                october,
                totals,
                tax,
                methods,
                PeriodNoticeCounts(2, 0, 0, 0, 0),
                SummaryAvailability.NONE,
            ).valueOrNull(),
        )

        val statistics = requireNotNull(PeriodMetricsCalculator.of(totals).valueOrNull())
        assertEquals(statistics.profit, summary.profit)
        assertEquals(Money(4_250_000), summary.profit.netSales)
        assertEquals(Money(1_530_000), summary.profit.grossProfit)
        // Gastos de 640.000, daños y pérdidas de 50.000 y costo de cobrar de 35.000.
        assertEquals(Money(725_000), summary.profit.totalExpenses)
        assertEquals(Money(805_000), summary.profit.netProfit)
        assertEquals(october, summary.period)
        assertSame(tax, summary.tax)
        assertEquals(methods, summary.methods)
        assertEquals(2L, summary.salesWithoutCost)
        assertTrue(summary.hasMovements)
    }

    @Test
    fun `a period with only purchases still has movements`() {
        val withPurchases = SummaryAvailability.NONE.copy(hasPurchases = true)

        val purchasesOnly = ManagementSummaryRules.summaryOf(
            october,
            PeriodTotals.ZERO,
            null,
            emptyList(),
            PeriodNoticeCounts.NONE,
            withPurchases,
        ).valueOrNull()
        val nothing = ManagementSummaryRules.summaryOf(
            october,
            PeriodTotals.ZERO,
            null,
            emptyList(),
            PeriodNoticeCounts.NONE,
            SummaryAvailability.NONE,
        ).valueOrNull()

        assertEquals(true, purchasesOnly?.hasMovements)
        assertEquals(false, nothing?.hasMovements)
        assertEquals(null, nothing?.tax)
    }

    @Test
    fun `a sum that does not fit has no summary`() {
        val absurd =
            PeriodTotals(
                sketchTotals().profit.copy(
                    salesNetTotal = Money(Long.MIN_VALUE),
                    returnsNetRefund = Money(1),
                ),
                salesCount = 1,
            )

        val result = ManagementSummaryRules.summaryOf(
            october,
            absurd,
            null,
            emptyList(),
            PeriodNoticeCounts.NONE,
            SummaryAvailability.NONE,
        )

        assertEquals(Checked.Overflow, result)
    }

    @Test
    fun `inventory and parties are offered only with data`() {
        assertEquals(
            listOf(SummaryContent.SALES_AND_PAYMENTS, SummaryContent.PURCHASES_AND_EXPENSES),
            ManagementSummaryRules.contentsOf(SummaryAvailability.NONE),
        )
        assertEquals(
            SummaryContent.entries,
            ManagementSummaryRules.contentsOf(SummaryAvailability(true, true, true)),
        )
        assertEquals(
            listOf(
                SummaryContent.SALES_AND_PAYMENTS,
                SummaryContent.PURCHASES_AND_EXPENSES,
                SummaryContent.CUSTOMERS_AND_SUPPLIERS,
            ),
            ManagementSummaryRules.contentsOf(SummaryAvailability(false, false, true)),
        )
    }

    @Test
    fun `sales and purchases start on, inventory and parties start off`() {
        assertEquals(
            setOf(SummaryContent.SALES_AND_PAYMENTS, SummaryContent.PURCHASES_AND_EXPENSES),
            ManagementSummaryRules.defaultChoice(SummaryContent.entries),
        )
        assertEquals(
            emptySet<SummaryContent>(),
            ManagementSummaryRules.defaultChoice(listOf(SummaryContent.CLOSING_INVENTORY)),
        )
    }

    @Test
    fun `each group brings its files in order, and nothing that is off or not offered`() {
        val all =
            ManagementSummaryRules.filesOf(SummaryContent.entries.toSet(), SummaryContent.entries)

        assertEquals(
            listOf(
                SummaryFile.Table(CsvExportTable.SALES),
                SummaryFile.Table(CsvExportTable.SALE_LINES),
                SummaryFile.Table(CsvExportTable.SALE_PAYMENTS),
                SummaryFile.Table(CsvExportTable.SALE_RETURNS),
                SummaryFile.Table(CsvExportTable.SALE_RETURN_LINES),
                SummaryFile.Table(CsvExportTable.PURCHASES),
                SummaryFile.Table(CsvExportTable.PURCHASE_LINES),
                SummaryFile.Table(CsvExportTable.PURCHASE_RETURNS),
                SummaryFile.Table(CsvExportTable.PURCHASE_RETURN_LINES),
                SummaryFile.Table(CsvExportTable.EXPENSES),
                SummaryFile.ClosingInventory,
                SummaryFile.Table(CsvExportTable.CUSTOMERS),
                SummaryFile.Table(CsvExportTable.SUPPLIERS),
            ),
            all,
        )
        // Encendido pero sin datos no entra, y apagado tampoco.
        val chosen =
            setOf(SummaryContent.CLOSING_INVENTORY, SummaryContent.CUSTOMERS_AND_SUPPLIERS)
        val offered =
            listOf(SummaryContent.SALES_AND_PAYMENTS, SummaryContent.CUSTOMERS_AND_SUPPLIERS)
        assertEquals(
            listOf(
                SummaryFile.Table(CsvExportTable.CUSTOMERS),
                SummaryFile.Table(CsvExportTable.SUPPLIERS),
            ),
            ManagementSummaryRules.filesOf(chosen, offered),
        )
        assertEquals(emptyList<SummaryFile>(), ManagementSummaryRules.filesOf(emptySet(), offered))
    }

    @Test
    fun `a period is in progress until its last day has passed`() {
        assertTrue(ManagementSummaryRules.inProgress(october, date(2026, 10, 31)))
        assertTrue(ManagementSummaryRules.inProgress(october, date(2026, 10, 1)))
        assertFalse(ManagementSummaryRules.inProgress(october, date(2026, 11, 1)))
    }

    @Test
    fun `the file stamp has only digits and dashes`() {
        assertEquals("2026-10", ManagementSummaryRules.fileStamp(october))
        val farBack = ReportPeriod.Month(YearMonth(987, 3))
        assertEquals("0987-03", ManagementSummaryRules.fileStamp(farBack))
        val oneDay = ReportPeriod.Range(date(2026, 10, 5), date(2026, 10, 5))
        assertEquals("2026-10-05", ManagementSummaryRules.fileStamp(oneDay))
        val fortnight = ReportPeriod.Range(date(2026, 10, 1), date(2026, 10, 15))
        assertEquals("2026-10-01_2026-10-15", ManagementSummaryRules.fileStamp(fortnight))
    }

    @Test
    fun `the screen shows the first ten methods and the file keeps all of them`() {
        val many = (1..12).map { index ->
            method("Método $index", PaymentMethodKind.OTHER, 1_000L - index)
        }

        val shown = ManagementSummaryRules.screenMethods(many)

        assertEquals(ManagementSummaryRules.SCREEN_METHODS, shown.size)
        assertEquals(many.take(10), shown)
        assertEquals(methods, ManagementSummaryRules.screenMethods(methods))
    }

    private fun sketchTotals() =
        PeriodTotals(
            ProfitTotals(
                salesNetTotal = Money(4_400_000),
                returnsNetRefund = Money(150_000),
                salesTotalCost = Money(2_800_000),
                returnsCostReturned = Money(80_000),
                recordedExpenses = Money(640_000),
                netShrinkage = Money(50_000),
                paymentFees = Money(35_000),
                cashRoundingTotal = Money.ZERO,
            ),
            salesCount = 42,
        )

    private fun date(year: Int, month: Int, day: Int) = BusinessDate(LocalDate(year, month, day))

    private fun method(name: String, kind: PaymentMethodKind, amount: Long) =
        PaymentMethodTotal(name, kind, salesCount = 1, amount = Money(amount), fees = Money.ZERO)
}
