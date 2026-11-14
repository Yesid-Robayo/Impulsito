package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.production.NewProductionBatchLine
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.CANDLE
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.LABEL
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.WAX
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.WICK
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Producir sobre el catálogo en memoria, con el ejemplo de la regla de producción: diez velas con
 * 0,200 kg de cera y una mecha cada una. Los valores esperados están calculados a mano.
 */
class RegisterProductionUseCaseTest {
    private val fixture = ProductionFixture().apply { seedCandles() }
    private val catalog = fixture.catalog

    @Test
    fun `the example of the rule moves the inputs out and the candles in at their sum`() = runTest {
        fixture.stockCandleInputs()
        val writesBefore = catalog.transactions.writeCalls
        val changesBefore = catalog.tables.changeSeq

        val result = fixture.register(candles(10, input(WAX, 2_000), input(WICK, 10_000)))

        assertEquals(RegisterProductionResult.Registered(1L, Money(17_500)), result)
        val wax = catalog.product(WAX)
        assertEquals(QuantityMilli(3_000), wax.onHand)
        assertEquals(Money(24_000), wax.stockValue)
        assertEquals(Money(8_000), wax.averageCost)
        val wick = catalog.product(WICK)
        assertEquals(QuantityMilli(90_000), wick.onHand)
        assertEquals(Money(13_500), wick.stockValue)
        val candle = catalog.product(CANDLE)
        assertEquals(QuantityMilli(10_000), candle.onHand)
        assertEquals(Money(17_500), candle.stockValue)
        assertEquals(Money(1_750), candle.averageCost)
        assertEquals(writesBefore + 1, catalog.transactions.writeCalls)
        assertEquals(changesBefore + 1, catalog.tables.changeSeq)
    }

    @Test
    fun `each input row and each movement is tied to the batch`() = runTest {
        fixture.stockCandleInputs()

        fixture.register(candles(10, input(WAX, 2_000), input(WICK, 10_000), note = " tanda "))

        val batch = catalog.tables.productionBatches.getValue(1L)
        assertEquals(CANDLE, batch.content.productId)
        assertEquals(CatalogFixture.MAIN_LOCATION_ID, batch.content.locationId)
        assertEquals(QuantityMilli(10_000), batch.content.quantity)
        assertEquals(Money(17_500), batch.content.totalCost)
        assertEquals("tanda", batch.content.note)
        assertEquals(CatalogFixture.NOW, batch.content.occurredAt)
        assertEquals(
            listOf(
                NewProductionBatchLine(WAX, QuantityMilli(2_000), Money(8_000), Money(16_000)),
                NewProductionBatchLine(WICK, QuantityMilli(10_000), Money(150), Money(1_500)),
            ),
            catalog.tables.productionLines.values.sortedBy { it.id }.map { it.content },
        )
        val out = fixture.movementsOf(WAX).last()
        assertEquals(StockMovementType.PRODUCTION_OUT, out.movement.type)
        assertEquals(QuantityMilli(-2_000), out.movement.quantity)
        assertEquals(Money(-16_000), out.totalCost)
        assertEquals(StockMovementSource(productionBatchId = 1L), out.source)
        val entry = fixture.movementsOf(CANDLE).single()
        assertEquals(StockMovementType.PRODUCTION_IN, entry.movement.type)
        assertEquals(QuantityMilli(10_000), entry.movement.quantity)
        assertEquals(Money(1_750), entry.unitCost)
        assertEquals(Money(17_500), entry.totalCost)
        assertEquals(StockMovementSource(productionBatchId = 1L), entry.source)
        // Los insumos salen antes de que entre lo producido.
        assertTrue(out.id < entry.id)
        // Todo sale de la ubicación principal y entra en ella, y sus saldos siguen al libro.
        val main = CatalogFixture.MAIN_LOCATION_ID
        assertEquals(
            listOf(QuantityMilli(3_000), QuantityMilli(90_000), QuantityMilli(10_000)),
            listOf(WAX, WICK, CANDLE).map {
                catalog.tables.stockBalances[StockKey(it, main, lotId = null)]
            },
        )
    }

    @Test
    fun `missing inputs do not block and leave their stock negative`() = runTest {
        fixture.opening(WAX, 1_000L, 8_000L)

        val result = fixture.register(candles(10, input(WAX, 2_000), input(WICK, 10_000)))

        // Sale 1 kg con su valor y el que falta al promedio: 16000. La mecha sin costo, cero.
        assertEquals(RegisterProductionResult.Registered(1L, Money(16_000)), result)
        assertEquals(QuantityMilli(-1_000), catalog.product(WAX).onHand)
        assertEquals(QuantityMilli(-10_000), catalog.product(WICK).onHand)
        assertEquals(Money(1_600), catalog.product(CANDLE).averageCost)
    }

    @Test
    fun `an input without stock adds its fixed cost and writes no movement`() = runTest {
        fixture.stockCandleInputs()
        catalog.database.seed { tables ->
            val label = tables.products.getValue(LABEL)
            val details = label.details.copy(fixedUnitCost = Money(300))
            tables.copy(products = tables.products + (LABEL to label.copy(details = details)))
        }

        val result = fixture.register(candles(10, input(WAX, 2_000), input(LABEL, 10_000)))

        assertEquals(RegisterProductionResult.Registered(1L, Money(19_000)), result)
        assertEquals(emptyList<Any>(), fixture.movementsOf(LABEL))
        assertEquals(2, catalog.tables.productionLines.size)
        assertEquals(Money(1_900), catalog.product(CANDLE).averageCost)
    }

    @Test
    fun `producing below the minimum of an input raises its alert in the same transaction`() =
        runTest {
            fixture.stockCandleInputs()
            catalog.database.seed { tables ->
                val wax = tables.products.getValue(WAX)
                val details = wax.details.copy(minStock = QuantityMilli(4_000))
                tables.copy(products = tables.products + (WAX to wax.copy(details = details)))
            }

            fixture.register(candles(10, input(WAX, 2_000), input(WICK, 10_000)))

            assertEquals(1, catalog.activeAlertsOf(WAX).size)
        }

    @Test
    fun `the product must exist, be current, carry stock without lots and have a recipe`() =
        runTest {
            catalog.seedProduct(10L, "Archivado", archived = true)
            catalog.seedProduct(11L, "Combo", stockMode = ProductStockMode.COMPONENTS)
            catalog.seedProduct(12L, "Sin receta")
            catalog.seedProduct(13L, "Con lotes")
            catalog.database.seed { tables ->
                val lots = tables.products.getValue(13L)
                val details = lots.details.copy(tracksLots = true)
                tables.copy(products = tables.products + (13L to lots.copy(details = details)))
            }
            val inputs = listOf(input(WAX, 1_000))

            assertEquals(RegisterProductionResult.NotFound, produce(99L, 1_000, inputs))
            assertEquals(RegisterProductionResult.ProductArchived, produce(10L, 1_000, inputs))
            assertEquals(RegisterProductionResult.NotStocked, produce(11L, 1_000, inputs))
            assertEquals(RegisterProductionResult.LotsNotSupported, produce(13L, 1_000, inputs))
            assertEquals(RegisterProductionResult.NoRecipe, produce(12L, 1_000, inputs))
            assertEquals(0L, catalog.tables.changeSeq)
        }

    @Test
    fun `the quantity follows its unit and the line limit`() = runTest {
        val inputs = listOf(input(WAX, 1_000))

        assertEquals(
            RegisterProductionResult.InvalidQuantity(UnitQuantityCheck.NotPositive),
            produce(CANDLE, 0, inputs),
        )
        assertEquals(
            RegisterProductionResult.InvalidQuantity(UnitQuantityCheck.FractionNotAllowed),
            produce(CANDLE, 1_500, inputs),
        )
        assertEquals(
            RegisterProductionResult.AboveLimit(Limit.LINE_QUANTITY_MILLI),
            produce(CANDLE, Limit.LINE_QUANTITY_MILLI.max + 1_000, inputs),
        )
    }

    @Test
    fun `the inputs must be present and fit a document`() = runTest {
        assertEquals(RegisterProductionResult.NoInputs, produce(CANDLE, 1_000, emptyList()))
        val many = (1..201).map { input(WAX, 1_000) }
        assertEquals(RegisterProductionResult.TooManyInputs, produce(CANDLE, 1_000, many))
    }

    @Test
    fun `each input is checked and the first problem is named with its row`() = runTest {
        catalog.seedProduct(20L, "Combo", stockMode = ProductStockMode.COMPONENTS)
        catalog.seedProduct(21L, "Insumo con lotes")
        catalog.database.seed { tables ->
            val lots = tables.products.getValue(21L)
            val details = lots.details.copy(tracksLots = true)
            tables.copy(products = tables.products + (21L to lots.copy(details = details)))
        }

        fun rejected(index: Int, reason: ProductionInputRejection) =
            RegisterProductionResult.InputRejected(index, reason)

        assertEquals(
            rejected(1, ProductionInputRejection.NOT_FOUND),
            produce(CANDLE, 1_000, listOf(input(WAX, 1), input(99L, 1))),
        )
        assertEquals(
            rejected(0, ProductionInputRejection.SAME_PRODUCT),
            produce(CANDLE, 1_000, listOf(input(CANDLE, 1))),
        )
        assertEquals(
            rejected(1, ProductionInputRejection.REPEATED),
            produce(CANDLE, 1_000, listOf(input(WAX, 1), input(WAX, 2))),
        )
        assertEquals(
            rejected(0, ProductionInputRejection.IS_COMBO),
            produce(CANDLE, 1_000, listOf(input(20L, 1))),
        )
        assertEquals(
            rejected(0, ProductionInputRejection.LOTS_NOT_SUPPORTED),
            produce(CANDLE, 1_000, listOf(input(21L, 1))),
        )
        assertEquals(
            rejected(0, ProductionInputRejection.INVALID_QUANTITY),
            produce(CANDLE, 1_000, listOf(input(WAX, 0))),
        )
        assertEquals(
            rejected(0, ProductionInputRejection.INVALID_QUANTITY),
            produce(CANDLE, 1_000, listOf(input(WAX, Limit.LINE_QUANTITY_MILLI.max + 1))),
        )
        assertEquals(0L, catalog.tables.changeSeq)
    }

    @Test
    fun `an input may consume half a unit even in a counting unit`() = runTest {
        val result = produce(CANDLE, 1_000, listOf(input(WICK, 500)))

        assertEquals(RegisterProductionResult.Registered(1L, Money.ZERO), result)
    }

    @Test
    fun `a note over its length is rejected`() = runTest {
        val note = "n".repeat(RegisterProductionUseCase.MAX_NOTE_LENGTH + 1)

        val result = fixture.register(candles(1, input(WAX, 200), note = note))

        assertEquals(RegisterProductionResult.NoteTooLong, result)
    }

    @Test
    fun `a cost over its limit is rejected and writes nothing`() = runTest {
        fixture.seedStock(WAX, 1_000, 2_000_000_000_000L)

        val result = produce(CANDLE, 1_000, listOf(input(WAX, 1_000)))

        assertEquals(RegisterProductionResult.AboveLimit(Limit.UNIT_MONEY), result)
        assertTrue(catalog.tables.productionBatches.isEmpty())
    }

    @Test
    fun `an overflow is reported as above the stock value limit`() = runTest {
        fixture.seedStock(WAX, 1, Long.MAX_VALUE, average = 1)

        val result = produce(CANDLE, 1_000, listOf(input(WAX, 2_000)))

        assertEquals(RegisterProductionResult.AboveLimit(Limit.STOCK_VALUE), result)
    }

    @Test
    fun `an unknown time zone writes nothing`() = runTest {
        val unknown = ProductionFixture(zoneId = "Lugar/Inventado").apply { seedCandles() }

        val result = unknown.register(candles(1, input(WAX, 200)))

        assertEquals(RegisterProductionResult.UnknownTimeZone, result)
        assertTrue(unknown.catalog.tables.productionBatches.isEmpty())
    }

    @Test
    fun `a production with cost waits for the currency, and one without cost does not`() = runTest {
        val unconfirmed = ProductionFixture(currencyConfirmed = false).apply {
            seedCandles()
            seedStock(WAX, 5_000, 40_000, average = 8_000)
        }

        assertEquals(
            RegisterProductionResult.CurrencyUnconfirmed,
            unconfirmed.register(candles(1, input(WAX, 200))),
        )
        assertEquals(
            RegisterProductionResult.Registered(1L, Money.ZERO),
            unconfirmed.register(candles(1, input(WICK, 1_000))),
        )
    }

    @Test
    fun `a storage failure leaves nothing and is reported`() = runTest {
        fixture.stockCandleInputs()
        val before = catalog.tables
        catalog.database.failAtWrite = 3
        catalog.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        val result = fixture.register(candles(10, input(WAX, 2_000), input(WICK, 10_000)))

        assertEquals(RegisterProductionResult.StorageFailed(StorageFailure.DISK_FULL), result)
        assertEquals(before, catalog.tables)
    }

    @Test
    fun `a failure at each write leaves nothing`() = runTest {
        suspend fun prepared() = ProductionFixture().apply {
            seedCandles()
            stockCandleInputs()
        }
        val request = candles(10, input(WAX, 2_000), input(WICK, 10_000))
        val counting = prepared()
        counting.register(request)
        val writes = counting.catalog.database.lastTransactionWrites
        assertTrue("Producir debe escribir algo", writes > 0)
        for (failAt in 1..writes) {
            val failing = prepared()
            val before = failing.catalog.tables
            failing.catalog.database.failAtWrite = failAt
            val failed = try {
                failing.register(request)
                false
            } catch (expected: InjectedFailure) {
                true
            }
            assertTrue("La escritura $failAt debió fallar", failed)
            assertEquals("La escritura $failAt dejó algo", before, failing.catalog.tables)
        }
    }

    private suspend fun produce(
        productId: Long,
        milli: Long,
        inputs: List<ProductionInput>,
    ): RegisterProductionResult =
        fixture.register(ProductionRequest(productId, QuantityMilli(milli), inputs, note = null))

    private fun candles(count: Long, vararg inputs: ProductionInput, note: String? = null) =
        ProductionRequest(CANDLE, QuantityMilli(count * 1_000), inputs.toList(), note)

    private fun input(productId: Long, milli: Long) =
        ProductionInput(productId, QuantityMilli(milli))
}
