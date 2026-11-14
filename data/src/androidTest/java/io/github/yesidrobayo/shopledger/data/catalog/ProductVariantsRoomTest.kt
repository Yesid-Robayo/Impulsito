package io.github.yesidrobayo.shopledger.data.catalog

import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantAxes
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ArchiveProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateVariantsResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateVariantsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ReactivateProductUseCase
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
 * Las variantes de un producto sobre la base real, con sus guardas: cada una con su libro, la
 * familia que se archiva y vuelve junta, y una falla después de cualquier escritura que no deja
 * nada. Todos los nombres y montos son inventados.
 */
internal class ProductVariantsRoomTest : CatalogDatabaseTest() {
    private val faults = WriteFaults()
    private lateinit var ports: DataPorts
    private lateinit var create: CreateProductUseCase
    private lateinit var createVariants: CreateVariantsUseCase
    private lateinit var archive: ArchiveProductUseCase
    private lateinit var reactivate: ReactivateProductUseCase
    private lateinit var delete: DeleteProductUseCase

    private val sizeAndColor = VariantAxes("Talla", "Color")

    @Before
    fun connectUseCases() {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        val products = FaultyProducts(ports.products, faults)
        val priceHistory = FaultyPriceHistory(ports.priceHistory, faults)
        val ledger = FaultyLedger(ports.stockLedger, faults)
        val alerts = FaultyAlerts(ports.lowStockAlerts, faults)
        val calendar = BusinessCalendarSource(ports.settings)
        val currencyLock = CurrencyLock(ports.settings)
        val tx = ports.transactions
        create = CreateProductUseCase(
            tx, products, ports.categories, ports.suppliers, ports.units, ports.systemRows,
            priceHistory, ledger, alerts, calendar, currencyLock, FIXED_CLOCK, ports.mediaAssets,
        )
        createVariants = CreateVariantsUseCase(
            tx, products, ports.productVariants, ports.units, ports.systemRows, priceHistory,
            ledger, alerts, calendar, currencyLock, FIXED_CLOCK,
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
    }

    @Test
    fun eachVariantIsAProductWithItsOwnLedgerAndCost() = runBlocking {
        val parent = parentNamed("Camiseta")

        val result = createVariants(
            parent,
            sizeAndColor,
            listOf(
                VariantDraft(
                    VariantValues("S", "Rojo"),
                    salePrice = Money(25_000L),
                    cost = Money(14_000L),
                    initialStock = QuantityMilli(4_000L),
                ),
                VariantDraft(
                    VariantValues("M", "Azul"),
                    salePrice = Money(26_000L),
                    cost = Money(15_000L),
                    initialStock = QuantityMilli(6_000L),
                ),
            ),
        )

        val ids = (result as CreateVariantsResult.Created).productIds
        assertEquals(
            listOf(
                "Camiseta · S · Rojo|$parent|Talla|Color|S|Rojo|4000|14000|56000",
                "Camiseta · M · Azul|$parent|Talla|Color|M|Azul|6000|15000|90000",
            ),
            rows(
                "SELECT name || '|' || parent_product_id || '|' || variant_axis_1 || '|' || " +
                    "variant_axis_2 || '|' || variant_value_1 || '|' || " +
                    "variant_value_2 || '|' || " +
                    "on_hand_milli || '|' || average_cost || '|' || stock_value FROM products " +
                    "WHERE parent_product_id IS NOT NULL ORDER BY id",
            ),
        )
        assertEquals(
            listOf("${ids[0]}|4000", "${ids[1]}|6000"),
            rows(
                "SELECT product_id || '|' || quantity_milli FROM stock_movements " +
                    "WHERE type = 'OPENING_BALANCE' ORDER BY id",
            ),
        )
        assertEquals(0L, count("SELECT on_hand_milli FROM products WHERE id = $parent"))
        assertEquals(ids, ports.productVariants.findVariants(parent).map { it.id })
        assertEquals(ids, ports.productVariants.observeVariants(parent).first().map { it.id })
        assertEquals(setOf(CatalogLink.VARIANT_PARENT), ports.products.catalogLinks(parent))
        assertEquals(
            DeleteProductResult.LinkedInCatalog(setOf(CatalogLink.VARIANT_PARENT)),
            delete(parent),
        )
    }

    @Test
    fun aVariantOfAVariantIsRejectedByTheUseCaseAndTheBase() = runBlocking {
        val parent = parentNamed("Camiseta")
        val variant = createdIds(parent, listOf("S")).single()

        assertEquals(
            CreateVariantsResult.ParentIsVariant,
            createVariants(variant, VariantAxes("Color", null), listOf(draft("Rojo"))),
        )
        guardFailure("guard:products") {
            database.productDao().insert(
                product("Camiseta · S · Rojo").copy(parentProductId = variant),
            )
        }
    }

    @Test
    fun archivingTheParentTakesItsActiveVariantsAndReactivatingBringsThemBack() = runBlocking {
        val parent = parentNamed("Camiseta")
        val ids = createdIds(parent, listOf("S", "M"))

        assertEquals(ArchiveProductResult.Archived, archive(parent))
        assertEquals(
            listOf(1L, 1L),
            rows(
                "SELECT archived_at IS NOT NULL FROM products WHERE parent_product_id = $parent " +
                    "ORDER BY id",
            ).map { it.toLong() },
        )
        assertEquals(ReactivateProductResult.ParentArchived, reactivate(ids[0]))

        assertEquals(ReactivateProductResult.Reactivated, reactivate(parent))
        assertEquals(0L, count("SELECT COUNT(*) FROM products WHERE archived_at IS NOT NULL"))
    }

    @Test
    fun aFailureAfterAnyWriteLeavesNothing() = runBlocking {
        val parent = parentNamed("Camiseta")
        val family = parentNamed("Pantalón")
        createdIds(family, listOf("28", "30"))
        val archivedFamily = parentNamed("Gorra")
        createdIds(archivedFamily, listOf("Única"))
        archive(archivedFamily)
        val operations = listOf<Pair<String, suspend () -> Unit>>(
            "crear variantes" to {
                createVariants(
                    parent,
                    sizeAndColor,
                    listOf(
                        VariantDraft(
                            VariantValues("S", "Rojo"),
                            salePrice = Money(25_000L),
                            cost = Money(14_000L),
                            initialStock = QuantityMilli(4_000L),
                            minStock = QuantityMilli(5_000L),
                        ),
                        VariantDraft(VariantValues("M", "Rojo")),
                    ),
                )
            },
            "archivar la familia" to { archive(family) },
            "reactivar la familia" to { reactivate(archivedFamily) },
        )
        for ((name, operation) in operations) {
            val before = snapshot()
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

    private suspend fun parentNamed(name: String): Long =
        (create(ProductForm(name = name)) as SaveProductResult.Saved).productId

    private suspend fun createdIds(parent: Long, sizes: List<String>): List<Long> {
        val result = createVariants(parent, VariantAxes("Talla", null), sizes.map(::draft))
        return (result as CreateVariantsResult.Created).productIds
    }

    private fun draft(value: String) = VariantDraft(VariantValues(value, null))

    private suspend fun rows(sql: String): List<String> = database.useReaderConnection {
        it.texts(sql)
    }

    /** Lo que cualquiera de estas operaciones podría dejar escrito. */
    private suspend fun snapshot(): List<List<String>> = listOf(
        rows(
            "SELECT id || '|' || name || '|' || COALESCE(parent_product_id, '-') || '|' || " +
                "sale_price || '|' || average_cost || '|' || stock_value || '|' || " +
                "on_hand_milli || '|' || COALESCE(archived_at, '-') FROM products ORDER BY id",
        ),
        rows("SELECT id || '|' || new_price FROM product_price_history ORDER BY id"),
        rows("SELECT id || '|' || quantity_milli FROM stock_movements ORDER BY id"),
        rows("SELECT product_id || '|' || on_hand_milli FROM stock_balances ORDER BY product_id"),
        rows("SELECT id || '|' || COALESCE(resolved_at, '-') FROM low_stock_alerts ORDER BY id"),
        rows("SELECT change_seq || '|' || COALESCE(first_unsynced_at, '-') FROM backup_state"),
    )
}
