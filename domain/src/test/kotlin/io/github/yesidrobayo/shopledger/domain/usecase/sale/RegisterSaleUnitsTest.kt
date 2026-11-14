package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCart
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCorrections
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vender en otra unidad: la línea congela el factor de la conversión, cobra por unidad entera de
 * esa unidad y saca del libro la cantidad en la unidad base, así que el saldo del producto sigue
 * siendo la suma de su libro. Los ejemplos de la conversión van como pruebas. Montos inventados.
 */
class RegisterSaleUnitsTest {
    private val world = SaleWorld().apply {
        units = units + mapOf(
            BOX to measureUnit(BOX, "BOX", UnitKind.COUNT, allowsFraction = false),
            GRAM to measureUnit(GRAM, "GRAM", UnitKind.MASS, allowsFraction = false),
        )
        addMethod(method())
    }

    private fun conversion(
        productId: Long,
        unitId: Long,
        factorMilli: Long,
        price: Long? = null,
        forSale: Boolean = true,
    ) = ProductConversion(
        id = productId * 100 + unitId,
        productId = productId,
        details = ConversionDetails(unitId, factorMilli, price?.let(::Money), null, forSale, true),
    )

    private suspend fun sell(vararg lines: SaleLineRequest): RegisterSaleResult =
        world.registerSale()(SaleRequest(lines.toList(), SaleWorld.CASH))

    private fun storedLine(): SaleLine = world.tables.lines.values.single()

    @Test
    fun threeBoxesOfTwentyFourLeaveSeventyTwoUnitsTheLedger() = runTest {
        // Base por unidad a 500, caja de 24 sin precio propio: la caja cuesta 24 por 500.
        world.addProduct(
            product(
                1,
                salePrice = 500,
                onHandMilli = 100_000,
                averageCost = 300,
                stockValue = 30_000,
            ),
        )
        world.conversions = listOf(conversion(1, BOX, 24_000))

        val result = sell(SaleLineRequest(1, QuantityMilli(3_000), unitId = BOX))

        assertTrue(result is RegisterSaleResult.Registered)
        val line = storedLine().content
        assertEquals(BOX, line.unitId)
        assertEquals(QuantityMilli(24_000), line.unitFactor)
        assertEquals(QuantityMilli(3_000), line.enteredQuantity)
        assertEquals(QuantityMilli(72_000), line.quantity)
        assertEquals(Money(12_000), line.unitPrice)
        assertEquals(Money(36_000), line.lineTotal)
        // 72 unidades a 300 de costo promedio.
        assertEquals(Money(21_600), line.lineCost)
        val movement = world.tables.movements.values.single()
        assertEquals(StockMovementType.SALE, movement.movement.type)
        assertEquals(QuantityMilli(-72_000), movement.movement.quantity)
        // El saldo sigue siendo lo que había más el libro, en la unidad base.
        assertEquals(QuantityMilli(28_000), world.tables.products.getValue(1).onHand)
        assertEquals(movement.balanceAfter, world.tables.products.getValue(1).onHand)
    }

    @Test
    fun twoHundredFiftyGramsOfAProductByTheKiloAreAQuarterKilo() = runTest {
        // A 20000 el kilo, un gramo vale 20.
        world.addProduct(product(2, salePrice = 20_000, unitId = SaleWorld.KILOGRAM))
        world.conversions = listOf(conversion(2, GRAM, 1))

        sell(SaleLineRequest(2, QuantityMilli(250_000), unitId = GRAM))

        val line = storedLine().content
        assertEquals(QuantityMilli(250), line.quantity)
        assertEquals(QuantityMilli(1), line.unitFactor)
        assertEquals(Money(20), line.unitPrice)
        assertEquals(Money(5_000), line.lineTotal)
    }

    @Test
    fun aBoxWithItsOwnPriceSellsAtThatPriceUnlessTheLineSaysOtherwise() = runTest {
        world.addProduct(product(1, salePrice = 500))
        world.conversions = listOf(conversion(1, BOX, 24_000, price = 10_000))

        sell(
            SaleLineRequest(1, QuantityMilli(1_000), unitId = BOX),
            SaleLineRequest(1, QuantityMilli(2_000), Money(9_000), unitId = BOX),
        )

        val lines = world.tables.lines.values.sortedBy { it.id }.map { it.content }
        assertEquals(listOf(Money(10_000), Money(9_000)), lines.map { it.unitPrice })
        assertEquals(listOf(Money(10_000), Money(18_000)), lines.map { it.lineTotal })
    }

    @Test
    fun aBoxOfTwentyFourBoughtForTenThousandSellsForExactlyItsCost() = runTest {
        // Un ejemplo de la conversión: la caja comprada en 10000 deja ese valor en el stock, y
        // venderla entera saca exactamente ese costo, sin restos de redondeo.
        world.addProduct(
            product(
                1,
                salePrice = 500,
                onHandMilli = 24_000,
                averageCost = 417,
                stockValue = 10_000,
            ),
        )
        world.conversions = listOf(conversion(1, BOX, 24_000))

        sell(SaleLineRequest(1, QuantityMilli(1_000), unitId = BOX))

        assertEquals(Money(10_000), storedLine().content.lineCost)
        assertEquals(Money.ZERO, world.tables.products.getValue(1).stockValue)
    }

    @Test
    fun aServiceInAnotherUnitLeavesNoLedgerRow() = runTest {
        world.addProduct(
            product(
                5,
                salePrice = 8_000,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = 2_500,
            ),
        )
        world.conversions = listOf(conversion(5, BOX, 10_000))

        sell(SaleLineRequest(5, QuantityMilli(1_000), unitId = BOX))

        val line = storedLine().content
        assertEquals(LineStockEffect.NONE, line.stockEffect)
        assertEquals(QuantityMilli(10_000), line.quantity)
        // Diez horas a 2500 de costo fijo.
        assertEquals(Money(25_000), line.lineCost)
        assertTrue(world.tables.movements.isEmpty())
    }

    @Test
    fun theBaseUnitWrittenOutIsTheUsualSale() = runTest {
        world.addProduct(product(1, salePrice = 500))

        sell(SaleLineRequest(1, QuantityMilli(2_000), unitId = SaleWorld.UNIT))

        val line = storedLine().content
        assertEquals(SaleWorld.UNIT, line.unitId)
        assertEquals(QuantityMilli(1_000), line.unitFactor)
        assertEquals(QuantityMilli(2_000), line.quantity)
        assertEquals(Money(1_000), line.lineTotal)
    }

    @Test
    fun aUnitTheProductDoesNotSellIsRejectedWithoutWriting() = runTest {
        world.addProduct(product(1, salePrice = 500))
        world.conversions = listOf(conversion(1, GRAM, 1, forSale = false))
        val before = world.tables

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.UNIT_UNAVAILABLE),
            sell(SaleLineRequest(1, QuantityMilli(1_000), unitId = BOX)),
        )
        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.UNIT_UNAVAILABLE),
            sell(SaleLineRequest(1, QuantityMilli(1_000), unitId = GRAM)),
        )
        assertEquals(before, world.tables)
    }

    @Test
    fun aBoxIsSoldWhole() = runTest {
        world.addProduct(product(1, salePrice = 500))
        world.conversions = listOf(conversion(1, BOX, 24_000))

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.FRACTION_NOT_ALLOWED),
            sell(SaleLineRequest(1, QuantityMilli(1_500), unitId = BOX)),
        )
    }

    @Test
    fun aComputedPriceOverTheLimitIsRejected() = runTest {
        world.addProduct(product(1, salePrice = 1_000_000_000_000))
        world.conversions = listOf(conversion(1, BOX, 24_000))

        assertEquals(
            RegisterSaleResult.LineRejected(0, LineRejection.INVALID_PRICE),
            sell(SaleLineRequest(1, QuantityMilli(1_000), unitId = BOX)),
        )
    }

    @Test
    fun theCartTotalIsTheRegisteredTotalInOtherUnits() = runTest {
        world.addProduct(product(1, salePrice = 333, onHandMilli = 100_000, stockValue = 10_000))
        world.addProduct(product(2, salePrice = 18_517, unitId = SaleWorld.KILOGRAM))
        world.conversions = listOf(conversion(1, BOX, 12_000), conversion(2, GRAM, 1))
        val lines = listOf(
            SaleLineRequest(1, QuantityMilli(2_000), unitId = BOX),
            SaleLineRequest(2, QuantityMilli(1_337_000), unitId = GRAM),
        )
        val conversions = world.conversions.groupBy { it.productId }
            .mapValues { (_, list) -> list.associate { it.details.unitId to it.details } }

        val preview = SaleCart.preview(
            lines,
            world.tables.products,
            setOf(SaleWorld.KILOGRAM),
            conversions = conversions,
        )
        val result = sell(*lines.toTypedArray())

        val sale = world.tables.sales.getValue((result as RegisterSaleResult.Registered).saleId)
        assertEquals(sale.content.total, preview.total)
        val stored = world.tables.lines.values.sortedBy { it.id }.map { it.content }
        assertEquals(stored.map { it.lineTotal }, preview.lines.map { it.total })
        assertEquals(stored.map { it.quantity }, preview.lines.map { it.baseQuantity })
        // Dos cajas son dos productos, y un producto por peso es uno.
        assertEquals(3L, preview.itemCount)
    }

    @Test
    fun aSaleByTheBoxVoidedAndCorrectedChargesTheSameFigures() = runTest {
        // Caja de 12 con precio propio, vendida a mano a 11000 con 500 de descuento.
        world.addProduct(product(1, salePrice = 1_000, onHandMilli = 100_000, stockValue = 50_000))
        world.conversions = listOf(conversion(1, BOX, 12_000, price = 12_000))
        val first = sell(
            SaleLineRequest(
                1,
                QuantityMilli(3_000),
                Money(11_000),
                LineDiscount.Amount(Money(500)),
                unitId = BOX,
            ),
        )
        val saleId = (first as RegisterSaleResult.Registered).saleId
        val detail = ObserveSaleDetailUseCase(world.sales, world.payments, world.products)(saleId)
        val voided = world.voidSale()(saleId, VoidReason.ENTRY_ERROR, null)
        assertEquals(VoidSaleResult.Voided, voided)

        val correction = SaleCorrections.of(requireNotNull(detail.first()))
        val second = world.registerSale()(
            SaleRequest(SaleCart.normalize(correction.lines), SaleWorld.CASH),
        )

        val newId = (second as RegisterSaleResult.Registered).saleId
        val lines = world.tables.lines.values.sortedBy { it.id }.map { it.content }
        val (original, corrected) = lines
        assertEquals(BOX, corrected.unitId)
        assertEquals(QuantityMilli(3_000), corrected.enteredQuantity)
        assertEquals(QuantityMilli(36_000), corrected.quantity)
        assertEquals(Money(11_000), corrected.unitPrice)
        assertEquals(Money(32_500), corrected.lineTotal)
        assertEquals(
            listOf(original.enteredQuantity, original.quantity, original.lineTotal),
            listOf(corrected.enteredQuantity, corrected.quantity, corrected.lineTotal),
        )
        val sales = world.tables.sales
        assertEquals(sales.getValue(saleId).content.total, sales.getValue(newId).content.total)
        // Anular devolvió las 36 unidades, y la corrección las saca otra vez.
        assertEquals(QuantityMilli(64_000), world.tables.products.getValue(1).onHand)
    }

    private companion object {
        const val BOX = 3L
        const val GRAM = 4L
    }
}
