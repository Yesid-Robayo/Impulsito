package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineLot
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRejection
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprar un producto que se controla por lotes: cada línea entra a su lote, nuevo o ya recibido,
 * o al lote sin asignar si no trae código. Productos, lotes y montos inventados.
 */
class PurchaseLotsTest {
    private val world = SaleWorld()

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private fun line(productId: Long, milli: Long, lot: PurchaseLineLot?, cost: Long = 1_000) =
        atUnitCost(productId, milli, cost).copy(lot = lot)

    private fun newLot(code: String, expiry: String? = null) =
        PurchaseLineLot.New(code, expiry?.let(::date))

    private suspend fun register(vararg lines: PurchaseLineRequest): RegisterPurchaseResult =
        world.registerPurchase()(PurchaseRequest(lines.toList()))

    @Test
    fun `a new lot is created with its code, expiry and the line it came with`() = runTest {
        world.addProduct(product(1, tracksLots = true))

        world.buy(line(1, 4_000, newLot(" A-118 ", "2026-11-20"), cost = 2_000))

        val lot = world.tables.lots.values.single()
        val purchaseLine = world.tables.purchaseLines.values.single()
        assertEquals("A-118", lot.code)
        assertEquals(date("2026-11-20"), lot.expiryDate)
        assertEquals(purchaseLine.id, lot.purchaseLineId)
        assertEquals(lot.id, purchaseLine.content.lotId)
        val row = world.movements().single()
        assertEquals(StockMovementType.PURCHASE, row.movement.type)
        assertEquals(lot.id, row.movement.key.lotId)
    }

    @Test
    fun `another delivery adds to its lot and a line without code goes unassigned`() = runTest {
        world.addProduct(product(1, tracksLots = true))
        world.buy(line(1, 2_000, newLot("L-1")))
        val lot = world.tables.lots.values.single().id
        val firstLine = world.tables.purchaseLines.values.single().id

        world.buy(line(1, 3_000, PurchaseLineLot.Existing(lot)), line(1, 1_000, lot = null))

        val unassigned = world.tables.lots.values.single { it.isUnassigned }.id
        val rows = world.movements()
        assertEquals(listOf(lot, lot, unassigned), rows.map { it.movement.key.lotId })
        assertEquals(QuantityMilli(5_000), world.lotRepository.findStock(1, 1).first().onHand)
        // Otra entrega no cambia la línea con que entró el lote.
        assertEquals(firstLine, world.tables.lots.getValue(lot).purchaseLineId)
        val last = world.tables.purchaseLines.values.maxBy { it.id }
        assertEquals(unassigned, last.content.lotId)
    }

    @Test
    fun `serials are lines of one unit, each with its own code`() = runTest {
        world.addProduct(product(1, tracksLots = true))

        world.buy(
            line(1, 1_000, newLot("SN-0001"), cost = 50_000),
            line(1, 1_000, newLot("SN-0002"), cost = 50_000),
        )

        assertEquals(listOf("SN-0001", "SN-0002"), world.tables.lots.values.map { it.code })
        assertEquals(QuantityMilli(2_000), world.tables.products.getValue(1).onHand)
    }

    @Test
    fun `each lot problem rejects its line without writing`() = runTest {
        world.addProduct(product(1, tracksLots = true))
        world.addProduct(product(2, tracksLots = true))
        world.addProduct(product(3))
        world.buy(line(1, 1_000, newLot("A-1")))
        val lotOfOne = world.tables.lots.values.single().id
        val before = world.tables

        val ok = line(1, 1_000, lot = null)
        val cases = listOf(
            line(3, 1_000, newLot("B-1")) to PurchaseLineRejection.LOT_NOT_TRACKED,
            line(2, 1_000, PurchaseLineLot.Existing(lotOfOne)) to
                PurchaseLineRejection.LOT_NOT_FOUND,
            line(1, 1_000, PurchaseLineLot.Existing(99)) to PurchaseLineRejection.LOT_NOT_FOUND,
            line(1, 1_000, newLot("  ")) to PurchaseLineRejection.INVALID_LOT_CODE,
            line(1, 1_000, newLot("x".repeat(61))) to PurchaseLineRejection.INVALID_LOT_CODE,
            line(1, 1_000, newLot("A-1")) to PurchaseLineRejection.LOT_CODE_REPEATED,
        )
        for ((rejected, reason) in cases) {
            assertEquals(RegisterPurchaseResult.LineRejected(1, reason), register(ok, rejected))
        }
        val twice = line(1, 1_000, newLot("C-1"))
        assertEquals(
            RegisterPurchaseResult.LineRejected(2, PurchaseLineRejection.LOT_CODE_REPEATED),
            register(ok, twice, twice),
        )
        assertEquals(before, world.tables)
        // El mismo código en otro producto sí vale: es único dentro del producto.
        assertTrue(register(line(2, 1_000, newLot("A-1"))) is RegisterPurchaseResult.Registered)
    }

    @Test
    fun `voiding the purchase takes the units back out of the same lot`() = runTest {
        world.addProduct(product(1, tracksLots = true))
        val purchaseId = world.buy(line(1, 2_000, newLot("V-1")))
        val lot = world.tables.lots.values.single().id

        world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        val compensation = world.movements().last()
        assertEquals(StockMovementType.PURCHASE_VOID, compensation.movement.type)
        assertEquals(lot, compensation.movement.key.lotId)
        assertEquals(QuantityMilli.ZERO, world.lotRepository.findStock(1, 1).single().onHand)
        assertNull(world.tables.lots.values.firstOrNull { it.isUnassigned })
    }

    @Test
    fun `a purchase whose lot was partly sold is not voided, and says what is missing`() = runTest {
        world.addMethod(method())
        world.addProduct(product(1, tracksLots = true))
        val purchaseId = world.buy(line(1, 5_000, newLot("V-2")))
        val lot = world.tables.lots.values.single().id
        world.registerSale()(
            SaleRequest(listOf(SaleLineRequest(1, QuantityMilli(3_000))), SaleWorld.CASH),
        )
        val before = world.tables

        val result = world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        // Anularla sacaría 5 de un lote que tiene 2: faltan 3, y no se escribe nada.
        assertEquals(VoidPurchaseResult.LotAlreadyUsed(1, lot, QuantityMilli(3_000)), result)
        assertEquals(before, world.tables)
    }

    @Test
    fun `a purchase into the unassigned lot is voided even if it goes negative`() = runTest {
        world.addMethod(method())
        world.addProduct(product(1, tracksLots = true))
        val purchaseId = world.buy(line(1, 2_000, lot = null))
        world.registerSale()(
            SaleRequest(listOf(SaleLineRequest(1, QuantityMilli(2_000))), SaleWorld.CASH),
        )

        val result = world.voidPurchase()(purchaseId, VoidReason.ENTRY_ERROR, null)

        assertEquals(VoidPurchaseResult.Voided, result)
        val unassigned = world.lotRepository.findStock(1, 1).single { it.isUnassigned }
        assertEquals(QuantityMilli(-2_000), unassigned.onHand)
    }
}
