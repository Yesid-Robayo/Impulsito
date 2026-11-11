package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confirmar una venta retomada de la espera: la espera sale en la misma transacción que la venta,
 * así que nunca quedan las dos, y si la venta se rechaza, la espera sigue ahí.
 */
class RegisterHeldSaleTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addProduct(product(1, salePrice = 2_500))
        arrange {
            it.copy(
                heldSales = mapOf(
                    HELD to HeldSale(
                        id = HELD,
                        label = "Mesa 4",
                        lines = listOf(
                            HeldSaleLine(1, SaleWorld.UNIT, QuantityMilli(2_000), null, Money.ZERO),
                        ),
                        createdAt = clock.now(),
                        updatedAt = clock.now(),
                    ),
                ),
            )
        }
    }

    private fun request(milli: Long) = SaleRequest(
        listOf(SaleLineRequest(1, QuantityMilli(milli))),
        SaleWorld.CASH,
        heldSaleId = HELD,
    )

    @Test
    fun theHeldSaleLeavesWithTheSaleItBecame() = runTest {
        val result = world.registerSale()(request(2_000))

        assertTrue(result is RegisterSaleResult.Registered)
        assertTrue(world.tables.heldSales.isEmpty())
        // Una sola transacción, contada: la venta es un hecho del negocio.
        assertEquals(1, world.commits)
        assertEquals(1L, world.tables.changeSeq)
    }

    @Test
    fun aRejectedSaleLeavesTheHeldSaleWhereItWas() = runTest {
        val result = world.registerSale()(request(0))

        assertTrue(result is RegisterSaleResult.LineRejected)
        assertEquals(setOf(HELD), world.tables.heldSales.keys)
        assertTrue(world.tables.sales.isEmpty())
    }

    @Test
    fun aHeldSaleDiscardedMeanwhileDoesNotStopTheSale() = runTest {
        world.arrange { it.copy(heldSales = emptyMap()) }

        val result = world.registerSale()(request(1_000))

        assertTrue(result is RegisterSaleResult.Registered)
        assertEquals(1, world.tables.sales.size)
    }

    private companion object {
        const val HELD = 7L
    }
}
