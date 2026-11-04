package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las categorías: nombre único por su forma normalizada, orden que no depende de tildes ni
 * mayúsculas, y eliminación libre que deja sin categoría a sus productos.
 */
@RunWith(AndroidJUnit4::class)
internal class CategoryDaoTest : CatalogDatabaseTest() {
    private val categories get() = database.categoryDao()
    private val products get() = database.productDao()

    @Test
    fun insertedCategoryReadsBackWithEveryColumn() = runBlocking {
        val withMargin = category("Bebidas frías", targetMarginBp = 3_500)
        val withoutMargin = category("Aseo")

        val withMarginId = categories.insert(withMargin)
        val withoutMarginId = categories.insert(withoutMargin)

        assertEquals(withMargin.copy(id = withMarginId), categories.findById(withMarginId))
        assertEquals(withoutMargin.copy(id = withoutMarginId), categories.findById(withoutMarginId))
        assertEquals("bebidas frias", categories.findById(withMarginId)?.normalizedName)
        assertNull(categories.findById(MISSING_ID))
    }

    @Test
    fun namesThatNormalizeAlikeCannotRepeat() = runBlocking {
        val firstId = categories.insert(category("Jabón Líquido"))

        // Tildes, mayúsculas y espacios de más no hacen otro nombre.
        constraintFailure(UNIQUE) { categories.insert(category("  JABON   liquido ")) }

        assertEquals(1L, count("SELECT COUNT(*) FROM categories"))
        assertEquals(firstId, categories.findByNormalizedName("jabon liquido")?.id)
    }

    @Test
    fun enieMakesADifferentName() = runBlocking {
        // En español la ñ es otra letra: Piña y Pina son dos categorías.
        categories.insert(category("Piña"))
        categories.insert(category("Pina"))

        assertEquals("Piña", categories.findByNormalizedName("piña")?.name)
        assertEquals("Pina", categories.findByNormalizedName("pina")?.name)
    }

    @Test
    fun renameCannotTakeAnotherCategoryName() = runBlocking {
        categories.insert(category("Lácteos"))
        val otherId = categories.insert(category("Panadería"))

        constraintFailure(UNIQUE) {
            categories.update(otherId, "LACTEOS", "lacteos", targetMarginBp = null, LATER)
        }

        assertEquals("Panadería", categories.findById(otherId)?.name)
    }

    @Test
    fun listIsOrderedByNormalizedName() = runBlocking {
        // Por el texto visible, Zumos iría antes que Árboles en un orden binario.
        for (name in listOf(
            "Zumos",
            "Árboles",
            "bebidas",
            "Carnes",
        )) {
            categories.insert(category(name))
        }

        val names = categories.observeAll().first()

        assertEquals(listOf("Árboles", "bebidas", "Carnes", "Zumos"), names.map { it.name })
    }

    @Test
    fun listEmitsAgainAfterEachCommit() = runBlocking {
        val emissions = Emissions(this, categories.observeAll())
        try {
            emissions.awaitValue(emptyList())

            val id = categories.insert(category("Snacks"))
            emissions.awaitValue(listOf(category("Snacks").copy(id = id)))

            categories.update(id, "Pasabocas", "pasabocas", targetMarginBp = 2_000, LATER)
            val renamed =
                CategoryEntity(id, "Pasabocas", "pasabocas", 2_000, CREATED_AT, LATER)
            emissions.awaitValue(listOf(renamed))

            categories.delete(id)
            emissions.awaitValue(emptyList())
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun updateChangesWhatThePersonWritesAndKeepsCreatedAt() = runBlocking {
        val id = categories.insert(category("Dulces", targetMarginBp = 4_000))

        val changed = categories.update(id, "Golosinas", "golosinas", targetMarginBp = null, LATER)

        assertEquals(1, changed)
        val expected =
            CategoryEntity(
                id = id,
                name = "Golosinas",
                normalizedName = "golosinas",
                targetMarginBp = null,
                createdAt = CREATED_AT,
                updatedAt = LATER,
            )
        assertEquals(expected, categories.findById(id))
        assertEquals(0, categories.update(MISSING_ID, "Otra", "otra", null, LATER))
    }

    @Test
    fun deletingACategoryInUseLeavesItsProductsWithoutCategory() = runBlocking {
        val drinks = categories.insert(category("Bebidas"))
        val cleaning = categories.insert(category("Aseo"))
        val water = products.insert(product("Agua", categoryId = drinks))
        val juice = products.insert(product("Jugo", categoryId = drinks, archivedAt = LATER))
        val soap = products.insert(product("Jabón", categoryId = cleaning))

        // Cuenta también los archivados: es lo que se avisa antes de eliminarla.
        assertEquals(2, categories.countProducts(drinks))

        assertEquals(1, categories.delete(drinks))

        assertNull(categories.findById(drinks))
        assertNull(products.findById(water)?.categoryId)
        assertNull(products.findById(juice)?.categoryId)
        assertEquals(cleaning, products.findById(soap)?.categoryId)
        assertEquals(3L, count("SELECT COUNT(*) FROM products"))
        assertEquals(0, categories.countProducts(drinks))
        assertEquals(0, categories.delete(drinks))
    }

    @Test
    fun productCannotPointToAMissingCategory() = runBlocking {
        constraintFailure(FOREIGN_KEY) {
            products.insert(product("Huérfano", categoryId = MISSING_ID))
        }

        assertEquals(0L, count("SELECT COUNT(*) FROM products"))
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
