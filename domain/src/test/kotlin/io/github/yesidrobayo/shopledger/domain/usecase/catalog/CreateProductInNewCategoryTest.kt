package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogReference
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.CATEGORY_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un producto creado con una categoría nueva por su nombre, como lo crea `Decir` con la categoría
 * que se dijo: la categoría y el producto van en la misma transacción, o no queda ninguno.
 */
class CreateProductInNewCategoryTest {
    private val fixture = CatalogFixture()

    private val soap = ProductForm(name = "Jabón Líquido", salePrice = Money(3_500L))

    private fun categoryNamed(name: String) =
        fixture.tables.categories.values.single { it.details.name == name }

    @Test
    fun `a new category is created with the product in one transaction`() = runTest {
        val result = fixture.create(soap, newCategory = "Aseo del hogar")

        val created = categoryNamed("Aseo del hogar")
        assertEquals("aseo del hogar", created.details.normalizedName)
        assertEquals(null, created.details.targetMargin)
        val saved = result as SaveProductResult.Saved
        assertEquals(created.id, fixture.product(saved.productId).details.categoryId)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(2, fixture.tables.categories.size)
    }

    @Test
    fun `a category with the same name is used instead of a second one`() = runTest {
        // "limpieza" sin mayúscula es la "Limpieza" que ya existe.
        val result = fixture.create(soap, newCategory = "  limpieza ")

        val saved = result as SaveProductResult.Saved
        assertEquals(CATEGORY_ID, fixture.product(saved.productId).details.categoryId)
        assertEquals(setOf(CATEGORY_ID), fixture.tables.categories.keys)
    }

    @Test
    fun `a name that is not a category creates nothing`() = runTest {
        assertEquals(
            SaveProductResult.MissingReference(CatalogReference.CATEGORY),
            fixture.create(soap, newCategory = "   "),
        )
        assertTrue(fixture.tables.products.isEmpty())
        assertEquals(setOf(CATEGORY_ID), fixture.tables.categories.keys)
    }

    @Test
    fun `a product that cannot be created leaves no new category`() = runTest {
        fixture.create(soap)

        assertEquals(
            SaveProductResult.DuplicateName("Jabón Líquido"),
            fixture.create(soap, newCategory = "Aseo"),
        )
        assertEquals(setOf(CATEGORY_ID), fixture.tables.categories.keys)
    }

    @Test
    fun `a failure at each write of the category and the product leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(prepare = {}) {
            create(soap, newCategory = "Aseo")
        }
    }
}
