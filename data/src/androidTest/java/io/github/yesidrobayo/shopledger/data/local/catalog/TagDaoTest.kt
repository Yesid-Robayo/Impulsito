package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las etiquetas: nombre único por su forma normalizada, orden que no depende de tildes ni
 * mayúsculas, un vínculo por producto y etiqueta, y vínculos que se van en cascada con cualquiera
 * de los dos.
 */
@RunWith(AndroidJUnit4::class)
internal class TagDaoTest : CatalogDatabaseTest() {
    private val tags get() = database.tagDao()
    private val products get() = database.productDao()

    @Test
    fun tagsReadBackByNormalizedNameAndCannotRepeatIt() = runBlocking {
        val offer = tag("Oferta")
        val offerId = tags.insert(offer)
        val seasonId = tags.insert(tag("Época de lluvias"))

        constraintFailure(UNIQUE) { tags.insert(tag("  OFERTA ")) }
        constraintFailure(UNIQUE) { tags.update(seasonId, "Oferta", "oferta", LATER) }

        assertEquals(offer.copy(id = offerId), tags.findById(offerId))
        assertEquals(offerId, tags.findByNormalizedName("oferta")?.id)
        assertEquals(1, tags.update(seasonId, "Lluvias", "lluvias", LATER))
        assertEquals(
            tag("Lluvias").copy(id = seasonId, updatedAt = LATER),
            tags.findById(seasonId),
        )
        assertNull(tags.findById(MISSING_ID))
        assertEquals(0, tags.update(MISSING_ID, "Otra", "otra", LATER))
    }

    @Test
    fun aProductTagsInOrderOnceAndTheFilterFindsItsProducts() = runBlocking {
        val shirt = products.insert(product("Camiseta"))
        val cap = products.insert(product("Gorra"))
        val offer = tags.insert(tag("Oferta"))
        val new = tags.insert(tag("Nuevo"))
        tags.link(ProductTagEntity(productId = cap, tagId = offer))
        tags.link(ProductTagEntity(productId = shirt, tagId = offer))

        constraintFailure(UNIQUE) { tags.link(ProductTagEntity(productId = shirt, tagId = offer)) }
        constraintFailure(FOREIGN_KEY) {
            tags.link(ProductTagEntity(productId = shirt, tagId = MISSING_ID))
        }
        assertEquals(listOf(shirt, cap), tags.findProductIdsWith(offer))

        val emissions = Emissions(this, tags.observeTagsOf(shirt).ids { it.id })
        try {
            emissions.awaitValue(listOf(offer))
            tags.link(ProductTagEntity(productId = shirt, tagId = new))
            // Por nombre normalizado: «nuevo» antes que «oferta».
            emissions.awaitValue(listOf(new, offer))
            assertEquals(1, tags.unlink(shirt, offer))
            emissions.awaitValue(listOf(new))
        } finally {
            emissions.stop()
        }
        assertEquals(0, tags.unlink(shirt, offer))
    }

    @Test
    fun linksGoWithTheirTagAndWithTheirProduct() = runBlocking {
        val shirt = products.insert(product("Camiseta"))
        val cap = products.insert(product("Gorra"))
        val offer = tags.insert(tag("Oferta"))
        val new = tags.insert(tag("Nuevo"))
        tags.link(ProductTagEntity(productId = shirt, tagId = offer))
        tags.link(ProductTagEntity(productId = cap, tagId = offer))
        tags.link(ProductTagEntity(productId = cap, tagId = new))

        // Borrar la etiqueta no toca a los productos.
        assertEquals(1, tags.delete(offer))
        assertEquals(1L, count("SELECT COUNT(*) FROM product_tags"))
        assertEquals(2L, count("SELECT COUNT(*) FROM products"))

        assertEquals(1, products.delete(cap))
        assertEquals(0L, count("SELECT COUNT(*) FROM product_tags"))
        assertEquals(emptyList<Long>(), tags.findProductIdsWith(new))
    }

    private fun tag(name: String) = TagEntity(
        name = name,
        normalizedName = TextNormalizer.normalize(name),
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )

    private companion object {
        const val MISSING_ID = 999L
    }
}
