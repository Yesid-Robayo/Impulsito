package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRejection
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sum
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.measureUnit
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Comprar por caja y vender por unidad, de punta a punta: la línea de compra congela la unidad, el
 * factor y la cantidad escrita, el libro suma la cantidad en la unidad base con el total de la
 * línea, y vender las unidades una a una se lleva exactamente lo pagado. El caso de 24 unidades por
 * 10000 es el de la regla del valor del stock. Montos inventados.
 */
class PurchaseUnitsTest {
    private val world = SaleWorld().apply {
        units = units + mapOf(
            BOX to measureUnit(BOX, "BOX", UnitKind.COUNT, allowsFraction = false),
            GRAM to measureUnit(GRAM, "GRAM", UnitKind.MASS, allowsFraction = true),
        )
    }

    private fun conversion(
        productId: Long,
        unitId: Long,
        factorMilli: Long,
        forPurchase: Boolean = true,
    ) = ProductConversion(
        id = productId * 100 + unitId,
        productId = productId,
        details = ConversionDetails(unitId, factorMilli, null, null, true, forPurchase),
    )

    private fun boxes(milli: Long, total: Long, unitId: Long? = BOX, productId: Long = 1) =
        PurchaseLineRequest(
            productId,
            QuantityMilli(milli),
            PurchaseLineAmount.LineTotal(Money(total)),
            unitId,
        )

    private suspend fun register(vararg lines: PurchaseLineRequest) =
        world.registerPurchase()(PurchaseRequest(lines.toList()))

    @Test
    fun `a box of twenty four for ten thousand sells out at exactly what was paid`() = runTest {
        world.addProduct(product(1, salePrice = 500))
        world.conversions = listOf(conversion(1, BOX, 24_000))

        assertEquals(
            true,
            register(boxes(1_000, total = 10_000)) is RegisterPurchaseResult.Registered,
        )

        val line = world.tables.purchaseLines.values.single().content
        assertEquals(BOX, line.unitId)
        assertEquals(QuantityMilli(24_000), line.unitFactor)
        assertEquals(QuantityMilli(1_000), line.enteredQuantity)
        assertEquals(QuantityMilli(24_000), line.quantity)
        // El costo unitario de la línea va por caja; el promedio, por unidad base.
        assertEquals(Money(10_000), line.unitCost)
        assertEquals(cost(24_000, 417, 10_000), world.costOf(1))
        assertEquals(Money(417), world.movements().single().unitCost)

        repeat(24) { world.sell(1, 1_000) }

        val costs = world.tables.lines.values.sortedBy { it.id }.map(SaleLine::content)
            .map { it.lineCost }
        assertEquals(Money(10_000), costs.sum())
        assertEquals(cost(0, 417, 0), world.costOf(1))
    }

    @Test
    fun `three boxes at a unit cost add seventy two units`() = runTest {
        world.addProduct(product(1))
        world.conversions = listOf(conversion(1, BOX, 24_000))

        val request = PurchaseLineRequest(
            1,
            QuantityMilli(3_000),
            PurchaseLineAmount.UnitCost(Money(10_000)),
            BOX,
        )
        register(request)

        val line = world.tables.purchaseLines.values.single().content
        assertEquals(QuantityMilli(72_000), line.quantity)
        assertEquals(Money(30_000), line.lineTotal)
        assertEquals(cost(72_000, 417, 30_000), world.costOf(1))
    }

    @Test
    fun `the base unit can be named or left out`() = runTest {
        world.addProduct(product(1))
        world.addProduct(product(2))

        register(boxes(2_000, total = 1_000, unitId = SaleWorld.UNIT), boxes(2_000, 1_000, null, 2))

        val (named, omitted) = world.tables.purchaseLines.values.sortedBy {
            it.id
        }.map { it.content }
        assertEquals(named.copy(productId = 0), omitted.copy(productId = 0))
        assertEquals(QuantityMilli(1_000), named.unitFactor)
    }

    @Test
    fun `a unit the product does not buy in is rejected`() = runTest {
        world.addProduct(product(1))
        world.conversions = listOf(conversion(1, BOX, 24_000, forPurchase = false))

        assertEquals(
            RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.UNIT_UNAVAILABLE),
            register(boxes(1_000, total = 100)),
        )
        assertEquals(
            RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.UNIT_UNAVAILABLE),
            register(boxes(1_000, total = 100, unitId = GRAM)),
        )
    }

    @Test
    fun `quantities are checked in the unit of the line and in the base`() = runTest {
        world.addProduct(product(1))
        world.addProduct(product(2, unitId = SaleWorld.KILOGRAM))
        world.conversions = listOf(
            conversion(1, BOX, 24_000),
            conversion(2, GRAM, 1),
            // Un factor que la base no deja guardar, como el de una copia dañada.
            conversion(1, GRAM, 0),
        )

        val cases = listOf(
            boxes(1_500, total = 100) to PurchaseLineRejection.FRACTION_NOT_ALLOWED,
            // 0,4 g de un producto por kilogramo redondea a cero en la base.
            boxes(400, total = 100, unitId = GRAM, productId = 2) to
                PurchaseLineRejection.INVALID_QUANTITY,
            // Un millón de cajas de 24 pasa del máximo de una línea en la base.
            boxes(1_000_000_000, total = 100) to PurchaseLineRejection.INVALID_QUANTITY,
            boxes(1_000, total = 100, unitId = GRAM) to PurchaseLineRejection.UNIT_UNAVAILABLE,
        )
        for ((line, reason) in cases) {
            assertEquals(RegisterPurchaseResult.LineRejected(0, reason), register(line))
        }
        // 250 g de un producto por kilogramo son 0,250 kg.
        register(boxes(250_000, total = 3_125, unitId = GRAM, productId = 2))
        assertEquals(
            QuantityMilli(250),
            world.tables.purchaseLines.values.single().content.quantity,
        )
    }

    private companion object {
        const val BOX = 3L
        const val GRAM = 4L
    }
}
