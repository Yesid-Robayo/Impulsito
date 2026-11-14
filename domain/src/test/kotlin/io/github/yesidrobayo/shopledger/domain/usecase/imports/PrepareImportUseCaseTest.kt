package io.github.yesidrobayo.shopledger.domain.usecase.imports

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportDraft
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.testing.FakeCustomerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceIndexRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceListRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La vista previa con lo que ya existe en el negocio: nombres vigentes y archivados, códigos en
 * uso, unidades en cualquier idioma, listas de precios y meses del índice. Datos inventados.
 */
class PrepareImportUseCaseTest {
    private val fixture = CatalogFixture()
    private val customers = FakeCustomerRepository(fixture.database)
    private val priceLists = FakePriceListRepository(fixture.database)
    private val priceIndex = FakePriceIndexRepository(fixture.database)
    private val prepare = PrepareImportUseCase(
        products = fixture.products,
        customers = customers,
        suppliers = fixture.suppliers,
        categories = fixture.categories,
        units = fixture.units,
        priceLists = priceLists,
        priceIndex = priceIndex,
        systemRows = fixture.systemRows,
        settings = fixture.settings,
        seedLabels = { table, key ->
            when {
                table == SeedTable.UNITS && key == "KILOGRAM" -> listOf("Kilogramo", "kg")
                table == SeedTable.PRICE_LISTS -> listOf("Precio de venta", "Sale price")
                else -> emptyList()
            }
        },
    )

    private fun sheet(vararg rows: List<String>) = ImportSheet(
        header = emptyList(),
        rows = rows.mapIndexed { index, cells -> ImportRow(index + 2, cells) },
        separator = CsvSeparator.SEMICOLON,
    )

    @Test
    fun `products are told apart by what already exists`() = runTest {
        fixture.seedProduct(1L, "Arroz 1 kg", barcode = "7700001")
        fixture.seedProduct(2L, "Jabón", archived = true)
        val columns = listOf("name", "base_unit", "barcode", "category", "default_supplier")

        val preview = prepare(
            sheet(
                listOf("arroz 1 KG", "", "", "Limpieza", "Proveedor Uno"),
                listOf("Jabon", "", "", "", ""),
                listOf("Harina", "KG", "", "Granos", "Proveedor Dos"),
                listOf("Avena", "unidad", "", "", ""),
                listOf("Sal", "", "7700001", "", ""),
            ),
            ImportTarget.PRODUCTS,
            columns,
            DecimalSeparator.COMMA,
        )

        assertEquals(listOf(2), preview.existing.map { it.row })
        assertEquals(listOf(3), preview.locked.map { it.row })
        assertEquals(listOf(4), preview.ready.map { it.row })
        assertEquals(
            listOf(
                ImportRowIssue(5, "base_unit", ImportIssue.UNIT_UNKNOWN),
                ImportRowIssue(6, "barcode", ImportIssue.BARCODE_USED),
            ),
            preview.issues,
        )
        // `Granos` es nueva; el proveedor archivado se crea otra vez como proveedor vigente.
        assertEquals(1, preview.newCategories)
        assertEquals(1, preview.newSuppliers)
    }

    @Test
    fun `a unit is recognized by its name in any language and amounts use the currency`() =
        runTest {
            val preview = prepare(
                sheet(listOf("Harina", "Kilogramo", "1,5", "2.500,25")),
                ImportTarget.PRODUCTS,
                listOf("name", "base_unit", "on_hand", "sale_price"),
                DecimalSeparator.COMMA,
            )

            val draft = preview.ready.single() as ImportDraft.Product
            assertEquals(CatalogFixture.KILOGRAM_ID, draft.unitId)
            assertEquals(1_500L, draft.onHand?.milli)
            // La moneda del negocio tiene dos decimales.
            assertEquals(Money(250_025L), draft.salePrice)
        }

    @Test
    fun `customers with a repeated name are not updated, and lists are found by name`() = runTest {
        fixture.database.seed { tables ->
            tables.copy(
                customers = mapOf(
                    1L to customer(1L, "Ana"),
                    2L to customer(2L, "Luis"),
                    3L to customer(3L, "Luis"),
                ),
                priceLists = mapOf(
                    CatalogFixture.DEFAULT_PRICE_LIST_ID to PriceList(
                        CatalogFixture.DEFAULT_PRICE_LIST_ID,
                        "DEFAULT",
                        null,
                        PriceListPricing.Fixed,
                        null,
                    ),
                    5L to PriceList(5L, null, "Mayorista", PriceListPricing.Fixed, null),
                ),
            )
        }

        val preview = prepare(
            sheet(
                listOf("Ana", "mayorista"),
                listOf("Luis", ""),
                listOf("Marta", "Sale price"),
            ),
            ImportTarget.CUSTOMERS,
            listOf("name", "price_list"),
            DecimalSeparator.COMMA,
        )

        assertEquals(listOf(2), preview.existing.map { it.row })
        assertEquals(listOf(3), preview.locked.map { it.row })
        assertEquals(5L, (preview.existing.single() as ImportDraft.Contact).priceListId)
        assertEquals(
            CatalogFixture.DEFAULT_PRICE_LIST_ID,
            (preview.ready.single() as ImportDraft.Contact).priceListId,
        )
    }

    @Test
    fun `suppliers and months that exist are recognized`() = runTest {
        fixture.database.seed { it.copy(priceIndex = mapOf("2026-01" to 120L)) }

        val suppliers = prepare(
            sheet(listOf("Proveedor uno"), listOf("Proveedor dos"), listOf("Nuevo")),
            ImportTarget.SUPPLIERS,
            listOf("name"),
            DecimalSeparator.COMMA,
        )
        val months = prepare(
            sheet(listOf("2026-01", "1"), listOf("2026-02", "2")),
            ImportTarget.PRICE_INDEX,
            listOf("month", "change_percent"),
            DecimalSeparator.COMMA,
        )

        assertEquals(listOf(2), suppliers.existing.map { it.row })
        assertEquals(listOf(3), suppliers.locked.map { it.row })
        assertEquals(listOf(4), suppliers.ready.map { it.row })
        assertEquals(listOf(2), months.existing.map { it.row })
        assertEquals(listOf(3), months.ready.map { it.row })
    }

    private fun customer(id: Long, name: String) = Customer(
        id = id,
        contact = CustomerContact(name, name.lowercase(), null, null, null),
        priceListId = null,
        balance = Money.ZERO,
        pointsBalance = 0L,
        anonymizedAt = null,
        archivedAt = null,
        createdAt = CatalogFixture.EARLIER,
        updatedAt = CatalogFixture.EARLIER,
    )
}
