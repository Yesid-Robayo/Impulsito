package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.production.ProductionBatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.CANDLE
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.WAX
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.WICK
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anular una producción sobre el catálogo en memoria, con el ejemplo de la regla de producción:
 * cada fila recibe su compensación una sola vez y el costo de cada producto sale de reproducir su
 * libro. Los valores esperados están calculados a mano.
 */
class VoidProductionUseCaseTest {
    private val fixture = ProductionFixture().apply { seedCandles() }
    private val catalog = fixture.catalog

    @Test
    fun `voiding brings the inputs back and takes the candles out, by reproduction`() = runTest {
        produceTenCandles()

        val result = fixture.void(1L, VoidReason.ENTRY_ERROR, " error ")

        assertEquals(VoidProductionResult.Voided, result)
        assertState(WAX, 5_000, 8_000, 40_000)
        assertState(WICK, 100_000, 150, 15_000)
        assertState(CANDLE, 0, 0, 0)
        // Las compensaciones vuelven a la ubicación principal, donde salieron y entraron.
        val main = CatalogFixture.MAIN_LOCATION_ID
        assertEquals(
            listOf(QuantityMilli(5_000), QuantityMilli(100_000), QuantityMilli.ZERO),
            listOf(WAX, WICK, CANDLE).map {
                catalog.tables.stockBalances[StockKey(it, main, lotId = null)]
            },
        )
        val batch = catalog.tables.productionBatches.getValue(1L)
        assertEquals(DocumentStatus.VOIDED, batch.status)
        assertEquals(Voiding(CatalogFixture.NOW, VoidReason.ENTRY_ERROR, "error"), batch.voiding)
        val voids = batchRows().filter { it.movement.type == StockMovementType.PRODUCTION_VOID }
        assertEquals(
            listOf(
                Triple(WAX, 2_000L, 16_000L),
                Triple(WICK, 10_000L, 1_500L),
                Triple(CANDLE, -10_000L, -17_500L),
            ),
            voids.map {
                Triple(it.movement.key.productId, it.movement.quantity.milli, it.totalCost.minor)
            },
        )
        assertTrue(voids.all { it.source == StockMovementSource(productionBatchId = 1L) })
        assertTrue(voids.all { it.businessDate == batch.content.businessDate })
    }

    @Test
    fun `a batch is compensated once`() = runTest {
        produceTenCandles()
        fixture.void(1L, VoidReason.ENTRY_ERROR, null)
        val changes = catalog.tables.changeSeq

        val again = fixture.void(1L, VoidReason.UNDO, null)

        assertEquals(VoidProductionResult.AlreadyVoided, again)
        assertEquals(6, batchRows().size)
        assertEquals(changes, catalog.tables.changeSeq)
    }

    @Test
    fun `voiding the first of two batches keeps the cost the second one froze`() = runTest {
        produceTenCandles()
        // La segunda tanda saca 1 kg de cera a 8000 y 5 mechas a 750: entra por 8750.
        fixture.register(
            ProductionRequest(
                CANDLE,
                QuantityMilli(5_000),
                listOf(input(WAX, 1_000), input(WICK, 5_000)),
                note = null,
            ),
        )
        assertState(CANDLE, 15_000, 1_750, 26_250)

        fixture.void(1L, VoidReason.ENTRY_ERROR, null)

        assertState(CANDLE, 5_000, 1_750, 8_750)
        assertState(WAX, 4_000, 8_000, 32_000)
        assertState(WICK, 95_000, 150, 14_250)
    }

    @Test
    fun `voiding after the candles left the stock lets it go negative`() = runTest {
        produceTenCandles()
        val adjusted = fixture.adjust(
            StockAdjustmentRequest.Change(
                CANDLE,
                StockAdjustmentReason.DAMAGED,
                QuantityMilli(4_000),
            ),
        )
        assertTrue(adjusted is StockAdjustmentResult.Adjusted)

        assertEquals(VoidProductionResult.Voided, fixture.void(1L, VoidReason.OTHER, null))

        // Las 4 velas dañadas salieron con 7000 congelados; sin la producción no queda valor.
        assertState(CANDLE, -4_000, 0, 0)
        assertState(WAX, 5_000, 8_000, 40_000)
    }

    @Test
    fun `voiding resolves the alert of an input that is back above its minimum`() = runTest {
        catalog.database.seed { tables ->
            val wax = tables.products.getValue(WAX)
            val details = wax.details.copy(minStock = QuantityMilli(4_000))
            tables.copy(products = tables.products + (WAX to wax.copy(details = details)))
        }
        produceTenCandles()
        assertEquals(1, catalog.activeAlertsOf(WAX).size)

        fixture.void(1L, VoidReason.ENTRY_ERROR, null)

        assertTrue(catalog.activeAlertsOf(WAX).isEmpty())
    }

    @Test
    fun `the reason, the note and the batch are checked before writing`() = runTest {
        produceTenCandles()
        val long = "n".repeat(VoidProductionUseCase.MAX_NOTE_LENGTH + 1)

        assertEquals(
            VoidProductionResult.ReasonNotAllowed,
            fixture.void(1L, VoidReason.CUSTOMER_RETURN, null),
        )
        assertEquals(
            VoidProductionResult.ReasonNotAllowed,
            fixture.void(1L, VoidReason.SUPPLIER_RETURN, null),
        )
        assertEquals(VoidProductionResult.NoteTooLong, fixture.void(1L, VoidReason.OTHER, long))
        assertEquals(VoidProductionResult.NotFound, fixture.void(99L, VoidReason.OTHER, null))
        assertEquals(3, batchRows().size)
    }

    @Test
    fun `an unknown time zone writes nothing`() = runTest {
        val unknown = ProductionFixture(zoneId = "Lugar/Inventado").apply { seedCandles() }
        unknown.catalog.database.seed { tables ->
            tables.copy(productionBatches = mapOf(1L to seededBatch()))
        }
        val before = unknown.catalog.tables

        assertEquals(
            VoidProductionResult.UnknownTimeZone,
            unknown.void(1L, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(before, unknown.catalog.tables)
    }

    @Test
    fun `a ledger that cannot be reproduced writes nothing`() = runTest {
        produceTenCandles()
        // Una compra que resta no puede salir de la app: llegaría de una copia dañada.
        catalog.database.seed { tables ->
            tables.copy(
                movements = tables.movements + (50L to brokenRow(50L)),
                lastIds = tables.lastIds + ("stock_movements" to 50L),
            )
        }
        val before = catalog.tables

        assertEquals(
            VoidProductionResult.LedgerDamaged,
            fixture.void(1L, VoidReason.ENTRY_ERROR, null),
        )
        assertEquals(before, catalog.tables)
    }

    @Test
    fun `a storage failure leaves nothing and is reported`() = runTest {
        produceTenCandles()
        val before = catalog.tables
        catalog.database.failAtWrite = 2
        catalog.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        val result = fixture.void(1L, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidProductionResult.StorageFailed(StorageFailure.DISK_FULL), result)
        assertEquals(before, catalog.tables)
    }

    @Test
    fun `a failure at each write leaves nothing`() = runTest {
        suspend fun prepared() = ProductionFixture().apply {
            seedCandles()
            stockCandleInputs()
            register(
                ProductionRequest(
                    CANDLE,
                    QuantityMilli(10_000),
                    listOf(input(WAX, 2_000), input(WICK, 10_000)),
                    note = null,
                ),
            )
        }
        val counting = prepared()
        counting.void(1L, VoidReason.ENTRY_ERROR, null)
        val writes = counting.catalog.database.lastTransactionWrites
        assertTrue("Anular debe escribir algo", writes > 0)
        for (failAt in 1..writes) {
            val failing = prepared()
            val before = failing.catalog.tables
            failing.catalog.database.failAtWrite = failAt
            val failed = try {
                failing.void(1L, VoidReason.ENTRY_ERROR, null)
                false
            } catch (expected: InjectedFailure) {
                true
            }
            assertTrue("La escritura $failAt debió fallar", failed)
            assertEquals("La escritura $failAt dejó algo", before, failing.catalog.tables)
        }
    }

    private suspend fun produceTenCandles() {
        fixture.stockCandleInputs()
        val result = fixture.register(
            ProductionRequest(
                CANDLE,
                QuantityMilli(10_000),
                listOf(input(WAX, 2_000), input(WICK, 10_000)),
                note = null,
            ),
        )
        assertEquals(RegisterProductionResult.Registered(1L, Money(17_500)), result)
    }

    private fun assertState(productId: Long, onHand: Long, average: Long, value: Long) {
        val product = catalog.product(productId)
        assertEquals(
            "producto $productId",
            Triple(onHand, average, value),
            Triple(product.onHand.milli, product.averageCost.minor, product.stockValue.minor),
        )
    }

    private fun batchRows(): List<StockMovementRecord> = catalog.tables.movements.values
        .filter { it.source.productionBatchId == 1L }
        .sortedBy { it.id }

    private fun input(productId: Long, milli: Long) =
        ProductionInput(productId, QuantityMilli(milli))

    private fun seededBatch() = ProductionBatch(
        id = 1L,
        content = NewProductionBatch(
            productId = CANDLE,
            locationId = CatalogFixture.MAIN_LOCATION_ID,
            quantity = QuantityMilli(1_000),
            totalCost = Money.ZERO,
            note = null,
            occurredAt = CatalogFixture.EARLIER,
            businessDate = requireNotNull(BusinessDate.parse("2023-11-13")),
            createdAt = CatalogFixture.EARLIER,
        ),
        voiding = null,
    )

    private fun brokenRow(id: Long) = StockMovementRecord(
        movement = StockMovementDraft(
            key = StockKey(CANDLE, CatalogFixture.MAIN_LOCATION_ID, lotId = null),
            type = StockMovementType.PURCHASE,
            quantity = QuantityMilli(-1_000),
            reversesMovementId = null,
        ).recordedAs(id),
        reason = null,
        unitCost = Money.ZERO,
        totalCost = Money.ZERO,
        balanceAfter = QuantityMilli(9_000),
        averageCostAfter = Money.ZERO,
        stockValueAfter = Money.ZERO,
        source = StockMovementSource.NONE,
        note = null,
        occurredAt = CatalogFixture.EARLIER,
        businessDate = requireNotNull(BusinessDate.parse("2023-11-13")),
        createdAt = CatalogFixture.EARLIER,
    )
}
