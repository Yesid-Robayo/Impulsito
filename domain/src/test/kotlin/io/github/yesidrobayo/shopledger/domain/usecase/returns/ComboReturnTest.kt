package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Devolver un combo: cada hoja con stock vuelve en proporción, con su `SALE_RETURN` ligado a la
 * hoja, y el costo congelado se reparte con cantidades acumuladas. Los valores van a mano.
 *
 * Se venden 3 kits de 2 de A, 1 de B y un servicio de costo fijo 300. A tiene 6 unidades que valen
 * 10000, así que las 6 que salen cuestan 10000 justos; B vale 2500 por unidad.
 */
class ComboReturnTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        withCombos = true
        addProduct(product(A, onHandMilli = 6_000, averageCost = 1_667, stockValue = 10_000))
        addProduct(product(B, onHandMilli = 10_000, averageCost = 2_500, stockValue = 25_000))
        addProduct(product(SERVICE, stockMode = ProductStockMode.UNTRACKED, fixedUnitCost = 300))
        addProduct(product(KIT, salePrice = 9_000, stockMode = ProductStockMode.COMPONENTS))
        addRecipe(
            RecipeComponent(1L, KIT, A, QuantityMilli(2_000)),
            RecipeComponent(2L, KIT, B, QuantityMilli(1_000)),
            RecipeComponent(3L, KIT, SERVICE, QuantityMilli(1_000)),
        )
    }

    private suspend fun sellThreeKits(): Pair<Long, Long> {
        val result = world.registerSale()(
            SaleRequest(listOf(SaleLineRequest(KIT, QuantityMilli(3_000), null)), SaleWorld.CASH),
        )
        val saleId = (result as RegisterSaleResult.Registered).saleId
        return saleId to world.tables.lines.values.single().id
    }

    private suspend fun giveBack(
        saleId: Long,
        lineId: Long,
        milli: Long,
        restock: Boolean = true,
    ) = world.registerSaleReturn()(
        SaleReturnRequest(
            saleId,
            listOf(ReturnLineRequest(lineId, QuantityMilli(milli), restock)),
            ReturnSettlement.Refund(null),
        ),
    )

    @Test
    fun eachTrackedLeafComesBackInProportionAndAddsUpExactly() = runTest {
        val (saleId, lineId) = sellThreeKits()
        // 6 de A por 10000, 3 de B por 7500 y 3 servicios por 900.
        assertEquals(Money(18_400), world.tables.lines.getValue(lineId).content.lineCost)

        repeat(3) {
            val result = giveBack(saleId, lineId, 1_000)
            assertTrue(result is RegisterSaleReturnResult.Registered)
        }

        val returnsOfA = returns(A)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), returnsOfA.map { it.movement.quantity.milli })
        // 10000 en tres partes acumuladas: 3333, 3334 y 3333.
        assertEquals(listOf(3_333L, 3_334L, 3_333L), returnsOfA.map { it.totalCost.minor })
        assertEquals(listOf(2_500L, 2_500L, 2_500L), returns(B).map { it.totalCost.minor })
        // El servicio no tiene stock: no vuelve nada al libro.
        assertTrue(returns(SERVICE).isEmpty())
        val leafOfA =
            world.tables.lineComponents.values.single { it.content.componentProductId == A }
        assertTrue(returnsOfA.all { it.source.saleLineComponentId == leafOfA.id })
        assertTrue(returnsOfA.all { it.source.saleReturnLineId != null })
        // Todo volvió: A tiene sus 6 unidades por sus 10000, y B sus 10 por 25000.
        val a = world.tables.products.getValue(A)
        assertEquals(QuantityMilli(6_000), a.onHand)
        assertEquals(Money(10_000), a.stockValue)
        assertEquals(QuantityMilli(10_000), world.tables.products.getValue(B).onHand)
        // La cabecera devuelve la parte de la línea, con el servicio: 18400 / 3 por devolución.
        val headers = world.tables.saleReturns.values.sortedBy { it.id }
        assertEquals(
            listOf(6_133L, 6_134L, 6_133L),
            headers.map { it.content.costReturned.minor },
        )
    }

    @Test
    fun nothingMoreThanWasSoldComesBack() = runTest {
        val (saleId, lineId) = sellThreeKits()
        giveBack(saleId, lineId, 2_000)

        val result = giveBack(saleId, lineId, 2_000)

        assertTrue(result is RegisterSaleReturnResult.AboveRemaining)
        assertEquals(2, returns(A).size + returns(B).size)
    }

    @Test
    fun whatWasLostDoesNotComeBack() = runTest {
        val (saleId, lineId) = sellThreeKits()

        giveBack(saleId, lineId, 1_000, restock = false)

        assertTrue(returns(A).isEmpty())
        assertEquals(QuantityMilli.ZERO, world.tables.products.getValue(A).onHand)
    }

    @Test
    fun voidingTheReturnTakesEachLeafOutAgain() = runTest {
        val (saleId, lineId) = sellThreeKits()
        val returnId =
            (giveBack(saleId, lineId, 1_000) as RegisterSaleReturnResult.Registered).saleReturnId

        world.voidSaleReturn()(returnId, VoidReason.ENTRY_ERROR, null)

        val voids = world.tables.movements.values
            .filter { it.movement.type == StockMovementType.SALE_RETURN_VOID }
        assertEquals(2, voids.size)
        val sources = (returns(A) + returns(B)).map { it.source }.toSet()
        assertEquals(sources, voids.map { it.source }.toSet())
        assertEquals(QuantityMilli.ZERO, world.tables.products.getValue(A).onHand)
        assertEquals(QuantityMilli(7_000), world.tables.products.getValue(B).onHand)
    }

    private fun returns(productId: Long): List<StockMovementRecord> = world.tables.movements.values
        .filter {
            it.movement.type == StockMovementType.SALE_RETURN &&
                it.movement.key.productId == productId
        }
        .sortedBy { it.id }

    private companion object {
        const val A = 10L
        const val B = 11L
        const val SERVICE = 12L
        const val KIT = 20L
    }
}
