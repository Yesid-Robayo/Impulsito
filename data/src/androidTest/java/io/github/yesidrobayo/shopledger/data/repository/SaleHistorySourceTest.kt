package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.payment.cardPayment
import io.github.yesidrobayo.shopledger.data.local.payment.cashPayment
import io.github.yesidrobayo.shopledger.data.local.sale.SALE_HISTORY_METHOD_PAGE_SQL
import io.github.yesidrobayo.shopledger.data.local.sale.SALE_HISTORY_PAGE_SQL
import io.github.yesidrobayo.shopledger.data.local.sale.SaleHistoryRowEntity
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El historial de ventas sobre la base real: los tramos no repiten ni saltan ventas que comparten la
 * hora, los filtros de fecha, método y anuladas, lo vendido por día sin anuladas, y la consulta que
 * recorre las ventas por su índice. Con filtro de método, las dos formas de leer un tramo dan las
 * mismas filas, y la que parte de los pagos no recorre las ventas. Ventas inventadas, con las filas
 * de las demás pruebas.
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

    @Test
    fun bothWaysOfReadingAMethodGiveTheSameRowsInTheSameOrder() = runBlocking {
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val card = count("SELECT id FROM payment_methods WHERE system_key = 'CARD'")
        // Tres ventas atrasadas a la misma hora, dos de días anteriores y una anulada; una cobra
        // dos veces con tarjeta, otra mezcla métodos y otra no usa tarjeta.
        val sameTime = List(3) { sell() }
        val older =
            sell(date = earlier, at = Instant.fromEpochMilliseconds(AT.toEpochMilliseconds() - DAY))
        val oldest =
            sell(
                date = earlier,
                at = Instant.fromEpochMilliseconds(AT.toEpochMilliseconds() - 2 * DAY),
            )
        val dao = database.salePaymentDao()
        commit {
            dao.insert(cardPayment(sameTime[0], card))
            dao.insert(cardPayment(sameTime[0], card))
            dao.insert(cardPayment(sameTime[1], card))
            dao.insert(cashPayment(sameTime[1], cash))
            dao.insert(cashPayment(sameTime[2], cash))
            dao.insert(cardPayment(older, card))
            dao.insert(cardPayment(oldest, card))
        }
        commit { sales.markVoided(older, Voiding(LATER_AT, VoidReason.ENTRY_ERROR, null)) }

        val filters = listOf(
            Triple<String?, String?, Boolean>(null, null, false),
            Triple(null, null, true),
            Triple(earlier.toString(), earlier.toString(), false),
            Triple(DATE.toString(), null, false),
        )
        for (methodId in listOf(card, cash)) {
            for ((from, to, voided) in filters) {
                val walked = allPages { after -> scanPage(methodId, from, to, voided, after) }
                val direct = allPages { after -> methodPage(methodId, from, to, voided, after) }
                assertEquals("Método $methodId con $from, $to, $voided", walked, direct)
            }
        }
        assertEquals(
            listOf(sameTime[1], sameTime[0], older, oldest),
            allPages { after -> methodPage(card, null, null, false, after) }.map { it.id },
        )
    }

    @Test
    fun onlyMethodsThatChargedASaleAreOffered() = runBlocking {
        val cash = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val card = count("SELECT id FROM payment_methods WHERE system_key = 'CARD'")
        assertEquals(emptySet<Long>(), history.methodsWithSales(setOf(cash, card)))
        val saleId = sell()
        commit { database.salePaymentDao().insert(cashPayment(saleId, cash)) }

        assertEquals(setOf(cash), history.methodsWithSales(setOf(cash, card)))
        assertEquals(emptySet<Long>(), history.methodsWithSales(emptySet()))
    }

    @Test
    fun aMethodPageStartsFromThePaymentsOfThatMethod() = runBlocking {
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared(
                    "EXPLAIN QUERY PLAN $SALE_HISTORY_METHOD_PAGE_SQL",
                ) { statement ->
                    // Método 1, sin más filtros, después de una venta cualquiera, un tramo de 50.
                    statement.bindLong(1, 1)
                    statement.bindLong(2, 0)
                    statement.bindNull(3)
                    statement.bindNull(4)
                    statement.bindLong(5, LATER)
                    statement.bindLong(6, 1)
                    statement.bindLong(7, 50)
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }
        assertTrue(
            "Plan sin el índice del método: $plan",
            plan.any { "index_sale_payments_payment_method_id_sale_id" in it },
        )
        // Cada venta del método se busca por su id: ninguna se recorre sin pagos de ese método.
        assertTrue(
            "Plan sin búsqueda por id: $plan",
            plan.any {
                "SEARCH s USING INTEGER PRIMARY KEY" in
                    it
            },
        )
        assertFalse("Plan que recorre las ventas: $plan", plan.any { it.startsWith("SCAN s") })
    }

    private suspend fun scanPage(
        methodId: Long,
        from: String?,
        to: String?,
        voided: Boolean,
        after: SaleHistoryRowEntity?,
    ) = database.saleDao().historyPage(from, to, methodId, voided, after?.occurredAt, after?.id, 2)

    private suspend fun methodPage(
        methodId: Long,
        from: String?,
        to: String?,
        voided: Boolean,
        after: SaleHistoryRowEntity?,
    ) = database.saleDao().historyPageOfMethod(
        methodId,
        from,
        to,
        voided,
        after?.occurredAt,
        after?.id,
        2,
    )

    /** Todos los tramos de dos filas, uno tras otro, hasta el primero que llega incompleto. */
    private suspend fun allPages(
        page: suspend (SaleHistoryRowEntity?) -> List<SaleHistoryRowEntity>,
    ): List<SaleHistoryRowEntity> {
        val rows = mutableListOf<SaleHistoryRowEntity>()
        while (true) {
            val next = page(rows.lastOrNull())
            rows += next
            if (next.size < 2) return rows
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
