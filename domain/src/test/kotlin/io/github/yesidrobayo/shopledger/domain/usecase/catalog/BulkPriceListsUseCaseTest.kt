package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRule
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPricePreviewer
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceListRepository
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture.Companion.DEFAULT_PRICE_LIST_ID
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La masiva sobre varias listas: el precio de venta de la inicial y el propio de una fija, en una
 * sola transacción y con un solo lote, y su deshacer. Montos inventados.
 */
class BulkPriceListsUseCaseTest {
    private val fixture = CatalogFixture().apply {
        seedProduct(1L, "Arroz", salePrice = Money(3_500L))
        seedProduct(2L, "Café", salePrice = Money(10_000L))
        seedProduct(3L, "Pan", salePrice = Money(800L))
        database.seed { tables ->
            tables.copy(
                priceLists = listOf(
                    PriceList(DEFAULT_PRICE_LIST_ID, "DEFAULT", null, PriceListPricing.Fixed, null),
                    PriceList(WHOLESALE, null, "Mayorista", PriceListPricing.Fixed, null),
                    PriceList(
                        SPECIAL,
                        null,
                        "Especial",
                        PriceListPricing.Relative(WHOLESALE, BasisPoints(0L)),
                        null,
                    ),
                    PriceList(
                        OLD,
                        null,
                        "Vieja",
                        PriceListPricing.Fixed,
                        Instant.fromEpochMilliseconds(1L),
                    ),
                ).associateBy { it.id },
                productPrices = mapOf((WHOLESALE to 1L) to Money(3_000L)),
            )
        }
    }
    private val lists = FakePriceListRepository(fixture.database)
    private val batch = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val undoBatch = Uuid.parse("00000000-0000-4000-8000-000000000002")

    private val applyBulk = ApplyBulkPriceUseCase(
        fixture.transactions,
        fixture.products,
        fixture.systemRows,
        fixture.priceHistory,
        BusinessCalendarSource(fixture.settings),
        CurrencyLock(fixture.settings),
        fixture.clock,
        newBatchId = { batch },
        priceLists = lists,
        productPrices = lists,
    )

    private val undoBulk = UndoBulkPriceUseCase(
        fixture.transactions,
        fixture.products,
        fixture.systemRows,
        fixture.priceHistory,
        BusinessCalendarSource(fixture.settings),
        fixture.clock,
        newBatchId = { undoBatch },
        productPrices = lists,
    )

    private val amount = BulkPriceAdjustment.Amount(Money(250L))

    private fun request(vararg listIds: Long, scope: BulkPriceScope = BulkPriceScope.All) =
        BulkPriceRequest(scope, amount, Money.ZERO, priceListIds = listIds.toSet())

    @Test
    fun `a tag and two lists change in one transaction with one batch`() = runTest {
        val tagged = BulkPriceScope.Tag(tagId = 9L, productIds = setOf(1L, 2L))

        val result =
            applyBulk(setOf(1L, 2L), request(DEFAULT_PRICE_LIST_ID, WHOLESALE, scope = tagged))

        assertEquals(ApplyBulkPriceResult.Applied(batch, changed = 4), result)
        val tables = fixture.tables
        assertEquals(Money(3_750L), fixture.product(1L).salePrice)
        assertEquals(Money(10_250L), fixture.product(2L).salePrice)
        // Arroz tenía 3000 propio; el café tomaba el de venta, 10000, y ahora tiene propio.
        assertEquals(Money(3_250L), tables.productPrices[WHOLESALE to 1L])
        assertEquals(Money(10_250L), tables.productPrices[WHOLESALE to 2L])
        assertEquals(Money(800L), fixture.product(3L).salePrice)
        val rows = tables.priceChanges.values.map { it.content }
        assertEquals(setOf(batch), rows.map { it.batchId }.toSet())
        assertEquals(setOf(PriceChangeSource.BULK), rows.map { it.source }.toSet())
        assertEquals(setOf(BulkPriceRule(amount, Money.ZERO)), rows.map { it.bulkRule }.toSet())
        assertEquals(
            listOf(DEFAULT_PRICE_LIST_ID, WHOLESALE, DEFAULT_PRICE_LIST_ID, WHOLESALE),
            tables.priceChanges.values.sortedBy { it.id }.map { it.content.priceListId },
        )
        assertEquals(1L, tables.changeSeq)
    }

    @Test
    fun `undo brings both lists back in one batch`() = runTest {
        applyBulk(setOf(1L, 2L), request(DEFAULT_PRICE_LIST_ID, WHOLESALE))
        // Después del lote, el precio propio del café cambia a mano: se deja.
        fixture.database.seed {
            it.copy(
                productPrices =
                    it.productPrices + ((WHOLESALE to 2L) to Money(9_999L)),
            )
        }

        val result = undoBulk(batch)

        assertEquals(UndoBulkPriceResult.Undone(reverted = 3, kept = 1), result)
        assertEquals(Money(3_500L), fixture.product(1L).salePrice)
        assertEquals(Money(10_000L), fixture.product(2L).salePrice)
        assertEquals(Money(3_000L), fixture.tables.productPrices[WHOLESALE to 1L])
        assertEquals(Money(9_999L), fixture.tables.productPrices[WHOLESALE to 2L])
        val offer =
            ObserveBulkUndoUseCase(
                fixture.priceHistory,
                fixture.products,
                lists,
                fixture.systemRows,
            )
        assertNull(offer().first())
    }

    @Test
    fun `an own price the batch created goes back to the price it started from`() = runTest {
        // El pan no tenía precio propio: el lote le crea 1050, y deshacer lo deja en 800, el que se veía.
        applyBulk(setOf(3L), request(WHOLESALE))
        assertEquals(Money(1_050L), fixture.tables.productPrices[WHOLESALE to 3L])

        assertEquals(UndoBulkPriceResult.Undone(reverted = 1, kept = 0), undoBulk(batch))
        assertEquals(Money(800L), fixture.tables.productPrices[WHOLESALE to 3L])
    }

    @Test
    fun `the offer counts what would come back in each list`() = runTest {
        applyBulk(setOf(1L), request(WHOLESALE))
        val offer =
            ObserveBulkUndoUseCase(
                fixture.priceHistory,
                fixture.products,
                lists,
                fixture.systemRows,
            )

        val plan = requireNotNull(offer().first())

        assertEquals(1, plan.reverts.size)
        assertEquals(WHOLESALE, plan.reverts.single().priceListId)
    }

    @Test
    fun `a relative, archived or missing list is an invalid request and writes nothing`() =
        runTest {
            val before = fixture.tables

            for (list in listOf(SPECIAL, OLD, 99L)) {
                assertEquals(
                    ApplyBulkPriceResult.InvalidRequest,
                    applyBulk(setOf(1L), request(list)),
                )
            }
            val unwired = ApplyBulkPriceUseCase(
                fixture.transactions,
                fixture.products,
                fixture.systemRows,
                fixture.priceHistory,
                BusinessCalendarSource(fixture.settings),
                CurrencyLock(fixture.settings),
                fixture.clock,
            )
            assertEquals(
                ApplyBulkPriceResult.InvalidRequest,
                unwired(setOf(1L), request(WHOLESALE)),
            )
            assertEquals(before, fixture.tables)
        }

    @Test
    fun `the preview shows one row per list with the list it changes`() = runTest {
        val catalog = fixture.products.observe(ArchiveFilter.ACTIVE).first()
        val context = BulkPriceContext(
            lists = fixture.tables.priceLists,
            ownPrices = mapOf(1L to mapOf(WHOLESALE to Money(3_000L))),
        )

        val preview = BulkPricePreviewer.preview(
            catalog,
            request(
                DEFAULT_PRICE_LIST_ID,
                WHOLESALE,
                SPECIAL,
                99L,
                scope = BulkPriceScope.Picked(setOf(1L)),
            ),
            context,
        )

        assertEquals(
            listOf(DEFAULT_PRICE_LIST_ID to Money(3_750L), WHOLESALE to Money(3_250L)),
            preview.changes.map { it.priceListId to it.change.newPrice },
        )
    }

    @Test
    fun `each write that fails leaves nothing`() = runTest {
        CatalogFixture.assertEachWriteFailureLeavesNothing({
            seedProduct(1L, "Arroz", salePrice = Money(3_500L))
            database.seed { tables ->
                tables.copy(
                    priceLists = listOf(
                        PriceList(
                            DEFAULT_PRICE_LIST_ID,
                            "DEFAULT",
                            null,
                            PriceListPricing.Fixed,
                            null,
                        ),
                        PriceList(WHOLESALE, null, "Mayorista", PriceListPricing.Fixed, null),
                    ).associateBy { it.id },
                )
            }
        }) {
            val repo = FakePriceListRepository(database)
            ApplyBulkPriceUseCase(
                transactions,
                products,
                systemRows,
                priceHistory,
                BusinessCalendarSource(settings),
                CurrencyLock(settings),
                clock,
                priceLists = repo,
                productPrices = repo,
            )(setOf(1L), request(DEFAULT_PRICE_LIST_ID, WHOLESALE))
        }
    }

    private companion object {
        const val WHOLESALE = 32L
        const val SPECIAL = 33L
        const val OLD = 34L
    }
}
