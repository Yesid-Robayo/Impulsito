package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceOrigin
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ListPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.DEFAULT_PRICE_LIST_ID
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.NOW
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cambiar el precio de un producto en una lista, con su fila de historial en esa lista. */
class SetListPriceUseCaseTest {
    @Test
    fun `a first own price in a fixed list goes to history from the sale price, with the list`() =
        runTest {
            val fixture = withLists()

            val result = fixture.setPrice(WHOLESALE, PRODUCT, Money(9_000L))

            val historyId = requireNotNull((result as SetListPriceResult.Changed).historyId)
            val row = fixture.tables.priceChanges.getValue(historyId).content
            assertEquals(WHOLESALE, row.priceListId)
            assertEquals(Money(10_000L), row.oldPrice)
            assertEquals(Money(9_000L), row.newPrice)
            assertEquals(PriceChangeSource.MANUAL, row.source)
            assertNull(row.batchId)
            assertEquals(NOW, row.occurredAt)
            assertEquals(Money(9_000L), fixture.tables.productPrices[WHOLESALE to PRODUCT])
            // El precio de venta no cambia, y la venta pasada tampoco: solo hay precio nuevo.
            assertEquals(Money(10_000L), fixture.catalog.product(PRODUCT).salePrice)
            assertEquals(1L, fixture.tables.changeSeq)
        }

    @Test
    fun `changing and removing an own price both leave history`() = runTest {
        val fixture = withLists()
        fixture.seedOwnPrice(WHOLESALE, PRODUCT, Money(9_000L))

        val changed = fixture.setPrice(WHOLESALE, PRODUCT, Money(8_800L))
        val removed = fixture.setPrice(WHOLESALE, PRODUCT, null)

        val changeRow = fixture.tables.priceChanges.getValue(
            requireNotNull((changed as SetListPriceResult.Changed).historyId),
        ).content
        assertEquals(Money(9_000L) to Money(8_800L), changeRow.oldPrice to changeRow.newPrice)
        val removeRow = fixture.tables.priceChanges.getValue(
            requireNotNull((removed as SetListPriceResult.Changed).historyId),
        ).content
        // Sin precio propio, la lista vuelve a dar el precio de venta.
        assertEquals(Money(8_800L) to Money(10_000L), removeRow.oldPrice to removeRow.newPrice)
        assertEquals(WHOLESALE, removeRow.priceListId)
        assertNull(fixture.tables.productPrices[WHOLESALE to PRODUCT])
    }

    @Test
    fun `removing an own price equal to the sale price changes nothing that is seen`() = runTest {
        val fixture = withLists()
        fixture.seedOwnPrice(WHOLESALE, PRODUCT, Money(10_000L))

        assertEquals(
            SetListPriceResult.Changed(historyId = null),
            fixture.setPrice(WHOLESALE, PRODUCT, null),
        )
        assertNull(fixture.tables.productPrices[WHOLESALE to PRODUCT])
        assertEquals(emptyMap<Long, Any>(), fixture.tables.priceChanges)
    }

    @Test
    fun `in the initial list the price is the sale price`() = runTest {
        val fixture = withLists()

        val result = fixture.setPrice(DEFAULT_PRICE_LIST_ID, PRODUCT, Money(10_500L))

        val row = fixture.tables.priceChanges.getValue(
            requireNotNull((result as SetListPriceResult.Changed).historyId),
        ).content
        assertEquals(DEFAULT_PRICE_LIST_ID, row.priceListId)
        assertEquals(Money(10_500L), fixture.catalog.product(PRODUCT).salePrice)
        assertEquals(emptyMap<Any, Money>(), fixture.tables.productPrices)
    }

    @Test
    fun `the price already seen writes nothing`() = runTest {
        val fixture = withLists()
        fixture.seedOwnPrice(WHOLESALE, PRODUCT, Money(9_000L))
        val before = fixture.tables

        assertEquals(
            SetListPriceResult.Unchanged,
            fixture.setPrice(WHOLESALE, PRODUCT, Money(9_000L)),
        )
        assertEquals(
            SetListPriceResult.Unchanged,
            fixture.setPrice(OTHER_FIXED, PRODUCT, Money(10_000L)),
        )
        assertEquals(SetListPriceResult.Unchanged, fixture.setPrice(OTHER_FIXED, PRODUCT, null))
        assertEquals(
            SetListPriceResult.Unchanged,
            fixture.setPrice(DEFAULT_PRICE_LIST_ID, PRODUCT, Money(10_000L)),
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `each reason to refuse writes nothing`() = runTest {
        val fixture = withLists()
        val before = fixture.tables

        assertEquals(
            SetListPriceResult.PriceOutOfRange,
            fixture.setPrice(WHOLESALE, PRODUCT, Money(-1L)),
        )
        assertEquals(
            SetListPriceResult.PriceOutOfRange,
            fixture.setPrice(WHOLESALE, PRODUCT, Money(1_000_000_000_001L)),
        )
        assertEquals(SetListPriceResult.ListNotFound, fixture.setPrice(99L, PRODUCT, Money(1L)))
        assertEquals(
            SetListPriceResult.ListArchived,
            fixture.setPrice(ARCHIVED, PRODUCT, Money(1L)),
        )
        assertEquals(
            SetListPriceResult.ListIsCalculated,
            fixture.setPrice(RELATIVE, PRODUCT, Money(1L)),
        )
        assertEquals(
            SetListPriceResult.DefaultPriceRequired,
            fixture.setPrice(DEFAULT_PRICE_LIST_ID, PRODUCT, null),
        )
        assertEquals(
            SetListPriceResult.ProductNotFound,
            fixture.setPrice(WHOLESALE, 99L, Money(1L)),
        )
        assertEquals(
            SetListPriceResult.ProductArchived,
            fixture.setPrice(WHOLESALE, ARCHIVED_PRODUCT, Money(1L)),
        )
        assertEquals(before, fixture.tables)
    }

    @Test
    fun `the price limit is a price everywhere, and zero only in the initial list`() = runTest {
        val fixture = withLists()

        assertEquals(
            SetListPriceResult.ZeroOwnPrice,
            fixture.setPrice(WHOLESALE, PRODUCT, Money.ZERO),
        )
        assertEquals(
            SetListPriceResult.Changed(1L),
            fixture.setPrice(WHOLESALE, PRODUCT, Money(1_000_000_000_000L)),
        )
        // En la inicial, cero es un producto sin precio.
        assertEquals(
            SetListPriceResult.Changed(2L),
            fixture.setPrice(DEFAULT_PRICE_LIST_ID, PRODUCT, Money.ZERO),
        )
    }

    @Test
    fun `an unknown zone, an unconfirmed currency and a restore are results`() = runTest {
        val noZone = withLists(PriceListFixture(zoneId = "Nowhere/Unknown"))
        assertEquals(
            SetListPriceResult.UnknownTimeZone,
            noZone.setPrice(WHOLESALE, PRODUCT, Money(1L)),
        )
        val noCurrency = withLists(PriceListFixture(currencyConfirmed = false))
        assertEquals(
            SetListPriceResult.CurrencyUnconfirmed,
            noCurrency.setPrice(WHOLESALE, PRODUCT, Money(1L)),
        )
        val restoring = withLists()
        restoring.catalog.transactions.restoreInProgress = true
        assertEquals(
            SetListPriceResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            restoring.setPrice(WHOLESALE, PRODUCT, Money(1L)),
        )
    }

    @Test
    fun `the resolver reads the own price and the chain of the product`() = runTest {
        val fixture = withLists()
        fixture.seedOwnPrice(WHOLESALE, PRODUCT, Money(9_000L))

        assertEquals(
            ListPriceResult.Priced(Money(8_500L), ListPriceOrigin.CALCULATED),
            fixture.resolver.priceOf(RELATIVE, PRODUCT, Money(10_000L)),
        )
        assertEquals(
            ListPriceResult.Priced(Money(9_000L), ListPriceOrigin.OWN),
            fixture.resolver.priceOf(WHOLESALE, PRODUCT, Money(10_000L)),
        )
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        PriceListFixture.assertEachWriteFailureLeavesNothing({ seed() }) {
            setPrice(WHOLESALE, PRODUCT, Money(9_000L))
        }
        PriceListFixture.assertEachWriteFailureLeavesNothing({
            seed()
            seedOwnPrice(WHOLESALE, PRODUCT, Money(9_000L))
        }) { setPrice(WHOLESALE, PRODUCT, null) }
        PriceListFixture.assertEachWriteFailureLeavesNothing({
            seed()
            seedOwnPrice(WHOLESALE, PRODUCT, Money(9_000L))
        }) { setPrice(WHOLESALE, PRODUCT, Money(9_500L)) }
        PriceListFixture.assertEachWriteFailureLeavesNothing({ seed() }) {
            setPrice(DEFAULT_PRICE_LIST_ID, PRODUCT, Money(9_500L))
        }
    }

    private fun withLists(fixture: PriceListFixture = PriceListFixture()) = fixture.apply { seed() }

    private fun PriceListFixture.seed() {
        catalog.seedProduct(PRODUCT, "Arroz", salePrice = Money(10_000L))
        catalog.seedProduct(ARCHIVED_PRODUCT, "Viejo", archived = true, salePrice = Money(1_000L))
        seedFixed(WHOLESALE, "Mayorista")
        seedFixed(OTHER_FIXED, "Otra fija")
        seedFixed(ARCHIVED, "Archivada", archived = true)
        // Relativa a la inicial: −15 %. Con 10000 de venta da 8500.
        seedRelative(RELATIVE, "Especial", baseId = DEFAULT_PRICE_LIST_ID, bp = -1_500L)
    }

    private companion object {
        const val PRODUCT = 1L
        const val ARCHIVED_PRODUCT = 2L
        const val WHOLESALE = 40L
        const val OTHER_FIXED = 41L
        const val ARCHIVED = 42L
        const val RELATIVE = 43L
    }
}
