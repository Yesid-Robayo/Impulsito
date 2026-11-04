package io.github.yesidrobayo.shopledger.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CheckedTest {
    @Test
    fun `map transforms a value`() {
        val checked: Checked<Long> = Checked.Value(6161L)

        assertEquals(Checked.Value(Money(6161L)), checked.map(::Money))
    }

    @Test
    fun `map keeps an overflow`() {
        val checked: Checked<Long> = Checked.Overflow

        assertSame(Checked.Overflow, checked.map(::Money))
    }

    @Test
    fun `map keeps the limit that was exceeded`() {
        val checked: Checked<Long> = Checked.AboveLimit(Limit.DOCUMENT_TOTAL)

        assertEquals(Checked.AboveLimit(Limit.DOCUMENT_TOTAL), checked.map(::Money))
    }

    @Test
    fun `value or null gives the value only when the operation was accepted`() {
        assertEquals(Money(1543L), Checked.Value(Money(1543L)).valueOrNull())
        assertNull(Checked.Overflow.valueOrNull())
        assertNull(Checked.AboveLimit(Limit.UNIT_MONEY).valueOrNull())
    }
}
