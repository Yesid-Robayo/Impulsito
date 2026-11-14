package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.HistoryLockedField
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlar un producto por lotes: solo con stock propio, y nunca después de tener historia. */
class ProductLotsTest {
    private val fixture = CatalogFixture()

    private fun savedId(result: SaveProductResult): Long =
        (result as SaveProductResult.Saved).productId

    @Test
    fun `a new product with lots puts its initial stock in the unassigned lot`() = runTest {
        val id = savedId(
            fixture.create(
                ProductForm(name = "Queso fresco", cost = Money(2_000L), tracksLots = true),
                initialStock = QuantityMilli(3_000L),
            ),
        )

        assertTrue(fixture.product(id).details.tracksLots)
        val lot = fixture.tables.lots.values.single()
        assertTrue(lot.isUnassigned)
        assertEquals(id, lot.productId)
        assertEquals(lot.id, fixture.movementsOf(id).single().movement.key.lotId)
    }

    @Test
    fun `a new product without lots or without own stock does not track lots`() = runTest {
        val plain = savedId(fixture.create(ProductForm(name = "Jabón"), QuantityMilli(1_000L)))
        val service = savedId(
            fixture.create(
                ProductForm(
                    name = "Arreglo",
                    stockMode = ProductStockMode.UNTRACKED,
                    tracksLots = true,
                ),
            ),
        )

        assertFalse(fixture.product(plain).details.tracksLots)
        assertFalse(fixture.product(service).details.tracksLots)
        assertEquals(null, fixture.movementsOf(plain).single().movement.key.lotId)
        assertTrue(fixture.tables.lots.isEmpty())
    }

    @Test
    fun `lots are turned on and off while the product has no history`() = runTest {
        val id = savedId(fixture.create(ProductForm(name = "Aceite")))

        fixture.edit(id, ProductForm(name = "Aceite", tracksLots = true))
        assertTrue(fixture.product(id).details.tracksLots)

        // Sin decir nada de lotes, la edición los deja como estaban.
        fixture.edit(id, ProductForm(name = "Aceite 900 ml"))
        assertTrue(fixture.product(id).details.tracksLots)

        fixture.edit(id, ProductForm(name = "Aceite 900 ml", tracksLots = false))
        assertFalse(fixture.product(id).details.tracksLots)
    }

    @Test
    fun `with history lots no longer change`() = runTest {
        val id = savedId(fixture.create(ProductForm(name = "Harina"), QuantityMilli(2_000L)))

        val result = fixture.edit(id, ProductForm(name = "Harina", tracksLots = true))

        val locked = SaveProductResult.LockedByHistory(setOf(HistoryLockedField.TRACKS_LOTS))
        assertEquals(locked, result)
        assertFalse(fixture.product(id).details.tracksLots)
    }

    @Test
    fun `the opening balance of a product with lots goes to a single unassigned lot`() = runTest {
        val id = savedId(fixture.create(ProductForm(name = "Leche", tracksLots = true)))

        val result = fixture.opening(id, QuantityMilli(6_000L), Money(1_500L))

        assertTrue(result is OpeningBalanceResult.Registered)
        val lot = fixture.tables.lots.values.single()
        assertTrue(lot.isUnassigned)
        assertEquals(lot.id, fixture.movementsOf(id).single().movement.key.lotId)
    }
}
