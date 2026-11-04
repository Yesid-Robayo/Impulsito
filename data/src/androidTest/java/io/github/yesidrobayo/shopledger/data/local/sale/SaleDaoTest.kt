package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.cash.openSession
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.customer.customer
import io.github.yesidrobayo.shopledger.data.local.functionsOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccount
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.data.local.stock.location
import io.github.yesidrobayo.shopledger.domain.model.Limit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las ventas y sus líneas: cada columna en su lugar y entera, montos con signo donde el documento
 * los admite, la lista de un período de la más nueva a la más antigua y por su índice, una
 * anulación que toca solo sus columnas y una sola vez, y líneas que solo se agregan, en su orden,
 * con el mismo producto repetido si hace falta.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleDaoTest : LedgerDatabaseTest() {
    private val sales get() = database.saleDao()
    private val lines get() = database.saleLineDao()

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen. Un recargo
        // en toda la venta y un redondeo de efectivo hacia arriba. El cliente, la sesión de caja y
        // la ubicación ya tienen tabla y llave: la venta apunta a unos que existen, con un id propio.
        val customerId = database.customerDao().insert(customer("Ana").copy(id = 8))
        val accountId = database.moneyAccountDao().insert(moneyAccount("Caja chica"))
        val sessionId = database.cashSessionDao().insert(openSession(accountId).copy(id = 5))
        database.locationDao().insert(location("Puesto de feria", kind = "STALL").copy(id = 2))
        val header =
            SaleEntity(
                status = "ACTIVE",
                netTotal = 42_017,
                taxTotal = 7_983,
                total = 50_000,
                totalCost = 21_500,
                totalReplacementCost = 23_100,
                orderAdjustment = 1_500,
                tipAmount = 2_000,
                cashRoundingAmount = 50,
                amountDue = 52_050,
                customerId = customerId,
                priceListId = priceListId,
                locationId = 2,
                cashSessionId = sessionId,
                quoteId = 6,
                orderId = 7,
                note = "Entrega en la tarde",
                occurredAt = CREATED_AT,
                createdAt = LATER,
                businessDate = "2026-01-15",
                voidedAt = null,
                voidReason = null,
                voidNote = null,
            )
        val saleId = sales.insert(header)
        val line =
            SaleLineEntity(
                saleId = saleId,
                productId = productId,
                unitId = 3,
                unitFactorMilli = 6_000,
                enteredQuantityMilli = 2_000,
                quantityMilli = 12_000,
                unitPrice = 24_250,
                unitCost = 1_792,
                lineDiscount = 0,
                orderAdjustmentShare = 1_500,
                netAmount = 42_017,
                taxRateBp = 1_900,
                taxNameSnapshot = "Tasa general",
                taxAmount = 7_983,
                lineTotal = 50_000,
                lineCost = 21_500,
                unitReplacementCost = 1_925,
                lineReplacementCost = 23_100,
                productNameSnapshot = "Café en grano",
                stockEffect = "DIRECT",
                parentLineId = null,
                priceListId = priceListId,
            )
        val lineId = lines.insert(line)

        assertEquals(header.copy(id = saleId), sales.findById(saleId))
        assertEquals(listOf(line.copy(id = lineId)), lines.findBySale(saleId))
        assertNull(sales.findById(MISSING_ID))
    }

    @Test
    fun sameProductRepeatsInASaleWithAnotherUnitOrAsAnExtra() = runBlocking {
        val saleId = sales.insert(sale(priceListId))
        val main = saleLine(saleId, productId, priceListId)
        val mainId = lines.insert(main)
        // El mismo producto en la misma venta: por caja, y como extra de la primera línea. El
        // índice (sale_id, product_id) no es único, así que ninguna choca.
        val byBoxId = lines.insert(
            main.copy(unitId = 2, unitFactorMilli = 12_000, quantityMilli = 24_000),
        )
        val extraId = lines.insert(main.copy(parentLineId = mainId))

        val stored = lines.findBySale(saleId)

        assertEquals(listOf(mainId, byBoxId, extraId), stored.map { it.id })
        assertEquals(setOf(productId), stored.map { it.productId }.toSet())
        assertEquals(mainId, stored.last().parentLineId)
    }

    @Test
    fun periodListIsNewestFirstWithBothEndsIncludedAndUsesItsIndex() = runBlocking {
        val before = sales.insert(sale(priceListId, businessDate = "2025-12-31"))
        val firstDay = sales.insert(sale(priceListId, businessDate = "2026-01-01"))
        val lastDay = sales.insert(sale(priceListId, businessDate = "2026-01-31"))
        // Con un reloj que dice antes, pero registrada después en la misma fecha de negocio.
        val sameLastDay =
            sales.insert(
                sale(priceListId, businessDate = "2026-01-31", occurredAt = CREATED_AT - 1),
            )
        // Registrada después, pero con una fecha anterior: el orden es por fecha de negocio.
        val middle = sales.insert(sale(priceListId, businessDate = "2026-01-10"))
        sales.insert(sale(priceListId, businessDate = "2026-02-01"))

        val emissions = Emissions(this, sales.observeIn("2026-01-01", "2026-01-31").ids { it.id })
        try {
            emissions.awaitValue(listOf(sameLastDay, lastDay, middle, firstDay))

            // Una anulada sigue en la lista, y la lista vuelve a emitir con una venta nueva.
            sales.markVoided(middle, voidedAt = LATER, voidReason = "UNDO", voidNote = null)
            val newest = sales.insert(sale(priceListId, businessDate = "2026-01-20"))
            emissions.awaitValue(listOf(sameLastDay, lastDay, newest, middle, firstDay))
        } finally {
            emissions.stop()
        }
        assertEquals(
            listOf(before),
            sales.observeIn("2025-12-31", "2025-12-31").first().map { it.id },
        )
        // Con años de ventas, la lista del período no recorre la tabla: busca por el índice. Es la
        // misma sentencia de observeIn.
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN $SALES_IN_PERIOD_SQL") { statement ->
                    statement.bindText(1, "2026-01-01")
                    statement.bindText(2, "2026-01-31")
                    // La cuarta columna del plan es el detalle de cada paso.
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }
        assertTrue(
            "Plan sin el índice: $plan",
            plan.any { it.contains("USING INDEX index_sales_status_business_date") },
        )
    }

    @Test
    fun voidingChangesOnlyItsColumnsAndOnlyOnce() = runBlocking {
        val id = sales.insert(sale(priceListId))
        val line = saleLine(id, productId, priceListId)
        val lineId = lines.insert(line)
        val active = requireNotNull(sales.findById(id))

        val emissions = Emissions(this, sales.observeById(id))
        try {
            emissions.awaitValue(active)
            assertEquals(1, sales.markVoided(id, LATER, "CUSTOMER_RETURN", "Devolvió todo"))
            val voided =
                active.copy(
                    status = "VOIDED",
                    voidedAt = LATER,
                    voidReason = "CUSTOMER_RETURN",
                    voidNote = "Devolvió todo",
                )
            emissions.awaitValue(voided)

            // Un documento se anula una sola vez: la segunda anulación no cambia nada.
            assertEquals(0, sales.markVoided(id, MUCH_LATER, "OTHER", null))
            assertEquals(voided, sales.findById(id))
        } finally {
            emissions.stop()
        }
        assertEquals(0, sales.markVoided(MISSING_ID, LATER, "ENTRY_ERROR", null))
        // Las líneas guardan el precio y el costo con que se vendió, también anulada la venta.
        assertEquals(listOf(line.copy(id = lineId)), lines.findBySale(id))
    }

    @Test
    fun linesKeepTheirSavedOrderAndEmitAgain() = runBlocking {
        val id = sales.insert(sale(priceListId))
        val otherId = sales.insert(sale(priceListId))
        val tea = database.productDao().insert(product("Té"))
        val firstId = lines.insert(saleLine(id, tea, priceListId))
        lines.insert(saleLine(otherId, productId, priceListId))
        val secondId = lines.insert(saleLine(id, productId, priceListId))

        val emissions = Emissions(this, lines.observeBySale(id).ids { it.id })
        try {
            emissions.awaitValue(listOf(firstId, secondId))

            val thirdId = lines.insert(saleLine(id, tea, priceListId))
            emissions.awaitValue(listOf(firstId, secondId, thirdId))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun keysRejectOrphansAndProtectWhatASaleUses() = runBlocking {
        constraintFailure(FOREIGN_KEY) { sales.insert(sale(MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            lines.insert(saleLine(MISSING_ID, productId, priceListId))
        }
        val id = sales.insert(sale(priceListId))
        // Un producto que no existe lo rechaza la guarda, antes que la llave: la línea pide un
        // producto cuyo modo de stock concuerde con su efecto.
        guardFailure("guard:sale_lines") { lines.insert(saleLine(id, MISSING_ID, priceListId)) }
        constraintFailure(FOREIGN_KEY) { lines.insert(saleLine(id, productId, MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            lines.insert(saleLine(id, productId, priceListId, parentLineId = MISSING_ID))
        }
        lines.insert(saleLine(id, productId, priceListId))

        // Con ventas, el producto se archiva y la lista de precios no se borra.
        constraintFailure(FOREIGN_KEY) { database.productDao().delete(productId) }
        constraintFailure(FOREIGN_KEY) {
            execute("DELETE FROM price_lists WHERE id = $priceListId")
        }
        assertEquals(1L, count("SELECT COUNT(*) FROM sale_lines"))
    }

    @Test
    fun moneyIsExactAtItsLimitsAndKeepsItsSign() = runBlocking {
        // El descuento de toda la venta y el redondeo del efectivo en su mínimo, con signo, y lo
        // demás en su tope. Lo que se cobra cuadra: total, propina y redondeo.
        val header =
            sale(priceListId).copy(
                netTotal = Limit.DOCUMENT_TOTAL.max - 1,
                taxTotal = 1,
                total = Limit.DOCUMENT_TOTAL.max,
                totalCost = Limit.DOCUMENT_TOTAL.max,
                totalReplacementCost = Limit.DOCUMENT_TOTAL.max,
                orderAdjustment = -Limit.DOCUMENT_TOTAL.max,
                tipAmount = Limit.UNIT_MONEY.max,
                cashRoundingAmount = -Limit.UNIT_MONEY.max,
                amountDue = Limit.DOCUMENT_TOTAL.max,
            )
        val id = sales.insert(header)
        val line =
            saleLine(id, productId, priceListId).copy(
                unitFactorMilli = Limit.LINE_QUANTITY_MILLI.max,
                enteredQuantityMilli = Limit.LINE_QUANTITY_MILLI.max,
                quantityMilli = Limit.LINE_QUANTITY_MILLI.max,
                unitPrice = Limit.UNIT_MONEY.max,
                unitCost = Limit.UNIT_MONEY.max,
                lineDiscount = Limit.DOCUMENT_TOTAL.max,
                orderAdjustmentShare = -Limit.DOCUMENT_TOTAL.max,
                netAmount = Limit.DOCUMENT_TOTAL.max - 1,
                taxRateBp = 10_000,
                taxAmount = 1,
                lineTotal = Limit.DOCUMENT_TOTAL.max,
                lineCost = Limit.DOCUMENT_TOTAL.max,
                unitReplacementCost = Limit.UNIT_MONEY.max,
                lineReplacementCost = Limit.DOCUMENT_TOTAL.max,
            )
        val lineId = lines.insert(line)

        assertEquals(header.copy(id = id), sales.findById(id))
        assertEquals(listOf(line.copy(id = lineId)), lines.findBySale(id))
        val signedIntegers =
            count(
                "SELECT COUNT(*) FROM sales s JOIN sale_lines l ON l.sale_id = s.id " +
                    "WHERE typeof(s.order_adjustment) = 'integer' AND s.order_adjustment < 0 " +
                    "AND typeof(s.cash_rounding_amount) = 'integer' " +
                    "AND s.cash_rounding_amount < 0 " +
                    "AND typeof(s.amount_due) = 'integer' AND typeof(l.unit_price) = 'integer' " +
                    "AND typeof(l.order_adjustment_share) = 'integer' " +
                    "AND l.order_adjustment_share < 0",
            )
        assertEquals(1L, signedIntegers)
    }

    @Test
    fun insertingWithAnExistingIdFailsAndKeepsTheOriginal() = runBlocking {
        val id = sales.insert(sale(priceListId))
        val original = saleLine(id, productId, priceListId)
        val lineId = lines.insert(original)

        // Un INSERT OR REPLACE borraría la fila y escribiría otra en su lugar. La guarda rechaza
        // el id repetido antes que la llave primaria, también con REPLACE.
        guardFailure("guard:sale_lines") {
            lines.insert(original.copy(id = lineId, unitPrice = 1))
        }
        guardFailure("guard:sales") { sales.insert(sale(priceListId).copy(id = id, total = 1)) }

        assertEquals(listOf(original.copy(id = lineId)), lines.findBySale(id))
        assertEquals(11_900L, sales.findById(id)?.total)
    }

    @Test
    fun theDaosOnlyVoidTheHeaderAndOnlyAppendLines() {
        // Una función nueva que edite una venta, o que edite o borre una línea, rompe esta prueba
        // a propósito: una venta confirmada solo se anula, y su línea congela precio y costo.
        assertEquals(
            setOf("observeIn", "observeById", "findById", "insert", "markVoided"),
            functionsOf(SaleDao::class.java),
        )
        assertEquals(
            setOf("observeBySale", "findBySale", "insert"),
            functionsOf(SaleLineDao::class.java),
        )
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
