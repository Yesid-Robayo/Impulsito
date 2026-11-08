package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.CATEGORY_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Crear, renombrar y eliminar categorías de producto. */
class CategoryUseCasesTest {
    private val fixture = CatalogFixture()

    @Test
    fun `a new category is saved trimmed, with its normalized name and margin`() = runTest {
        val result = fixture.createCategory(" Lácteos ", BasisPoints(3_500L))

        val id = (result as SaveCategoryResult.Saved).categoryId
        val category = fixture.tables.categories.getValue(id)
        assertEquals(CategoryDetails("Lácteos", "lacteos", BasisPoints(3_500L)), category.details)
        assertEquals(NOW, category.createdAt)
        assertEquals(1L, fixture.tables.changeSeq)
    }

    @Test
    fun `a repeated category name is rejected naming the existing one`() = runTest {
        val before = fixture.tables

        assertEquals(
            SaveCategoryResult.DuplicateName("Limpieza"),
            fixture.createCategory("LIMPIEZA"),
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `an invalid category is rejected with every reason`() = runTest {
        assertEquals(
            SaveCategoryResult.Invalid(
                setOf(
                    CategoryFormIssue.NAME_TOO_LONG,
                    CategoryFormIssue.TARGET_MARGIN_OUT_OF_RANGE,
                ),
            ),
            fixture.createCategory("a".repeat(41), BasisPoints(10_000L)),
        )
        assertEquals(
            SaveCategoryResult.Invalid(setOf(CategoryFormIssue.NAME_EMPTY)),
            fixture.editCategory(CATEGORY_ID, "", null),
        )
    }

    @Test
    fun `renaming keeps the id and can change only the accents`() = runTest {
        assertEquals(
            SaveCategoryResult.Saved(CATEGORY_ID),
            fixture.editCategory(CATEGORY_ID, "Limpiéza", null),
        )

        val category = fixture.tables.categories.getValue(CATEGORY_ID)
        assertEquals("Limpiéza", category.details.name)
        assertEquals(NOW, category.updatedAt)
    }

    @Test
    fun `renaming to another category's name is rejected, and saving unchanged writes nothing`() =
        runTest {
            fixture.createCategory("Bebidas")
            val before = fixture.tables

            assertEquals(
                SaveCategoryResult.DuplicateName("Bebidas"),
                fixture.editCategory(CATEGORY_ID, "bebidas", null),
            )
            assertEquals(
                SaveCategoryResult.Saved(CATEGORY_ID),
                fixture.editCategory(CATEGORY_ID, "Limpieza", null),
            )
            assertEquals(SaveCategoryResult.NotFound, fixture.editCategory(99L, "Otra", null))
            assertEquals(before, fixture.tables)
        }

    @Test
    fun `deleting a category with 12 products says 12 and leaves them without category`() =
        runTest {
            repeat(12) { index ->
                fixture.create(ProductForm(name = "Producto $index", categoryId = CATEGORY_ID))
            }

            assertEquals(12, fixture.deleteCategory.affectedProducts(CATEGORY_ID))
            assertEquals(
                DeleteCategoryResult.Deleted(affectedProducts = 12),
                fixture.deleteCategory(CATEGORY_ID),
            )

            assertNull(fixture.tables.categories[CATEGORY_ID])
            fixture.tables.products.values.forEach { assertNull(it.details.categoryId) }
        }

    @Test
    fun `deleting a missing category writes nothing`() = runTest {
        assertEquals(DeleteCategoryResult.NotFound, fixture.deleteCategory(99L))
    }

    @Test
    fun `a restore in progress rejects every category write`() = runTest {
        fixture.transactions.restoreInProgress = true
        val failed = StorageFailure.RESTORE_IN_PROGRESS

        assertEquals(SaveCategoryResult.StorageFailed(failed), fixture.createCategory("Bebidas"))
        assertEquals(
            SaveCategoryResult.StorageFailed(failed),
            fixture.editCategory(CATEGORY_ID, "Aseo", null),
        )
        assertEquals(
            DeleteCategoryResult.StorageFailed(failed),
            fixture.deleteCategory(CATEGORY_ID),
        )
    }

    @Test
    fun `a failure at each write of a category leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(prepare = {
        }) { createCategory("Bebidas") }
        CatalogFixture.assertEachWriteFailureLeavesNothing(prepare = {
        }) { editCategory(CATEGORY_ID, "Aseo", null) }
        CatalogFixture.assertEachWriteFailureLeavesNothing(prepare = {
        }) { deleteCategory(CATEGORY_ID) }
    }
}
