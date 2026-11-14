package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLineComponent
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vender un combo: el ejemplo del costo por componentes, escrito a mano, las hojas congeladas, una
 * fila de venta por hoja con stock ligada a su hoja, los dos invariantes de la venta, la anulación
 * por movimiento y que una falla en cualquier escritura no deje nada.
 *
 * El kit lleva 2 de A, 1 de B y un servicio de costo fijo 300; A vale 1000 por unidad y B 2500.
 */
class RegisterComboSaleTest {
    private val world = newWorld()

    /** La venta del kit, con su receta y el stock de sus componentes. */
    private fun newWorld(): SaleWorld = SaleWorld().apply {
        addMethod(method())
        withCombos = true
        addProduct(product(A, onHandMilli = 10_000, averageCost = 1_000, stockValue = 10_000))
        addProduct(product(B, onHandMilli = 1_000, averageCost = 2_500, stockValue = 2_500))
        addProduct(product(SERVICE, stockMode = ProductStockMode.UNTRACKED, fixedUnitCost = 300))
        addProduct(product(KIT, salePrice = 9_000, stockMode = ProductStockMode.COMPONENTS))
        addRecipe(
            RecipeComponent(1L, KIT, A, QuantityMilli(2_000)),
            RecipeComponent(2L, KIT, B, QuantityMilli(1_000)),
            RecipeComponent(3L, KIT, SERVICE, QuantityMilli(1_000)),
        )
    }

    private suspend fun sell(vararg lines: SaleLineRequest): Long {
        val result = world.registerSale()(SaleRequest(lines.toList(), SaleWorld.CASH))
        return (result as RegisterSaleResult.Registered).saleId
    }

    private fun line(productId: Long, milli: Long) =
        SaleLineRequest(productId, QuantityMilli(milli), unitPrice = null)

    @Test
    fun twoKitsOfTheBusinessRulesExampleCost9600() = runTest {
        val saleId = sell(line(KIT, 2_000))

        val saleLine = world.tables.lines.values.single()
        assertEquals(LineStockEffect.COMPONENTS, saleLine.content.stockEffect)
        assertEquals(Money(9_600), saleLine.content.lineCost)
        // El costo unitario de la línea es el de una unidad del combo: 9600 por 1000 entre 2000.
        assertEquals(Money(4_800), saleLine.content.unitCost)
        assertEquals(Money(9_600), world.tables.sales.getValue(saleId).content.totalCost)
        val leaves = world.tables.lineComponents.values.sortedBy { it.id }
        assertEquals(
            listOf(
                NewSaleLineComponent(A, QuantityMilli(4_000), true, Money(1_000), Money(4_000)),
                // B tenía 1 unidad: sale 1 a su valor y 1 más al promedio, sin bloquear.
                NewSaleLineComponent(B, QuantityMilli(2_000), true, Money(2_500), Money(5_000)),
                NewSaleLineComponent(SERVICE, QuantityMilli(2_000), false, Money(300), Money(600)),
            ),
            leaves.map { it.content },
        )
        assertTrue(leaves.all { it.saleLineId == saleLine.id })
        val movements = saleMovements()
        assertEquals(listOf(A, B), movements.map { it.movement.key.productId })
        assertEquals(listOf(-4_000L, -2_000L), movements.map { it.movement.quantity.milli })
        assertEquals(listOf(-4_000L, -5_000L), movements.map { it.totalCost.minor })
        assertEquals(
            listOf(leaves[0].id, leaves[1].id),
            movements.map { it.source.saleLineComponentId },
        )
        assertTrue(movements.all { it.source.saleLineId == saleLine.id })
        assertEquals(QuantityMilli(6_000), world.tables.products.getValue(A).onHand)
        assertEquals(QuantityMilli(-1_000), world.tables.products.getValue(B).onHand)
        assertEquals(QuantityMilli.ZERO, world.tables.products.getValue(KIT).onHand)
        assertEquals(1, world.commits)
        assertSaleInvariantsHold()
    }

    @Test
    fun aComponentAlsoSoldAloneContinuesFromTheCostTheComboLeft() = runTest {
        // El kit se lleva 4 de A al promedio 1000; la línea suelta de A sigue desde ese estado.
        sell(line(KIT, 2_000), line(A, 1_000))

        val movements = saleMovements().filter { it.movement.key.productId == A }
        assertEquals(listOf(-4_000L, -1_000L), movements.map { it.movement.quantity.milli })
        assertEquals(listOf(-4_000L, -1_000L), movements.map { it.totalCost.minor })
        assertEquals(QuantityMilli(5_000), world.tables.products.getValue(A).onHand)
        assertSaleInvariantsHold()
    }

    @Test
    fun aComboInsideACombo() = runTest {
        world.addProduct(product(BOX, salePrice = 20_000, stockMode = ProductStockMode.COMPONENTS))
        world.addRecipe(
            RecipeComponent(4L, BOX, KIT, QuantityMilli(1_500)),
            RecipeComponent(5L, BOX, A, QuantityMilli(1_000)),
        )

        sell(line(BOX, 1_000))

        // 1,5 kits llevan 3 de A, 1,5 de B y 1,5 servicios; más 1 de A suelto en la caja.
        val leaves = world.tables.lineComponents.values.sortedBy { it.id }.map { it.content }
        assertEquals(listOf(A, B, SERVICE, A), leaves.map { it.componentProductId })
        assertEquals(listOf(3_000L, 1_500L, 1_500L, 1_000L), leaves.map { it.quantity.milli })
        assertSaleInvariantsHold()
    }

    @Test
    fun voidingAComboCreatesOneVoidPerSaleMovement() = runTest {
        val saleId = sell(line(KIT, 2_000))

        val result = world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null)

        assertTrue(result is VoidSaleResult.Voided)
        val voids = world.tables.movements.values.filter {
            it.movement.type == StockMovementType.SALE_VOID
        }
        assertEquals(2, voids.size)
        assertEquals(
            saleMovements().map { it.id }.toSet(),
            voids.mapNotNull { it.movement.reversesMovementId }.toSet(),
        )
        // Cada anulación queda ligada a la misma hoja que su venta.
        assertEquals(
            saleMovements().map { it.source }.toSet(),
            voids.map { it.source }.toSet(),
        )
        assertEquals(QuantityMilli(10_000), world.tables.products.getValue(A).onHand)
        assertEquals(QuantityMilli(1_000), world.tables.products.getValue(B).onHand)
        // Las hojas no se borran ni cambian: explican lo que salió.
        assertEquals(3, world.tables.lineComponents.size)
    }

    @Test
    fun aLeafThatRoundsToZeroIsRejected() = runTest {
        // Una caja lleva una milésima de kit, y el kit 0,4 de otro producto: una caja consume
        // 1000 × 1 × 400 / 1000000 = 0,4 milésimas, que redondea a cero.
        world.addProduct(product(BOX, salePrice = 100, stockMode = ProductStockMode.COMPONENTS))
        world.addProduct(product(TINY, onHandMilli = 1_000))
        world.addRecipe(
            RecipeComponent(9L, KIT, TINY, QuantityMilli(400)),
            RecipeComponent(10L, BOX, KIT, QuantityMilli(1)),
        )

        val result = world.registerSale()(SaleRequest(listOf(line(BOX, 1_000)), SaleWorld.CASH))

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.COMPONENT_ROUNDS_TO_ZERO),
            result,
        )
        assertTrue(world.tables.sales.isEmpty())
    }

    @Test
    fun aComponentWithLotsIsRejectedWithoutTheLots() = runTest {
        world.withLots = false
        world.addProduct(product(LOTS, tracksLots = true))
        world.addRecipe(RecipeComponent(9L, KIT, LOTS, QuantityMilli(1_000)))

        val result = world.registerSale()(SaleRequest(listOf(line(KIT, 1_000)), SaleWorld.CASH))

        assertEquals(RegisterSaleResult.LineRejected(0, LineRejection.LOTS_NOT_SUPPORTED), result)
    }

    @Test
    fun aThirdLevelFromADamagedBaseIsRejected() = runTest {
        world.addProduct(product(BOX, stockMode = ProductStockMode.COMPONENTS))
        world.addProduct(product(TRAY, stockMode = ProductStockMode.COMPONENTS))
        world.addRecipe(
            RecipeComponent(4L, TRAY, BOX, QuantityMilli(1_000)),
            RecipeComponent(5L, BOX, KIT, QuantityMilli(1_000)),
        )

        val result = world.registerSale()(SaleRequest(listOf(line(TRAY, 1_000)), SaleWorld.CASH))

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.COMPONENTS_NOT_SUPPORTED),
            result,
        )
    }

    @Test
    fun aLeafAboveTheQuantityOfALineIsRejected() = runTest {
        // Un millón de kits llevarían dos millones de A: más que el tope de una línea.
        val result = world.registerSale()(
            SaleRequest(listOf(line(KIT, 1_000_000_000)), SaleWorld.CASH),
        )

        assertEquals(RegisterSaleResult.LineRejected(0, LineRejection.INVALID_QUANTITY), result)
    }

    @Test
    fun anArchivedComponentIsStillDiscounted() = runTest {
        world.arrange { tables ->
            val archived = tables.products.getValue(B).copy(archivedAt = SaleWorld.EPOCH)
            tables.copy(products = tables.products + (B to archived))
        }

        sell(line(KIT, 1_000))

        assertEquals(QuantityMilli.ZERO, world.tables.products.getValue(B).onHand)
    }

    @Test
    fun aFailureAtEachWriteLeavesNothing() = runTest {
        val request = SaleRequest(listOf(line(KIT, 2_000)), SaleWorld.CASH)
        // Falla en la escritura 1, 2, 3... hasta que ya no queda ninguna por fallar y se confirma.
        var failAt = 1
        while (true) {
            val fresh = newWorld()
            fresh.failAtWrite = failAt
            val failed = runCatching { fresh.registerSale()(request) }.exceptionOrNull()
            if (failed == null) {
                assertEquals(1, fresh.tables.sales.size)
                break
            }
            assertTrue("Escritura $failAt", failed is InjectedSaleFailure)
            assertTrue(fresh.tables.sales.isEmpty())
            assertTrue(fresh.tables.lineComponents.isEmpty())
            assertTrue(fresh.tables.movements.isEmpty())
            assertEquals(QuantityMilli(10_000), fresh.tables.products.getValue(A).onHand)
            failAt++
        }
        // Cabecera, línea, tres hojas, dos filas con su producto y el pago, cada uno con su id.
        assertTrue("Escrituras: ${failAt - 1}", failAt > 10)
    }

    @Test
    fun withoutRecipesAComboIsStillRejected() = runTest {
        world.withCombos = false

        val result = world.registerSale()(SaleRequest(listOf(line(KIT, 1_000)), SaleWorld.CASH))

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.COMPONENTS_NOT_SUPPORTED),
            result,
        )
    }

    private fun saleMovements(): List<StockMovementRecord> = world.tables.movements.values
        .filter { it.movement.type == StockMovementType.SALE }
        .sortedBy { it.id }

    /**
     * Las dos reglas de la venta, como sus consultas sobre la base: cada hoja con stock tiene
     * filas de venta que suman su cantidad, y ninguna hoja guardada es un combo.
     */
    private fun assertSaleInvariantsHold() {
        val tables = world.tables
        for (leaf in tables.lineComponents.values) {
            val sold = tables.movements.values.filter {
                it.source.saleLineComponentId == leaf.id &&
                    it.movement.type == StockMovementType.SALE
            }
            if (leaf.content.tracked) {
                assertEquals(
                    -leaf.content.quantity.milli,
                    sold.sumOf { it.movement.quantity.milli },
                )
            } else {
                assertTrue(sold.isEmpty())
            }
            val mode = tables.products.getValue(leaf.content.componentProductId).details.stockMode
            assertTrue(mode != ProductStockMode.COMPONENTS)
        }
    }

    private companion object {
        const val A = 10L
        const val B = 11L
        const val SERVICE = 12L
        const val KIT = 20L
        const val BOX = 21L
        const val TRAY = 22L
        const val TINY = 30L
        const val LOTS = 31L
    }
}
