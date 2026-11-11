package io.github.yesidrobayo.shopledger.domain.rule.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Más vendidos`: primero los que más líneas tienen en los últimos treinta días, después los demás por
 * nombre, sin archivados ni variantes sueltas, y hasta cincuenta. Nombres inventados.
 */
class FrequentProductsTest {
    private fun named(id: Long, name: String, archived: Boolean = false) =
        product(id, name = name, archived = archived)

    private fun variantOf(parent: Long, id: Long, name: String): Product {
        val base = product(id, name = name)
        return base.copy(details = base.details.copy(parentProductId = parent))
    }

    private fun names(products: List<Product>) = products.map { it.details.name }

    @Test
    fun `without sales history the order is alphabetical`() {
        val products = listOf(named(1L, "Jabon"), named(2L, "Arroz"), named(3L, "Cafe"))

        assertEquals(
            listOf("Arroz", "Cafe", "Jabon"),
            names(FrequentProducts.rank(products, emptyMap())),
        )
    }

    @Test
    fun `sold products go first by line count, then the rest by name`() {
        val products = listOf(
            named(1L, "Arroz"),
            named(2L, "Cafe"),
            named(3L, "Huevos"),
            named(4L, "Jabon"),
            named(5L, "Azucar"),
        )

        val ranked = FrequentProducts.rank(products, mapOf(4L to 3, 2L to 7, 3L to 3))

        // Huevos y Jabón empatan en 3 líneas: va primero el nombre.
        assertEquals(listOf("Cafe", "Huevos", "Jabon", "Arroz", "Azucar"), names(ranked))
    }

    @Test
    fun `variants add their lines to their product and never appear alone`() {
        val products = listOf(
            named(1L, "Camiseta"),
            variantOf(parent = 1L, id = 2L, name = "Camiseta roja"),
            variantOf(parent = 1L, id = 3L, name = "Camiseta azul"),
            named(4L, "Gorra"),
        )

        val ranked = FrequentProducts.rank(products, mapOf(4L to 3, 2L to 2, 3L to 2))

        assertEquals(listOf("Camiseta", "Gorra"), names(ranked))
    }

    @Test
    fun `archived products are not offered, even with sales`() {
        val products = listOf(named(1L, "Arroz", archived = true), named(2L, "Cafe"))

        assertEquals(listOf("Cafe"), names(FrequentProducts.rank(products, mapOf(1L to 9))))
    }

    @Test
    fun `at most fifty products are offered`() {
        val products = (1L..60L).map { named(it, "Producto " + it.toString().padStart(2, '0')) }

        val ranked = FrequentProducts.rank(products, mapOf(60L to 1))

        assertEquals(FrequentProducts.LIMIT, ranked.size)
        assertEquals("Producto 60", ranked.first().details.name)
        assertEquals("Producto 49", ranked.last().details.name)
    }

    @Test
    fun `the window counts thirty days, today included`() {
        val today = BusinessDate(LocalDate(2026, 11, 30))

        assertEquals(BusinessDate(LocalDate(2026, 11, 1)), FrequentProducts.since(today))
    }
}
