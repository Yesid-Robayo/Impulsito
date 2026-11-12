package io.github.yesidrobayo.shopledger.ui.components

import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import org.junit.Assert.assertEquals
import org.junit.Test

/** Volver a escribir una cifra guardada como la escribiría la persona, sin miles ni ceros de más. */
class NumberInputTextTest {
    private val comma = NumberSymbols(',', '-', DigitGrouping('.', 3, 3))
    private val point = NumberSymbols('.', '-', DigitGrouping(',', 3, 3))

    @Test
    fun `amounts keep their decimals without trailing zeros nor grouping`() {
        assertEquals("6000,5", fixedPointInputText(600_050, 2, comma))
        assertEquals("6000.05", fixedPointInputText(600_005, 2, point))
        assertEquals("6000", fixedPointInputText(600_000, 2, comma))
        assertEquals("1234567", fixedPointInputText(1_234_567, 0, comma))
        assertEquals("0", fixedPointInputText(0, 2, comma))
    }

    @Test
    fun `quantities in thousandths read back as written`() {
        assertEquals("2,5", fixedPointInputText(2_500, 3, comma))
        assertEquals("0,125", fixedPointInputText(125, 3, comma))
        assertEquals("1", fixedPointInputText(1_000, 3, comma))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative value is not something to write back`() {
        fixedPointInputText(-1, 2, comma)
    }
}
