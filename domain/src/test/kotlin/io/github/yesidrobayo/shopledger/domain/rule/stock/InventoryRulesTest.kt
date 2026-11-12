package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La lista por agotarse, su orden y el valor del inventario, con los ejemplos de las reglas. */
class InventoryRulesTest {
    private val at = Instant.fromEpochMilliseconds(0L)

    private fun product(
        id: Long,
        name: String,
        onHand: Long,
        minStock: Long = 5_000L,
        value: Long = 0L,
        average: Long = 100L,
        mode: ProductStockMode = ProductStockMode.TRACKED,
        archived: Boolean = false,
    ) = Product(
        id = id,
        details = CatalogFixture.details(name, mode, QuantityMilli(minStock)),
        salePrice = Money(1_000L),
        initialCost = Money(average),
        averageCost = Money(average),
        stockValue = Money(value),
        onHand = QuantityMilli(onHand),
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = if (archived) at else null,
        createdAt = at,
        updatedAt = at,
    )

    @Test
    fun `stock of minus one, zero and two come out in that order`() {
        val two = product(1L, "Arroz", 2_000L)
        val zero = product(2L, "Aceite", 0L)
        val minusOne = product(3L, "Jabón", -1_000L)

        assertEquals(
            listOf(minusOne, zero, two),
            InventoryRules.lowStock(listOf(two, zero, minusOne)),
        )
    }

    @Test
    fun `inside each group the order is by name, then by id`() {
        val b = product(1L, "Bebida", 1_000L)
        val a = product(2L, "Azúcar", 3_000L)
        val twin = product(3L, "Azúcar", 4_000L)
        val outB = product(4L, "Banano", 0L)
        val outA = product(5L, "Arepa", 0L)

        assertEquals(
            listOf(outA, outB, a, twin, b),
            InventoryRules.lowStock(listOf(b, twin, outB, a, outA)),
        )
    }

    @Test
    fun `reaching the minimum is already low, above it is not`() {
        val atMinimum = product(1L, "En el mínimo", 5_000L)
        val above = product(2L, "Encima", 5_001L)

        assertEquals(listOf(atMinimum), InventoryRules.lowStock(listOf(atMinimum, above)))
    }

    @Test
    fun `services, components and archived products never run low`() {
        val service = product(1L, "Servicio", 0L, mode = ProductStockMode.UNTRACKED)
        val combo = product(2L, "Combo", 0L, mode = ProductStockMode.COMPONENTS)
        val archived = product(3L, "Archivado", -2_000L, archived = true)

        assertTrue(InventoryRules.lowStock(listOf(service, combo, archived)).isEmpty())
        assertTrue(InventoryRules.negative(listOf(service, combo, archived)).isEmpty())
        assertNull(InventoryRules.statusOf(service))
    }

    @Test
    fun `each tracked product has its status`() {
        assertEquals(InventoryStatus.NEGATIVE, InventoryRules.statusOf(product(1L, "A", -1L)))
        assertEquals(InventoryStatus.OUT, InventoryRules.statusOf(product(1L, "A", 0L)))
        assertEquals(InventoryStatus.LOW, InventoryRules.statusOf(product(1L, "A", 5_000L)))
        assertEquals(InventoryStatus.NORMAL, InventoryRules.statusOf(product(1L, "A", 6_000L)))
    }

    @Test
    fun `the urgency groups put negative first and everything else last`() {
        assertEquals(0, InventoryRules.urgency(product(1L, "A", -1L)))
        assertEquals(1, InventoryRules.urgency(product(1L, "A", 0L)))
        assertEquals(2, InventoryRules.urgency(product(1L, "A", 1_000L)))
        assertEquals(2, InventoryRules.urgency(product(1L, "A", 9_000L)))
        assertEquals(
            2,
            InventoryRules.urgency(product(1L, "A", 0L, mode = ProductStockMode.UNTRACKED)),
        )
    }

    @Test
    fun `only negative stock goes in the negative filter, by name`() {
        val b = product(1L, "Bebida", -1_000L)
        val a = product(2L, "Azúcar", -3_000L)

        assertEquals(
            listOf(a, b),
            InventoryRules.negative(listOf(b, product(3L, "Cero", 0L), a)),
        )
    }

    @Test
    fun `the inventory value of the rules is 151150`() {
        // 11 unidades que valen 14900, 4,250 kg que valen 136250 y −2 unidades que valen 0.
        val products = listOf(
            product(1L, "Unidades", 11_000L, value = 14_900L),
            product(2L, "Por peso", 4_250L, value = 136_250L),
            product(3L, "Negativo", -2_000L, value = 0L),
        )

        assertEquals(Money(151_150L), InventoryRules.value(products))
    }

    @Test
    fun `a value that does not fit is no value`() {
        val huge = product(1L, "A", 1_000L, value = Long.MAX_VALUE)

        assertNull(InventoryRules.value(listOf(huge, product(2L, "B", 1_000L, value = 1L))))
        assertEquals(Money.ZERO, InventoryRules.value(emptyList()))
    }

    @Test
    fun `stock without cost is flagged, and nothing else is`() {
        val noCost = product(1L, "Sin costo", 3_000L, value = 0L, average = 0L)

        assertTrue(InventoryRules.hasStockWithoutCost(listOf(noCost)))
        assertFalse(
            InventoryRules.hasStockWithoutCost(
                listOf(
                    product(2L, "Archivado", 3_000L, value = 0L, average = 0L, archived = true),
                    product(
                        3L,
                        "Servicio",
                        3_000L,
                        average = 0L,
                        mode = ProductStockMode.UNTRACKED,
                    ),
                    product(4L, "Agotado", 0L, value = 0L, average = 0L),
                    product(5L, "Con valor", 3_000L, value = 1L, average = 0L),
                    product(6L, "Con promedio", 3_000L, value = 0L, average = 1L),
                ),
            ),
        )
    }
}
