package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.repository.RoomCatalogPresenceSource
import io.github.yesidrobayo.shopledger.data.repository.RoomSalesReportRepository
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesTotals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sumas de ventas de un período sobre la base real, las que muestra Inicio: solo documentos
 * activos de su período, cada uno con su propia fecha, una nueva emisión con cada venta, y la consulta
 * por los índices de fecha. Los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SalesTotalsQueryTest : LedgerDatabaseTest() {
    private val sales get() = database.saleDao()
    private val returns get() = database.saleReturnDao()
    private val reports by lazy { RoomSalesReportRepository(database) }

    private val today = ReportPeriod.Day(BusinessDate(LocalDate(2026, 11, 2)))

    /** Una venta activa de [net] sin impuesto que costó [cost], en [date]. */
    private suspend fun saleOf(date: String, net: Long, cost: Long): Long = sales.insert(
        sale(priceListId, businessDate = date).copy(
            netTotal = net,
            taxTotal = 0,
            total = net,
            totalCost = cost,
            orderAdjustment = 0,
            tipAmount = 0,
            cashRoundingAmount = 0,
            amountDue = net,
        ),
    )

    /** Una devolución activa de [saleId] por [net] sin impuesto, que devolvió [cost], en [date]. */
    private suspend fun returnOf(saleId: Long, date: String, net: Long, cost: Long): Long =
        returns.insert(
            saleReturn(saleId).copy(
                netRefund = net,
                taxRefund = 0,
                totalRefund = net,
                costReturned = cost,
                businessDate = date,
            ),
        )

    @Test
    fun onlyActiveDocumentsOfTheDayCountEachWithItsOwnDate() = runBlocking {
        val first = saleOf("2026-11-02", net = 40_000, cost = 25_000)
        saleOf("2026-11-02", net = 12_500, cost = 8_000)
        // Una devolución de hoy resta hoy, aunque su venta sea de ayer.
        val yesterday = saleOf("2026-11-01", net = 9_999, cost = 1_111)
        returnOf(yesterday, "2026-11-02", net = 2_500, cost = 1_000)
        // Lo anulado no cuenta, ni en las sumas ni en el número de ventas.
        sales.markVoided(saleOf("2026-11-02", net = 7_777, cost = 777), LATER, "ENTRY_ERROR", null)
        returns.markVoided(
            returnOf(first, "2026-11-02", net = 300, cost = 100),
            LATER,
            "ENTRY_ERROR",
            null,
        )
        // Lo de otros días tampoco.
        saleOf("2026-11-03", net = 8_888, cost = 888)
        returnOf(first, "2026-11-03", net = 1_000, cost = 500)

        assertEquals(
            SalesTotals(
                salesNetTotal = Money(52_500),
                returnsNetRefund = Money(2_500),
                salesTotalCost = Money(33_000),
                returnsCostReturned = Money(1_000),
                salesCount = 2,
            ),
            reports.observeTotals(today).first(),
        )
    }

    @Test
    fun aDayWithoutDocumentsSumsZero() = runBlocking {
        assertEquals(SalesTotals.ZERO, reports.observeTotals(today).first())
    }

    @Test
    fun aNewSaleAndAVoidEmitTheNewSums() = runBlocking {
        val emissions = Emissions(this, reports.observeTotals(today))
        try {
            emissions.awaitValue(SalesTotals.ZERO)
            val id = saleOf("2026-11-02", net = 10_000, cost = 6_000)
            emissions.awaitValue(
                SalesTotals(Money(10_000), Money.ZERO, Money(6_000), Money.ZERO, 1),
            )
            sales.markVoided(id, LATER, "ENTRY_ERROR", null)
            emissions.awaitValue(SalesTotals.ZERO)
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun eachSumSearchesItsTableByTheDateIndex() = runBlocking {
        // Con años de ventas, ninguna suma recorre su tabla entera. Es la misma sentencia de la
        // consulta.
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN $SALES_TOTALS_SQL") { statement ->
                    statement.bindText(1, "2026-11-02")
                    statement.bindText(2, "2026-11-02")
                    // La cuarta columna del plan es el detalle de cada paso.
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }
        for (table in listOf("sales", "sale_returns")) {
            assertTrue(
                "Plan sin el índice de $table: $plan",
                plan.any {
                    it.contains("USING") &&
                        it.contains("index_${table}_status_business_date")
                },
            )
        }
        assertFalse(
            "Una suma recorre su tabla entera: $plan",
            plan.any { it.startsWith("SCAN") && !it.contains("CONSTANT ROW") },
        )
    }

    @Test
    fun theCatalogIsEmptyUntilTheFirstProductAndStaysWithAnArchivedOne() = runBlocking {
        val presence = RoomCatalogPresenceSource(database)
        // La base de estas pruebas ya trae un producto: se borra para empezar vacía.
        database.productDao().delete(productId)
        val emissions = Emissions(this, presence.observeHasProducts())
        try {
            emissions.awaitValue(false)
            val id = database.productDao().insert(product("Té"))
            emissions.awaitValue(true)
            database.productDao().setArchivedAt(id, LATER, LATER)
            assertTrue(presence.observeHasProducts().first())
        } finally {
            emissions.stop()
        }
    }
}
