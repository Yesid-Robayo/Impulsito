package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.ids
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los extras de un producto: cada columna en su lugar, en el orden de la persona y después por
 * `id`, escrituras que tocan solo sus columnas, extras que se van con su producto y un producto que
 * no se borra mientras sea extra de otro.
 */
@RunWith(AndroidJUnit4::class)
internal class ProductModifierDaoTest : CatalogDatabaseTest() {
    private val modifiers get() = database.productModifierDao()
    private val products get() = database.productDao()

    @Test
    fun extrasFollowTheirOrderThenTheirIdAndEmitAgain() = runBlocking {
        val burger = products.insert(product("Hamburguesa"))
        val cheese = products.insert(product("Queso extra"))
        val bacon = products.insert(product("Tocineta"))
        val sauce = products.insert(product("Salsa"))
        // Cada columna con un valor distinto, para que dos columnas cruzadas no pasen.
        val second = modifier(burger, cheese, sortOrder = 2).copy(updatedAt = LATER)
        val secondId = modifiers.insert(second)
        val firstId = modifiers.insert(modifier(burger, bacon, sortOrder = 1))
        val tiedId = modifiers.insert(modifier(burger, sauce, sortOrder = 2, groupName = null))
        modifiers.insert(modifier(cheese, sauce, sortOrder = 0))

        assertEquals(second.copy(id = secondId), modifiers.findByProduct(burger)[1])
        val emissions = Emissions(this, modifiers.observeByProduct(burger).ids { it.id })
        try {
            emissions.awaitValue(listOf(firstId, secondId, tiedId))
            assertEquals(
                1,
                modifiers.update(firstId, groupName = "Salsas", sortOrder = 9, MUCH_LATER),
            )
            emissions.awaitValue(listOf(secondId, tiedId, firstId))
            assertEquals(1, modifiers.delete(secondId))
            emissions.awaitValue(listOf(tiedId, firstId))
        } finally {
            emissions.stop()
        }
        assertEquals(
            modifier(burger, bacon, sortOrder = 9, groupName = "Salsas")
                .copy(id = firstId, updatedAt = MUCH_LATER),
            modifiers.findByProduct(burger).last(),
        )
        assertEquals(0, modifiers.update(MISSING_ID, null, 0, LATER))
    }

    @Test
    fun extrasGoWithTheirProductAndProtectTheProductTheyOffer() = runBlocking {
        val burger = products.insert(product("Hamburguesa"))
        val cheese = products.insert(product("Queso extra"))
        modifiers.insert(modifier(burger, cheese))

        // Un producto que es extra de otro no se borra: el otro ofrecería algo que no existe.
        constraintFailure(FOREIGN_KEY) { products.delete(cheese) }
        constraintFailure(FOREIGN_KEY) { modifiers.insert(modifier(burger, MISSING_ID)) }

        assertEquals(1, products.delete(burger))
        assertEquals(0L, count("SELECT COUNT(*) FROM product_modifiers"))
        assertEquals(1, products.delete(cheese))
    }

    private fun modifier(
        productId: Long,
        modifierId: Long,
        sortOrder: Long = 0,
        groupName: String? = "Adiciones",
    ) = ProductModifierEntity(
        productId = productId,
        modifierProductId = modifierId,
        groupName = groupName,
        sortOrder = sortOrder,
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )

    private companion object {
        const val MISSING_ID = 999L
    }
}
