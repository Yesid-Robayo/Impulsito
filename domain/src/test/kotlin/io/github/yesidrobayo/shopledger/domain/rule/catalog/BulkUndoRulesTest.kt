package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRule
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoPlan
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoRevert
import io.github.yesidrobayo.shopledger.domain.model.catalog.NewPriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CatalogFixture
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué vuelve y qué se deja al deshacer la última actualización masiva. */
class BulkUndoRulesTest {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val batch = Uuid.parse("00000000-0000-4000-8000-000000000001")

    private fun product(id: Long, price: Long): Product = Product(
        id = id,
        details = CatalogFixture.details("Producto $id"),
        salePrice = Money(price),
        initialCost = Money.ZERO,
        averageCost = Money.ZERO,
        stockValue = Money.ZERO,
        onHand = QuantityMilli.ZERO,
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = at,
        updatedAt = at,
    )

    private fun bulkRow(id: Long, productId: Long, old: Long, new: Long) = NewPriceChange(
        productId = productId,
        priceListId = 1L,
        oldPrice = Money(old),
        newPrice = Money(new),
        source = PriceChangeSource.BULK,
        batchId = batch,
        bulkRule = BulkPriceRule(BulkPriceAdjustment.Percent(BasisPoints(800L)), Money(100L)),
        occurredAt = at,
        businessDate = BusinessDate(LocalDate(2026, 11, 12)),
    ).recordedAs(id)

    @Test
    fun `a price still as the batch left it goes back, one changed later stays`() {
        // De un lote de tres: el 1 sigue en 13400, el 2 se cambió a mano y el 3 ya no está.
        val rows = listOf(
            bulkRow(1L, 1L, old = 12_350L, new = 13_400L),
            bulkRow(2L, 2L, old = 10_000L, new = 10_800L),
            bulkRow(3L, 3L, old = 500L, new = 600L),
        )

        val plan = BulkUndoRules.plan(
            batch,
            rows,
            listOf(product(1L, 13_400L), product(2L, 11_000L)),
        )

        assertEquals(
            BulkUndoPlan(
                batch,
                listOf(BulkUndoRevert(1L, Money(13_400L), Money(12_350L))),
                kept = 2,
            ),
            plan,
        )
        assertTrue(plan.canUndo)
    }

    @Test
    fun `a list row reads the own price of that list, or the sale price without one`() {
        val rows = listOf(1L, 2L, 3L).map { id ->
            val row = bulkRow(id, id, old = 900L, new = 1_000L)
            row.copy(content = row.content.copy(priceListId = WHOLESALE))
        }
        val own =
            mapOf(1L to mapOf(WHOLESALE to Money(1_000L)), 2L to mapOf(OTHER to Money(1_000L)))

        val plan = BulkUndoRules.plan(
            batch,
            rows,
            listOf(product(1L, 5L), product(2L, 1_000L), product(3L, 7L)),
            defaultListId = 1L,
            ownPrices = own,
        )

        // El 1 con su precio propio y el 2 con el de venta siguen como los dejó el lote; el 3 no.
        assertEquals(listOf(1L, 2L), plan.reverts.map { it.productId })
        assertEquals(listOf(WHOLESALE, WHOLESALE), plan.reverts.map { it.priceListId })
        assertEquals(1, plan.kept)
    }

    @Test
    fun `a row without a previous price is left as it is`() {
        val initial = PriceChange(
            id = 1L,
            content = bulkRow(1L, 1L, old = 1L, new = 900L).content.copy(
                oldPrice = null,
                source = PriceChangeSource.INITIAL,
                batchId = null,
                bulkRule = null,
            ),
        )

        val plan = BulkUndoRules.plan(batch, listOf(initial), listOf(product(1L, 900L)))

        assertEquals(1, plan.kept)
        assertFalse(plan.canUndo)
    }

    private companion object {
        const val WHOLESALE = 2L
        const val OTHER = 3L
    }
}
