package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El precio de un producto en cada tipo de lista, con los ejemplos de las reglas escritos a mano. */
class ListPriceRulesTest {
    private val lists = listOf(
        PriceList(DEFAULT, "DEFAULT", null, PriceListPricing.Fixed, archivedAt = null),
        PriceList(WHOLESALE, null, "Mayorista", PriceListPricing.Fixed, archivedAt = null),
        relative(MINUS_15, base = DEFAULT, bp = -1_500L),
        relative(PLUS_5_ON_MINUS_15, base = MINUS_15, bp = 500L),
        relative(ON_WHOLESALE, base = WHOLESALE, bp = 1_000L),
        relative(HUNDRED_TIMES, base = DEFAULT, bp = 1_000_000L),
        relative(ON_MISSING, base = 999L, bp = 0L),
        relative(LOOP_A, base = LOOP_B, bp = 0L),
        relative(LOOP_B, base = LOOP_A, bp = 0L),
        relative(ON_HUNDRED_TIMES, base = HUNDRED_TIMES, bp = 0L),
    ).associateBy { it.id }

    @Test
    fun `the initial list is the sale price`() {
        assertEquals(priced(10_000L, ListPriceOrigin.SALE_PRICE), resolve(DEFAULT))
    }

    @Test
    fun `a fixed list without its own price takes the sale price, and with one uses it`() {
        assertEquals(priced(10_000L, ListPriceOrigin.SALE_PRICE), resolve(WHOLESALE))
        assertEquals(
            priced(9_000L, ListPriceOrigin.OWN),
            resolve(WHOLESALE, own = mapOf(WHOLESALE to Money(9_000L))),
        )
    }

    @Test
    fun `10000 with minus 15 percent is 8500, and plus 5 percent on that is 8925`() {
        assertEquals(priced(8_500L, ListPriceOrigin.CALCULATED), resolve(MINUS_15))
        assertEquals(priced(8_925L, ListPriceOrigin.CALCULATED), resolve(PLUS_5_ON_MINUS_15))
    }

    @Test
    fun `a relative list starts from the own price of its fixed base`() {
        // 9000 × 1,10 = 9900.
        assertEquals(
            priced(9_900L, ListPriceOrigin.CALCULATED),
            resolve(ON_WHOLESALE, own = mapOf(WHOLESALE to Money(9_000L))),
        )
    }

    @Test
    fun `a relative price rounds half away from zero`() {
        // 3333 × 0,85 = 2833,05 y 3335 × 0,85 = 2834,75.
        assertEquals(
            Money(2_833L),
            ListPriceRules.relativePrice(Money(3_333L), BasisPoints(-1_500L)),
        )
        assertEquals(
            Money(2_835L),
            ListPriceRules.relativePrice(Money(3_335L), BasisPoints(-1_500L)),
        )
        assertEquals(Money.ZERO, ListPriceRules.relativePrice(Money(3_335L), BasisPoints(-10_000L)))
    }

    @Test
    fun `a price above the limit per unit cannot be sold, also further down the chain`() {
        val salePrice = Money(10_000_000_001L)
        assertEquals(ListPriceResult.AboveLimit, resolve(HUNDRED_TIMES, salePrice = salePrice))
        assertEquals(ListPriceResult.AboveLimit, resolve(ON_HUNDRED_TIMES, salePrice = salePrice))
        // 10^10 × 101 = 1,01 × 10^12, que pasa del tope; 9.900.990.099 × 101 queda justo debajo.
        assertEquals(
            priced(999_999_999_999L, ListPriceOrigin.CALCULATED),
            resolve(HUNDRED_TIMES, salePrice = Money(9_900_990_099L)),
        )
        assertNull(ListPriceRules.relativePrice(Money(Long.MAX_VALUE), BasisPoints(1_000L)))
    }

    @Test
    fun `a missing list or a loop is never priced`() {
        assertEquals(ListPriceResult.Unresolvable, resolve(12_345L))
        assertEquals(ListPriceResult.Unresolvable, resolve(ON_MISSING))
        assertEquals(ListPriceResult.Unresolvable, resolve(LOOP_A))
    }

    @Test
    fun `a list knows whether it is the initial one, archived and its kind`() {
        val initial = lists.getValue(DEFAULT)
        assertTrue(initial.isDefault)
        assertFalse(initial.isArchived)
        assertEquals(PriceListKind.FIXED, initial.pricing.kind)
        val archived = lists.getValue(MINUS_15).copy(archivedAt = Instant.fromEpochMilliseconds(1L))
        assertFalse(archived.isDefault)
        assertTrue(archived.isArchived)
        assertEquals(PriceListKind.RELATIVE, archived.pricing.kind)
    }

    private fun resolve(
        id: Long,
        salePrice: Money = Money(10_000L),
        own: Map<Long, Money> = emptyMap(),
    ): ListPriceResult = ListPriceRules.resolve(id, lists, salePrice, own)

    private fun priced(price: Long, origin: ListPriceOrigin) =
        ListPriceResult.Priced(Money(price), origin)

    private fun relative(id: Long, base: Long, bp: Long) = PriceList(
        id = id,
        systemKey = null,
        name = "Lista $id",
        pricing = PriceListPricing.Relative(base, BasisPoints(bp)),
        archivedAt = null,
    )

    private companion object {
        const val DEFAULT = 31L
        const val WHOLESALE = 32L
        const val MINUS_15 = 33L
        const val PLUS_5_ON_MINUS_15 = 34L
        const val ON_WHOLESALE = 35L
        const val HUNDRED_TIMES = 36L
        const val ON_MISSING = 37L
        const val LOOP_A = 38L
        const val LOOP_B = 39L
        const val ON_HUNDRED_TIMES = 40L
    }
}
