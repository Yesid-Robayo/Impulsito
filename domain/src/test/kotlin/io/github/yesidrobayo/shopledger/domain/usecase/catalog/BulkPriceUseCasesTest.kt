package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRule
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkExclusion
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPricePreviewer
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.ACTIVE_SUPPLIER_ID
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La vista previa y el aplicar de una actualización masiva por porcentaje. */
class BulkPriceUseCasesTest {
    private val fixture = CatalogFixture().apply {
        seedProduct(1L, "Arroz", salePrice = Money(12_350L), initialCost = Money(8_000L))
        seedProduct(2L, "Café", salePrice = Money(10_000L), initialCost = Money(9_500L))
        seedProduct(3L, "Sin precio", salePrice = Money.ZERO)
        seedProduct(4L, "Viejo", archived = true, salePrice = Money(1_000L))
        seedProduct(5L, "Pan", salePrice = Money(80L), defaultSupplierId = ACTIVE_SUPPLIER_ID)
    }

    private suspend fun catalog() = fixture.products.observe(ArchiveFilter.ACTIVE).first() +
        fixture.products.observe(ArchiveFilter.ARCHIVED).first()

    private fun request(
        percent: Long,
        step: Long = 0L,
        scope: BulkPriceScope = BulkPriceScope.All,
        removed: Set<Long> = emptySet(),
    ) = BulkPriceRequest(scope, BasisPoints(percent), Money(step), removed)

    @Test
    fun `the preview puts the flagged first and the excluded apart`() = runTest {
        // Con −10 %, el café queda bajo su costo de 9500, y el pan de 80 sube a 100 por el paso.
        val preview = BulkPricePreviewer.preview(catalog(), request(-1_000L, step = 100L))

        assertEquals(listOf(2L, 5L, 1L), preview.changes.map { it.productId })
        assertTrue(preview.changes.first().change.negativeMargin)
        assertEquals(
            mapOf(3L to BulkExclusion.NO_PRICE, 4L to BulkExclusion.ARCHIVED),
            preview.excluded.associate { it.productId to it.reason },
        )
        assertTrue(preview.changes[1].change.distorted)
        assertEquals(2, preview.flagged)
    }

    @Test
    fun `the scope and removed rows narrow the preview`() = runTest {
        val bySupplier =
            BulkPricePreviewer.preview(
                catalog(),
                request(500L, step = 500L, scope = BulkPriceScope.Supplier(ACTIVE_SUPPLIER_ID)),
            )
        // 80 con +5 % y paso 500 es 500: la variación real está lejos de la pedida.
        assertEquals(listOf(5L), bySupplier.changes.map { it.productId })
        assertTrue(bySupplier.changes.single().change.distorted)

        val picked =
            BulkPricePreviewer.preview(
                catalog(),
                request(800L, scope = BulkPriceScope.Picked(setOf(1L, 2L)), removed = setOf(2L)),
            )
        assertEquals(listOf(1L), picked.changes.map { it.productId })
        assertEquals(1, picked.removed)
        assertEquals(
            emptyList<Long>(),
            BulkPricePreviewer.preview(
                catalog(),
                request(800L, scope = BulkPriceScope.Category(99L)),
            )
                .changes.map { it.productId },
        )
    }

    @Test
    fun `applying writes every price and a BULK row with the same batch and rule`() = runTest {
        val batch = Uuid.parse("00000000-0000-4000-8000-000000000001")

        val result = fixture.apply(listOf(1L, 2L, 3L, 4L), 800L, 100L, batch)

        assertEquals(ApplyBulkPriceResult.Applied(batch, 2), result)
        assertEquals(Money(13_400L), fixture.product(1L).salePrice)
        assertEquals(Money(10_800L), fixture.product(2L).salePrice)
        assertEquals(Money.ZERO, fixture.product(3L).salePrice)
        assertEquals(Money(1_000L), fixture.product(4L).salePrice)
        val rows = fixture.tables.priceChanges.values.map { it.content }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.source == PriceChangeSource.BULK && it.batchId == batch })
        val rule = BulkPriceRule(BulkPriceAdjustment.Percent(BasisPoints(800L)), Money(100L))
        assertTrue(rows.all { it.bulkRule == rule })
        assertEquals(Money(12_350L), rows.single { it.productId == 1L }.oldPrice)
        assertEquals(1L, fixture.tables.changeSeq)
    }

    @Test
    fun `nothing to change, an invalid request and a restore write nothing`() = runTest {
        val before = fixture.tables

        assertEquals(ApplyBulkPriceResult.NothingToChange, fixture.apply(listOf(3L, 4L), 800L))
        assertEquals(ApplyBulkPriceResult.InvalidRequest, fixture.apply(listOf(1L), 0L))
        assertEquals(ApplyBulkPriceResult.InvalidRequest, fixture.apply(listOf(1L), 800L, -1L))
        assertEquals(before, fixture.tables)

        fixture.transactions.restoreInProgress = true
        assertEquals(
            ApplyBulkPriceResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            fixture.apply(listOf(1L), 800L),
        )
    }

    @Test
    fun `an unknown zone and an unconfirmed currency are results`() = runTest {
        val noZone = CatalogFixture(zoneId = "Nowhere/Unknown")
        noZone.seedProduct(1L, "Arroz", salePrice = Money(1_000L))
        assertEquals(ApplyBulkPriceResult.UnknownTimeZone, noZone.apply(listOf(1L), 800L))

        val noCurrency = CatalogFixture(currencyConfirmed = false)
        noCurrency.seedProduct(1L, "Arroz", salePrice = Money(1_000L))
        assertEquals(ApplyBulkPriceResult.CurrencyUnconfirmed, noCurrency.apply(listOf(1L), 800L))
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing({
            seedProduct(1L, "Arroz", salePrice = Money(1_000L), onHand = QuantityMilli.ZERO)
            seedProduct(2L, "Café", salePrice = Money(2_000L))
        }) { apply(listOf(1L, 2L), 800L) }
    }

    private suspend fun CatalogFixture.apply(
        ids: List<Long>,
        percent: Long,
        step: Long = 0L,
        batch: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000009"),
    ): ApplyBulkPriceResult = ApplyBulkPriceUseCase(
        transactions,
        products,
        systemRows,
        priceHistory,
        BusinessCalendarSource(settings),
        CurrencyLock(settings),
        clock,
    ) { batch }(ids, BasisPoints(percent), Money(step))
}
