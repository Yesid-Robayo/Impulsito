package io.github.yesidrobayo.shopledger.domain.usecase.production

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.production.ProductionInput
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.CANDLE
import io.github.yesidrobayo.shopledger.domain.usecase.production.ProductionFixture.Companion.WAX
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.LoadLotChoicesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Producir con lotes: un insumo con lotes sale por vencimiento o por el lote elegido, con una fila
 * por lote; lo producido entra al lote que dice su código, o al sin asignar; y anular devuelve
 * cada fila a su lote. Queso y pan inventados, con lotes y montos inventados.
 */
class ProductionLotsTest {
    private val fixture = ProductionFixture().apply { seedCandles() }
    private val catalog = fixture.catalog
    private val stock = StockFixture(catalog)

    /**
     * El queso, con lotes: A vence el 10 y tiene 3; B vence el 20 y tiene 5, todo a 1000 la
     * unidad. El pan, con lotes, lleva medio queso por unidad. Devuelve los lotes A y B.
     */
    private suspend fun seedCheese(): Pair<Long, Long> {
        catalog.seedProduct(CHEESE, "Queso inventado", tracksLots = true)
        catalog.seedProduct(BREAD, "Pan de queso inventado", tracksLots = true)
        fixture.seedRecipe(BREAD, CHEESE to 500L)
        val later = stock.seedLot(CHEESE, "B", expiry = "2026-11-20")
        val sooner = stock.seedLot(CHEESE, "A", expiry = "2026-11-10")
        stock.receiveIntoLot(CHEESE, later, units = 5_000L, unitCost = 1_000L)
        stock.receiveIntoLot(CHEESE, sooner, units = 3_000L, unitCost = 1_000L)
        return sooner to later
    }

    private suspend fun bread(
        milli: Long,
        vararg inputs: ProductionInput,
        lot: ProductionLot? = null,
    ): RegisterProductionResult = fixture.registerWithLots(
        ProductionRequest(BREAD, QuantityMilli(milli), inputs.toList(), note = null, lot = lot),
    )

    private fun cheese(milli: Long, lotId: Long? = null) =
        ProductionInput(CHEESE, QuantityMilli(milli), lotId)

    private fun rowsOf(productId: Long, type: StockMovementType): List<StockMovementRecord> =
        fixture.movementsOf(productId).filter { it.movement.type == type }

    private fun unassignedOf(productId: Long): Long = catalog.tables.lots.values
        .single { it.productId == productId && it.isUnassigned }.id

    private fun lotOf(productId: Long, code: String): Long = catalog.tables.lots.values
        .single { it.productId == productId && it.code == code }.id

    private suspend fun onHand(productId: Long, lotId: Long): Long = catalog.ledger
        .onHandAt(StockKey(productId, CatalogFixture.MAIN_LOCATION_ID, lotId)).milli

    private fun date(text: String): BusinessDate = requireNotNull(BusinessDate.parse(text))

    private fun registered(result: RegisterProductionResult): RegisterProductionResult.Registered {
        assertTrue("Debía registrarse: $result", result is RegisterProductionResult.Registered)
        return result as RegisterProductionResult.Registered
    }

    @Test
    fun `an input with lots leaves by expiry, one row per lot, and the rest from unassigned`() =
        runTest {
            val (sooner, later) = seedCheese()

            val result = registered(bread(18_000, cheese(9_000)))

            val unassigned = unassignedOf(CHEESE)
            val rows = rowsOf(CHEESE, StockMovementType.PRODUCTION_OUT)
            assertEquals(listOf(sooner, later, unassigned), rows.map { it.movement.key.lotId })
            val quantities = rows.map { it.movement.quantity.milli }
            assertEquals(listOf(-3_000L, -5_000L, -1_000L), quantities)
            assertEquals(listOf(-3_000L, -5_000L), rows.take(2).map { it.totalCost.minor })
            // Lo producido cuesta la suma de las filas, y sin código entra al lote sin asignar.
            assertEquals(result.totalCost.minor, rows.sumOf { -it.totalCost.minor })
            val entry = rowsOf(BREAD, StockMovementType.PRODUCTION_IN).single()
            assertEquals(unassignedOf(BREAD), entry.movement.key.lotId)
            assertEquals(0L, onHand(CHEESE, sooner))
            assertEquals(0L, onHand(CHEESE, later))
            assertEquals(-1_000L, onHand(CHEESE, unassigned))
        }

    @Test
    fun `a chosen lot leaves first and the others stay`() = runTest {
        val (sooner, later) = seedCheese()

        registered(bread(8_000, cheese(4_000, lotId = later)))

        val rows = rowsOf(CHEESE, StockMovementType.PRODUCTION_OUT)
        assertEquals(listOf(later), rows.map { it.movement.key.lotId })
        assertEquals(1_000L, onHand(CHEESE, later))
        assertEquals(3_000L, onHand(CHEESE, sooner))
    }

    @Test
    fun `the finished product enters a new lot, and the same code adds to it`() = runTest {
        seedCheese()

        registered(bread(2_000, cheese(1_000), lot = ProductionLot(" P-1 ", date("2026-12-01"))))
        registered(bread(2_000, cheese(1_000), lot = ProductionLot("P-1", date("2027-01-01"))))

        val lot = catalog.tables.lots.values.single { it.productId == BREAD && it.code == "P-1" }
        // Sumar a un lote que ya existe no cambia su vencimiento.
        assertEquals(date("2026-12-01"), lot.expiryDate)
        val entries = rowsOf(BREAD, StockMovementType.PRODUCTION_IN)
        assertEquals(listOf(lot.id, lot.id), entries.map { it.movement.key.lotId })
        assertEquals(4_000L, onHand(BREAD, lot.id))
    }

    @Test
    fun `a blank code leaves the finished product in its unassigned lot`() = runTest {
        seedCheese()

        registered(bread(2_000, cheese(1_000), lot = ProductionLot("   ", null)))

        val entry = rowsOf(BREAD, StockMovementType.PRODUCTION_IN).single()
        assertEquals(unassignedOf(BREAD), entry.movement.key.lotId)
        assertTrue(catalog.tables.lots.values.none { it.productId == BREAD && !it.isUnassigned })
    }

    @Test
    fun `a wrong lot is rejected and nothing is written`() = runTest {
        val (sooner, _) = seedCheese()
        val foreign = stock.seedLot(BREAD, "Z")
        val candle = ProductionRequest(
            CANDLE,
            QuantityMilli(1_000),
            listOf(ProductionInput(WAX, QuantityMilli(200))),
            note = null,
            lot = ProductionLot("X", null),
        )

        assertEquals(RegisterProductionResult.LotNotTracked, fixture.registerWithLots(candle))
        assertEquals(
            RegisterProductionResult.LotCodeTooLong(60),
            bread(2_000, cheese(1_000), lot = ProductionLot("x".repeat(61), null)),
        )
        assertEquals(
            RegisterProductionResult.InputRejected(0, ProductionInputRejection.LOT_NOT_FOUND),
            bread(2_000, cheese(1_000, lotId = foreign)),
        )
        val waxFromALot = candle.copy(
            inputs = listOf(ProductionInput(WAX, QuantityMilli(200), lotId = sooner)),
            lot = null,
        )
        assertEquals(
            RegisterProductionResult.InputRejected(0, ProductionInputRejection.LOT_NOT_FOUND),
            fixture.registerWithLots(waxFromALot),
        )
        assertTrue(catalog.tables.productionBatches.isEmpty())
        assertTrue(rowsOf(CHEESE, StockMovementType.PRODUCTION_OUT).isEmpty())
    }

    @Test
    fun `voiding gives each input back to its lot and takes the product out of its lot`() =
        runTest {
            val (sooner, later) = seedCheese()
            val batch = registered(bread(18_000, cheese(9_000), lot = ProductionLot("P-1", null)))

            val result = fixture.voidWithLots(batch.batchId, VoidReason.ENTRY_ERROR, null)

            assertEquals(VoidProductionResult.Voided, result)
            assertEquals(3_000L, onHand(CHEESE, sooner))
            assertEquals(5_000L, onHand(CHEESE, later))
            assertEquals(0L, onHand(CHEESE, unassignedOf(CHEESE)))
            assertEquals(0L, onHand(BREAD, lotOf(BREAD, "P-1")))
            val voids = rowsOf(CHEESE, StockMovementType.PRODUCTION_VOID) +
                rowsOf(BREAD, StockMovementType.PRODUCTION_VOID)
            assertEquals(4, voids.size)
        }

    @Test
    fun `voiding when part of the produced lot already left is refused with what is missing`() =
        runTest {
            seedCheese()
            val batch = registered(bread(2_000, cheese(1_000), lot = ProductionLot("P-1", null)))
            val lot = lotOf(BREAD, "P-1")
            val takeOut = StockAdjustmentRequest.Change(
                BREAD,
                StockAdjustmentReason.EXPIRED,
                QuantityMilli(1_000),
                lotId = lot,
            )
            assertTrue(stock.adjust(takeOut) is StockAdjustmentResult.Adjusted)
            val before = fixture.movementsOf(BREAD)

            val result = fixture.voidWithLots(batch.batchId, VoidReason.ENTRY_ERROR, null)

            assertEquals(
                VoidProductionResult.LotAlreadyUsed(BREAD, lot, QuantityMilli(1_000)),
                result,
            )
            assertEquals(before, fixture.movementsOf(BREAD))
            assertEquals(DocumentStatus.ACTIVE, fixture.batches.findById(batch.batchId)?.status)
        }

    @Test
    fun `the form reads the lots of the inputs and of the product, and nothing without lots`() =
        runTest {
            val (sooner, later) = seedCheese()
            stock.seedLot(BREAD, "P-9", expiry = "2026-12-01")
            val calendar = BusinessCalendarSource(catalog.settings)
            val choices = LoadLotChoicesUseCase(
                catalog.lots,
                catalog.products,
                catalog.systemRows,
                calendar,
                catalog.settings,
                catalog.clock,
            )
            val load = LoadProductionLotsUseCase(catalog.lots, choices, calendar, catalog.clock)

            val read = load(BREAD, setOf(CHEESE))

            val offered = read.inputs.getValue(CHEESE).lots.map { it.stock.lotId }
            assertEquals(listOf(sooner, later), offered)
            assertEquals(listOf("P-9"), read.outputLots.map { it.code })
            assertNotNull(read.today)
            val none = load(null, emptySet())
            val empty = ProductionLots(today = read.today, weekStartDay = read.weekStartDay)
            assertEquals(empty, none)
        }

    private companion object {
        const val CHEESE = 30L
        const val BREAD = 31L
    }
}
