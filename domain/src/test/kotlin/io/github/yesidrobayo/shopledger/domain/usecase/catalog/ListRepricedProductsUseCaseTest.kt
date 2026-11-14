package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Solo lo que cambió de precio`: los productos con una fila de precio de la lista inicial desde
 * la fecha elegida, incluida. Un cambio en otra lista no cuenta, porque no cambia la etiqueta.
 */
class ListRepricedProductsUseCaseTest {
    private val fixture = CatalogFixture()
    private val list =
        ListRepricedProductsUseCase(fixture.transactions, fixture.systemRows, fixture.priceHistory)

    @Test
    fun `products repriced on or after the date come out once each`() = runTest {
        seed(id = 1L, product = 10L, date = "2026-11-01", source = PriceChangeSource.INITIAL)
        seed(id = 2L, product = 11L, date = "2026-11-09")
        seed(id = 3L, product = 12L, date = "2026-11-10", source = PriceChangeSource.INITIAL)
        seed(id = 4L, product = 11L, date = "2026-11-12")
        // Otra lista, el mismo día: no cambia lo que dice la etiqueta.
        seed(id = 5L, product = 13L, date = "2026-11-12", listId = OTHER_LIST)

        val result = list(date("2026-11-10"))

        assertEquals(RepricedProductsResult.Found(setOf(11L, 12L)), result)
    }

    @Test
    fun `without changes since the date the set is empty`() = runTest {
        seed(id = 1L, product = 10L, date = "2026-11-01", source = PriceChangeSource.INITIAL)

        assertEquals(RepricedProductsResult.Found(emptySet()), list(date("2026-11-02")))
    }

    @Test
    fun `a storage failure is reported, not thrown`() = runTest {
        val failing = object : PriceHistoryRepository by fixture.priceHistory {
            override suspend fun findProductsRepricedSince(
                priceListId: Long,
                from: BusinessDate,
            ): Set<Long> = throw StorageFailureException(StorageFailure.RESTORE_IN_PROGRESS)
        }
        val broken = ListRepricedProductsUseCase(fixture.transactions, fixture.systemRows, failing)

        assertEquals(
            RepricedProductsResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            broken(date("2026-11-01")),
        )
    }

    private fun seed(
        id: Long,
        product: Long,
        date: String,
        source: PriceChangeSource = PriceChangeSource.MANUAL,
        listId: Long = CatalogFixture.DEFAULT_PRICE_LIST_ID,
    ) {
        val change = NewPriceChange(
            productId = product,
            priceListId = listId,
            oldPrice = if (source == PriceChangeSource.INITIAL) null else Money(1_000L),
            newPrice = Money(1_200L),
            source = source,
            batchId = null,
            bulkRule = null,
            occurredAt = CatalogFixture.NOW,
            businessDate = date(date),
        )
        fixture.database.seed {
            it.copy(priceChanges = it.priceChanges + (id to change.recordedAs(id)))
        }
    }

    private fun date(text: String): BusinessDate = requireNotNull(BusinessDate.parse(text))

    private companion object {
        const val OTHER_LIST = 32L
    }
}
