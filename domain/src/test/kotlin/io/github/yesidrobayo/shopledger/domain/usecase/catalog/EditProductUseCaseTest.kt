package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogReference
import io.github.yesidrobayo.shopledger.domain.model.catalog.HistoryLockedField
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ARCHIVED_SUPPLIER_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ARCHIVED_UNIT_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.UNIT_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditProductUseCaseTest {
    private val fixture = CatalogFixture()

    /** El formulario tal como la pantalla lo llena con el producto guardado. */
    private fun formOf(productId: Long): ProductForm {
        val product = fixture.product(productId)
        return ProductForm(
            name = product.details.name,
            categoryId = product.details.categoryId,
            defaultSupplierId = product.details.defaultSupplierId,
            baseUnitId = product.details.baseUnitId,
            stockMode = product.details.stockMode,
            salePrice = product.salePrice,
            cost = product.initialCost,
            minStock = product.details.minStock,
            barcode = product.details.barcode,
        )
    }

    private suspend fun createSoap(initialStock: Long = 0L) {
        fixture.create(
            ProductForm(
                name = "Jabón Líquido",
                salePrice = Money(3_500L),
                cost = Money(1_200L),
                minStock = QuantityMilli(5_000L),
            ),
            QuantityMilli(initialStock),
        )
    }

    @Test
    fun `without movements a new cost moves the initial and the average cost, adding no row`() =
        runTest {
            createSoap()

            val result = fixture.edit(1L, formOf(1L).copy(cost = Money(1_300L)))

            assertEquals(SaveProductResult.Saved(1L), result)
            val product = fixture.product(1L)
            assertEquals(Money(1_300L), product.initialCost)
            assertEquals(Money(1_300L), product.averageCost)
            assertTrue(fixture.movementsOf(1L).isEmpty())
        }

    @Test
    fun `with movements the cost is locked`() = runTest {
        createSoap(initialStock = 24_000L)
        val before = fixture.tables

        val result = fixture.edit(1L, formOf(1L).copy(cost = Money(1_300L)))

        assertEquals(SaveProductResult.CostLockedByMovements, result)
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `renaming changes the name and its normalized form, and nothing of the ledger`() = runTest {
        createSoap(initialStock = 24_000L)
        val ledgerBefore = fixture.movementsOf(1L)

        fixture.edit(1L, formOf(1L).copy(name = " Jabón de manos "))

        val details = fixture.product(1L).details
        assertEquals("Jabón de manos", details.name)
        assertEquals("jabon de manos", details.normalizedName)
        assertEquals(ledgerBefore, fixture.movementsOf(1L))
        assertEquals(NOW, fixture.product(1L).updatedAt)
    }

    @Test
    fun `with history the base unit and the stock mode are locked`() = runTest {
        createSoap(initialStock = 24_000L)

        val result = fixture.edit(
            1L,
            formOf(
                1L,
            ).copy(
                baseUnitId = KILOGRAM_ID,
                stockMode = ProductStockMode.UNTRACKED,
                cost = Money.ZERO,
                minStock = QuantityMilli.ZERO,
            ),
        )

        assertEquals(
            SaveProductResult.LockedByHistory(
                setOf(HistoryLockedField.BASE_UNIT, HistoryLockedField.STOCK_MODE),
            ),
            result,
        )
    }

    @Test
    fun `without history a product can turn into a service, and its alert is resolved`() = runTest {
        createSoap()
        assertEquals(1, fixture.activeAlertsOf(1L).size)

        val result = fixture.edit(
            1L,
            formOf(
                1L,
            ).copy(
                stockMode = ProductStockMode.UNTRACKED,
                cost = Money(900L),
                minStock = QuantityMilli.ZERO,
            ),
        )

        assertEquals(SaveProductResult.Saved(1L), result)
        val product = fixture.product(1L)
        assertEquals(ProductStockMode.UNTRACKED, product.details.stockMode)
        assertEquals(Money(900L), product.details.fixedUnitCost)
        assertEquals(Money.ZERO, product.initialCost)
        assertTrue(fixture.activeAlertsOf(1L).isEmpty())
    }

    @Test
    fun `a new price leaves a manual row with the old and the new price`() = runTest {
        createSoap()

        fixture.edit(1L, formOf(1L).copy(salePrice = Money(3_800L)))

        assertEquals(Money(3_800L), fixture.product(1L).salePrice)
        val manual = fixture.tables.priceChanges.values.map { it.content }.last()
        assertEquals(PriceChangeSource.MANUAL, manual.source)
        assertEquals(Money(3_500L), manual.oldPrice)
        assertEquals(Money(3_800L), manual.newPrice)
    }

    @Test
    fun `saving without changes writes nothing and counts no change`() = runTest {
        createSoap()
        val before = fixture.tables

        val result = fixture.edit(1L, formOf(1L))

        assertEquals(SaveProductResult.Saved(1L), result)
        assertEquals(before, fixture.tables)
        assertEquals(1, fixture.transactions.rollbacks)
    }

    @Test
    fun `the same price with another name leaves no price row`() = runTest {
        createSoap()

        fixture.edit(1L, formOf(1L).copy(name = "Jabón"))

        assertEquals(1, fixture.tables.priceChanges.size)
    }

    @Test
    fun `raising the minimum over the stock creates a silent alert and lowering it resolves it`() =
        runTest {
            createSoap(initialStock = 6_000L)
            assertTrue(fixture.activeAlertsOf(1L).isEmpty())

            fixture.edit(1L, formOf(1L).copy(minStock = QuantityMilli(8_000L)))
            val alert = fixture.activeAlertsOf(1L).single()
            assertEquals(LowStockNotificationStatus.SUPPRESSED, alert.notificationStatus)
            assertEquals(QuantityMilli(6_000L), alert.onHandAtTrigger)

            fixture.edit(1L, formOf(1L).copy(minStock = QuantityMilli(5_000L)))
            assertTrue(fixture.activeAlertsOf(1L).isEmpty())
            assertNotNull(fixture.tables.alerts.getValue(alert.id).resolvedAt)
        }

    @Test
    fun `changing the minimum without crossing leaves the alerts alone`() = runTest {
        createSoap(initialStock = 9_000L)

        fixture.edit(1L, formOf(1L).copy(minStock = QuantityMilli(6_000L)))

        assertTrue(fixture.tables.alerts.isEmpty())
    }

    @Test
    fun `another active product with the name is a duplicate, the product itself is not`() =
        runTest {
            createSoap()
            fixture.create(ProductForm(name = "Detergente", barcode = "123"))

            assertEquals(
                SaveProductResult.DuplicateName("Detergente"),
                fixture.edit(1L, formOf(1L).copy(name = "DETERGENTE")),
            )
            assertEquals(
                SaveProductResult.DuplicateBarcode("Detergente"),
                fixture.edit(1L, formOf(1L).copy(barcode = "123")),
            )
            assertEquals(
                SaveProductResult.Saved(2L),
                fixture.edit(2L, formOf(2L).copy(name = "detergente")),
            )
        }

    @Test
    fun `an archived product can be renamed but not repriced`() = runTest {
        createSoap()
        fixture.archive(1L)
        fixture.create(ProductForm(name = "Jabón Líquido"))

        assertEquals(
            SaveProductResult.ArchivedPriceLocked,
            fixture.edit(1L, formOf(1L).copy(salePrice = Money(4_000L))),
        )
        assertEquals(
            SaveProductResult.ArchivedPriceLocked,
            fixture.edit(1L, formOf(1L).copy(cost = Money(1L))),
        )
        // Archivado no choca con nadie: puede tomar un nombre libre o uno en uso.
        assertEquals(
            SaveProductResult.Saved(1L),
            fixture.edit(1L, formOf(1L).copy(name = "Jabón viejo")),
        )
        assertEquals("Jabón viejo", fixture.product(1L).details.name)
    }

    @Test
    fun `a habitual supplier archived later and the current archived unit are kept`() = runTest {
        fixture.seedProduct(
            id = 5L,
            name = "Bolsa",
            baseUnitId = ARCHIVED_UNIT_ID,
            defaultSupplierId = ARCHIVED_SUPPLIER_ID,
        )

        val result = fixture.edit(5L, formOf(5L).copy(name = "Bolsa grande"))

        assertEquals(SaveProductResult.Saved(5L), result)
        assertEquals(
            SaveProductResult.MissingReference(CatalogReference.UNIT),
            fixture.edit(5L, formOf(5L).copy(baseUnitId = 999L)),
        )
    }

    @Test
    fun `a null unit means the counting unit`() = runTest {
        fixture.create(ProductForm(name = "Queso", baseUnitId = KILOGRAM_ID))

        fixture.edit(1L, formOf(1L).copy(baseUnitId = null))

        assertEquals(UNIT_ID, fixture.product(1L).details.baseUnitId)
    }

    @Test
    fun `rejections write nothing`() = runTest {
        createSoap()

        assertEquals(SaveProductResult.NotFound, fixture.edit(99L, formOf(1L)))
        assertEquals(
            SaveProductResult.Invalid(setOf(ProductFormIssue.NAME_EMPTY)),
            fixture.edit(1L, formOf(1L).copy(name = "")),
        )
        assertEquals(
            SaveProductResult.MissingReference(CatalogReference.CATEGORY),
            fixture.edit(1L, formOf(1L).copy(categoryId = 999L)),
        )
        assertEquals(1L, fixture.tables.changeSeq)
    }

    @Test
    fun `an unknown time zone rejects a price change`() = runTest {
        val lost = CatalogFixture(zoneId = "Nowhere/Unknown")
        lost.seedProduct(id = 1L, name = "Arroz", salePrice = Money(100L))

        val form = ProductForm(name = "Arroz", baseUnitId = UNIT_ID, salePrice = Money(200L))

        assertEquals(SaveProductResult.UnknownTimeZone, lost.edit(1L, form))
        // Renombrar no necesita fecha de negocio.
        assertEquals(
            SaveProductResult.Saved(1L),
            lost.edit(1L, form.copy(name = "Arroz blanco", salePrice = Money(100L))),
        )
    }

    @Test
    fun `adding a price without a confirmed currency is rejected`() = runTest {
        val unconfirmed = CatalogFixture(currencyConfirmed = false)
        unconfirmed.seedProduct(id = 1L, name = "Bolsa")
        val form = ProductForm(name = "Bolsa", baseUnitId = UNIT_ID)

        assertEquals(
            SaveProductResult.CurrencyUnconfirmed,
            unconfirmed.edit(1L, form.copy(salePrice = Money(100L))),
        )
        assertEquals(
            SaveProductResult.Saved(1L),
            unconfirmed.edit(1L, form.copy(name = "Bolsa grande")),
        )
    }

    @Test
    fun `a restore in progress rejects the edit`() = runTest {
        createSoap()
        fixture.transactions.restoreInProgress = true

        assertEquals(
            SaveProductResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            fixture.edit(1L, formOf(1L).copy(name = "Otro")),
        )
    }

    @Test
    fun `a failure at each write of a full edit leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = {
                seedProduct(
                    id = 1L,
                    name = "Arroz",
                    salePrice = Money(100L),
                    initialCost = Money(50L),
                    onHand = QuantityMilli.ZERO,
                )
            },
        ) {
            edit(
                1L,
                ProductForm(
                    name = "Arroz blanco",
                    baseUnitId = UNIT_ID,
                    salePrice = Money(200L),
                    cost = Money(60L),
                    minStock = QuantityMilli(2_000L),
                ),
            )
        }
    }
}
