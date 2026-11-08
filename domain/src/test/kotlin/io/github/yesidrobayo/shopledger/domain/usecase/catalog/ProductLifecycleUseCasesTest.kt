package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockAlert
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Archivar, reactivar y eliminar un producto. */
class ProductLifecycleUseCasesTest {
    private val fixture = CatalogFixture()

    @Test
    fun `archiving keeps the stock and resolves the active alert`() = runTest {
        fixture.create(
            ProductForm(name = "Arroz", minStock = QuantityMilli(5_000L)),
            QuantityMilli(3_000L),
        )
        val alert = fixture.activeAlertsOf(1L).single()

        assertEquals(ArchiveProductResult.Archived, fixture.archive(1L))

        val product = fixture.product(1L)
        assertEquals(NOW, product.archivedAt)
        assertEquals(QuantityMilli(3_000L), product.onHand)
        assertEquals(NOW, fixture.tables.alerts.getValue(alert.id).resolvedAt)
        assertEquals(2L, fixture.tables.changeSeq)
    }

    @Test
    fun `archiving twice or a missing product writes nothing`() = runTest {
        fixture.seedProduct(id = 1L, name = "Arroz", archived = true)
        val before = fixture.tables

        assertEquals(ArchiveProductResult.AlreadyArchived, fixture.archive(1L))
        assertEquals(ArchiveProductResult.NotFound, fixture.archive(9L))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `reactivating a low product creates its alert silently`() = runTest {
        fixture.seedProduct(
            id = 1L,
            name = "Arroz",
            archived = true,
            onHand = QuantityMilli(3_000L),
            minStock = QuantityMilli(5_000L),
        )

        assertEquals(ReactivateProductResult.Reactivated, fixture.reactivate(1L))

        assertNull(fixture.product(1L).archivedAt)
        val alert = fixture.activeAlertsOf(1L).single()
        assertEquals(LowStockNotificationStatus.SUPPRESSED, alert.notificationStatus)
    }

    @Test
    fun `reactivating a product over its minimum creates no alert`() = runTest {
        fixture.seedProduct(
            id = 1L,
            name = "Arroz",
            archived = true,
            onHand = QuantityMilli(9_000L),
            minStock = QuantityMilli(5_000L),
        )

        assertEquals(ReactivateProductResult.Reactivated, fixture.reactivate(1L))
        assertTrue(fixture.tables.alerts.isEmpty())
    }

    @Test
    fun `a name or a barcode taken by an active product blocks the reactivation`() = runTest {
        fixture.seedProduct(id = 1L, name = "Jabón Líquido", archived = true, barcode = "123")
        fixture.seedProduct(id = 2L, name = "jabon liquido")
        val before = fixture.tables

        assertEquals(ReactivateProductResult.NameTaken("jabon liquido"), fixture.reactivate(1L))
        assertEquals(before, fixture.tables)

        fixture.seedProduct(id = 2L, name = "Detergente", barcode = "123")
        assertEquals(ReactivateProductResult.BarcodeTaken("Detergente"), fixture.reactivate(1L))
    }

    @Test
    fun `reactivating an active or missing product writes nothing`() = runTest {
        fixture.seedProduct(id = 1L, name = "Arroz")

        assertEquals(ReactivateProductResult.NotArchived, fixture.reactivate(1L))
        assertEquals(ReactivateProductResult.NotFound, fixture.reactivate(9L))
        assertEquals(0L, fixture.tables.changeSeq)
    }

    @Test
    fun `an unknown time zone stops a reactivation that needs an alert`() = runTest {
        val lost = CatalogFixture(zoneId = "Nowhere/Unknown")
        lost.seedProduct(id = 1L, name = "Arroz", archived = true)

        assertEquals(ReactivateProductResult.UnknownTimeZone, lost.reactivate(1L))
        assertTrue(lost.product(1L).archivedAt != null)
    }

    @Test
    fun `a product without history is deleted with its price rows and alerts`() = runTest {
        fixture.create(ProductForm(name = "Arroz"))
        assertEquals(1, fixture.tables.alerts.size)

        assertEquals(DeleteProductResult.Deleted, fixture.delete(1L))

        assertTrue(fixture.tables.products.isEmpty())
        assertTrue(fixture.tables.priceChanges.isEmpty())
        assertTrue(fixture.tables.alerts.isEmpty())
    }

    @Test
    fun `a product with an opening balance has history and is not deleted`() = runTest {
        fixture.create(ProductForm(name = "Arroz"), QuantityMilli(2_000L))
        val before = fixture.tables

        assertEquals(DeleteProductResult.HasHistory, fixture.delete(1L))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `a product with history in other documents is not deleted`() = runTest {
        fixture.seedProduct(id = 1L, name = "Arroz")
        fixture.database.seed { it.copy(productsWithOtherHistory = setOf(1L)) }

        assertEquals(DeleteProductResult.HasHistory, fixture.delete(1L))
    }

    @Test
    fun `a component, extra or variant parent says why before trying`() = runTest {
        fixture.seedProduct(id = 1L, name = "Pan")
        val links = setOf(CatalogLink.COMPONENT, CatalogLink.EXTRA, CatalogLink.VARIANT_PARENT)
        fixture.database.seed { it.copy(catalogLinks = mapOf(1L to links)) }

        assertEquals(DeleteProductResult.LinkedInCatalog(links), fixture.delete(1L))
        assertEquals(1, fixture.tables.products.size)
    }

    @Test
    fun `deleting a missing product writes nothing`() = runTest {
        assertEquals(DeleteProductResult.NotFound, fixture.delete(9L))
    }

    @Test
    fun `a restore in progress rejects archive, reactivate and delete`() = runTest {
        fixture.seedProduct(id = 1L, name = "Arroz")
        fixture.transactions.restoreInProgress = true
        val failed = StorageFailure.RESTORE_IN_PROGRESS

        assertEquals(ArchiveProductResult.StorageFailed(failed), fixture.archive(1L))
        assertEquals(ReactivateProductResult.StorageFailed(failed), fixture.reactivate(1L))
        assertEquals(DeleteProductResult.StorageFailed(failed), fixture.delete(1L))
    }

    @Test
    fun `a failure at each write of archive, reactivate and delete leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = {
                seedProduct(
                    id = 1L,
                    name = "Arroz",
                    onHand = QuantityMilli(1_000L),
                    minStock = QuantityMilli(2_000L),
                )
                database.seed { tables ->
                    tables.copy(
                        alerts = mapOf(
                            1L to LowStockAlert(
                                id = 1L,
                                productId = 1L,
                                onHandAtTrigger = QuantityMilli(1_000L),
                                minStockAtTrigger = QuantityMilli(2_000L),
                                notificationStatus = LowStockNotificationStatus.SHOWN,
                                occurredAt = CatalogFixture.EARLIER,
                                businessDate = requireNotNull(
                                    BusinessDate.parse("2023-11-13"),
                                ),
                                resolvedAt = null,
                            ),
                        ),
                    )
                }
            },
        ) { archive(1L) }
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = { seedProduct(id = 1L, name = "Arroz", archived = true) },
        ) { reactivate(1L) }
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = { seedProduct(id = 1L, name = "Arroz", salePrice = Money(10L)) },
        ) { delete(1L) }
    }
}
