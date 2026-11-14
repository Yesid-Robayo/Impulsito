package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLineProblem
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guardar la receta de un combo sobre el catálogo en memoria, con la guarda de la tabla imitada en
 * el repositorio falso. Los nombres son inventados.
 */
class RecipeUseCasesTest {
    private val fixture = CatalogFixture().apply { seedCatalog() }
    private val save = with(fixture) {
        SaveRecipeUseCase(transactions, products, components, clock)
    }

    private val changes: Long get() = fixture.tables.changeSeq

    @Test
    fun `a new recipe is saved in one transaction, in the order it was built`() = runTest {
        val result = save(KIT, listOf(line(TEA, 2000), line(CUP, 1000), line(SERVICE, 1000)))

        assertEquals(SaveRecipeResult.Saved, result)
        assertEquals(
            listOf(TEA to 2000L, CUP to 1000L, SERVICE to 1000L),
            recipeOf(KIT).map { it.componentProductId to it.quantity.milli },
        )
        assertEquals(1, fixture.transactions.writeCalls)
        assertEquals(1L, changes)
    }

    @Test
    fun `saving the same recipe again writes nothing`() = runTest {
        save(KIT, listOf(line(TEA, 2000), line(CUP, 1000)))

        val again = save(KIT, listOf(line(TEA, 2000), line(CUP, 1000)))

        assertEquals(SaveRecipeResult.Saved, again)
        assertEquals(1L, changes)
    }

    @Test
    fun `a changed recipe removes, updates and adds only what changed`() = runTest {
        save(KIT, listOf(line(TEA, 2000), line(CUP, 1000)))
        val teaRow = recipeOf(KIT).first { it.componentProductId == TEA }

        val result = save(KIT, listOf(line(TEA, 3000), line(SERVICE, 500)))

        assertEquals(SaveRecipeResult.Saved, result)
        assertEquals(
            listOf(RecipeComponent(teaRow.id, KIT, TEA, QuantityMilli(3000))),
            recipeOf(KIT).filter { it.componentProductId == TEA },
        )
        assertEquals(
            listOf(TEA to 3000L, SERVICE to 500L),
            recipeOf(KIT).map { it.componentProductId to it.quantity.milli },
        )
        assertEquals(2L, changes)
    }

    @Test
    fun `an empty recipe removes every component`() = runTest {
        save(KIT, listOf(line(TEA, 2000), line(CUP, 1000)))

        assertEquals(SaveRecipeResult.Saved, save(KIT, emptyList()))
        assertEquals(emptyList<RecipeComponent>(), recipeOf(KIT))
    }

    @Test
    fun `a combo inside a combo is saved when the inner one only holds leaves`() = runTest {
        save(KIT, listOf(line(TEA, 2000)))

        assertEquals(SaveRecipeResult.Saved, save(BOX, listOf(line(KIT, 2000), line(CUP, 1000))))
    }

    @Test
    fun `a cycle is rejected and nothing is written`() = runTest {
        save(BOX, listOf(line(KIT, 1000)))

        val result = save(KIT, listOf(line(TEA, 1000), line(BOX, 1000)))

        assertEquals(
            SaveRecipeResult.Invalid(listOf(RecipeIssue.Line(1, RecipeLineProblem.CYCLE))),
            result,
        )
        assertEquals(emptyList<RecipeComponent>(), recipeOf(KIT))
        assertEquals(1L, changes)
    }

    @Test
    fun `a third level is rejected, also where the table guard would let it pass`() = runTest {
        // TRAY contiene a BOX y BOX contiene a KIT, que todavía no tiene receta. La guarda de la
        // tabla mira un nivel a cada lado y aceptaría la hoja; la regla recorre todo el camino.
        save(BOX, listOf(line(KIT, 1000)))
        save(TRAY, listOf(line(BOX, 1000)))

        val result = save(KIT, listOf(line(TEA, 1000)))

        assertEquals(
            SaveRecipeResult.Invalid(listOf(RecipeIssue.Line(0, RecipeLineProblem.TOO_DEEP))),
            result,
        )
        assertEquals(emptyList<RecipeComponent>(), recipeOf(KIT))
    }

    @Test
    fun `a missing combo is not found`() = runTest {
        assertEquals(SaveRecipeResult.NotFound, save(999L, listOf(line(TEA, 1000))))
    }

    @Test
    fun `a service has no recipe`() = runTest {
        assertEquals(SaveRecipeResult.NotCombo, save(SERVICE, listOf(line(CUP, 1000))))
        assertEquals(0L, changes)
    }

    @Test
    fun `a product with its own stock saves the recipe it is produced with`() = runTest {
        val result = save(TEA, listOf(line(CUP, 1000), line(KIT, 500)))

        assertEquals(SaveRecipeResult.Saved, result)
        assertEquals(
            listOf(CUP to 1000L, KIT to 500L),
            recipeOf(TEA).map { it.componentProductId to it.quantity.milli },
        )
    }

    @Test
    fun `a produced product shares the levels and cycles of the combos`() = runTest {
        save(KIT, listOf(line(TEA, 1000)))

        val result = save(TEA, listOf(line(KIT, 1000)))

        assertEquals(
            SaveRecipeResult.Invalid(listOf(RecipeIssue.Line(0, RecipeLineProblem.CYCLE))),
            result,
        )
        assertEquals(emptyList<RecipeComponent>(), recipeOf(TEA))
    }

    @Test
    fun `a storage failure leaves nothing and is reported`() = runTest {
        fixture.database.failAtWrite = 2
        fixture.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        val result = save(KIT, listOf(line(TEA, 2000), line(CUP, 1000)))

        assertEquals(SaveRecipeResult.StorageFailed(StorageFailure.DISK_FULL), result)
        assertEquals(emptyList<RecipeComponent>(), recipeOf(KIT))
    }

    @Test
    fun `a failure at each write of a changed recipe leaves the previous one`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = {
                seedCatalog()
                seedRecipe(KIT, TEA to 2000L, CUP to 1000L)
            },
            run = {
                SaveRecipeUseCase(transactions, products, components, clock)(
                    KIT,
                    listOf(line(TEA, 3000), line(SERVICE, 500)),
                )
            },
        )
    }

    @Test
    fun `a combo that becomes a service loses its recipe with the same edit`() = runTest {
        save(KIT, listOf(line(TEA, 2000), line(CUP, 1000)))
        save(BOX, listOf(line(TEA, 1000)))

        val result = fixture.edit(KIT, form("Kit de té", ProductStockMode.UNTRACKED))

        assertEquals(SaveProductResult.Saved(KIT), result)
        assertEquals(emptyList<RecipeComponent>(), recipeOf(KIT))
        assertEquals(1, recipeOf(BOX).size)
        assertEquals(
            ProductStockMode.UNTRACKED,
            fixture.tables.products.getValue(KIT).details.stockMode,
        )
    }

    @Test
    fun `a combo that starts carrying stock keeps its recipe to produce it`() = runTest {
        save(KIT, listOf(line(TEA, 2000), line(CUP, 1000)))

        val result = fixture.edit(KIT, form("Kit de té", ProductStockMode.TRACKED))

        assertEquals(SaveProductResult.Saved(KIT), result)
        assertEquals(2, recipeOf(KIT).size)
    }

    @Test
    fun `editing a combo that stays one keeps its recipe`() = runTest {
        save(KIT, listOf(line(TEA, 2000)))

        fixture.edit(KIT, form("Kit de té grande", ProductStockMode.COMPONENTS))

        assertEquals(1, recipeOf(KIT).size)
    }

    private fun recipeOf(parentId: Long): List<RecipeComponent> =
        fixture.tables.productComponents.values
            .filter { it.parentProductId == parentId }
            .sortedBy { it.id }

    private fun line(productId: Long, milli: Long) = RecipeLine(productId, QuantityMilli(milli))

    private fun form(name: String, mode: ProductStockMode) =
        ProductForm(name = name, stockMode = mode)

    private companion object {
        const val TEA = 101L
        const val CUP = 102L
        const val SERVICE = 103L
        const val KIT = 201L
        const val BOX = 202L
        const val TRAY = 203L

        fun CatalogFixture.seedCatalog() {
            seedProduct(TEA, "Té en hojas")
            seedProduct(CUP, "Taza")
            seedProduct(SERVICE, "Empaque", stockMode = ProductStockMode.UNTRACKED)
            seedProduct(KIT, "Kit de té", stockMode = ProductStockMode.COMPONENTS)
            seedProduct(BOX, "Caja regalo", stockMode = ProductStockMode.COMPONENTS)
            seedProduct(TRAY, "Bandeja", stockMode = ProductStockMode.COMPONENTS)
        }

        fun CatalogFixture.seedRecipe(parentId: Long, vararg rows: Pair<Long, Long>) {
            database.seed { tables ->
                val added = rows.mapIndexed { index, (componentId, milli) ->
                    val id = 900L + index
                    id to RecipeComponent(id, parentId, componentId, QuantityMilli(milli))
                }
                tables.copy(
                    productComponents = tables.productComponents + added,
                    lastIds = tables.lastIds + ("product_components" to 900L + rows.size),
                )
            }
        }
    }
}
