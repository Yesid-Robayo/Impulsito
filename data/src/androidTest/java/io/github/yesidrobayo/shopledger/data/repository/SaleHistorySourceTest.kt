package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.payment.cardPayment
import io.github.yesidrobayo.shopledger.data.local.sale.SALE_HISTORY_PAGE_SQL
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El historial de ventas sobre la base real: los tramos no repiten ni saltan ventas que comparten la
 * hora, los filtros de fecha, método y anuladas, lo vendido por día sin anuladas, y la consulta que
 * recorre las ventas por su índice. Ventas inventadas, con las filas de las demás pruebas.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleHistorySourceTest : RepositoryTest() {
    private val sales by lazy { RoomSaleRepository(database) }
    private val history by lazy { RoomSaleHistorySource(database) }

    private val earlier = requireNotNull(BusinessDate.parse("2025-12-30"))

    /** Una venta de una línea con fecha [date] y hora [at]; devuelve su id. */
    private suspend fun sell(date: BusinessDate = DATE, at: Instant = AT): Long = commit {
        val saleId = sales.insert(newSale(priceListId, businessDate = date, at = at))
        sales.insertLine(saleId, newSaleLine(productId, priceListId))
        saleId
    }

    @Test
    fun eightSalesAtTheSameTimeComeInPagesOfFourWithoutRepeatingOrSkipping() = runBlocking {
        // Ventas atrasadas: todas a las 12:00 de su día, con la misma hora guardada.
        val ids = List(8) { sell() }
        val first = history.page(SaleHistoryFilter(), after = null, limit = 4)
        val second = history.page(SaleHistoryFilter(), after = first.last().cursor, limit = 4)
        val third = history.page(SaleHistoryFilter(), after = second.last().cursor, limit = 4)

        assertEquals(ids.reversed(), (first + second).map { it.id })
        assertEquals(emptyList<Long>(), third.map { it.id })
        val row = first.first()
        assertEquals("Café", row.firstProductName)
        assertEquals(1, row.lineCount)
        assertEquals(0, row.paymentCount)
    }

    @Test
    fun filtersAndDayTotalsLeaveVoidedSalesOut() = runBlocking {
        val old = sell(date = earlier)
        val today = sell()
        val voided = sell()
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        commit { database.salePaymentDao().insert(cardPayment(today, cash)) }
        commit { sales.markVoided(voided, Voiding(LATER_AT, VoidReason.ENTRY_ERROR, null)) }

        val all = history.page(SaleHistoryFilter(), null, 10)
        assertEquals(listOf(voided, today, old), all.map { it.id })
        assertTrue(all.first().voided)
        assertEquals(
            setOf(today, voided),
            history.page(SaleHistoryFilter(from = DATE, to = DATE), null, 10).map { it.id }.toSet(),
        )
        assertEquals(
            listOf(today),
            history.page(SaleHistoryFilter(paymentMethodId = cash), null, 10).map { it.id },
        )
        assertEquals(
            listOf(voided),
            history.page(SaleHistoryFilter(onlyVoided = true), null, 10).map { it.id },
        )
        assertEquals(
            mapOf(DATE to Money(11_900L), earlier to Money(11_900L)),
            history.dayTotals(SaleHistoryFilter(), setOf(DATE, earlier)),
        )
        val none = history.dayTotals(SaleHistoryFilter(), emptySet())
        assertEquals(emptyMap<BusinessDate, Money>(), none)
    }

    @Test
    fun theMarkChangesWithANewSaleAndWithAVoid() = runBlocking {
        history.observeChanges().test {
            val empty = awaitItem()
            val saleId = sell()
            val withSale = awaitItem()
            commit { sales.markVoided(saleId, Voiding(LATER_AT, VoidReason.ENTRY_ERROR, null)) }
            val voided = awaitItem()
            assertEquals(3, setOf(empty, withSale, voided).size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aPageWalksTheSalesByTheirTimeIndex() = runBlocking {
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN $SALE_HISTORY_PAGE_SQL") { statement ->
                    // Sin filtros, después de una venta cualquiera, un tramo de 50. Los parámetros
                    // se numeran por su primera aparición en la sentencia.
                    statement.bindLong(1, 0)
                    statement.bindNull(2)
                    statement.bindNull(3)
                    statement.bindNull(4)
                    statement.bindLong(5, LATER)
                    statement.bindLong(6, 1)
                    statement.bindLong(7, 50)
                    // El padre de cada paso, y su detalle: un paso de primer nivel tiene padre 0.
                    buildList {
                        while (statement.step()) add(statement.getLong(1) to statement.getText(3))
                    }
                }
            }
        val top = plan.filter { (parent, _) -> parent == 0L }.map { it.second }
        assertTrue("Plan sin el índice: $plan", top.any { "index_sales_occurred_at_id" in it })
        // El tramo sale en el orden del índice; solo la subconsulta del primer producto ordena sus
        // pocas líneas.
        assertTrue("Plan que ordena aparte: $plan", top.none { "TEMP B-TREE FOR ORDER BY" in it })
    }
}
