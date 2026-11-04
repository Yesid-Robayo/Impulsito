package io.github.yesidrobayo.shopledger.data.local.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.customer.customer
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.sale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las cotizaciones: cada columna en su lugar, las abiertas de la más nueva a la más vieja, una sola
 * conversión o cancelación, y llaves que impiden borrar una convertida o un producto cotizado. Una
 * cotización sin convertir se borra con sus líneas.
 */
@RunWith(AndroidJUnit4::class)
internal class QuoteDaoTest : LedgerDatabaseTest() {
    private val quotes get() = database.quoteDao()

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        // El cliente y la unidad existen: la unidad 3 es una de las iniciales.
        val customerId = database.customerDao().insert(customer("Ana"))
        val full =
            quote(businessDate = "2026-01-15").copy(
                customerId = customerId,
                validUntil = null,
                netTotal = 42_017,
                taxTotal = 7_983,
                total = 50_000,
                note = "Precio por volumen",
                createdAt = LATER,
                updatedAt = MUCH_LATER,
            )
        val id = quotes.insert(full)
        val first = quoteLine(id, productId).copy(unitId = 3, taxNameSnapshot = null)
        val second = quoteLine(id, productId)
        val firstId = quotes.insertLine(first)
        val secondId = quotes.insertLine(second)

        assertEquals(full.copy(id = id), quotes.findById(id))
        assertEquals(
            listOf(first.copy(id = firstId), second.copy(id = secondId)),
            quotes.findLines(id),
        )
        assertNull(quotes.findById(MISSING_ID))
    }

    @Test
    fun openQuotesComeNewestFirstAndLeaveWhenConvertedOrCancelled() = runBlocking {
        val older = quotes.insert(quote(businessDate = "2026-01-01"))
        val newer = quotes.insert(quote(businessDate = "2026-01-20"))
        val sameDay = quotes.insert(quote(businessDate = "2026-01-20"))
        val saleId = database.saleDao().insert(sale(priceListId))

        val emissions = Emissions(this, quotes.observeOpen().ids { it.id })
        try {
            emissions.awaitValue(listOf(sameDay, newer, older))

            assertEquals(1, quotes.markConverted(newer, saleId, updatedAt = LATER))
            emissions.awaitValue(listOf(sameDay, older))

            assertEquals(1, quotes.markCancelled(older, updatedAt = LATER))
            emissions.awaitValue(listOf(sameDay))
        } finally {
            emissions.stop()
        }
        // Una sola vez: ni se convierte de nuevo ni se cancela después.
        assertEquals(0, quotes.markConverted(newer, saleId, updatedAt = MUCH_LATER))
        assertEquals(0, quotes.markCancelled(newer, updatedAt = MUCH_LATER))
        assertEquals(0, quotes.markConverted(older, saleId, updatedAt = MUCH_LATER))
        assertEquals("CONVERTED", quotes.findById(newer)?.status)
        assertEquals(saleId, quotes.findById(newer)?.convertedSaleId)
    }

    @Test
    fun editingRewritesTheTotalsAndLinesNeedAnOpenQuote() = runBlocking {
        val id = quotes.insert(quote())
        val lineId = quotes.insertLine(quoteLine(id, productId))

        assertEquals(1, quotes.deleteLine(lineId))
        assertEquals(1, quotes.update(id, null, "2026-02-15", 0, 0, 0, "Sin líneas", LATER))
        assertEquals(
            quote().copy(
                id = id,
                validUntil = "2026-02-15",
                netTotal = 0,
                taxTotal = 0,
                total = 0,
                note = "Sin líneas",
                updatedAt = LATER,
            ),
            quotes.findById(id),
        )

        assertEquals(1, quotes.markCancelled(id, updatedAt = LATER))
        guardFailure("guard:quote_lines") { quotes.insertLine(quoteLine(id, productId)) }
        // Los totales siempre cuadran: neto más impuesto.
        guardFailure("guard:quotes") { quotes.update(id, null, null, 1, 0, 2, null, MUCH_LATER) }
    }

    @Test
    fun aQuoteWithoutSaleIsDeletedWithItsLinesAndAConvertedOneIsNot() = runBlocking {
        val open = quotes.insert(quote())
        quotes.insertLine(quoteLine(open, productId))
        assertEquals(1, quotes.delete(open))
        assertEquals(emptyList<QuoteLineEntity>(), quotes.findLines(open))

        val converted = quotes.insert(quote())
        val saleId = database.saleDao().insert(sale(priceListId).copy(quoteId = converted))
        assertEquals(1, quotes.markConverted(converted, saleId, updatedAt = LATER))
        constraintFailure(FOREIGN_KEY) { quotes.delete(converted) }
        assertEquals(0, quotes.delete(MISSING_ID))

        // Una venta no apunta a una cotización que no existe.
        constraintFailure(FOREIGN_KEY) {
            database.saleDao().insert(sale(priceListId).copy(quoteId = MISSING_ID))
        }
    }

    @Test
    fun aQuotedProductOrCustomerIsNotDeletedAndALineNeedsItsQuote() = runBlocking {
        val customerId = database.customerDao().insert(customer("Ana"))
        val id = quotes.insert(quote().copy(customerId = customerId))
        quotes.insertLine(quoteLine(id, productId))

        constraintFailure(FOREIGN_KEY) { database.productDao().delete(productId) }
        constraintFailure(FOREIGN_KEY) { database.customerDao().delete(customerId) }
        constraintFailure(FOREIGN_KEY) { quotes.insertLine(quoteLine(id, MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            quotes.insertLine(quoteLine(id, productId).copy(unitId = MISSING_ID))
        }
        // Una unidad cotizada está en uso, y no se borra.
        val box = requireNotNull(database.unitDao().findBySystemKey("BOX")).id
        assertEquals(false, database.unitDao().isInUse(box))
        quotes.insertLine(quoteLine(id, productId).copy(unitId = box))
        assertEquals(true, database.unitDao().isInUse(box))
        constraintFailure(FOREIGN_KEY) { database.unitDao().delete(box) }
        constraintFailure(FOREIGN_KEY) { quotes.insert(quote().copy(customerId = MISSING_ID)) }
    }

    @Test
    fun keysAndIndexesAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf(
                "converted_sale_id -> sales SET NULL",
                "customer_id -> customers RESTRICT",
            ),
            database.foreignKeysOf("quotes"),
        )
        assertEquals(
            listOf(
                "product_id -> products RESTRICT",
                "quote_id -> quotes CASCADE",
                "unit_id -> units RESTRICT",
            ),
            database.foreignKeysOf("quote_lines"),
        )
        assertEquals(
            listOf(
                "index_quotes_converted_sale_id (converted_sale_id)",
                "index_quotes_customer_id (customer_id)",
                "index_quotes_status_business_date (status, business_date)",
            ),
            database.indexesOf("quotes"),
        )
        assertEquals(
            listOf(
                "index_quote_lines_product_id (product_id)",
                "index_quote_lines_quote_id (quote_id)",
                "index_quote_lines_unit_id (unit_id)",
            ),
            database.indexesOf("quote_lines"),
        )
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
