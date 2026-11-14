package io.github.yesidrobayo.shopledger.domain.rule.document

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lo reservado y lo disponible, con los ejemplos de la regla y valores escritos a mano. */
class OrderReservationsTest {
    private fun q(milli: Long) = QuantityMilli(milli)

    @Test
    fun `ten in stock and four reserved leave six available`() {
        assertEquals(q(6_000), OrderReservations.available(q(10_000), q(4_000)))
    }

    @Test
    fun `reserving more than there is leaves a negative availability, without blocking`() {
        assertEquals(q(-1_000), OrderReservations.available(q(2_000), q(3_000)))
    }

    @Test
    fun `a product with its own stock adds every open line`() {
        val reserved = OrderReservations.byProduct(
            lines = mapOf(1L to q(4_000)),
            modes = mapOf(1L to ProductStockMode.TRACKED),
            recipes = emptyMap(),
        )

        assertEquals(mapOf(1L to q(4_000)), reserved)
    }

    @Test
    fun `a combo reserves its tracked leaves and a service reserves nothing`() {
        // El combo 10 lleva 2 del producto 1 y 0,5 del servicio 2: 3 combos son 6 y 1,5.
        val recipes = mapOf(
            10L to listOf(
                RecipeComponent(1, 10L, 1L, q(2_000)),
                RecipeComponent(2, 10L, 2L, q(500)),
            ),
        )
        val modes = mapOf(
            1L to ProductStockMode.TRACKED,
            2L to ProductStockMode.UNTRACKED,
            10L to ProductStockMode.COMPONENTS,
        )

        val reserved = OrderReservations.byProduct(
            lines = mapOf(10L to q(3_000), 1L to q(1_000), 2L to q(4_000)),
            modes = modes,
            recipes = recipes,
        )

        assertEquals(mapOf(1L to q(7_000)), reserved)
    }

    @Test
    fun `a combo that cannot be expanded and an unknown product reserve nothing`() {
        // Un tercer nivel de combos solo llega con una base dañada.
        val recipes = mapOf(
            10L to listOf(RecipeComponent(1, 10L, 11L, q(1_000))),
            11L to listOf(RecipeComponent(2, 11L, 12L, q(1_000))),
            12L to listOf(RecipeComponent(3, 12L, 1L, q(1_000))),
            20L to listOf(RecipeComponent(4, 20L, 1L, q(1))),
            30L to listOf(RecipeComponent(5, 30L, 1L, q(1_000_000_000))),
        )
        val modes = mapOf(
            1L to ProductStockMode.TRACKED,
            10L to ProductStockMode.COMPONENTS,
            11L to ProductStockMode.COMPONENTS,
            12L to ProductStockMode.COMPONENTS,
            20L to ProductStockMode.COMPONENTS,
            30L to ProductStockMode.COMPONENTS,
        )

        val reserved = OrderReservations.byProduct(
            // El combo 30 llevaría más de lo que cabe en una línea.
            lines = mapOf(10L to q(1_000), 20L to q(100), 30L to q(1_000_000), 99L to q(1_000)),
            modes = modes,
            recipes = recipes,
        )

        assertEquals(emptyMap<Long, QuantityMilli>(), reserved)
    }
}
