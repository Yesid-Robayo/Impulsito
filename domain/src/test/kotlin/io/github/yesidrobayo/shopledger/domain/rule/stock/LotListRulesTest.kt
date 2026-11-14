package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoice
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.LotDue
import io.github.yesidrobayo.shopledger.domain.model.stock.LotEntry
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué lote entra en cada filtro de la pantalla de lotes, en qué orden y cuánto vale lo que está
 * por vencer. Hoy es 2026-11-14, con 30 días de aviso. Productos y códigos inventados.
 */
class LotListRulesTest {
    private val cheese = product(
        1L,
        name = "Fresh cheese",
        onHandMilli = 10_000L,
        averageCost = 1_500L,
        stockValue = 15_000L,
        tracksLots = true,
    )
    private val oil = product(
        2L,
        name = "Cooking oil",
        onHandMilli = 4_000L,
        averageCost = 2_000L,
        stockValue = 8_000L,
        tracksLots = true,
    )

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private fun entry(
        id: Long,
        product: Product = cheese,
        onHand: Long = 1_000L,
        expiry: String? = null,
        unassigned: Boolean = false,
    ): LotEntry {
        val due = expiry?.let { ExpiryRules.dueOf(date(it), date("2026-11-14"), 30) }
        val lot = StockLot(
            id = id,
            productId = product.id,
            code = if (unassigned) null else "L-$id",
            isUnassigned = unassigned,
            expiryDate = expiry?.let(::date),
            purchaseLineId = null,
            expiryNotifiedAt = null,
            createdAt = Instant.fromEpochMilliseconds(0L),
            closedAt = null,
        )
        return LotEntry(lot, product, QuantityMilli(onHand), emptyList(), due)
    }

    @Test
    fun `expiring takes coded lots with stock in their days and not yet expired`() {
        assertTrue(LotListRules.matches(entry(1L, expiry = "2026-11-20"), LotFilter.EXPIRING))
        assertTrue(LotListRules.matches(entry(1L, expiry = "2026-11-14"), LotFilter.EXPIRING))
        // Vencido, sin stock, sin vencimiento, lejos o sin asignar: no entra.
        assertFalse(LotListRules.matches(entry(1L, expiry = "2026-11-10"), LotFilter.EXPIRING))
        val empty = entry(1L, onHand = 0L, expiry = "2026-11-20")
        assertFalse(LotListRules.matches(empty, LotFilter.EXPIRING))
        assertFalse(LotListRules.matches(entry(1L), LotFilter.EXPIRING))
        assertFalse(LotListRules.matches(entry(1L, expiry = "2027-01-30"), LotFilter.EXPIRING))
        assertFalse(LotListRules.matches(entry(1L, unassigned = true), LotFilter.EXPIRING))
    }

    @Test
    fun `expired takes coded lots with stock past their date`() {
        assertTrue(LotListRules.matches(entry(1L, expiry = "2026-11-13"), LotFilter.EXPIRED))
        assertFalse(LotListRules.matches(entry(1L, expiry = "2026-11-14"), LotFilter.EXPIRED))
        assertFalse(LotListRules.matches(entry(1L), LotFilter.EXPIRED))
        assertFalse(
            LotListRules.matches(entry(1L, onHand = 0L, expiry = "2026-11-01"), LotFilter.EXPIRED),
        )
        assertFalse(LotListRules.matches(entry(1L, unassigned = true), LotFilter.EXPIRED))
    }

    @Test
    fun `all keeps every coded lot, also the empty ones, and unassigned only with a balance`() {
        assertTrue(LotListRules.matches(entry(1L, onHand = 0L), LotFilter.ALL))
        assertFalse(LotListRules.matches(entry(1L, unassigned = true), LotFilter.ALL))
        val oversold = entry(1L, onHand = -2_000L, unassigned = true)
        assertTrue(LotListRules.matches(oversold, LotFilter.UNASSIGNED))
        val settled = entry(1L, onHand = 0L, unassigned = true)
        assertFalse(LotListRules.matches(settled, LotFilter.UNASSIGNED))
        assertFalse(LotListRules.matches(entry(1L), LotFilter.UNASSIGNED))
    }

    @Test
    fun `groups by product in exit order, the group whose first lot expires first going first`() {
        val entries = listOf(
            entry(1L, expiry = null),
            entry(2L, expiry = "2026-12-01"),
            entry(3L, expiry = "2026-11-20"),
            entry(4L, product = oil, expiry = "2026-11-16"),
            entry(5L, expiry = "2026-11-20"),
            entry(6L, product = oil, onHand = 0L),
        )

        val groups = LotListRules.grouped(entries, LotFilter.ALL)

        assertEquals(listOf(oil.id, cheese.id), groups.map { it.product.id })
        assertEquals(listOf(4L, 6L), groups[0].entries.map { it.lot.id })
        // Dos del mismo día salen por el más antiguo; los que no vencen, al final.
        assertEquals(listOf(3L, 5L, 2L, 1L), groups[1].entries.map { it.lot.id })
        assertEquals(6, LotListRules.count(entries, LotFilter.ALL))
        assertEquals(4, LotListRules.count(entries, LotFilter.EXPIRING))
    }

    @Test
    fun `two groups that start the same day go by name, then by id`() {
        val twin = product(3L, name = cheese.details.name, tracksLots = true)
        val entries = listOf(
            entry(1L, product = twin, expiry = "2026-11-20"),
            entry(2L, product = cheese, expiry = "2026-11-20"),
            entry(3L, product = oil, expiry = "2026-11-20"),
        )

        val groups = LotListRules.grouped(entries, LotFilter.EXPIRING)

        assertEquals(listOf(oil.id, cheese.id, twin.id), groups.map { it.product.id })
    }

    @Test
    fun `expiring value is each lot at the cost it would go out with`() {
        // Queso: 10 und valen 15.000; 2 und salen con 3.000. Aceite: 4 und valen 8.000; 1 und,
        // 2.000.
        val entries = listOf(
            entry(1L, onHand = 2_000L, expiry = "2026-11-20"),
            entry(2L, product = oil, onHand = 1_000L, expiry = "2026-11-18"),
            entry(3L, onHand = 5_000L, expiry = "2026-11-01"),
        )

        assertEquals(Money(5_000L), LotListRules.expiringValue(entries))
        assertEquals(Money.ZERO, LotListRules.expiringValue(emptyList()))
    }

    @Test
    fun `expiring value is unknown when a lot or the sum does not fit`() {
        val huge = product(
            9L,
            onHandMilli = 1_000L,
            averageCost = Long.MAX_VALUE / 2,
            stockValue = Long.MAX_VALUE / 2,
            tracksLots = true,
        )
        val tooMuch = entry(1L, product = huge, onHand = 5_000L, expiry = "2026-11-20")
        assertNull(LotListRules.expiringValue(listOf(tooMuch)))

        // Cada uno cabe, pero los dos juntos no.
        val half = entry(2L, product = huge, onHand = 1_000L, expiry = "2026-11-20")
        val other = entry(3L, product = huge, onHand = 1_000L, expiry = "2026-11-21")
        val third = entry(4L, product = huge, onHand = 1_000L, expiry = "2026-11-22")
        assertNull(LotListRules.expiringValue(listOf(half, other, third)))
    }

    @Test
    fun `due marks today, the notice days and an unknown day`() {
        val today = date("2026-11-14")
        assertNull(ExpiryRules.dueOf(null, today, 30))
        val dueToday = ExpiryRules.dueOf(today, today, 0)
        assertEquals(LotDue(date("2026-11-14"), 0, soon = true), dueToday)
        assertEquals(
            LotDue(date("2026-12-15"), 31, soon = false),
            ExpiryRules.dueOf(date("2026-12-15"), today, 30),
        )
        val unknown = requireNotNull(ExpiryRules.dueOf(date("2026-11-01"), null, 30))
        assertNull(unknown.daysLeft)
        assertFalse(unknown.soon)
        assertFalse(unknown.isExpired)
        assertTrue(requireNotNull(ExpiryRules.dueOf(date("2026-11-13"), today, 30)).isExpired)
    }

    @Test
    fun `the proposed choice is the first in exit order`() {
        val stock = LotStock(7L, "L-7", null, isUnassigned = false, onHand = QuantityMilli(1_000L))
        assertEquals(7L, LotChoices(listOf(LotChoice(stock, null)), null).proposed?.stock?.lotId)
        assertNull(LotChoices(emptyList(), stock).proposed)
        assertTrue(entry(1L).hasStock)
        assertFalse(entry(1L, onHand = 0L).hasStock)
    }
}
