package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un insumo no se vende: la venta lo rechaza con su motivo, sin escribir nada. Lo que se vende y
 * también va en recetas se vende como siempre, y un combo que se vende saca el stock de sus
 * insumos. Datos inventados.
 */
class RegisterSaleUsageTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        withCombos = true
        addProduct(
            product(
                FLOUR,
                name = "Harina",
                salePrice = 900,
                onHandMilli = 10_000,
                averageCost = 300,
                stockValue = 3_000,
                usage = ProductUsage.INPUT,
            ),
        )
        addProduct(
            product(
                SUGAR,
                name = "Azucar",
                salePrice = 700,
                onHandMilli = 5_000,
                averageCost = 200,
                stockValue = 1_000,
                usage = ProductUsage.BOTH,
            ),
        )
        addProduct(
            product(
                BREAD,
                name = "Pan",
                salePrice = 1_500,
                stockMode = ProductStockMode.COMPONENTS,
            ),
        )
        addRecipe(RecipeComponent(1L, BREAD, FLOUR, QuantityMilli(2_000)))
    }

    private fun line(productId: Long, milli: Long) =
        SaleLineRequest(productId, QuantityMilli(milli), unitPrice = null)

    private suspend fun sell(vararg lines: SaleLineRequest): RegisterSaleResult =
        world.registerSale()(SaleRequest(lines.toList(), SaleWorld.CASH))

    @Test
    fun `an input is rejected with its reason and nothing is written`() = runTest {
        val result = sell(line(SUGAR, 1_000), line(FLOUR, 1_000))

        assertEquals(
            RegisterSaleResult.LineRejected(1, LineRejection.PRODUCT_NOT_SOLD),
            result,
        )
        assertEquals(0, world.commits)
        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.movements.isEmpty())
        assertEquals(QuantityMilli(10_000), world.tables.products.getValue(FLOUR).onHand)
    }

    @Test
    fun `an archived input says it is archived first`() = runTest {
        world.addProduct(
            product(OLD, archived = true, usage = ProductUsage.INPUT),
        )

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.PRODUCT_ARCHIVED),
            sell(line(OLD, 1_000)),
        )
    }

    @Test
    fun `what is also an input is sold as always`() = runTest {
        val result = sell(line(SUGAR, 2_000))

        assertTrue(result is RegisterSaleResult.Registered)
        assertEquals(QuantityMilli(3_000), world.tables.products.getValue(SUGAR).onHand)
    }

    @Test
    fun `a combo that is sold takes the stock of its inputs`() = runTest {
        val result = sell(line(BREAD, 1_000))

        assertTrue(result is RegisterSaleResult.Registered)
        val movements = world.tables.movements.values
            .filter { it.movement.type == StockMovementType.SALE }
        assertEquals(listOf(FLOUR), movements.map { it.movement.key.productId })
        assertEquals(QuantityMilli(8_000), world.tables.products.getValue(FLOUR).onHand)
    }

    private companion object {
        const val FLOUR = 10L
        const val SUGAR = 11L
        const val BREAD = 20L
        const val OLD = 30L
    }
}
