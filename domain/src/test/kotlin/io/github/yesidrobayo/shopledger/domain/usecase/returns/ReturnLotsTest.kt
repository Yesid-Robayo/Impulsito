package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineLot
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRejection
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.atUnitCost
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.buy
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.TestLocations
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.sale.testLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Devolver y anular con lotes: lo devuelto vuelve a los lotes de los que salió, en la ubicación de
 * la venta, y nada deja negativo un lote con código. Lotes de 3 y 5 a 1500, como el ejemplo de las
 * reglas. Productos, lotes y montos inventados.
 */
class ReturnLotsTest {
    private val world = SaleWorld().apply { addMethod(method()) }
    private var lotL1 = 0L
    private var lotL2 = 0L

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    /** El producto 1 con L1, 3 que vencen antes, y L2, 5, en [location] o la inicial. */
    private suspend fun stockedLots(location: Long? = null) {
        world.addProduct(product(1, tracksLots = true))
        val l2 = atUnitCost(1, 5_000, cost = 1_500)
            .copy(lot = PurchaseLineLot.New("L2", date("2026-11-20")))
        val l1 = atUnitCost(1, 3_000, cost = 1_500)
            .copy(lot = PurchaseLineLot.New("L1", date("2026-11-10")))
        world.registerPurchase()(PurchaseRequest(listOf(l2, l1), locationId = location))
        lotL2 = world.tables.lots.values.single { it.code == "L2" }.id
        lotL1 = world.tables.lots.values.single { it.code == "L1" }.id
    }

    /** Vende [milli] del producto 1 y devuelve el id de la venta y el de su línea. */
    private suspend fun sell(milli: Long, location: Long? = null): Pair<Long, Long> {
        val line = SaleLineRequest(1, QuantityMilli(milli))
        val request = SaleRequest(listOf(line), SaleWorld.CASH, locationId = location)
        val sold = world.registerSale()(request)
        val saleId = (sold as RegisterSaleResult.Registered).saleId
        return saleId to world.tables.lines.values.single { it.saleId == saleId }.id
    }

    private suspend fun giveBack(saleId: Long, lineId: Long, milli: Long) =
        world.registerSaleReturn()(
            SaleReturnRequest(
                saleId,
                listOf(ReturnLineRequest(lineId, QuantityMilli(milli))),
                ReturnSettlement.Refund(null),
            ),
        )

    private fun returnRows(): List<StockMovementRecord> = world.tables.movements.values
        .filter { it.movement.type == StockMovementType.SALE_RETURN }
        .sortedBy { it.id }

    private suspend fun onHand(lotId: Long, location: Long = SaleWorld.MAIN_LOCATION) =
        world.ledger.onHandAt(StockKey(1, location, lotId))

    @Test
    fun `returns go back to the lots they left, in exit order, with their cost`() = runTest {
        stockedLots()
        // Salen 3 de L1 y 3 de L2.
        val (saleId, lineId) = sell(6_000)

        assertTrue(giveBack(saleId, lineId, 2_000) is RegisterSaleReturnResult.Registered)
        assertTrue(giveBack(saleId, lineId, 3_000) is RegisterSaleReturnResult.Registered)

        val rows = returnRows()
        assertEquals(listOf(lotL1, lotL1, lotL2), rows.map { it.movement.key.lotId })
        assertEquals(listOf(2_000L, 1_000L, 2_000L), rows.map { it.movement.quantity.milli })
        // Cada tramo lleva su parte del costo congelado de 1500 por unidad.
        assertEquals(listOf(3_000L, 1_500L, 3_000L), rows.map { it.totalCost.minor })
        assertEquals(QuantityMilli(3_000), onHand(lotL1))
        assertEquals(QuantityMilli(4_000), onHand(lotL2))
    }

    @Test
    fun `what was sold from the unassigned lot comes back to it`() = runTest {
        stockedLots()
        // Salen 3 de L1, 5 de L2 y 1 del sin asignar.
        val (saleId, lineId) = sell(9_000)
        val unassigned = world.tables.lots.values.single { it.isUnassigned }.id

        giveBack(saleId, lineId, 9_000)

        assertEquals(
            listOf(lotL1, lotL2, unassigned),
            returnRows().map { it.movement.key.lotId },
        )
        assertEquals(QuantityMilli.ZERO, onHand(unassigned))
    }

    @Test
    fun `a return at the stall comes back to the stall`() = runTest {
        world.locationRepository = TestLocations(listOf(testLocation(STALL, archived = false)))
        stockedLots(location = STALL)
        val (saleId, lineId) = sell(2_000, location = STALL)

        giveBack(saleId, lineId, 2_000)

        val row = returnRows().single()
        assertEquals(StockKey(1, STALL, lotL1), row.movement.key)
        assertEquals(QuantityMilli(3_000), onHand(lotL1, STALL))
    }

    @Test
    fun `voiding a return whose units were sold again is refused with what is missing`() = runTest {
        stockedLots()
        val (saleId, lineId) = sell(3_000)
        val returned = giveBack(saleId, lineId, 3_000) as RegisterSaleReturnResult.Registered
        // Vuelven 3 a L1, y se venden otra vez: L1 queda en 0.
        sell(3_000)
        val before = world.tables

        val result = world.voidSaleReturn()(returned.saleReturnId, VoidReason.ENTRY_ERROR, null)

        assertEquals(
            VoidSaleReturnResult.LotAlreadyUsed(1, lotL1, QuantityMilli(3_000)),
            result,
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun `a supplier return leaves from the lot of its purchase line and never below zero`() =
        runTest {
            world.addProduct(product(1, tracksLots = true))
            val purchaseId = world.buy(
                atUnitCost(1, 5_000, cost = 1_500).copy(lot = PurchaseLineLot.New("L9", null)),
            )
            val lineId = world.tables.purchaseLines.values.single().id
            val lot = world.tables.lots.values.single().id
            sell(4_000)

            val tooMuch = supplierReturn(purchaseId, lineId, 2_000)
            val enough = supplierReturn(purchaseId, lineId, 1_000)

            val above = SupplierReturnLineRejection.ABOVE_LOT_STOCK
            assertEquals(RegisterSupplierReturnResult.LineRejected(0, above), tooMuch)
            assertTrue(enough is RegisterSupplierReturnResult.Registered)
            val row = world.tables.movements.values.last()
            assertEquals(StockMovementType.PURCHASE_RETURN, row.movement.type)
            assertEquals(lot, row.movement.key.lotId)
            assertEquals(QuantityMilli.ZERO, onHand(lot))
        }

    @Test
    fun `a loose supplier return of a product with lots leaves from the unassigned lot`() =
        runTest {
            world.addProduct(product(1, tracksLots = true))
            val lotLine = atUnitCost(1, 2_000, cost = 1_500)
            world.buy(lotLine.copy(lot = PurchaseLineLot.New("L9", null)))

            val result = world.registerSupplierReturn()(
                SupplierReturnRequest(
                    lines = listOf(SupplierReturnLineRequest.Loose(1, QuantityMilli(1_000))),
                    resolution = PurchaseReturnResolution.CASH_REFUND,
                ),
            )

            assertTrue(result is RegisterSupplierReturnResult.Registered)
            val unassigned = world.tables.lots.values.single { it.isUnassigned }.id
            assertEquals(unassigned, world.tables.movements.values.last().movement.key.lotId)
            assertEquals(QuantityMilli(-1_000), onHand(unassigned))
        }

    private suspend fun supplierReturn(purchaseId: Long, lineId: Long, milli: Long) =
        world.registerSupplierReturn()(
            SupplierReturnRequest(
                lines = listOf(
                    SupplierReturnLineRequest.FromPurchaseLine(lineId, QuantityMilli(milli)),
                ),
                resolution = PurchaseReturnResolution.CASH_REFUND,
                purchaseId = purchaseId,
            ),
        )

    private companion object {
        const val STALL = 30L
    }
}
