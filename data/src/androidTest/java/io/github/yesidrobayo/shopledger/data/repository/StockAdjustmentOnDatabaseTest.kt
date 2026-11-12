package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidAdjustmentResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockAdjustmentUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El ajuste de stock y su anulación sobre la base real, con sus guardas: cada fila nueva pasa la
 * guarda del libro, el libro nunca se edita, cada escritura cuenta un cambio y las consultas de
 * invariantes quedan en cero. Un conteo sin diferencias no escribe ni cuenta nada.
 *
 * Los productos y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class StockAdjustmentOnDatabaseTest : RepositoryTest() {
    private lateinit var ports: DataPorts
    private lateinit var adjust: AdjustStockUseCase
    private lateinit var void: VoidStockAdjustmentUseCase

    @Before
    fun connectUseCases() {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        val calendar = BusinessCalendarSource(ports.settings)
        adjust = AdjustStockUseCase(
            runner, ports.products, ports.units, ports.systemRows, ports.stockLedger,
            ports.lowStockAlerts, calendar, CurrencyLock(ports.settings), FIXED_CLOCK,
        )
        void = VoidStockAdjustmentUseCase(
            runner,
            ports.products,
            ports.stockLedger,
            ports.lowStockAlerts,
            calendar,
            FIXED_CLOCK,
        )
    }

    private suspend fun stockedProduct(minStock: Long = 0L): Long = commit {
        val id = ports.products.insert(
            newProduct("Jabón inventado", productDetails("Jabón inventado", minStock = minStock)),
        )
        ports.stockLedger.append(openingRow(id, units = 11))
        id
    }

    @Test
    fun adjustmentsAndTheirVoidPassEveryGuard() = runBlocking {
        val id = stockedProduct()
        val before = changeSeq()

        val expired = adjust(
            StockAdjustmentRequest.Change(id, StockAdjustmentReason.EXPIRED, QuantityMilli(3_000L)),
        )
        val counted = adjust(StockAdjustmentRequest.Count(id, QuantityMilli(6_000L), "Inventado"))
        val other = adjust(
            StockAdjustmentRequest.Change(
                id,
                StockAdjustmentReason.OTHER,
                QuantityMilli(2_000L),
                increase = true,
                note = "Apareció",
            ),
        )
        assertTrue(expired is StockAdjustmentResult.Adjusted)
        assertTrue(counted is StockAdjustmentResult.Adjusted)
        assertTrue(other is StockAdjustmentResult.Adjusted)
        val voided = void((expired as StockAdjustmentResult.Adjusted).movementId)

        assertTrue(voided is VoidAdjustmentResult.Voided)
        assertEquals(VoidAdjustmentResult.AlreadyVoided, void(expired.movementId))
        assertEquals(before + 4, changeSeq())
        val rows = ports.stockLedger.findByProduct(id)
        assertEquals(
            listOf(
                StockMovementType.OPENING_BALANCE,
                StockMovementType.ADJUSTMENT,
                StockMovementType.ADJUSTMENT,
                StockMovementType.ADJUSTMENT,
                StockMovementType.ADJUSTMENT_VOID,
            ),
            rows.map { it.movement.type },
        )
        // 11 a 1200 valen 13200: vencen 3 por 3600, el conteo encuentra 6 de 8 y quita 2 por 2400,
        // y aparecen 2 por 2400. Al anular el vencimiento, el libro efectivo vuelve a 11 unidades.
        assertEquals(
            listOf(13_200L, -3_600L, -2_400L, 2_400L, 3_600L),
            rows.map { it.totalCost.minor },
        )
        val product = requireNotNull(ports.products.findById(id))
        assertEquals(QuantityMilli(11_000L), product.onHand)
        assertEquals(Money(13_200L), product.stockValue)
        assertEquals(Money(1_200L), product.averageCost)
        assertInvariantsHold()
    }

    @Test
    fun aCountWithoutDifferencesWritesNothing() = runBlocking {
        val id = stockedProduct()
        val before = changeSeq()

        assertEquals(
            StockAdjustmentResult.NoDifference,
            adjust(StockAdjustmentRequest.Count(id, QuantityMilli(11_000L))),
        )

        assertEquals(before, changeSeq())
        assertEquals(1, ports.stockLedger.findByProduct(id).size)
    }

    @Test
    fun crossingTheMinimumLeavesOnePendingAlert() = runBlocking {
        val id = stockedProduct(minStock = 10_000L)

        val damaged =
            StockAdjustmentRequest.Change(id, StockAdjustmentReason.DAMAGED, QuantityMilli(1_000L))
        adjust(damaged)
        adjust(damaged)

        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM low_stock_alerts WHERE product_id = $id " +
                    "AND resolved_at IS NULL AND notification_status = 'PENDING'",
            ),
        )
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }
}
