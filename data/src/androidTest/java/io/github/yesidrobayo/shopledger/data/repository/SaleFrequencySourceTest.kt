package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.sale.PRODUCT_LINE_COUNTS_SQL
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los más vendidos sobre la base real: cuenta líneas de ventas activas desde una fecha, deja de
 * contar una venta al anularla y busca por los índices, sin recorrer el historial. Ventas y productos
 * inventados, con las filas de las demás pruebas de repositorios.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleFrequencySourceTest : RepositoryTest() {
    private val sales by lazy { RoomSaleRepository(database) }
    private val frequency by lazy { RoomSaleFrequencySource(database) }

    private val before = requireNotNull(BusinessDate.parse("2025-12-31"))

    /** Una venta de una línea de [product] con fecha [date]; devuelve su id. */
    private suspend fun sell(product: Long, date: BusinessDate = DATE): Long = commit {
        val saleId = sales.insert(newSale(priceListId, businessDate = date))
        sales.insertLine(saleId, newSaleLine(product, priceListId))
        saleId
    }

    @Test
    fun countsLinesOfActiveSalesSinceTheDateAndForgetsAVoidedSale() = runBlocking {
        val other = database.productDao().insert(product("Té inventado"))

        frequency.observeLineCounts(DATE).test {
            assertEquals(emptyMap<Long, Int>(), awaitItem())
            sell(productId)
            assertEquals(mapOf(productId to 1), awaitItem())
            sell(productId)
            assertEquals(mapOf(productId to 2), awaitItem())
            // Una venta anterior a la fecha no cuenta.
            sell(other, date = before)
            val voided = sell(other)
            assertEquals(mapOf(productId to 2, other to 1), awaitItem())
            commit { sales.markVoided(voided, Voiding(LATER_AT, VoidReason.ENTRY_ERROR, null)) }
            assertEquals(mapOf(productId to 2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            mapOf(productId to 2, other to 1),
            frequency.observeLineCounts(before).first(),
        )
    }

    @Test
    fun theCountSearchesSalesByStatusAndDateWithItsIndex() = runBlocking {
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN $PRODUCT_LINE_COUNTS_SQL") { statement ->
                    statement.bindText(1, "2026-01-01")
                    // La cuarta columna del plan es el detalle de cada paso.
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }

        assertTrue(
            "Plan sin el índice de ventas: $plan",
            plan.any { it.contains("INDEX index_sales_status_business_date") },
        )
        assertTrue(
            "Plan sin el índice de líneas: $plan",
            plan.any { it.contains("index_sale_lines_sale_id_product_id") },
        )
    }
}
