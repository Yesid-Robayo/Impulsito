package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deshacer la venta recién confirmada: es la anulación de siempre, con el motivo `UNDO`, en una
 * sola transacción. La venta sigue en el historial, anulada, y el libro recibe su compensatorio.
 */
class UndoSaleUseCaseTest {
    private fun world(): SaleWorld = SaleWorld().apply {
        addMethod(method())
        addProduct(product(1, salePrice = 2_500))
        addProduct(product(2, salePrice = 900))
    }

    private suspend fun SaleWorld.sell(): Long {
        val request = SaleRequest(
            listOf(
                SaleLineRequest(1, QuantityMilli(2_000)),
                SaleLineRequest(2, QuantityMilli(1_000)),
            ),
            SaleWorld.CASH,
        )
        return (registerSale()(request) as RegisterSaleResult.Registered).saleId
    }

    private fun SaleWorld.undo() = UndoSaleUseCase(voidSale())

    @Test
    fun undoVoidsTheSaleWithTheUndoReasonInOneTransaction() = runTest {
        val world = world()
        val saleId = world.sell()
        val writesBefore = world.writeCalls
        val changesBefore = world.tables.changeSeq

        assertEquals(VoidSaleResult.Voided, world.undo()(saleId))

        // Una transacción, confirmada y contada como un cambio: deshacer es un hecho del negocio.
        assertEquals(writesBefore + 1, world.writeCalls)
        assertEquals(changesBefore + 1, world.tables.changeSeq)
        val sale = world.tables.sales.getValue(saleId)
        assertEquals(DocumentStatus.VOIDED, sale.status)
        assertEquals(VoidReason.UNDO, sale.voiding?.reason)
        assertNull(sale.voiding?.note)
        assertEquals(world.clock.now(), sale.voiding?.voidedAt)
        // Nada se borra: la venta, sus líneas y su pago siguen ahí.
        assertEquals(2, world.tables.lines.size)
        assertEquals(1, world.tables.payments.size)
        // Cada producto vuelve a su saldo de antes de vender.
        assertEquals(QuantityMilli.ZERO, world.tables.products.getValue(1).onHand)
        assertEquals(QuantityMilli.ZERO, world.tables.products.getValue(2).onHand)
    }

    @Test
    fun undoCompensatesExactlyLikeAVoid() = runTest {
        val undone = world()
        val voided = world()
        val undoneId = undone.sell()
        val voidedId = voided.sell()

        assertEquals(VoidSaleResult.Voided, undone.undo()(undoneId))
        assertEquals(
            VoidSaleResult.Voided,
            voided.voidSale()(voidedId, VoidReason.ENTRY_ERROR, null),
        )

        // El libro y los productos quedan iguales: solo cambia el motivo guardado en la venta.
        assertEquals(voided.tables.movements, undone.tables.movements)
        assertEquals(voided.tables.products, undone.tables.products)
        assertEquals(
            voided.tables.sales.getValue(voidedId).let {
                it.copy(voiding = it.voiding?.copy(reason = VoidReason.UNDO))
            },
            undone.tables.sales.getValue(undoneId),
        )
        val compensations = undone.tables.movements.values
            .filter { it.movement.type == StockMovementType.SALE_VOID }
        assertEquals(2, compensations.size)
    }

    @Test
    fun aSecondUndoChangesNothing() = runTest {
        val world = world()
        val saleId = world.sell()
        assertEquals(VoidSaleResult.Voided, world.undo()(saleId))
        val afterFirst = world.tables

        assertEquals(VoidSaleResult.AlreadyVoided, world.undo()(saleId))

        assertEquals(afterFirst, world.tables)
    }

    @Test
    fun undoingAMissingSaleWritesNothing() = runTest {
        val world = world()
        world.sell()
        val before = world.tables

        assertEquals(VoidSaleResult.SaleNotFound, world.undo()(99))

        assertEquals(before, world.tables)
    }
}
