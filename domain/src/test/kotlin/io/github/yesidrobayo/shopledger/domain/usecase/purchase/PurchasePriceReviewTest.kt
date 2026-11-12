package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseCostChange
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.SupplierLastCost
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.port.CategoryRepository
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.sale.taxRate
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tras una compra: un costo conocido que sube marca el producto para revisar su precio, y el
 * resumen dice si el margen quedó por debajo del objetivo del producto, de su categoría o del
 * negocio, que en las pruebas es 30 %. También lo último pagado a cada proveedor. Montos
 * inventados.
 */
class PurchasePriceReviewTest {
    private val world = SaleWorld()

    /** Una categoría con margen objetivo propio, de 10 %. */
    private val categories = object : CategoryRepository {
        override fun observeAll(): Flow<List<Category>> = error("No se usa")

        override suspend fun findById(id: Long): Category? = Category(
            id = id,
            details = CategoryDetails("Invented", "invented", BasisPoints(1_000)),
            createdAt = SaleWorld.EPOCH,
            updatedAt = SaleWorld.EPOCH,
        ).takeIf { id == CATEGORY }

        override suspend fun findByNormalizedName(normalizedName: String): Category? =
            error("No se usa")

        override suspend fun countProducts(id: Long): Int = error("No se usa")

        override fun observeProductCounts(): Flow<Map<Long, Int>> = error("No se usa")

        override suspend fun insert(details: CategoryDetails, createdAt: Instant): Long =
            error("No se usa")

        override suspend fun update(id: Long, details: CategoryDetails, updatedAt: Instant) =
            error("No se usa")

        override suspend fun delete(id: Long) = error("No se usa")
    }

    private fun register() = RegisterPurchaseUseCase(
        transactions = world.transactions,
        settings = world.settings,
        products = world.products,
        purchases = world.purchases,
        suppliers = world.supplierRepository,
        units = world.unitRepository,
        systemRows = world.systemRowRepository,
        ledger = world.ledger,
        taxRates = world.taxRateRepository,
        clock = world.clock,
        productUnits = world.productUnitRepository,
        categories = categories,
    )

    private suspend fun buy(vararg lines: PurchaseLineRequest) =
        (register()(PurchaseRequest(lines.toList())) as RegisterPurchaseResult.Registered)
            .costChanges.associateBy { it.productId }

    /** Un producto a 1500 con 10 unidades a 1000. */
    private fun stocked(id: Long, salePrice: Long = 1_500) = product(
        id,
        salePrice = salePrice,
        onHandMilli = 10_000,
        averageCost = 1_000,
        stockValue = 10_000,
    )

    @Test
    fun `a known cost that rises marks the product and a lower margin asks to review it`() =
        runTest {
            world.addProduct(stocked(1))

            // 10 más a 1400: el costo pasa de 1000 a 1200, y el margen de 1500 queda en 20 %.
            val change = buy(atUnitCost(1, 10_000, cost = 1_400)).getValue(1)

            assertEquals(
                PurchaseCostChange(1, Money(1_000), Money(1_200), marginBelowTarget = true),
                change,
            )
            assertTrue(change.costRose)
            assertEquals(NOW, world.tables.products.getValue(1).priceReviewSince)
        }

    @Test
    fun `an earlier mark is kept, and a cost that falls marks nothing`() = runTest {
        val since = Instant.fromEpochMilliseconds(1_000)
        world.addProduct(stocked(1).copy(priceReviewSince = since))
        world.addProduct(stocked(2))

        val changes = buy(atUnitCost(1, 10_000, cost = 1_400), atUnitCost(2, 10_000, cost = 600))

        assertEquals(since, world.tables.products.getValue(1).priceReviewSince)
        assertNull(world.tables.products.getValue(2).priceReviewSince)
        // 1500 con costo 800 deja 46,7 %: por encima del 30 % del negocio.
        assertFalse(changes.getValue(2).costRose)
        assertFalse(changes.getValue(2).marginBelowTarget)
    }

    @Test
    fun `a first cost is not a rise, and the margin still counts`() = runTest {
        world.addProduct(product(1, salePrice = 1_000))

        val change = buy(atUnitCost(1, 1_000, cost = 900)).getValue(1)

        assertFalse(change.costRose)
        assertTrue(change.marginBelowTarget)
        assertNull(world.tables.products.getValue(1).priceReviewSince)
    }

    @Test
    fun `the target comes from the product, then its category, then the business`() = runTest {
        // Las tres dejan 20 % de margen: el producto pide 15 %, la categoría 10 % y el negocio 30 %.
        world.addProduct(
            stocked(1).let {
                it.copy(details = it.details.copy(targetMargin = BasisPoints(1_500)))
            },
        )
        world.addProduct(
            stocked(2).let {
                it.copy(details = it.details.copy(categoryId = CATEGORY))
            },
        )
        world.addProduct(stocked(3).let { it.copy(details = it.details.copy(categoryId = 99)) })

        val changes = buy(
            atUnitCost(1, 10_000, cost = 1_400),
            atUnitCost(2, 10_000, cost = 1_400),
            atUnitCost(3, 10_000, cost = 1_400),
        )

        assertEquals(
            listOf(false, false, true),
            (1L..3L).map {
                changes.getValue(it).marginBelowTarget
            },
        )
    }

    @Test
    fun `without a price there is no margin to compare, and the tax is left out`() = runTest {
        world.taxRates = mapOf(5L to taxRate(5, "Invented tax", 2_500))
        world.setTaxes(TaxMode.INCLUDED, defaultTaxRateId = 5)
        world.addProduct(stocked(1, salePrice = 0))
        // 1875 con 25 % incluido son 1500 sin impuesto: el margen sobre 1200 es 20 %.
        world.addProduct(stocked(2, salePrice = 1_875))

        val changes = buy(atUnitCost(1, 10_000, cost = 1_400), atUnitCost(2, 10_000, cost = 1_400))

        assertFalse(changes.getValue(1).marginBelowTarget)
        assertTrue(changes.getValue(2).marginBelowTarget)
    }

    @Test
    fun `a cost change knows when it rose`() {
        assertTrue(PurchaseCostChange(1, Money(1), Money(2)).costRose)
        assertFalse(PurchaseCostChange(1, Money(2), Money(2)).costRose)
        assertFalse(PurchaseCostChange(1, Money(2), Money(1)).costRose)
        assertFalse(PurchaseCostChange(1, Money.ZERO, Money(2)).costRose)
    }

    @Test
    fun `the last cost of each supplier leaves voided purchases out`() = runTest {
        world.addSupplier(7)
        world.addProduct(product(1))
        val past = BusinessDate(LocalDate(2026, 10, 30))
        val repository = world.registerPurchase()
        repository(PurchaseRequest(listOf(atUnitCost(1, 1_000, cost = 100)), 7, date = past))
        repository(PurchaseRequest(listOf(atUnitCost(1, 1_000, cost = 120)), 7))
        val voided = world.buy(atUnitCost(1, 1_000, cost = 900), supplierId = 7)
        world.voidPurchase()(voided, VoidReason.ENTRY_ERROR, null)
        val withoutSupplier = world.buy(atUnitCost(1, 1_000, cost = 90), date = past)

        val costs = world.purchases.observeLastCosts(1).first()

        assertEquals(
            listOf(
                SupplierLastCost(7, "Supplier 7", 2, TODAY, SaleWorld.UNIT, Money(120)),
                SupplierLastCost(null, null, withoutSupplier, past, SaleWorld.UNIT, Money(90)),
            ),
            costs,
        )
    }

    private companion object {
        const val CATEGORY = 3L
    }
}
