package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import org.junit.Assert.assertEquals
import org.junit.Test

class QuantitySignTest {
    private val samples = listOf(QuantityMilli(-1L), QuantityMilli.ZERO, QuantityMilli(1L))

    @Test
    fun `positive allows only what enters`() {
        assertEquals(listOf(false, false, true), samples.map(QuantitySign.POSITIVE::allows))
    }

    @Test
    fun `negative allows only what leaves`() {
        assertEquals(listOf(true, false, false), samples.map(QuantitySign.NEGATIVE::allows))
    }

    @Test
    fun `non zero allows both directions but never nothing`() {
        assertEquals(listOf(true, false, true), samples.map(QuantitySign.NON_ZERO::allows))
    }

    @Test
    fun `zero allows only a row that moves no units`() {
        assertEquals(listOf(false, true, false), samples.map(QuantitySign.ZERO::allows))
    }

    @Test
    fun `the extremes of a long keep their sign`() {
        assertEquals(true, QuantitySign.POSITIVE.allows(QuantityMilli(Long.MAX_VALUE)))
        assertEquals(true, QuantitySign.NEGATIVE.allows(QuantityMilli(Long.MIN_VALUE)))
    }
}
