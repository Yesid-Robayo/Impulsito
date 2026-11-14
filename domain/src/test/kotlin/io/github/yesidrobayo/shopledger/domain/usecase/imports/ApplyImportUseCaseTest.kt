package io.github.yesidrobayo.shopledger.domain.usecase.imports

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportApplyResult
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportExistingPolicy
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.FakeCustomerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceIndexRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceListRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aplicar una importación en una transacción: productos con su stock inicial y su fila de precio
 * `IMPORT`, lo que ya existe omitido o actualizado sin tocar stock ni costo, y una falla en
 * cualquier escritura que no deja nada. Datos inventados; la moneda tiene dos decimales.
 */
class ApplyImportUseCaseTest {
    private class Importer(val fixture: CatalogFixture = CatalogFixture()) {
        private val customers = FakeCustomerRepository(fixture.database)
        private val priceIndex = FakePriceIndexRepository(fixture.database)
        val prepare = PrepareImportUseCase(
            products = fixture.products,
            customers = customers,
            suppliers = fixture.suppliers,
            categories = fixture.categories,
            units = fixture.units,
            priceLists = FakePriceListRepository(fixture.database),
            priceIndex = priceIndex,
            systemRows = fixture.systemRows,
            settings = fixture.settings,
            seedLabels = { _, _ -> emptyList() },
        )
        val apply = ApplyImportUseCase(
            transactions = fixture.transactions,
            writes = ImportWrites(
                products = fixture.products,
                categories = fixture.categories,
                suppliers = fixture.suppliers,
                customers = customers,
                units = fixture.units,
                priceIndex = priceIndex,
                systemRows = fixture.systemRows,
                priceHistory = fixture.priceHistory,
                ledger = fixture.ledger,
                alerts = fixture.alerts,
            ),
            calendar = BusinessCalendarSource(fixture.settings),
            currencyLock = CurrencyLock(fixture.settings),
            clock = fixture.clock,
        )

        suspend fun preview(
            target: ImportTarget,
            columns: List<String?>,
            vararg rows: List<String>,
        ): ImportPreview = prepare(
            ImportSheet(
                header = emptyList(),
                rows = rows.mapIndexed { index, cells -> ImportRow(index + 2, cells) },
                separator = CsvSeparator.SEMICOLON,
            ),
            target,
            columns,
            DecimalSeparator.COMMA,
        )
    }

    private val productColumns =
        listOf("name", "category", "default_supplier", "sale_price", "average_cost", "on_hand")

    private suspend fun Importer.products(): ImportPreview = preview(
        ImportTarget.PRODUCTS,
        productColumns,
        listOf("Lentejas", "Granos", "Molino Alto", "4.200", "3.000", "10"),
        listOf("Garbanzos", "granos", "", "5.000", "", ""),
    )

    @Test
    fun `new products enter with their opening stock and an import price row`() = runTest {
        val importer = Importer()
        val preview = importer.products()

        val result = importer.apply(preview, ImportExistingPolicy.SKIP)

        assertEquals(ImportApplyResult.Applied(created = 2, updated = 0, skipped = 0), result)
        val tables = importer.fixture.tables
        val lentils = tables.products.values.single { it.details.name == "Lentejas" }
        assertEquals(Money(420_000L), lentils.salePrice)
        assertEquals(Money(300_000L), lentils.initialCost)
        // Las dos filas nombran la misma categoría: se crea una sola.
        assertEquals(1, tables.categories.values.count { it.details.name == "Granos" })
        assertEquals(1, tables.suppliers.values.count { it.details.name == "Molino Alto" })
        val movement = importer.fixture.movementsOf(lentils.id).single()
        assertEquals(StockMovementType.OPENING_BALANCE, movement.movement.type)
        assertEquals(QuantityMilli(10_000L), movement.movement.quantity)
        // 10 a 3.000 cada una.
        assertEquals(Money(3_000_000L), movement.totalCost)
        assertEquals(
            listOf(PriceChangeSource.IMPORT, PriceChangeSource.IMPORT),
            tables.priceChanges.values.map { it.content.source },
        )
    }

    @Test
    fun `importing the same file again creates nothing`() = runTest {
        val importer = Importer()
        importer.apply(importer.products(), ImportExistingPolicy.SKIP)
        val before = importer.fixture.tables.products.size

        val again = importer.products()
        val result = importer.apply(again, ImportExistingPolicy.SKIP)

        assertEquals(0, again.ready.size)
        assertEquals(ImportApplyResult.Applied(created = 0, updated = 0, skipped = 2), result)
        assertEquals(before, importer.fixture.tables.products.size)
    }

    @Test
    fun `updating changes the price and catalog data but never the stock or the cost`() = runTest {
        val importer = Importer()
        importer.fixture.seedProduct(
            1L,
            "Arroz",
            salePrice = Money(250_000L),
            initialCost = Money(200_000L),
            onHand = QuantityMilli(5_000L),
        )
        val preview = importer.preview(
            ImportTarget.PRODUCTS,
            listOf("name", "sale_price", "average_cost", "on_hand", "min_stock", "barcode"),
            listOf("arroz", "2.800", "9.999", "50", "8", "7700"),
        )

        val result = importer.apply(preview, ImportExistingPolicy.UPDATE)

        assertEquals(ImportApplyResult.Applied(created = 0, updated = 1, skipped = 0), result)
        val rice = importer.fixture.product(1L)
        assertEquals(Money(280_000L), rice.salePrice)
        assertEquals(Money(200_000L), rice.initialCost)
        assertEquals(QuantityMilli(5_000L), rice.onHand)
        assertEquals(QuantityMilli(8_000L), rice.details.minStock)
        assertEquals("7700", rice.details.barcode)
        val change = importer.fixture.tables.priceChanges.values.single()
        assertEquals(Money(250_000L), change.content.oldPrice)
        assertEquals(PriceChangeSource.IMPORT, change.content.source)
        assertTrue(importer.fixture.movementsOf(1L).isEmpty())
        // El stock quedó por debajo del mínimo nuevo: la alerta nace callada.
        assertEquals(1, importer.fixture.activeAlertsOf(1L).size)
    }

    @Test
    fun `skipping existing rows writes nothing at all`() = runTest {
        val importer = Importer()
        importer.fixture.seedProduct(1L, "Arroz")
        val preview = importer.preview(ImportTarget.PRODUCTS, listOf("name"), listOf("Arroz"))
        val before = importer.fixture.tables

        val result = importer.apply(preview, ImportExistingPolicy.SKIP)

        assertEquals(ImportApplyResult.Applied(created = 0, updated = 0, skipped = 1), result)
        assertEquals(before, importer.fixture.tables)
    }

    @Test
    fun `a name saved after the review leaves everything as it was`() = runTest {
        val importer = Importer()
        val preview = importer.products()
        importer.fixture.seedProduct(9L, "Garbanzos")
        val before = importer.fixture.tables

        val result = importer.apply(preview, ImportExistingPolicy.SKIP)

        assertEquals(ImportApplyResult.Outdated, result)
        assertEquals(before, importer.fixture.tables)
    }

    @Test
    fun `amounts need a confirmed currency`() = runTest {
        val importer = Importer(CatalogFixture(currencyConfirmed = false))
        val preview = importer.products()

        val result = importer.apply(preview, ImportExistingPolicy.SKIP)

        assertEquals(ImportApplyResult.CurrencyUnconfirmed, result)
        assertTrue(importer.fixture.tables.products.isEmpty())
    }

    @Test
    fun `a storage failure comes back typed`() = runTest {
        val importer = Importer()
        val preview = importer.products()
        importer.fixture.database.failAtWrite = 1
        importer.fixture.database.failure = {
            StorageFailureException(StorageFailure.DISK_FULL)
        }

        val result = importer.apply(preview, ImportExistingPolicy.SKIP)

        assertEquals(ImportApplyResult.StorageFailed(StorageFailure.DISK_FULL), result)
    }

    @Test
    fun `a failure in any write of the import leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(prepare = {}) {
            val importer = Importer(this)
            importer.apply(importer.products(), ImportExistingPolicy.SKIP)
        }
    }

    @Test
    fun `customers, suppliers and months are created or updated`() = runTest {
        val importer = Importer()
        val customers = importer.preview(
            ImportTarget.CUSTOMERS,
            listOf("name", "phone"),
            listOf("Marta", "300 000 0000"),
        )
        importer.apply(customers, ImportExistingPolicy.SKIP)
        val updated = importer.preview(
            ImportTarget.CUSTOMERS,
            listOf("name", "email", "phone"),
            listOf("MARTA", "marta@example.com", ""),
        )
        val customerResult = importer.apply(updated, ImportExistingPolicy.UPDATE)

        val suppliers = importer.preview(
            ImportTarget.SUPPLIERS,
            listOf("name", "payment_terms_days"),
            listOf("Proveedor Uno", "15"),
            listOf("Granja", "30"),
        )
        val supplierResult = importer.apply(suppliers, ImportExistingPolicy.UPDATE)

        importer.fixture.database.seed { it.copy(priceIndex = mapOf("2026-01" to 100L)) }
        val months = importer.preview(
            ImportTarget.PRICE_INDEX,
            listOf("month", "change_percent"),
            listOf("2026-01", "1,5"),
            listOf("2026-02", "-0,25"),
        )
        val monthResult = importer.apply(months, ImportExistingPolicy.UPDATE)

        val tables = importer.fixture.tables
        val marta = tables.customers.values.single().contact
        assertEquals("300 000 0000", marta?.phone)
        assertEquals("marta@example.com", marta?.email)
        assertEquals(ImportApplyResult.Applied(0, 1, 0), customerResult)
        assertEquals(ImportApplyResult.Applied(1, 1, 0), supplierResult)
        assertEquals(
            15,
            tables.suppliers.values.single { it.details.name == "Proveedor Uno" }
                .details.paymentTermsDays,
        )
        assertEquals(ImportApplyResult.Applied(1, 1, 0), monthResult)
        assertEquals(mapOf("2026-01" to 150L, "2026-02" to -25L), tables.priceIndex)
    }

    @Test
    fun `products without stock keep their fixed cost`() = runTest {
        val importer = Importer()
        val preview = importer.preview(
            ImportTarget.PRODUCTS,
            listOf("name", "stock_mode", "sale_price", "average_cost"),
            listOf("Corte de cabello", "UNTRACKED", "20.000", "5.000"),
        )

        importer.apply(preview, ImportExistingPolicy.SKIP)

        val service = importer.fixture.tables.products.values.single()
        assertEquals(ProductStockMode.UNTRACKED, service.details.stockMode)
        assertEquals(Money(500_000L), service.details.fixedUnitCost)
        assertEquals(Money.ZERO, service.initialCost)
    }

    @Test
    fun `a month added after the review leaves everything as it was`() = runTest {
        val importer = Importer()
        val months = importer.preview(
            ImportTarget.PRICE_INDEX,
            listOf("month", "change_percent"),
            listOf("2026-03", "1"),
        )
        importer.fixture.database.seed { it.copy(priceIndex = mapOf("2026-03" to 1L)) }

        assertEquals(
            ImportApplyResult.Outdated,
            importer.apply(months, ImportExistingPolicy.SKIP),
        )
        assertEquals(BasisPoints(1L).bp, importer.fixture.tables.priceIndex["2026-03"])
    }
}
