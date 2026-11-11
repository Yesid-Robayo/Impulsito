package io.github.yesidrobayo.shopledger.domain.rule.sale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El nombre de una venta en espera: recortado, vacío es sin nombre, y hasta 40 caracteres. */
class HeldSaleRulesTest {
    @Test
    fun theLabelIsTrimmedAndBlankIsNoName() {
        assertEquals("Mesa 4", HeldSaleRules.labelOf("  Mesa 4 "))
        assertNull(HeldSaleRules.labelOf("   "))
        assertNull(HeldSaleRules.labelOf(null))
    }

    @Test
    fun fortyCharactersFitAndAnEmojiCountsAsOne() {
        assertTrue(HeldSaleRules.fits(null))
        assertTrue(HeldSaleRules.fits("x".repeat(40)))
        assertFalse(HeldSaleRules.fits("x".repeat(41)))
        // 39 letras y un emoji, que ocupa dos unidades de String: 40 caracteres.
        assertTrue(HeldSaleRules.fits("x".repeat(39) + "🍽"))
        // Una mitad suelta de un par cuenta como un carácter, como la cuenta la base.
        assertTrue(HeldSaleRules.fits("x".repeat(39) + "\uD83C"))
        assertFalse(HeldSaleRules.fits("x".repeat(40) + "\uD83C"))
        assertTrue(HeldSaleRules.fits("\uD83Cx"))
    }
}
