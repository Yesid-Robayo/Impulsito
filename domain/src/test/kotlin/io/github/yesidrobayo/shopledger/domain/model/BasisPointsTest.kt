package io.github.yesidrobayo.shopledger.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasisPointsTest {
    @Test
    fun `one hundred percent is ten thousand basis points`() {
        assertEquals(10_000L, BasisPoints.ONE_HUNDRED_PERCENT.bp)
        assertEquals(0L, BasisPoints.ZERO.bp)
    }

    @Test
    fun `bulk price update goes from minus 99_99 to plus 999_99 percent`() {
        assertEquals(-9_999L, BasisPoints.MIN_BULK_PERCENT.bp)
        assertEquals(99_999L, BasisPoints.MAX_BULK_PERCENT.bp)
    }

    @Test
    fun `target margin starts at 30 percent and stops at 99 percent`() {
        assertEquals(3_000L, BasisPoints.DEFAULT_TARGET_MARGIN.bp)
        assertEquals(9_900L, BasisPoints.MAX_TARGET_MARGIN.bp)
        assertTrue(BasisPoints.DEFAULT_TARGET_MARGIN <= BasisPoints.MAX_TARGET_MARGIN)
        assertTrue(BasisPoints.MAX_TARGET_MARGIN < BasisPoints.ONE_HUNDRED_PERCENT)
    }

    @Test
    fun `compares by basis points`() {
        assertTrue(BasisPoints(-1L) < BasisPoints.ZERO)
        assertTrue(BasisPoints(1250L) > BasisPoints(1249L))
        assertEquals(0, BasisPoints(1250L).compareTo(BasisPoints(1250L)))
    }
}
