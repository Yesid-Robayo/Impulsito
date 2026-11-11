package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.purchase
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.repository.RoomTaxReportRepository
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxTotals
import io.github.yesidrobayo.shopledger.domain.rule.tax.TaxEstimateRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sumas del impuesto estimado de un período sobre la base real: solo documentos activos de su
 * período, cada uno con su propia fecha, una nueva emisión con cada venta, y la consulta por los
 * índices de fecha. Los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class TaxTotalsQueryTest : LedgerDatabaseTest() {
    private val sales get() = database.saleDao()
    private val reports by lazy { RoomTaxReportRepository(database) }

    private val november = ReportPeriod.Month(YearMonth(2026, 11))

    /** Una venta activa con [tax] de impuesto sobre un neto de 100.000, en [date]. */
    private suspend fun saleWithTax(date: String, tax: Long): Long = sales.insert(
        sale(priceListId, businessDate = date).copy(
            netTotal = 100_000,
            taxTotal = tax,
            total = 100_000 + tax,
            // Más la propina de 1.000 y el redondeo de -100 de la venta de prueba.
            amountDue = 100_000 + tax + 900,
        ),
    )

    /** Una devolución activa de [saleId] con [tax] de impuesto devuelto, en [date]. */
    private suspend fun returnWithTax(saleId: Long, date: String, tax: Long): Long =
        database.saleReturnDao().insert(
            saleReturn(saleId).copy(
                netRefund = 10_000,
                taxRefund = tax,
                totalRefund = 10_000 + tax,
                businessDate = date,
            ),
        )

    /** Una compra activa, pagada entera, con [tax] de impuesto sobre un neto de 100.000. */
    private suspend fun purchaseWithTax(date: String, tax: Long): Long =
        database.purchaseDao().insert(
            purchase(supplierId = null, businessDate = date).copy(
                netTotal = 100_000,
                taxTotal = tax,
                total = 100_000 + tax,
                costTotal = 100_000,
                amountPaid = 100_000 + tax,
            ),
        )

    @Test
    fun theWorkedExampleCountsOnlyActiveDocumentsOfThePeriod() = runBlocking {
        // 52.500 cobrados en dos ventas, 2.100 devueltos y 21.000 pagados en noviembre.
        val first = saleWithTax("2026-11-02", tax = 30_000)
        saleWithTax("2026-11-30", tax = 22_500)
        returnWithTax(first, "2026-11-20", tax = 2_100)
        purchaseWithTax("2026-11-05", tax = 21_000)
        // Lo anulado no cuenta.
        sales.markVoided(saleWithTax("2026-11-15", tax = 9_999), LATER, "ENTRY_ERROR", null)
        database.saleReturnDao().markVoided(
            returnWithTax(first, "2026-11-21", tax = 500),
            LATER,
            "ENTRY_ERROR",
            null,
        )
        database.purchaseDao().markVoided(
            purchaseWithTax("2026-11-06", tax = 4_444),
            LATER,
            "ENTRY_ERROR",
            null,
        )
        // Lo de otros meses tampoco, aunque la devolución sea de una venta de noviembre.
        saleWithTax("2026-10-31", tax = 7_777)
        saleWithTax("2026-12-01", tax = 8_888)
        returnWithTax(first, "2026-12-02", tax = 300)
        purchaseWithTax("2026-10-01", tax = 1_234)

        val totals = reports.observeTotals(november).first()

        assertEquals(TaxTotals(Money(52_500), Money(2_100), Money(21_000)), totals)
        assertEquals(
            TaxEstimate(collected = Money(50_400), paid = Money(21_000)),
            TaxEstimateRules.estimate(totals),
        )
        val day = ReportPeriod.Day(BusinessDate(LocalDate(2026, 11, 2)))
        assertEquals(
            TaxTotals(Money(30_000), Money.ZERO, Money.ZERO),
            reports.observeTotals(day).first(),
        )
    }

    @Test
    fun aPeriodWithoutDocumentsSumsZero() = runBlocking {
        assertEquals(TaxTotals.ZERO, reports.observeTotals(november).first())
    }

    @Test
    fun aNewSaleEmitsTheNewSums() = runBlocking {
        val emissions = Emissions(this, reports.observeTotals(november))
        try {
            emissions.awaitValue(TaxTotals.ZERO)
            saleWithTax("2026-11-10", tax = 1_900)
            emissions.awaitValue(TaxTotals(Money(1_900), Money.ZERO, Money.ZERO))
            purchaseWithTax("2026-11-11", tax = 500)
            emissions.awaitValue(TaxTotals(Money(1_900), Money.ZERO, Money(500)))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun eachSumSearchesItsTableByTheDateIndex() = runBlocking {
        // Con años de documentos, ninguna suma recorre su tabla entera. Es la misma sentencia de la
        // consulta.
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN $TAX_TOTALS_SQL") { statement ->
                    statement.bindText(1, "2026-11-01")
                    statement.bindText(2, "2026-11-30")
                    // La cuarta columna del plan es el detalle de cada paso.
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }
        for (table in listOf("sales", "sale_returns", "purchases")) {
            assertTrue(
                "Plan sin el índice de $table: $plan",
                plan.any { it.contains("USING INDEX index_${table}_status_business_date") },
            )
        }
    }
}
