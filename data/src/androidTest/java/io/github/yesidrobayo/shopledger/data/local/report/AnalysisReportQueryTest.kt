package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.customer.customer
import io.github.yesidrobayo.shopledger.data.local.movement
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.data.repository.RoomAnalysisReportRepository
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProduct
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que el análisis de `Números` lee de la base real: los productos con stock y sin salidas por
 * venta sin anular en el rango, y los clientes por lo vendido sin impuesto en sus ventas activas.
 * Cada valor esperado está escrito a mano. Nombres y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class AnalysisReportQueryTest : LedgerDatabaseTest() {
    private val products get() = database.productDao()
    private val ledger get() = database.stockMovementDao()
    private val analysis by lazy { RoomAnalysisReportRepository(database) }

    /** Un producto vigente con [onHand] milésimas en stock que valen [value]. */
    private suspend fun stocked(name: String, onHand: Long, value: Long): Long =
        products.insert(product(name).copy(onHandMilli = onHand, stockValue = value))

    /** Una salida por venta de [productId] en [date], y el id de la fila y de su línea. */
    private suspend fun soldOn(productId: Long, date: String): Pair<Long, Long> {
        val saleId = database.saleDao().insert(sale(priceListId, businessDate = date))
        val lineId = database.saleLineDao().insert(saleLine(saleId, productId, priceListId))
        val movementId = ledger.insert(
            movement(productId, "SALE", -1_000, 1_000, -1_000, 0, 1_000, 0).copy(
                saleLineId = lineId,
                businessDate = date,
            ),
        )
        return movementId to lineId
    }

    /** Una venta activa de [net] sin impuesto, del cliente [customerId], en [date]. */
    private suspend fun saleOf(customerId: Long?, net: Long, date: String = TODAY): Long =
        database.saleDao().insert(
            sale(priceListId, businessDate = date).copy(
                netTotal = net,
                taxTotal = 0,
                total = net,
                orderAdjustment = 0,
                tipAmount = 0,
                cashRoundingAmount = 0,
                amountDue = net,
                totalCost = 0,
                totalReplacementCost = 0,
                customerId = customerId,
            ),
        )

    private fun day(text: String) = ReportPeriod.Day(BusinessDate(LocalDate.parse(text)))

    @Test
    fun idleProductsHaveStockAndNoUncompensatedSaleInTheRange() = runBlocking {
        val tea = stocked("Té verde", onHand = 4_000, value = 8_000)
        val yerba = stocked("Yerba", onHand = 2_000, value = 9_000)
        val sugar = stocked("Azúcar", onHand = 1_000, value = 9_000)
        // Vendido hoy: se movió.
        soldOn(stocked("Arroz", onHand = 5_000, value = 20_000), TODAY)
        // Vendido solo ayer: hoy está quieto.
        soldOn(tea, YESTERDAY)
        // Vendido hoy y anulado: su compensatorio lo deja quieto.
        val (voided, voidedLine) = soldOn(yerba, TODAY)
        ledger.insert(
            movement(yerba, "SALE_VOID", 1_000, 1_000, 1_000, 0, 1_000, 0).copy(
                reversesMovementId = voided,
                saleLineId = voidedLine,
                businessDate = TODAY,
            ),
        )
        // Sin stock, archivado o insumo: nunca están quietos.
        products.insert(product("Sal"))
        products.insert(
            product("Harina vieja", archivedAt = CREATED_AT)
                .copy(onHandMilli = 1_000, stockValue = 50_000),
        )
        products.insert(
            product("Levadura").copy(onHandMilli = 1_000, stockValue = 70_000, usage = "INPUT"),
        )

        val idle = analysis.observeIdleProducts(day(TODAY), limit = 2).first()

        // Del de más valor al de menos, y un empate por id: la yerba antes que el azúcar.
        assertEquals(
            listOf(
                IdleProduct(yerba, "Yerba", UNIT_ID, QuantityMilli(2_000), Money(9_000)),
                IdleProduct(sugar, "Azúcar", UNIT_ID, QuantityMilli(1_000), Money(9_000)),
            ),
            idle.products,
        )
        assertEquals(3L, idle.total)
        val all = analysis.observeIdleProducts(day(TODAY), limit = 10).first()
        assertEquals(listOf(yerba, sugar, tea), all.products.map { it.productId })
    }

    @Test
    fun idleProductsLookForSalesByTheLedgerIndex() = runBlocking {
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN $IDLE_PRODUCTS_SQL") { statement ->
                    statement.bindText(1, TODAY)
                    statement.bindText(2, TODAY)
                    statement.bindLong(3, 10)
                    // La cuarta columna del plan es el detalle de cada paso.
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }

        assertTrue(
            "Plan sin el índice: $plan",
            plan.any { it.contains("USING INDEX index_stock_movements_type_business_date") },
        )
        assertTrue(
            "Plan sin el índice de compensación: $plan",
            plan.any { it.contains("index_stock_movements_reverses_movement_id") },
        )
    }

    @Test
    fun topCustomersAddTheirActiveSalesOfTheRangeWithoutTax() = runBlocking {
        val customers = database.customerDao()
        val ana = customers.insert(customer("Cliente Ana"))
        val beto = customers.insert(customer("Cliente Beto"))
        val ciro = customers.insert(customer("Cliente Ciro"))
        saleOf(ana, 3_000)
        saleOf(ana, 4_000)
        saleOf(beto, 7_000)
        saleOf(ciro, 2_000)
        // Ni una venta sin cliente, ni una anulada, ni una de ayer cuentan.
        saleOf(null, 90_000)
        val voided = saleOf(ciro, 50_000)
        database.saleDao().markVoided(voided, CREATED_AT, "ENTRY_ERROR", null)
        saleOf(ciro, 60_000, date = YESTERDAY)

        assertEquals(
            listOf(
                // Un empate por lo vendido va por id: Ana antes que Beto.
                CustomerSales(ana, "Cliente Ana", 2, Money(7_000)),
                CustomerSales(beto, "Cliente Beto", 1, Money(7_000)),
            ),
            analysis.observeTopCustomers(day(TODAY), limit = 2).first(),
        )
        assertEquals(
            CustomerSales(ciro, "Cliente Ciro", 1, Money(2_000)),
            analysis.observeTopCustomers(day(TODAY), limit = 10).first().last(),
        )
    }

    private companion object {
        const val YESTERDAY = "2026-11-01"
        const val TODAY = "2026-11-02"
    }
}
