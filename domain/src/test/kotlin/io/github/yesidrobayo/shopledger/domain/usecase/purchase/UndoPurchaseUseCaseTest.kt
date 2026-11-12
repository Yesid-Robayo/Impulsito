package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deshacer una compra: la anulación de siempre con el motivo de deshacer. El stock sale con su
 * compensatorio y el costo vuelve al de antes. Montos inventados.
 */
class UndoPurchaseUseCaseTest {
    private val world = SaleWorld()

    @Test
    fun `undo voids the purchase with its reason and compensates its stock`() = runTest {
        world.addProduct(product(1))
        world.open(1, 10_000, total = 10_000)
        val purchaseId = world.buy(atUnitCost(1, 1_000, cost = 50_000))

        val undo = UndoPurchaseUseCase(world.voidPurchase())
        assertEquals(VoidPurchaseResult.Voided, undo(purchaseId))

        val purchase = world.tables.purchases.getValue(purchaseId)
        assertEquals(VoidReason.UNDO, purchase.voiding?.reason)
        assertEquals(StockMovementType.PURCHASE_VOID, world.movements().last().movement.type)
        // Como el escenario A: el costo vuelve a 1000 exacto.
        assertEquals(cost(10_000, 1_000, 10_000), world.costOf(1))
    }
}
