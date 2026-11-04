package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El historial de precios solo crece: se agrega y se lee en el orden en que se registró, ninguna
 * escritura reemplaza una fila, y solo desaparece con su producto.
 */
@RunWith(AndroidJUnit4::class)
internal class PriceHistoryDaoTest : CatalogDatabaseTest() {
    private val history get() = database.priceHistoryDao()
    private val products get() = database.productDao()
    private var priceListId = 0L
    private var productId = 0L

    @Before
    fun createProductAndPriceList() = runBlocking {
        priceListId = insertPriceList("default")
        productId = products.insert(product("Café"))
    }

    @Test
    fun historyIsReadInTheOrderItWasRecordedNotByItsClock() = runBlocking {
        val initial =
            manualChange(
                productId,
                priceListId,
                oldPrice = 0,
                newPrice = 0,
                occurredAt = MUCH_LATER,
            )
                .copy(source = "INITIAL", oldPrice = null)
        // El reloj del teléfono retrocedió: la segunda fila dice ser anterior a la primera.
        val raise =
            manualChange(
                productId,
                priceListId,
                oldPrice = 9_000,
                newPrice = 9_500,
                occurredAt = CREATED_AT,
            )
        val otherProduct = products.insert(product("Té"))

        val initialId = history.insert(initial)
        val otherId = history.insert(manualChange(otherProduct, priceListId, 2_000, 2_200))
        val raiseId = history.insert(raise)

        val emissions = Emissions(this, history.observeByProduct(productId))
        try {
            emissions.awaitValue(listOf(initial.copy(id = initialId), raise.copy(id = raiseId)))

            // Una fila nueva llega al final, y la lista vuelve a emitir.
            val cut =
                manualChange(
                    productId,
                    priceListId,
                    oldPrice = 9_500,
                    newPrice = 9_200,
                    occurredAt = LATER,
                )
            val cutId = history.insert(cut)
            emissions.awaitValue(
                listOf(
                    initial.copy(id = initialId),
                    raise.copy(id = raiseId),
                    cut.copy(id = cutId),
                ),
            )
        } finally {
            emissions.stop()
        }
        assertEquals(1L, count("SELECT COUNT(*) FROM product_price_history WHERE id = $otherId"))
    }

    @Test
    fun everyBulkModeKeepsItsOwnRuleColumn() = runBlocking {
        val percent = bulk(PERCENT_BATCH, "PERCENT").copy(percentBp = -1_250)
        val amount = bulk(AMOUNT_BATCH, "AMOUNT").copy(amountDelta = -300)
        val margin = bulk(MARGIN_BATCH, "TARGET_MARGIN").copy(targetMarginBp = 4_000)
        val rate = bulk(RATE_BATCH, "REFERENCE_RATE").copy(fxRateMicro = 4_150_000_000)
        val undo =
            manualChange(productId, priceListId, oldPrice = 9_000, newPrice = 10_000)
                .copy(source = "BULK_UNDO", batchId = UNDO_BATCH)

        val stored = listOf(percent, amount, margin, rate, undo).map {
            it.copy(id = history.insert(it))
        }

        for (row in stored) {
            assertEquals(
                listOf(row),
                history.findByBatch(requireNotNull(row.batchId)),
            )
        }
    }

    @Test
    fun batchReadsOnlyItsRowsInRecordedOrder() = runBlocking {
        val tea = products.insert(product("Té"))
        val cocoa = products.insert(product("Cacao"))
        val first = history.insert(bulk(PERCENT_BATCH, "PERCENT", tea).copy(percentBp = 500))
        history.insert(bulk(AMOUNT_BATCH, "AMOUNT", productId).copy(amountDelta = 100))
        val second = history.insert(bulk(PERCENT_BATCH, "PERCENT", cocoa).copy(percentBp = 500))
        val third = history.insert(bulk(PERCENT_BATCH, "PERCENT", productId).copy(percentBp = 500))

        assertEquals(listOf(first, second, third), history.findByBatch(PERCENT_BATCH).map { it.id })
        assertEquals(emptyList<PriceHistoryEntity>(), history.findByBatch(UNDO_BATCH))
    }

    @Test
    fun insertingWithAnExistingIdFailsAndKeepsTheOriginal() = runBlocking {
        val original = manualChange(productId, priceListId, oldPrice = 9_000, newPrice = 9_500)
        val id = history.insert(original)

        // Un INSERT OR REPLACE borraría la fila y escribiría otra en su lugar.
        constraintFailure(UNIQUE) {
            history.insert(manualChange(productId, priceListId, 9_000, 1).copy(id = id))
        }

        assertEquals(listOf(original.copy(id = id)), rowsOf(productId))
    }

    @Test
    fun rowNeedsAnExistingProductAndPriceList() = runBlocking {
        constraintFailure(FOREIGN_KEY) {
            history.insert(manualChange(MISSING_ID, priceListId, 1_000, 1_100))
        }
        constraintFailure(FOREIGN_KEY) {
            history.insert(manualChange(productId, MISSING_ID, 1_000, 1_100))
        }

        assertEquals(0L, count("SELECT COUNT(*) FROM product_price_history"))
    }

    @Test
    fun priceListWithHistoryCannotBeDeleted() = runBlocking {
        history.insert(manualChange(productId, priceListId, 1_000, 1_100))

        constraintFailure(FOREIGN_KEY) {
            execute("DELETE FROM price_lists WHERE id = $priceListId")
        }

        assertEquals(1L, count("SELECT COUNT(*) FROM price_lists WHERE id = $priceListId"))
    }

    @Test
    fun deletingAProductDeletesOnlyItsOwnHistory() = runBlocking {
        val tea = products.insert(product("Té"))
        history.insert(manualChange(productId, priceListId, 1_000, 1_100))
        history.insert(manualChange(productId, priceListId, 1_100, 1_200))
        val teaRow = history.insert(manualChange(tea, priceListId, 800, 900))

        assertEquals(1, products.delete(productId))

        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM product_price_history WHERE product_id = $productId"),
        )
        assertEquals(emptyList<PriceHistoryEntity>(), rowsOf(productId))
        assertEquals(listOf(teaRow), rowsOf(tea).map { it.id })
    }

    @Test
    fun theDaoOnlyAppendsAndReads() {
        // Una función nueva que edite o borre el historial rompe esta prueba a propósito: cada
        // precio pasado explica una venta pasada, y no se reescribe.
        val functions = PriceHistoryDao::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(setOf("observeByProduct", "findByBatch", "insert"), functions)
    }

    private suspend fun rowsOf(product: Long): List<PriceHistoryEntity> =
        history.observeByProduct(product).first()

    private fun bulk(batchId: String, mode: String, product: Long = productId): PriceHistoryEntity =
        manualChange(product, priceListId, oldPrice = 10_000, newPrice = 9_000)
            .copy(source = "BULK", batchId = batchId, bulkMode = mode, roundingStep = 50)

    private companion object {
        const val MISSING_ID = 999L
        const val PERCENT_BATCH = "6f1c2a4e-0000-4000-8000-000000000001"
        const val AMOUNT_BATCH = "6f1c2a4e-0000-4000-8000-000000000002"
        const val MARGIN_BATCH = "6f1c2a4e-0000-4000-8000-000000000003"
        const val RATE_BATCH = "6f1c2a4e-0000-4000-8000-000000000004"
        const val UNDO_BATCH = "6f1c2a4e-0000-4000-8000-000000000005"
    }
}
