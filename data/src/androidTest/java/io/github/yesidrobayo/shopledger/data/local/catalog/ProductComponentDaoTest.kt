package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.ids
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las recetas de los combos: cada columna en su lugar, en el orden en que se armaron, un componente
 * una sola vez por combo, la receta que se va con su combo y un componente que no se borra mientras
 * esté en una receta.
 */
@RunWith(AndroidJUnit4::class)
internal class ProductComponentDaoTest : CatalogDatabaseTest() {
    private val components get() = database.productComponentDao()
    private val products get() = database.productDao()

    @Test
    fun aRecipeReadsBackInTheOrderItWasBuiltAndEmitsAgain() = runBlocking {
        val combo = products.insert(combo("Desayuno"))
        val other = products.insert(combo("Almuerzo"))
        val coffee = products.insert(product("Café"))
        val bread = products.insert(product("Pan"))
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen.
        val first = component(combo, coffee, quantityMilli = 250).copy(updatedAt = LATER)
        val firstId = components.insert(first)
        val otherId = components.insert(component(other, coffee))
        val second = component(combo, bread, quantityMilli = 2_000)
        val secondId = components.insert(second)

        assertEquals(
            listOf(first.copy(id = firstId), second.copy(id = secondId)),
            components.findByParent(combo),
        )
        assertEquals(listOf(firstId, otherId), components.findByComponent(coffee).map { it.id })
        assertEquals(emptyList<ProductComponentEntity>(), components.findByParent(coffee))

        val emissions = Emissions(this, components.observeByParent(combo).ids { it.id })
        try {
            emissions.awaitValue(listOf(firstId, secondId))
            components.delete(firstId)
            emissions.awaitValue(listOf(secondId))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun aComponentGoesOncePerComboAndOnlyItsQuantityChanges() = runBlocking {
        val combo = products.insert(combo("Desayuno"))
        val coffee = products.insert(product("Café"))
        val id = components.insert(component(combo, coffee))

        constraintFailure(UNIQUE) { components.insert(component(combo, coffee)) }

        assertEquals(1, components.updateQuantity(id, quantityMilli = 1_000_000_000, LATER))
        assertEquals(
            component(combo, coffee, quantityMilli = 1_000_000_000)
                .copy(id = id, updatedAt = LATER),
            components.findByParent(combo).single(),
        )
        assertEquals(0, components.updateQuantity(MISSING_ID, quantityMilli = 1, LATER))
    }

    @Test
    fun theRecipeGoesWithItsComboAndProtectsItsComponents() = runBlocking {
        val combo = products.insert(combo("Desayuno"))
        val coffee = products.insert(product("Café"))
        components.insert(component(combo, coffee))

        // Un producto que está en una receta no se borra: el combo vendería algo que no existe.
        constraintFailure(FOREIGN_KEY) { products.delete(coffee) }
        constraintFailure(FOREIGN_KEY) { components.insert(component(combo, MISSING_ID)) }

        assertEquals(1, products.delete(combo))
        assertEquals(0L, count("SELECT COUNT(*) FROM product_components"))
        assertEquals(1, products.delete(coffee))
    }

    private fun combo(name: String) = product(name).copy(stockMode = "COMPONENTS")

    private fun component(parentId: Long, componentId: Long, quantityMilli: Long = 1_000) =
        ProductComponentEntity(
            parentProductId = parentId,
            componentProductId = componentId,
            quantityMilli = quantityMilli,
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
        )

    private companion object {
        const val MISSING_ID = 999L
    }
}
