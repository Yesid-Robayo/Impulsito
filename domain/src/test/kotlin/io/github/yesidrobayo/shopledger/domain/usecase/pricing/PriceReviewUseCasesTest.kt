package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPricePreviewer
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.DEFAULT_PRICE_LIST_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.EARLIER
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La cola `Costo nuevo sin revisar`: muestra lo marcado y vigente, el más antiguo primero; descartar
 * lo saca sin tocar el precio, y cambiar el precio por cualquier camino también lo saca. Productos y
 * montos inventados.
 */
class PriceReviewUseCasesTest {
    private val fixture = CatalogFixture()
    private val queue = ObservePriceReviewQueueUseCase(fixture.products)
    private val dismiss =
        DismissPriceReviewUseCase(fixture.transactions, fixture.products, fixture.clock)

    init {
        seedFlagged(fixture, 1L, "Jabón", EARLIER + 2.hours)
        seedFlagged(fixture, 2L, "Arroz", EARLIER)
        seedFlagged(fixture, 3L, "Té", EARLIER, archived = true)
        fixture.seedProduct(4L, "Sal", salePrice = Money(900L))
    }

    @Test
    fun theQueueHasTheFlaggedAndActiveOnesTheOldestFirst() = runTest {
        assertEquals(listOf(2L, 1L), queue().first().map { it.id })
    }

    @Test
    fun dismissingLeavesThePriceAndLeavesTheQueue() = runTest {
        val before = fixture.product(1L)

        assertEquals(DismissPriceReviewResult.Dismissed, dismiss(1L))

        val after = fixture.product(1L)
        assertNull(after.priceReviewSince)
        assertEquals(before.salePrice, after.salePrice)
        assertEquals(NOW, after.updatedAt)
        assertTrue(fixture.tables.priceChanges.isEmpty())
        assertEquals(listOf(2L), queue().first().map { it.id })
    }

    @Test
    fun whatIsNotInTheQueueWritesNothing() = runTest {
        val before = fixture.tables

        assertEquals(DismissPriceReviewResult.NotPending, dismiss(4L))
        assertEquals(DismissPriceReviewResult.NotPending, dismiss(99L))

        assertEquals(before, fixture.tables)
    }

    @Test
    fun aFailedDismissLeavesNothing() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = { seedFlagged(this, 1L, "Jabón", EARLIER) },
            run = { DismissPriceReviewUseCase(transactions, products, clock)(1L) },
        )
    }

    @Test
    fun changingThePriceTakesItOutOfTheQueue() = runTest {
        val result = changePrice(fixture, 1L, Money(4_000L))

        assertTrue(result is ChangePriceResult.Changed)
        assertNull(fixture.product(1L).priceReviewSince)
        CatalogFixture.assertEachWriteFailureLeavesNothing(
            prepare = { seedFlagged(this, 1L, "Jabón", EARLIER) },
            run = { changePrice(this, 1L, Money(4_000L)) },
        )
    }

    @Test
    fun editingThePriceTakesItOutAndEditingSomethingElseDoesNot() = runTest {
        fixture.edit(1L, formOf(1L).copy(name = "Jabón de manos"))
        assertEquals(EARLIER + 2.hours, fixture.product(1L).priceReviewSince)

        fixture.edit(1L, formOf(1L).copy(salePrice = Money(4_000L)))

        assertNull(fixture.product(1L).priceReviewSince)
    }

    @Test
    fun theInitialListPriceTakesItOut() = runTest {
        val lists = PriceListFixture()
        seedFlagged(lists.catalog, 1L, "Jabón", EARLIER)

        lists.setPrice(DEFAULT_PRICE_LIST_ID, 1L, Money(4_000L))

        assertNull(lists.catalog.product(1L).priceReviewSince)
    }

    @Test
    fun theBulkUpdateOfTheSalePriceTakesThemOut() = runTest {
        val bulk = ApplyBulkPriceUseCase(
            fixture.transactions,
            fixture.products,
            fixture.systemRows,
            fixture.priceHistory,
            BusinessCalendarSource(fixture.settings),
            CurrencyLock(fixture.settings),
            fixture.clock,
        ) { BATCH }
        val request =
            BulkPriceRequest(BulkPriceScope.PendingReview, BasisPoints(1_000L), Money.ZERO)

        // La pantalla manda los de su vista previa: los dos de la cola.
        val result = bulk(setOf(1L, 2L), request)

        assertEquals(ApplyBulkPriceResult.Applied(BATCH, changed = 2), result)
        assertTrue(queue().first().isEmpty())
    }

    @Test
    fun theBulkPreviewOfThePendingOnesLeavesTheRestOut() {
        val products = fixture.tables.products.values.filterNot { it.isArchived }
        val request =
            BulkPriceRequest(BulkPriceScope.PendingReview, BasisPoints(1_000L), Money.ZERO)

        val preview = BulkPricePreviewer.preview(products, request)

        assertEquals(listOf(1L, 2L), preview.changes.map { it.productId }.sorted())
    }

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

    private companion object {
        val BATCH: Uuid = Uuid.parse("00000000-0000-4000-8000-000000001211")

        fun seedFlagged(
            fixture: CatalogFixture,
            id: Long,
            name: String,
            since: Instant,
            archived: Boolean = false,
        ) {
            fixture.seedProduct(id, name, archived = archived, salePrice = Money(3_500L))
            fixture.database.seed { tables ->
                val product = tables.products.getValue(id)
                tables.copy(
                    products =
                        tables.products + (id to product.copy(priceReviewSince = since)),
                )
            }
        }

        suspend fun changePrice(fixture: CatalogFixture, id: Long, price: Money) = with(fixture) {
            ChangePriceUseCase(
                transactions,
                products,
                systemRows,
                priceHistory,
                BusinessCalendarSource(settings),
                CurrencyLock(settings),
                clock,
            )(id, price)
        }
    }
}
