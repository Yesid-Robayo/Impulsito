package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogReference
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.model.stock.StockKey
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ACTIVE_SUPPLIER_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ARCHIVED_SUPPLIER_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ARCHIVED_UNIT_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.CATEGORY_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.DEFAULT_PRICE_LIST_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.KILOGRAM_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.MAIN_LOCATION_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.UNIT_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateProductUseCaseTest {
    private val fixture = CatalogFixture()

    private val soap = ProductForm(
        name = "Jabón Líquido",
        salePrice = Money(3_500L),
        cost = Money(1_200L),
    )

    @Test
    fun `a product with 24 units at 1200 opens its ledger in one transaction`() = runTest {
        val result = fixture.create(soap, initialStock = QuantityMilli(24_000L))

        assertEquals(SaveProductResult.Saved(productId = 1L), result)
        val product = fixture.product(1L)
        assertEquals(QuantityMilli(24_000L), product.onHand)
        assertEquals(Money(1_200L), product.averageCost)
        assertEquals(Money(28_800L), product.stockValue)
        assertEquals(Money(1_200L), product.initialCost)
        assertEquals(UNIT_ID, product.details.baseUnitId)
        assertEquals("jabon liquido", product.details.normalizedName)
        val opening = fixture.movementsOf(1L).single()
        assertEquals(StockKey(1L, MAIN_LOCATION_ID, lotId = null), opening.movement.key)
        assertEquals(StockMovementType.OPENING_BALANCE, opening.movement.type)
        assertEquals(QuantityMilli(24_000L), opening.movement.quantity)
        assertEquals(Money(1_200L), opening.unitCost)
        assertEquals(Money(28_800L), opening.totalCost)
        assertEquals(StockMovementSource.NONE, opening.source)
        assertEquals(BusinessDate.parse("2023-11-14"), opening.businessDate)
        val price = fixture.tables.priceChanges.values.single().content
        assertEquals(PriceChangeSource.INITIAL, price.source)
        assertNull(price.oldPrice)
        assertEquals(Money(3_500L), price.newPrice)
        assertEquals(DEFAULT_PRICE_LIST_ID, price.priceListId)
        // Por encima del mínimo de cero: no nace bajo.
        assertTrue(fixture.tables.alerts.isEmpty())
        assertEquals(1, fixture.transactions.commits)
        assertEquals(1L, fixture.tables.changeSeq)
    }

    @Test
    fun `the first product with money locks the currency, and an unconfirmed one rejects it`() =
        runTest {
            fixture.create(soap)
            assertEquals(NOW, fixture.settings.settings.value.regional.currency.lockedAt)

            val unconfirmed = CatalogFixture(currencyConfirmed = false)
            assertEquals(SaveProductResult.CurrencyUnconfirmed, unconfirmed.create(soap))
            assertTrue(unconfirmed.tables.products.isEmpty())
            // Sin dinero no hace falta moneda: el producto se crea y nada queda bloqueado.
            assertEquals(
                SaveProductResult.Saved(1L),
                unconfirmed.create(ProductForm(name = "Bolsa")),
            )
            assertTrue(unconfirmed.settings.lockCalls.isEmpty())
        }

    @Test
    fun `the opening balance is neither a purchase nor an expense`() = runTest {
        fixture.create(soap, initialStock = QuantityMilli(24_000L))

        assertTrue(fixture.tables.purchases.isEmpty())
        assertTrue(fixture.tables.purchaseLines.isEmpty())
    }

    @Test
    fun `a name alone creates a counted product with nothing else and a silent alert`() = runTest {
        val result = fixture.create(ProductForm(name = "Arroz"))

        assertEquals(SaveProductResult.Saved(1L), result)
        val product = fixture.product(1L)
        assertEquals(UNIT_ID, product.details.baseUnitId)
        assertEquals(ProductStockMode.TRACKED, product.details.stockMode)
        assertEquals(Money.ZERO, product.salePrice)
        assertEquals(Money.ZERO, product.initialCost)
        assertEquals(QuantityMilli.ZERO, product.onHand)
        assertEquals(QuantityMilli.ZERO, product.details.minStock)
        assertTrue(fixture.movementsOf(1L).isEmpty())
        assertEquals(Money.ZERO, fixture.tables.priceChanges.values.single().content.newPrice)
        // Sin stock y con mínimo cero ya está agotado: su alerta nace callada.
        val alert = fixture.activeAlertsOf(1L).single()
        assertEquals(LowStockNotificationStatus.SUPPRESSED, alert.notificationStatus)
        assertEquals(NOW, alert.occurredAt)
    }

    @Test
    fun `a product born under its minimum gets a silent alert with the numbers of the moment`() =
        runTest {
            fixture.create(
                soap.copy(minStock = QuantityMilli(5_000L)),
                initialStock = QuantityMilli(3_000L),
            )

            val alert = fixture.activeAlertsOf(1L).single()
            assertEquals(QuantityMilli(3_000L), alert.onHandAtTrigger)
            assertEquals(QuantityMilli(5_000L), alert.minStockAtTrigger)
            assertEquals(LowStockNotificationStatus.SUPPRESSED, alert.notificationStatus)
        }

    @Test
    fun `a repeated name is rejected naming the existing product and nothing is saved`() = runTest {
        fixture.create(soap)
        val before = fixture.tables

        val result = fixture.create(ProductForm(name = "jabon liquido"))

        assertEquals(SaveProductResult.DuplicateName("Jabón Líquido"), result)
        assertEquals(before, fixture.tables)
        assertEquals(1, fixture.transactions.rollbacks)
    }

    @Test
    fun `an archived product does not reserve its name`() = runTest {
        fixture.seedProduct(id = 7L, name = "Jabón Líquido", archived = true)

        assertEquals(SaveProductResult.Saved(8L), fixture.create(soap))
    }

    @Test
    fun `a repeated barcode is rejected naming the product that has it`() = runTest {
        fixture.seedProduct(id = 7L, name = "Detergente", barcode = "7701234")

        val result = fixture.create(soap.copy(barcode = " 7701234 "))

        assertEquals(SaveProductResult.DuplicateBarcode("Detergente"), result)
    }

    @Test
    fun `every form problem comes back at once and nothing is written`() = runTest {
        val result = fixture.create(
            ProductForm(name = " ", salePrice = Money(-1L)),
            QuantityMilli(1_500L),
        )

        assertEquals(
            SaveProductResult.Invalid(
                setOf(
                    ProductFormIssue.NAME_EMPTY,
                    ProductFormIssue.SALE_PRICE_OUT_OF_RANGE,
                    ProductFormIssue.INITIAL_STOCK_FRACTION_NOT_ALLOWED,
                ),
            ),
            result,
        )
        assertTrue(fixture.tables.products.isEmpty())
        assertEquals(0L, fixture.tables.changeSeq)
    }

    @Test
    fun `a weighed product takes a fractional opening balance`() = runTest {
        val cheese = ProductForm(name = "Queso", baseUnitId = KILOGRAM_ID, cost = Money(30_000L))

        fixture.create(cheese, initialStock = QuantityMilli(2_500L))

        val product = fixture.product(1L)
        assertEquals(KILOGRAM_ID, product.details.baseUnitId)
        assertEquals(QuantityMilli(2_500L), product.onHand)
        assertEquals(Money(75_000L), product.stockValue)
    }

    @Test
    fun `a category, supplier or unit that can no longer be chosen is named`() = runTest {
        assertEquals(
            SaveProductResult.MissingReference(CatalogReference.CATEGORY),
            fixture.create(soap.copy(categoryId = 999L)),
        )
        assertEquals(
            SaveProductResult.MissingReference(CatalogReference.SUPPLIER),
            fixture.create(soap.copy(defaultSupplierId = ARCHIVED_SUPPLIER_ID)),
        )
        assertEquals(
            SaveProductResult.MissingReference(CatalogReference.SUPPLIER),
            fixture.create(soap.copy(defaultSupplierId = 999L)),
        )
        assertEquals(
            SaveProductResult.MissingReference(CatalogReference.UNIT),
            fixture.create(soap.copy(baseUnitId = ARCHIVED_UNIT_ID)),
        )
        assertEquals(
            SaveProductResult.MissingReference(CatalogReference.UNIT),
            fixture.create(soap.copy(baseUnitId = 999L)),
        )
        assertTrue(fixture.tables.products.isEmpty())
    }

    @Test
    fun `category and habitual supplier are kept`() = runTest {
        fixture.create(soap.copy(categoryId = CATEGORY_ID, defaultSupplierId = ACTIVE_SUPPLIER_ID))

        val details = fixture.product(1L).details
        assertEquals(CATEGORY_ID, details.categoryId)
        assertEquals(ACTIVE_SUPPLIER_ID, details.defaultSupplierId)
    }

    @Test
    fun `a service keeps its cost as a fixed cost and is not watched`() = runTest {
        fixture.create(
            ProductForm(name = "Corte", stockMode = ProductStockMode.UNTRACKED, cost = Money(800L)),
        )

        val product = fixture.product(1L)
        assertEquals(Money(800L), product.details.fixedUnitCost)
        assertEquals(Money.ZERO, product.initialCost)
        assertTrue(fixture.tables.alerts.isEmpty())
    }

    @Test
    fun `an opening balance over the document limit is rejected`() = runTest {
        val result = fixture.create(
            soap.copy(cost = Money(1_000_000_000_000L)),
            initialStock = QuantityMilli(1_000_000_000L),
        )

        assertEquals(SaveProductResult.AboveLimit(Limit.DOCUMENT_TOTAL), result)
        assertTrue(fixture.tables.products.isEmpty())
    }

    @Test
    fun `an unknown business time zone rejects before writing`() = runTest {
        val lost = CatalogFixture(zoneId = "Nowhere/Unknown")

        assertEquals(SaveProductResult.UnknownTimeZone, lost.create(soap))
        assertTrue(lost.tables.products.isEmpty())
    }

    @Test
    fun `a restore in progress rejects before writing`() = runTest {
        fixture.transactions.restoreInProgress = true

        assertEquals(
            SaveProductResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            fixture.create(soap),
        )
        assertTrue(fixture.tables.products.isEmpty())
    }

    @Test
    fun `a full disk midway leaves nothing and says so`() = runTest {
        fixture.database.failAtWrite = 3
        fixture.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

        val result = fixture.create(soap, initialStock = QuantityMilli(24_000L))

        assertEquals(SaveProductResult.StorageFailed(StorageFailure.DISK_FULL), result)
        assertTrue(fixture.tables.products.isEmpty())
        assertEquals(0L, fixture.tables.changeSeq)
    }

    @Test
    fun `a failure at each write of a product with stock and alert leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(prepare = {}) {
            create(soap.copy(minStock = QuantityMilli(5_000L)), QuantityMilli(3_000L))
        }
    }
}
