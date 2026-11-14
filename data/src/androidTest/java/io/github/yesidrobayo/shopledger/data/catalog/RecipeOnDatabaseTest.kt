package io.github.yesidrobayo.shopledger.data.catalog

import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductComponentEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLineProblem
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * La receta de un combo guardada sobre la base real: lo que escribe, lo que rechaza antes de
 * llegar a la guarda, y que las consultas del invariante de combos quedan sin filas. Todos los
 * nombres son inventados.
 */
internal class RecipeOnDatabaseTest : CatalogDatabaseTest() {
    private lateinit var ports: DataPorts
    private lateinit var save: SaveRecipeUseCase

    @Before
    fun connectUseCases() {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        save = SaveRecipeUseCase(
            ports.transactions,
            ports.products,
            ports.productComponents,
            FIXED_CLOCK,
        )
    }

    @Test
    fun aRecipeIsSavedChangedAndEmptiedInOneTransactionEach() = runBlocking {
        val tea = insertProduct("Té verde", "TRACKED")
        val cup = insertProduct("Taza blanca", "TRACKED")
        val wrap = insertProduct("Envoltura", "UNTRACKED")
        val kit = insertProduct("Kit de té", "COMPONENTS")
        val before = changeSeq()

        assertEquals(SaveRecipeResult.Saved, save(kit, listOf(line(tea, 2000), line(cup, 1000))))
        assertEquals(listOf("$tea|2000", "$cup|1000"), recipeOf(kit))
        assertEquals(before + 1L, changeSeq())

        assertEquals(SaveRecipeResult.Saved, save(kit, listOf(line(tea, 3000), line(wrap, 500))))
        assertEquals(listOf("$tea|3000", "$wrap|500"), recipeOf(kit))
        assertEquals(before + 2L, changeSeq())

        // La misma receta otra vez no escribe nada.
        assertEquals(SaveRecipeResult.Saved, save(kit, listOf(line(tea, 3000), line(wrap, 500))))
        assertEquals(before + 2L, changeSeq())

        assertEquals(SaveRecipeResult.Saved, save(kit, emptyList()))
        assertEquals(emptyList<String>(), recipeOf(kit))
        assertEquals(emptyList<String>(), invariantRows())
    }

    @Test
    fun cyclesAndAThirdLevelAreRejectedAndNothingIsWritten() = runBlocking {
        val tea = insertProduct("Té negro", "TRACKED")
        val kit = insertProduct("Kit de desayuno", "COMPONENTS")
        val box = insertProduct("Caja regalo", "COMPONENTS")
        val tray = insertProduct("Bandeja grande", "COMPONENTS")
        assertEquals(SaveRecipeResult.Saved, save(box, listOf(line(kit, 1000))))
        assertEquals(SaveRecipeResult.Saved, save(tray, listOf(line(box, 2000))))
        val before = changeSeq()

        val cycle = save(kit, listOf(line(tray, 1000)))
        // La guarda de la tabla solo mira un nivel a cada lado y dejaría pasar esta hoja, que
        // pondría a la bandeja tres niveles por encima del té.
        val thirdLevel = save(kit, listOf(line(tea, 1000)))

        assertEquals(invalid(RecipeLineProblem.CYCLE), cycle)
        assertEquals(invalid(RecipeLineProblem.TOO_DEEP), thirdLevel)
        assertEquals(emptyList<String>(), recipeOf(kit))
        assertEquals(before, changeSeq())
        assertEquals(emptyList<String>(), invariantRows())
    }

    @Test
    fun theInvariantFindsTheThirdLevelThatTheTableGuardLetsThrough() = runBlocking {
        // Lo que la regla evita: escrito directo en la tabla, la guarda lo acepta y solo la
        // consulta del invariante lo encuentra.
        val tea = insertProduct("Té rojo", "TRACKED")
        val kit = insertProduct("Kit de merienda", "COMPONENTS")
        val box = insertProduct("Caja de merienda", "COMPONENTS")
        val tray = insertProduct("Bandeja de merienda", "COMPONENTS")
        val components = database.productComponentDao()
        components.insert(component(box, kit))
        components.insert(component(tray, box))
        components.insert(component(kit, tea))

        assertEquals(listOf("$tray"), invariantRows())
    }

    @Test
    fun aComboThatStopsBeingOneLosesItsRecipeInTheSameEdit() = runBlocking {
        val tea = insertProduct("Té de frutas", "TRACKED")
        val kit = insertProduct("Kit de frutas", "COMPONENTS")
        assertEquals(SaveRecipeResult.Saved, save(kit, listOf(line(tea, 1000))))
        val edit = EditProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings), FIXED_CLOCK,
            ports.productComponents,
            ports.mediaAssets,
        )

        val form = ProductForm(name = "Kit de frutas", stockMode = ProductStockMode.TRACKED)
        val result = edit(kit, form)

        assertEquals(SaveProductResult.Saved(kit), result)
        assertEquals(emptyList<String>(), recipeOf(kit))
        assertEquals("TRACKED", database.productDao().findById(kit)?.stockMode)
    }

    private suspend fun insertProduct(name: String, mode: String): Long =
        database.productDao().insert(
            product(name).copy(
                stockMode = mode,
                averageCost = if (mode == "TRACKED") 1_200 else 0,
                initialCost = if (mode == "TRACKED") 1_200 else 0,
            ),
        )

    private fun component(parent: Long, component: Long) = ProductComponentEntity(
        parentProductId = parent,
        componentProductId = component,
        quantityMilli = 1000,
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )

    private fun line(productId: Long, milli: Long) = RecipeLine(productId, QuantityMilli(milli))

    private fun invalid(problem: RecipeLineProblem) =
        SaveRecipeResult.Invalid(listOf(RecipeIssue.Line(0, problem)))

    private suspend fun recipeOf(parent: Long): List<String> = database.useReaderConnection {
        it.texts(
            "SELECT component_product_id || '|' || quantity_milli FROM product_components " +
                "WHERE parent_product_id = $parent ORDER BY id",
        )
    }

    /** Las filas que devuelven las dos consultas del invariante de combos. */
    private suspend fun invariantRows(): List<String> = database.useReaderConnection { connection ->
        InvariantQueries.MODEL
            .filter { it.label.startsWith("I-22") }
            .flatMap { connection.texts(it.sql) }
    }

    private suspend fun changeSeq(): Long = count("SELECT change_seq FROM backup_state")
}
