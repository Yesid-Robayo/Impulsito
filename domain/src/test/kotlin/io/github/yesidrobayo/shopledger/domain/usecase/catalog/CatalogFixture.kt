package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeCategoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeLowStockAlertRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeStockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeSupplierRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeSystemRowRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FakeUnitRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.testing.ProductStockHeader
import io.github.yesidrobayo.shopledger.domain.testing.Tables
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Un catálogo en memoria con las filas iniciales que crea la base, y todos los casos de uso de
 * producto y categoría conectados a él.
 *
 * Las unidades, la ubicación y la lista de precios iniciales no tienen id 1 a propósito: un caso de
 * uso que supusiera el id en vez de buscarlo por su clave fallaría aquí.
 */
internal class CatalogFixture(zoneId: String = "UTC", currencyConfirmed: Boolean = true) {
    val database = InMemoryDatabase()
    val transactions = FakeTransactionRunner(database)
    val clock = FixedClock.atEpochMillis(NOW_MILLIS)
    val settings = FakeAppSettingsRepository(appSettings(regional(zoneId, currencyConfirmed)))
    val products = FakeProductRepository(database)
    val categories = FakeCategoryRepository(database)
    val suppliers = FakeSupplierRepository(database)
    val units = FakeUnitRepository(database)
    val systemRows = FakeSystemRowRepository(database)
    val priceHistory = FakePriceHistoryRepository(database)
    val ledger = FakeStockLedgerRepository(database)
    val alerts = FakeLowStockAlertRepository(database)
    private val calendar = BusinessCalendarSource(settings)
    private val currencyLock = CurrencyLock(settings)

    val create = CreateProductUseCase(
        transactions, products, categories, suppliers, units, systemRows, priceHistory, ledger,
        alerts, calendar, currencyLock, clock,
    )
    val edit = EditProductUseCase(
        transactions, products, categories, suppliers, units, systemRows, priceHistory, ledger,
        alerts, calendar, currencyLock, clock,
    )
    val archive = ArchiveProductUseCase(transactions, products, alerts, clock)
    val reactivate = ReactivateProductUseCase(transactions, products, alerts, calendar, clock)
    val delete = DeleteProductUseCase(transactions, products)
    val opening = RegisterOpeningBalanceUseCase(
        transactions,
        products,
        units,
        systemRows,
        ledger,
        alerts,
        calendar,
        currencyLock,
        clock,
    )
    val createCategory = CreateCategoryUseCase(transactions, categories, clock)
    val editCategory = EditCategoryUseCase(transactions, categories, clock)
    val deleteCategory = DeleteCategoryUseCase(transactions, categories)

    init {
        database.seed { tables ->
            tables.copy(
                units = listOf(
                    unit(UNIT_ID, "UNIT", UnitKind.COUNT, allowsFraction = false),
                    unit(KILOGRAM_ID, "KILOGRAM", UnitKind.MASS, allowsFraction = true),
                    unit(ARCHIVED_UNIT_ID, null, UnitKind.OTHER, false).copy(archivedAt = EARLIER),
                ).associateBy { it.id },
                systemRows = mapOf(
                    SystemRow.DEFAULT_UNIT to UNIT_ID,
                    SystemRow.MAIN_LOCATION to MAIN_LOCATION_ID,
                    SystemRow.DEFAULT_PRICE_LIST to DEFAULT_PRICE_LIST_ID,
                ),
                suppliers = listOf(
                    supplier(ACTIVE_SUPPLIER_ID, "Proveedor Uno", archivedAt = null),
                    supplier(ARCHIVED_SUPPLIER_ID, "Proveedor Dos", archivedAt = EARLIER),
                ).associateBy { it.id },
                categories = mapOf(CATEGORY_ID to category(CATEGORY_ID, "Limpieza")),
            )
        }
    }

    val tables: Tables get() = database.tables.value

    fun product(id: Long): Product = requireNotNull(tables.products[id]).withStock(tables)

    fun movementsOf(productId: Long): List<StockMovementRecord> =
        tables.movements.values.filter { it.movement.key.productId == productId }.sortedBy { it.id }

    fun activeAlertsOf(productId: Long): List<LowStockAlert> =
        tables.alerts.values.filter { it.productId == productId && it.isActive }

    /** Siembra un producto ya guardado, como lo dejaría la base, con su estado de stock. */
    fun seedProduct(
        id: Long,
        name: String,
        archived: Boolean = false,
        stockMode: ProductStockMode = ProductStockMode.TRACKED,
        salePrice: Money = Money.ZERO,
        initialCost: Money = Money.ZERO,
        onHand: QuantityMilli = QuantityMilli.ZERO,
        minStock: QuantityMilli = QuantityMilli.ZERO,
        barcode: String? = null,
        baseUnitId: Long = UNIT_ID,
        defaultSupplierId: Long? = null,
    ) {
        val stored = Product(
            id = id,
            details = details(name, stockMode, minStock, barcode, baseUnitId, defaultSupplierId),
            salePrice = salePrice,
            initialCost = initialCost,
            averageCost = initialCost,
            stockValue = Money.ZERO,
            onHand = onHand,
            replacementCost = Money.ZERO,
            replacementCostAt = null,
            priceReviewSince = null,
            archivedAt = if (archived) EARLIER else null,
            createdAt = EARLIER,
            updatedAt = EARLIER,
        )
        database.seed { tables ->
            tables.copy(
                products = tables.products + (id to stored),
                productStock = tables.productStock +
                    (id to ProductStockHeader(onHand, initialCost, Money.ZERO)),
                lastIds =
                    tables.lastIds + ("products" to maxOf(id, tables.lastIds["products"] ?: 0L)),
            )
        }
    }

    companion object {
        const val NOW_MILLIS = 1_700_000_000_000L
        val EARLIER: Instant = Instant.fromEpochMilliseconds(NOW_MILLIS - 86_400_000L)
        val NOW: Instant = Instant.fromEpochMilliseconds(NOW_MILLIS)
        const val UNIT_ID = 11L
        const val KILOGRAM_ID = 12L
        const val ARCHIVED_UNIT_ID = 13L
        const val MAIN_LOCATION_ID = 21L
        const val DEFAULT_PRICE_LIST_ID = 31L
        const val ACTIVE_SUPPLIER_ID = 41L
        const val ARCHIVED_SUPPLIER_ID = 42L
        const val CATEGORY_ID = 51L

        /**
         * Corre [run] una vez para contar cuántas escrituras hace, y después una vez por cada una, en
         * un catálogo nuevo preparado por [prepare], con una falla inyectada en esa escritura. Tras cada
         * falla exige que no quede nada: las tablas confirmadas son las de antes.
         */
        suspend fun assertEachWriteFailureLeavesNothing(
            prepare: CatalogFixture.() -> Unit,
            run: suspend CatalogFixture.() -> Unit,
        ) {
            val counting = CatalogFixture().apply(prepare)
            counting.run()
            val writes = counting.database.lastTransactionWrites
            assertTrue("La operación debe escribir algo", writes > 0)
            for (failAt in 1..writes) {
                val fixture = CatalogFixture().apply(prepare)
                val before = fixture.tables
                fixture.database.failAtWrite = failAt
                val failure = try {
                    fixture.run()
                    null
                } catch (expected: InjectedFailure) {
                    expected
                }
                assertTrue("La escritura $failAt debió fallar", failure != null)
                assertEquals("La escritura $failAt dejó algo", before, fixture.tables)
            }
        }

        fun details(
            name: String,
            stockMode: ProductStockMode = ProductStockMode.TRACKED,
            minStock: QuantityMilli = QuantityMilli.ZERO,
            barcode: String? = null,
            baseUnitId: Long = UNIT_ID,
            defaultSupplierId: Long? = null,
        ): ProductDetails = ProductDetails(
            name = name,
            normalizedName = TextNormalizer.normalize(name),
            categoryId = null,
            defaultSupplierId = defaultSupplierId,
            baseUnitId = baseUnitId,
            stockMode = stockMode,
            tracksLots = false,
            fixedUnitCost = Money.ZERO,
            minStock = minStock,
            barcode = barcode,
            parentProductId = null,
            variantAxis1 = null,
            variantAxis2 = null,
            variantValue1 = null,
            variantValue2 = null,
            photoMediaId = null,
            taxRateId = null,
            targetMargin = null,
            referenceCost = null,
            referencePrice = null,
            expiryNoticeDays = null,
        )

        /** Ajustes de prueba con la zona [zoneId] y la moneda confirmada o sin confirmar. */
        private fun regional(zoneId: String, currencyConfirmed: Boolean): RegionalSettings {
            val regional = regionalSettings(zoneId, DayOfWeek.MONDAY)
            if (currencyConfirmed) return regional
            val unconfirmed = regional.currency.copy(code = CurrencyCode.UNCONFIRMED, decimals = 0)
            return regional.copy(currency = unconfirmed)
        }

        private fun unit(id: Long, key: String?, kind: UnitKind, allowsFraction: Boolean) =
            MeasureUnit(
                id = id,
                systemKey = key,
                name = if (key == null) "Bulto" else null,
                symbol = null,
                kind = kind,
                allowsFraction = allowsFraction,
                archivedAt = null,
            )

        private fun supplier(id: Long, name: String, archivedAt: Instant?) = Supplier(
            id = id,
            details = SupplierDetails(
                name = name,
                normalizedName = TextNormalizer.normalize(name),
                phone = null,
                email = null,
                notes = null,
                paymentTermsDays = null,
            ),
            balance = Money.ZERO,
            archivedAt = archivedAt,
            createdAt = EARLIER,
            updatedAt = EARLIER,
        )

        private fun category(id: Long, name: String) = Category(
            id = id,
            details = CategoryDetails(name, TextNormalizer.normalize(name), targetMargin = null),
            createdAt = EARLIER,
            updatedAt = EARLIER,
        )

        private fun Product.withStock(tables: Tables): Product {
            val stock = tables.productStock[id] ?: return this
            return copy(
                onHand = stock.onHand,
                averageCost = stock.averageCost,
                stockValue = stock.stockValue,
            )
        }
    }
}
