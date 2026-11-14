package io.github.yesidrobayo.shopledger.data.catalog

import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductComponentEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductModifierEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.category
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditCategoryUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.EditProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OpeningBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RegisterOpeningBalanceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveCategoryResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Los casos de uso de producto y categoría sobre la base real, con sus guardas y sus llaves: lo que
 * escribe cada uno, y que una falla después de cualquiera de sus escrituras no deje nada.
 *
 * Todos los nombres y montos son inventados.
 */
internal class CatalogUseCasesRoomTest : CatalogDatabaseTest() {
    private val faults = WriteFaults()
    private lateinit var ports: DataPorts
    private lateinit var create: CreateProductUseCase
    private lateinit var edit: EditProductUseCase
    private lateinit var archive: ArchiveProductUseCase
    private lateinit var reactivate: ReactivateProductUseCase
    private lateinit var delete: DeleteProductUseCase
    private lateinit var opening: RegisterOpeningBalanceUseCase
    private lateinit var createCategory: CreateCategoryUseCase
    private lateinit var editCategory: EditCategoryUseCase
    private lateinit var deleteCategory: DeleteCategoryUseCase

    private val soap =
        ProductForm(name = "Jabón Líquido", salePrice = Money(3_500L), cost = Money(1_200L))

    @Before
    fun connectUseCases() {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        val products = FaultyProducts(ports.products, faults)
        val priceHistory = FaultyPriceHistory(ports.priceHistory, faults)
        val ledger = FaultyLedger(ports.stockLedger, faults)
        val alerts = FaultyAlerts(ports.lowStockAlerts, faults)
        val categories = FaultyCategories(ports.categories, faults)
        val calendar = BusinessCalendarSource(ports.settings)
        val currencyLock = CurrencyLock(ports.settings)
        val tx = ports.transactions
        create = CreateProductUseCase(
            tx, products, categories, ports.suppliers, ports.units, ports.systemRows, priceHistory,
            ledger, alerts, calendar, currencyLock, FIXED_CLOCK,
        )
        edit = EditProductUseCase(
            tx, products, categories, ports.suppliers, ports.units, ports.systemRows, priceHistory,
            ledger, alerts, calendar, currencyLock, FIXED_CLOCK, ports.productComponents,
        )
        archive = ArchiveProductUseCase(tx, products, alerts, FIXED_CLOCK, ports.productVariants)
        reactivate = ReactivateProductUseCase(
            tx,
            products,
            alerts,
            calendar,
            FIXED_CLOCK,
            ports.productVariants,
        )
        delete = DeleteProductUseCase(tx, products)
        opening = RegisterOpeningBalanceUseCase(
            tx,
            products,
            ports.units,
            ports.systemRows,
            ledger,
            alerts,
            calendar,
            currencyLock,
            FIXED_CLOCK,
        )
        createCategory = CreateCategoryUseCase(tx, categories, FIXED_CLOCK)
        editCategory = EditCategoryUseCase(tx, categories, FIXED_CLOCK)
        deleteCategory = DeleteCategoryUseCase(tx, categories)
    }

    @Test
    fun systemRowsAndUnitsAreFoundByTheirStableKeys() = runBlocking {
        assertEquals(UNIT_ID, ports.systemRows.findId(SystemRow.DEFAULT_UNIT))
        assertEquals(1L, ports.systemRows.findId(SystemRow.MAIN_LOCATION))
        val defaultList = count("SELECT id FROM price_lists WHERE system_key = 'DEFAULT'")
        assertEquals(defaultList, ports.systemRows.findId(SystemRow.DEFAULT_PRICE_LIST))
        val kilogram = requireNotNull(ports.units.findById(KILOGRAM_ID))
        assertEquals("KILOGRAM", kilogram.systemKey)
        assertEquals(UnitKind.MASS, kilogram.kind)
        assertTrue(kilogram.allowsFraction)
        assertEquals(12, ports.units.observeActive().first().size)
    }

    @Test
    fun aProductWithOpeningStockWritesEveryRowInOneTransaction() = runBlocking {
        val before = changeSeq()

        val result = create(soap, QuantityMilli(24_000L))

        val productId = (result as SaveProductResult.Saved).productId
        val row = requireNotNull(database.productDao().findById(productId))
        assertEquals(24_000L, row.onHandMilli)
        assertEquals(1_200L, row.averageCost)
        assertEquals(28_800L, row.stockValue)
        assertEquals(
            listOf("OPENING_BALANCE|24000|28800|1"),
            rows(
                "SELECT type || '|' || quantity_milli || '|' || total_cost || '|' || location_id " +
                    "FROM stock_movements",
            ),
        )
        assertEquals(
            listOf("24000"),
            rows("SELECT on_hand_milli FROM stock_balances WHERE product_id = $productId"),
        )
        assertEquals(
            listOf("INITIAL|3500"),
            rows(
                "SELECT source || '|' || new_price FROM product_price_history " +
                    "WHERE old_price IS NULL",
            ),
        )
        assertEquals(before + 1L, changeSeq())
    }

    @Test
    fun aRepeatedNameIsRejectedWithoutWritingOrCounting() = runBlocking {
        create(soap)
        val before = snapshot()

        assertEquals(
            SaveProductResult.DuplicateName("Jabón Líquido"),
            create(ProductForm(name = "JABON liquido")),
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun theCostChangesOnlyWithoutMovements() = runBlocking {
        val fresh = (create(soap) as SaveProductResult.Saved).productId
        val stocked = (
            create(
                soap.copy(name = "Detergente"),
                QuantityMilli(3_000L),
            ) as SaveProductResult.Saved
            ).productId

        assertEquals(
            SaveProductResult.Saved(fresh),
            edit(fresh, soap.copy(baseUnitId = UNIT_ID, cost = Money(1_300L))),
        )
        val row = requireNotNull(database.productDao().findById(fresh))
        assertEquals(1_300L, row.initialCost)
        assertEquals(1_300L, row.averageCost)
        assertEquals(
            SaveProductResult.CostLockedByMovements,
            edit(
                stocked,
                soap.copy(name = "Detergente", baseUnitId = UNIT_ID, cost = Money(1_300L)),
            ),
        )
    }

    @Test
    fun aComponentAnExtraOrAVariantParentSaysWhyItCannotBeDeleted() = runBlocking {
        val bread = database.productDao().insert(product("Pan"))
        val combo = database.productDao().insert(
            product("Desayuno").copy(stockMode = "COMPONENTS", initialCost = 0, averageCost = 0),
        )
        val coffee = database.productDao().insert(product("Café"))
        database.productComponentDao().insert(
            ProductComponentEntity(0, combo, bread, 1_000L, CREATED_AT, CREATED_AT),
        )
        database.productModifierDao().insert(
            ProductModifierEntity(0, coffee, bread, null, 1L, CREATED_AT, CREATED_AT),
        )
        database.productDao().insert(product("Pan integral", parentProductId = bread))

        assertEquals(
            DeleteProductResult.LinkedInCatalog(
                setOf(CatalogLink.COMPONENT, CatalogLink.EXTRA, CatalogLink.VARIANT_PARENT),
            ),
            delete(bread),
        )
        // El combo y el café no tienen vínculos ni historia: se eliminan.
        assertEquals(DeleteProductResult.Deleted, delete(combo))
        assertEquals(DeleteProductResult.Deleted, delete(coffee))
        val created = (
            create(
                ProductForm(name = "Galleta"),
                QuantityMilli(2_000L),
            ) as SaveProductResult.Saved
            ).productId
        assertEquals(DeleteProductResult.HasHistory, delete(created))
    }

    @Test
    fun archiveReactivateAndOpeningBalanceKeepTheAlertsRight() = runBlocking {
        val id = (
            create(
                soap.copy(minStock = QuantityMilli(5_000L)),
            ) as SaveProductResult.Saved
            ).productId
        assertEquals(
            listOf("SUPPRESSED"),
            rows("SELECT notification_status FROM low_stock_alerts WHERE resolved_at IS NULL"),
        )

        assertEquals(ArchiveProductResult.Archived, archive(id))
        assertEquals(
            emptyList<String>(),
            rows("SELECT id FROM low_stock_alerts WHERE resolved_at IS NULL"),
        )
        assertEquals(ReactivateProductResult.Reactivated, reactivate(id))
        assertEquals(
            listOf("SUPPRESSED"),
            rows("SELECT notification_status FROM low_stock_alerts WHERE resolved_at IS NULL"),
        )

        assertTrue(
            opening(id, QuantityMilli(6_000L), Money(1_200L)) is OpeningBalanceResult.Registered,
        )
        assertEquals(
            emptyList<String>(),
            rows("SELECT id FROM low_stock_alerts WHERE resolved_at IS NULL"),
        )
        assertEquals(
            OpeningBalanceResult.AlreadyHasMovements,
            opening(id, QuantityMilli(1_000L), Money(1L)),
        )
    }

    @Test
    fun deletingACategoryLeavesItsProductsWithoutCategory() = runBlocking {
        val categoryId = (createCategory("Limpieza") as SaveCategoryResult.Saved).categoryId
        repeat(3) { create(ProductForm(name = "Producto $it", categoryId = categoryId)) }
        assertEquals(SaveCategoryResult.Saved(categoryId), editCategory(categoryId, "Aseo", null))

        assertEquals(3, deleteCategory.affectedProducts(categoryId))
        assertEquals(DeleteCategoryResult.Deleted(3), deleteCategory(categoryId))
        assertEquals(0L, count("SELECT COUNT(*) FROM products WHERE category_id IS NOT NULL"))
    }

    @Test
    fun aFailureAfterEachWriteOfEveryOperationLeavesNothing() = runBlocking {
        val categoryId = database.categoryDao().insert(category("Limpieza"))
        val seeded =
            savedId(
                create(
                    soap.copy(name = "Base", minStock = QuantityMilli(1_000L)),
                    QuantityMilli(5_000L),
                ),
            )
        val archived = savedId(create(ProductForm(name = "Archivado")))
        archive(archived)
        val bare = savedId(create(ProductForm(name = "Sin nada")))
        val empty = savedId(create(ProductForm(name = "Vacío")))
        val operations: List<Pair<String, suspend () -> Unit>> = listOf(
            "crear" to
                {
                    create(
                        soap.copy(categoryId = categoryId, minStock = QuantityMilli(9_000L)),
                        QuantityMilli(3_000L),
                    )
                },
            "editar" to {
                edit(
                    seeded,
                    soap.copy(
                        name = "Base nueva",
                        baseUnitId = UNIT_ID,
                        salePrice = Money(4_000L),
                        minStock = QuantityMilli(8_000L),
                    ),
                )
            },
            "archivar" to { archive(seeded) },
            "reactivar" to { reactivate(archived) },
            "inventario inicial" to { opening(empty, QuantityMilli(2_000L), Money(300L)) },
            "eliminar" to { delete(bare) },
            "crear categoría" to { createCategory("Bebidas") },
            "renombrar categoría" to { editCategory(categoryId, "Aseo", null) },
            "eliminar categoría" to { deleteCategory(categoryId) },
        )
        for ((name, operation) in operations) {
            val before = snapshot()
            // Falla después de la primera escritura, después de la segunda, y así hasta que la
            // operación termina sin llegar a la escritura pedida: esa última vez confirma.
            var failAt = 1
            while (true) {
                faults.reset(failAfter = failAt)
                val failed = try {
                    operation()
                    false
                } catch (expected: InjectedWriteFailure) {
                    true
                }
                if (!failed) break
                assertEquals("$name: la escritura $failAt dejó algo", before, snapshot())
                failAt++
            }
            assertTrue("$name no escribió nada", failAt > 1)
            assertTrue("$name no confirmó", before != snapshot())
        }
        faults.reset(failAfter = null)
        assertEquals(listOf("ok"), rows("PRAGMA integrity_check"))
    }

    private fun savedId(result: SaveProductResult): Long =
        (result as SaveProductResult.Saved).productId

    private suspend fun changeSeq(): Long = count("SELECT change_seq FROM backup_state")

    private suspend fun rows(sql: String): List<String> = database.useReaderConnection {
        it.texts(sql)
    }

    /** Lo que cualquiera de estas operaciones podría dejar escrito. */
    private suspend fun snapshot(): List<List<String>> = listOf(
        rows(
            "SELECT id || '|' || name || '|' || normalized_name || '|' || " +
                "COALESCE(category_id, '-') || '|' || sale_price || '|' || " +
                "initial_cost || '|' || " +
                "average_cost || '|' || stock_value || '|' || on_hand_milli || '|' || " +
                "min_stock_milli || '|' || COALESCE(archived_at, '-') FROM products ORDER BY id",
        ),
        rows("SELECT id || '|' || new_price FROM product_price_history ORDER BY id"),
        rows("SELECT id || '|' || quantity_milli FROM stock_movements ORDER BY id"),
        rows("SELECT product_id || '|' || on_hand_milli FROM stock_balances ORDER BY product_id"),
        rows("SELECT id || '|' || COALESCE(resolved_at, '-') FROM low_stock_alerts ORDER BY id"),
        rows("SELECT id || '|' || name FROM categories ORDER BY id"),
        rows("SELECT change_seq || '|' || COALESCE(first_unsynced_at, '-') FROM backup_state"),
    )
}
