package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineLot
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.buy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vender un producto que se controla por lotes: sale primero el lote que vence antes, con una fila
 * por lote valorada en orden, y la línea cuesta la suma de sus filas. Es el ejemplo de las reglas:
 * lotes de 3 y 5 con el stock en 8 unidades que valen 12000. Productos, lotes y montos inventados.
 */
class SaleLotsTest {
    private val world = SaleWorld().apply { addMethod(method()) }
    private var lotL1 = 0L
    private var lotL2 = 0L

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    /**
     * El producto 1, con lotes: L1 tiene 3 y vence antes, aunque entró después; L2 tiene 5. Todo a
     * 1500.
     */
    private suspend fun stockedLots() {
        world.addProduct(product(1, tracksLots = true))
        world.buy(
            atUnitCost(1, 5_000, cost = 1_500)
                .copy(lot = PurchaseLineLot.New("L2", date("2026-11-20"))),
        )
        world.buy(
            atUnitCost(1, 3_000, cost = 1_500)
                .copy(lot = PurchaseLineLot.New("L1", date("2026-11-10"))),
        )
        lotL2 = world.tables.lots.values.single { it.code == "L2" }.id
        lotL1 = world.tables.lots.values.single { it.code == "L1" }.id
    }

    private suspend fun sell(vararg lines: SaleLineRequest): RegisterSaleResult =
        world.registerSale()(SaleRequest(lines.toList(), SaleWorld.CASH))

    private fun saleRows(): List<StockMovementRecord> = world.tables.movements.values
        .filter { it.movement.type == StockMovementType.SALE }
        .sortedBy { it.id }

    private fun lotsOf(rows: List<StockMovementRecord>) = rows.map { it.movement.key.lotId }

    private fun quantitiesOf(rows: List<StockMovementRecord>) =
        rows.map { it.movement.quantity.milli }

    private fun costsOf(rows: List<StockMovementRecord>) = rows.map { it.totalCost.minor }

    @Test
    fun `a sale at the stall takes from the lots at the stall, not from the initial`() = runTest {
        world.locationRepository = TestLocations(listOf(testLocation(STALL, archived = false)))
        world.addProduct(product(1, tracksLots = true))
        // L1 vence antes pero está en la inicial; en el puesto solo hay L2.
        world.buy(
            atUnitCost(1, 3_000, cost = 1_500)
                .copy(lot = PurchaseLineLot.New("L1", date("2026-11-10"))),
        )
        val atStall = atUnitCost(1, 5_000, cost = 1_500)
            .copy(lot = PurchaseLineLot.New("L2", date("2026-11-20")))
        world.registerPurchase()(PurchaseRequest(listOf(atStall), locationId = STALL))
        lotL2 = world.tables.lots.values.single { it.code == "L2" }.id

        val line = SaleLineRequest(1, QuantityMilli(2_000))
        val sold =
            world.registerSale()(SaleRequest(listOf(line), SaleWorld.CASH, locationId = STALL))

        assertTrue(sold is RegisterSaleResult.Registered)
        val row = saleRows().single()
        assertEquals(lotL2, row.movement.key.lotId)
        assertEquals(STALL, row.movement.key.locationId)
        assertEquals(-2_000L, row.movement.quantity.milli)
    }

    @Test
    fun `selling 6 takes 3 from each lot in expiry order and costs 9000`() = runTest {
        stockedLots()

        assertTrue(sell(SaleLineRequest(1, QuantityMilli(6_000))) is RegisterSaleResult.Registered)

        val rows = saleRows()
        // L1 vence antes, aunque entró después: sale primero.
        assertEquals(listOf(lotL1, lotL2), lotsOf(rows))
        assertEquals(listOf(-3_000L, -3_000L), quantitiesOf(rows))
        assertEquals(listOf(-4_500L, -4_500L), costsOf(rows))
        assertEquals(Money(9_000), world.tables.lines.values.single().content.lineCost)
        assertEquals(QuantityMilli(2_000), world.tables.products.getValue(1).onHand)
    }

    @Test
    fun `selling 9 takes the missing unit from the unassigned lot without blocking`() = runTest {
        stockedLots()

        assertTrue(sell(SaleLineRequest(1, QuantityMilli(9_000))) is RegisterSaleResult.Registered)

        val rows = saleRows()
        val unassigned = world.tables.lots.values.single { it.isUnassigned }.id
        assertEquals(listOf(lotL1, lotL2, unassigned), lotsOf(rows))
        assertEquals(listOf(-3_000L, -5_000L, -1_000L), quantitiesOf(rows))
        // El total de la línea es la suma de sus filas, cada una valorada en orden.
        val lineCost = world.tables.lines.values.single().content.lineCost
        assertEquals(-costsOf(rows).sum(), lineCost.minor)
        assertEquals(QuantityMilli(-1_000), world.lotRepository.findStock(1, 1).last().onHand)
    }

    @Test
    fun `a chosen lot leaves first and the next line follows from what is left`() = runTest {
        stockedLots()

        val result = sell(
            SaleLineRequest(1, QuantityMilli(4_000), lotId = lotL2),
            SaleLineRequest(1, QuantityMilli(3_000)),
        )

        assertTrue(result is RegisterSaleResult.Registered)
        val rows = saleRows()
        assertEquals(listOf(lotL2, lotL1), lotsOf(rows))
        assertEquals(listOf(-4_000L, -3_000L), quantitiesOf(rows))
        val left = world.lotRepository.findStock(1, 1).map { it.onHand.milli }
        assertEquals(listOf(1_000L, 0L), left)
    }

    @Test
    fun `a lot of another product or for a product without lots is rejected`() = runTest {
        stockedLots()
        world.addProduct(product(2))

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.LOT_NOT_FOUND),
            sell(SaleLineRequest(2, QuantityMilli(1_000), lotId = lotL1)),
        )
        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.LOT_NOT_FOUND),
            sell(SaleLineRequest(1, QuantityMilli(1_000), lotId = 99L)),
        )
        assertTrue(saleRows().isEmpty())
    }

    @Test
    fun `a combo leaf leaves by expiry and a combo never takes a chosen lot`() = runTest {
        world.withCombos = true
        stockedLots()
        world.addProduct(product(5, salePrice = 9_000, stockMode = ProductStockMode.COMPONENTS))
        world.addRecipe(RecipeComponent(9L, 5, 1, QuantityMilli(2_000)))

        val result = sell(SaleLineRequest(5, QuantityMilli(3_000)))

        assertTrue(result is RegisterSaleResult.Registered)
        assertEquals(listOf(lotL1, lotL2), lotsOf(saleRows()))
        assertEquals(listOf(-3_000L, -3_000L), quantitiesOf(saleRows()))
        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.LOT_NOT_FOUND),
            sell(SaleLineRequest(5, QuantityMilli(1_000), lotId = lotL1)),
        )
    }

    @Test
    fun `voiding the sale gives each unit back to its lot`() = runTest {
        stockedLots()
        val sale = sell(SaleLineRequest(1, QuantityMilli(6_000))) as RegisterSaleResult.Registered

        world.voidSale()(sale.saleId, VoidReason.ENTRY_ERROR, null)

        val back = world.lotRepository.findStock(1, 1).map { it.onHand.milli }
        assertEquals(listOf(5_000L, 3_000L), back)
    }

    private companion object {
        const val STALL = 30L
    }
}
