package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Nombres, cantidades y costos inventados; los esperados están escritos a mano.
class ReorderPlannerTest {
    @Test
    fun `the example of the rule groups by usual supplier with its estimated cost`() {
        val coffee = product(1, "Café", onHand = 12_000, min = 5_000, supplier = 10)

        val plan = plan(listOf(coffee), unitsOut = mapOf(1L to 45_000))

        assertTrue(plan.hasRecentSales)
        val group = plan.groups.single()
        assertEquals(10L, group.supplier?.id)
        val line = group.lines.single()
        assertEquals(QuantityMilli(38_000), line.suggestion.suggested)
        assertEquals(8L, line.suggestion.daysLeft)
        assertFalse(line.soldOut)
        // 38 unidades a 1.250 cuestan 47.500.
        assertEquals(Money(47_500), estimate(line))
    }

    @Test
    fun `sold out goes first, then fewer days left, then the ones without sales`() {
        val products = listOf(
            product(1, "Arroz", onHand = 10_000, min = 0),
            product(2, "Azúcar", onHand = 0, min = 0),
            product(3, "Harina", onHand = 1_000, min = 0),
            product(4, "Sal", onHand = 1_000, min = 3_000),
        )
        val sales = mapOf(1L to 30_000L, 2L to 30_000L, 3L to 30_000L)

        val lines = plan(products, sales).groups.single().lines

        assertEquals(listOf(2L, 3L, 1L, 4L), lines.map { it.product.id })
        assertTrue(lines[0].soldOut)
        // Sin ventas, bajo el mínimo: entra con cero para llenarlo a mano.
        assertEquals(QuantityMilli.ZERO, lines[3].suggestion.suggested)
        assertNull(lines[3].suggestion.daysLeft)
    }

    @Test
    fun `nothing to restock leaves no groups but remembers there were sales`() {
        val plan = plan(listOf(product(1, "Arroz", onHand = 90_000, min = 0)), mapOf(1L to 3_000))

        assertTrue(plan.groups.isEmpty())
        assertTrue(plan.hasRecentSales)
    }

    @Test
    fun `without any sales and nothing below its minimum the list is empty`() {
        val plan = plan(listOf(product(1, "Arroz", onHand = 0, min = 0)), emptyMap())

        assertTrue(plan.groups.isEmpty())
        assertFalse(plan.hasRecentSales)
    }

    @Test
    fun `an archived supplier or none goes to the group without supplier, at the end`() {
        val products = listOf(
            product(1, "Arroz", onHand = 0, min = 1_000, supplier = 11),
            product(2, "Azúcar", onHand = 0, min = 1_000, supplier = 12),
            product(3, "Café", onHand = 0, min = 1_000, supplier = 10),
            product(4, "Harina", onHand = 0, min = 1_000),
        )
        val suppliers = listOf(
            supplier(10, "Zeta Mayorista"),
            supplier(11, "Abarrotes Norte"),
            supplier(12, "Bodega Cerrada", archived = true),
        )

        val groups = plan(products, emptyMap(), suppliers = suppliers).groups

        assertEquals(listOf(11L, 10L, null), groups.map { it.supplier?.id })
        assertEquals(listOf(2L, 4L), groups.last().lines.map { it.product.id })
    }

    @Test
    fun `only products with their own stock appear, and each variant on its own`() {
        val parent = product(1, "Camiseta", onHand = 0, min = 1_000)
        val variant = product(2, "Camiseta M", onHand = 0, min = 1_000).let {
            it.copy(details = it.details.copy(parentProductId = 1L))
        }
        val products = listOf(
            parent,
            variant,
            product(3, "Servicio", onHand = 0, min = 1_000, mode = ProductStockMode.UNTRACKED),
            product(4, "Combo", onHand = 0, min = 1_000, mode = ProductStockMode.COMPONENTS),
            product(5, "Viejo", onHand = 0, min = 1_000).copy(archivedAt = AT),
        )

        val lines = plan(products, emptyMap()).groups.single().lines

        assertEquals(listOf(2L), lines.map { it.product.id })
    }

    @Test
    fun `the supplier and category filters keep only what they name`() {
        val products = listOf(
            product(1, "Arroz", onHand = 0, min = 1_000, supplier = 10, category = 7),
            product(2, "Azúcar", onHand = 0, min = 1_000, category = 7),
            product(3, "Café", onHand = 0, min = 1_000, supplier = 10, category = 8),
            product(4, "Sal", onHand = 0, min = 1_000, supplier = 10),
        )

        val ofSupplier = plan(products, emptyMap(), ReorderQuery(supplier = of(10)))
        val without = plan(products, emptyMap(), ReorderQuery(supplier = without()))
        val category = plan(products, emptyMap(), ReorderQuery(categoryId = 7L))

        assertEquals(listOf(1L, 3L, 4L), ofSupplier.groups.single().lines.map { it.product.id })
        assertEquals(listOf(2L), without.groups.single().lines.map { it.product.id })
        assertEquals(listOf(listOf(1L), listOf(2L)), category.groups.map { g -> g.ids() })
    }

    @Test
    fun `a location reads its own stock, and a product never moved there has none`() {
        val products = listOf(
            product(1, "Arroz", onHand = 50_000, min = 2_000),
            product(2, "Azúcar", onHand = 50_000, min = 2_000),
        )

        val lines = plan(products, emptyMap(), onHandAt = mapOf(1L to QuantityMilli(1_000)))
            .groups.single().lines

        assertEquals(listOf(QuantityMilli.ZERO, QuantityMilli(1_000)), lines.map { it.onHand })
    }

    @Test
    fun `the smallest purchase unit rounds up, and the cost falls back to the average`() {
        val coffee = product(1, "Café", onHand = 12_000, min = 5_000, replacement = 0)
        val units = mapOf(
            1L to listOf(
                conversion(unitId = 30, factor = 24_000, forPurchase = true),
                conversion(unitId = 31, factor = 12_000, forPurchase = true),
                conversion(unitId = 32, factor = 6_000, forPurchase = false),
                conversion(unitId = 33, factor = 0, forPurchase = true),
            ),
        )

        val line = plan(listOf(coffee), mapOf(1L to 45_000), conversions = units)
            .groups.single().lines.single()

        assertEquals(31L, line.purchaseUnit?.unitId)
        // 38 unidades piden 4 paquetes de 12: 48.
        assertEquals(QuantityMilli(48_000), line.suggestion.suggested)
        assertEquals(Money(900), line.unitCost)
    }

    @Test
    fun `the estimated cost adds every line and gives up when it does not fit`() {
        val items = listOf(Money(1_250) to QuantityMilli(2_500), Money(99) to QuantityMilli.ZERO)
        assertEquals(Money(3_125), ReorderPlanner.estimatedCost(items))
        assertEquals(Money.ZERO, ReorderPlanner.estimatedCost(emptyList()))

        val huge = Money(Long.MAX_VALUE) to QuantityMilli(2_000)
        assertNull(ReorderPlanner.estimatedCost(listOf(huge)))
        val half = Money(Long.MAX_VALUE / 2 + 1) to QuantityMilli(1_000)
        assertNull(ReorderPlanner.estimatedCost(listOf(half, half)))
    }

    private fun ReorderGroup.ids() = lines.map { it.product.id }

    private fun estimate(line: ReorderLine): Money? =
        ReorderPlanner.estimatedCost(listOf(line.unitCost to line.suggestion.suggested))

    private fun of(id: Long): ReorderSupplierFilter = ReorderSupplierFilter.Of(id)

    private fun without(): ReorderSupplierFilter = ReorderSupplierFilter.Without

    private fun plan(
        products: List<Product>,
        unitsOut: Map<Long, Long>,
        query: ReorderQuery = ReorderQuery(),
        suppliers: List<Supplier> = listOf(supplier(10, "Distribuidora Sur")),
        onHandAt: Map<Long, QuantityMilli>? = null,
        conversions: Map<Long, List<ConversionDetails>> = emptyMap(),
    ): ReorderPlan = ReorderPlanner.plan(
        query = query,
        products = products,
        suppliers = suppliers,
        unitsOut = unitsOut.mapValues { QuantityMilli(it.value) },
        onHandAt = onHandAt,
        conversions = conversions,
    )

    private fun product(
        id: Long,
        name: String,
        onHand: Long,
        min: Long,
        supplier: Long? = null,
        category: Long? = null,
        mode: ProductStockMode = ProductStockMode.TRACKED,
        replacement: Long = 1_250,
    ): Product = Product(
        id = id,
        details = CatalogFixture.details(
            name,
            stockMode = mode,
            minStock = QuantityMilli(min),
            defaultSupplierId = supplier,
        ).copy(categoryId = category),
        salePrice = Money(2_000),
        initialCost = Money.ZERO,
        averageCost = Money(900),
        stockValue = Money.ZERO,
        onHand = QuantityMilli(onHand),
        replacementCost = Money(replacement),
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

    private fun supplier(id: Long, name: String, archived: Boolean = false) = Supplier(
        id = id,
        details = SupplierDetails(
            name = name,
            normalizedName = TextNormalizer.normalize(name),
            phone = null,
            email = null,
            notes = null,
            paymentTermsDays = null,
        ),
        balance = Money.ZERO,
        archivedAt = if (archived) AT else null,
        createdAt = AT,
        updatedAt = AT,
    )

    private fun conversion(unitId: Long, factor: Long, forPurchase: Boolean) = ConversionDetails(
        unitId = unitId,
        factorMilli = factor,
        salePrice = null,
        barcode = null,
        forSale = true,
        forPurchase = forPurchase,
    )

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }
}
