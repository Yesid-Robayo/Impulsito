package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.movement
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.data.repository.RoomSaleOutflowSource
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que salió por ventas, leído del libro para sugerir cuánto reponer: solo filas `SALE` sin
 * compensar, desde una fecha de negocio y, si se pide, de una ubicación. Ni las devoluciones ni
 * los ajustes cuentan. Nombres y cantidades inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class SaleOutflowQueryTest : LedgerDatabaseTest() {
    private val ledger get() = database.stockMovementDao()

    @Test
    fun onlyUncompensatedSalesSinceTheDateCountByProductAndLocation() = runBlocking {
        database.locationDao().insert(location("Puesto de feria", kind = "STALL").copy(id = 2))
        val otherId = database.productDao().insert(product("Té verde"))
        val line = insertSaleWithLine()
        saleRow(productId, -2_000, "2026-01-01", line.id)
        saleRow(productId, -3_000, "2026-01-10", insertSaleWithLine().id)
        saleRow(productId, -500, "2026-01-12", insertSaleWithLine().id, locationId = 2)
        // Antes de la ventana: no cuenta.
        saleRow(productId, -1_000, "2025-12-31", insertSaleWithLine().id)
        // Una venta anulada del otro producto: su compensatorio la saca.
        val otherLine = otherSaleLine(otherId)
        val voided = saleRow(otherId, -4_000, "2026-01-05", otherLine)
        ledger.insert(
            movement(otherId, "SALE_VOID", 4_000, 1_000, 4_000, 0, 1_000, 0, businessDate = DAY)
                .copy(reversesMovementId = voided, saleLineId = otherLine),
        )
        // Una devolución y un ajuste del primero: tampoco cuentan.
        ledger.insert(
            movement(productId, "SALE_RETURN", 1_000, 1_000, 1_000, 0, 1_000, 0)
                .copy(saleReturnLineId = insertSaleReturnLine(line).id, businessDate = DAY),
        )
        ledger.insert(
            movement(productId, "ADJUSTMENT", -700, 1_000, -700, 0, 1_000, 0)
                .copy(reason = "DAMAGED", businessDate = DAY),
        )
        val source = RoomSaleOutflowSource(database)
        val since = requireNotNull(BusinessDate.parse(DAY))

        assertEquals(
            mapOf(productId to QuantityMilli(5_500)),
            source.observeUnitsOut(since, null).first(),
        )
        assertEquals(
            mapOf(productId to QuantityMilli(5_000)),
            source.observeUnitsOut(since, MAIN_LOCATION_ID).first(),
        )
        assertEquals(
            mapOf(productId to QuantityMilli(500)),
            source.observeUnitsOut(since, 2).first(),
        )
        assertEquals(emptyMap<Long, QuantityMilli>(), source.observeUnitsOut(since, 99).first())

        // Con años de ventas, la consulta no recorre el libro: busca por el índice de tipo y
        // fecha.
        val plan =
            database.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN $SALE_OUTFLOW_SQL") { statement ->
                    statement.bindText(1, DAY)
                    // Las dos apariciones de `:locationId` comparten un solo índice.
                    statement.bindNull(2)
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

    /** Una fila `SALE` de [product] en [businessDate], y su id. */
    private suspend fun saleRow(
        product: Long,
        quantity: Long,
        businessDate: String,
        saleLineId: Long,
        locationId: Long = MAIN_LOCATION_ID,
    ): Long = ledger.insert(
        movement(product, "SALE", quantity, 1_000, quantity, 0, 1_000, 0).copy(
            saleLineId = saleLineId,
            locationId = locationId,
            businessDate = businessDate,
        ),
    )

    private suspend fun otherSaleLine(product: Long): Long {
        val saleId = database.saleDao().insert(sale(priceListId))
        return database.saleLineDao().insert(saleLine(saleId, product, priceListId))
    }

    private companion object {
        const val DAY = "2026-01-01"
    }
}
