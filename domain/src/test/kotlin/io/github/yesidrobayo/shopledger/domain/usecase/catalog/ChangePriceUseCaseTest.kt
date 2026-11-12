package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.DEFAULT_PRICE_LIST_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cambiar el precio de un producto, con su fila de historial y sin tocar nada más. */
class ChangePriceUseCaseTest {
    @Test
    fun `3500 to 3800 leaves the price and a MANUAL row from 3500 to 3800`() = runTest {
        val fixture = CatalogFixture()
        fixture.seedProduct(1L, "Arroz", salePrice = Money(3_500L))

        val result = fixture.change(1L, Money(3_800L))

        val id = (result as ChangePriceResult.Changed).historyId
        val row = fixture.tables.priceChanges.getValue(id).content
        assertEquals(Money(3_500L), row.oldPrice)
        assertEquals(Money(3_800L), row.newPrice)
        assertEquals(PriceChangeSource.MANUAL, row.source)
        assertEquals(DEFAULT_PRICE_LIST_ID, row.priceListId)
        assertNull(row.batchId)
        assertEquals(NOW, row.occurredAt)
        assertEquals(Money(3_800L), fixture.product(1L).salePrice)
        assertEquals(1L, fixture.tables.changeSeq)
    }

    @Test
    fun `the same price writes nothing`() = runTest {
        val fixture = CatalogFixture()
        fixture.seedProduct(1L, "Arroz", salePrice = Money(3_500L))
        val before = fixture.tables

        assertEquals(ChangePriceResult.Unchanged, fixture.change(1L, Money(3_500L)))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `each reason to refuse writes nothing`() = runTest {
        val fixture = CatalogFixture()
        fixture.seedProduct(1L, "Arroz", salePrice = Money(3_500L))
        fixture.seedProduct(2L, "Viejo", archived = true, salePrice = Money(1_000L))
        val before = fixture.tables

        assertEquals(ChangePriceResult.PriceOutOfRange, fixture.change(1L, Money(-1L)))
        assertEquals(
            ChangePriceResult.PriceOutOfRange,
            fixture.change(1L, Money(1_000_000_000_001L)),
        )
        assertEquals(ChangePriceResult.NotFound, fixture.change(99L, Money(10L)))
        assertEquals(ChangePriceResult.ProductArchived, fixture.change(2L, Money(10L)))
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `zero and the price limit are prices`() = runTest {
        val fixture = CatalogFixture()
        fixture.seedProduct(1L, "Arroz", salePrice = Money(3_500L))

        assertEquals(ChangePriceResult.Changed(1L), fixture.change(1L, Money.ZERO))
        assertEquals(
            ChangePriceResult.Changed(2L),
            fixture.change(1L, Money(1_000_000_000_000L)),
        )
    }

    @Test
    fun `an unknown zone, an unconfirmed currency and a restore are results`() = runTest {
        val noZone = CatalogFixture(zoneId = "Nowhere/Unknown")
        noZone.seedProduct(1L, "Arroz")
        assertEquals(ChangePriceResult.UnknownTimeZone, noZone.change(1L, Money(10L)))

        val noCurrency = CatalogFixture(currencyConfirmed = false)
        noCurrency.seedProduct(1L, "Arroz")
        assertEquals(ChangePriceResult.CurrencyUnconfirmed, noCurrency.change(1L, Money(10L)))

        val restoring = CatalogFixture()
        restoring.seedProduct(1L, "Arroz")
        restoring.transactions.restoreInProgress = true
        assertEquals(
            ChangePriceResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            restoring.change(1L, Money(10L)),
        )
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing({
            seedProduct(1L, "Arroz", salePrice = Money(3_500L))
        }) { change(1L, Money(3_800L)) }
    }

    private suspend fun CatalogFixture.change(productId: Long, price: Money): ChangePriceResult =
        ChangePriceUseCase(
            transactions,
            products,
            systemRows,
            priceHistory,
            BusinessCalendarSource(settings),
            CurrencyLock(settings),
            clock,
        )(productId, price)
}
