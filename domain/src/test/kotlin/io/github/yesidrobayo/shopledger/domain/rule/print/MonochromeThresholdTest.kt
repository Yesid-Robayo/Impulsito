package io.github.yesidrobayo.shopledger.domain.rule.print

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonochromeThresholdTest {
    @Test
    fun `black prints and white paper does not`() {
        assertTrue(MonochromeThreshold.isDark(0xFF000000.toInt()))
        assertFalse(MonochromeThreshold.isDark(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `a transparent dot is white paper`() {
        assertFalse(MonochromeThreshold.isDark(0x00000000))
    }

    @Test
    fun `a mid gray falls on each side of the cutoff`() {
        // 127 de brillo queda justo debajo del corte y se imprime; 128 ya no.
        assertTrue(MonochromeThreshold.isDark(0xFF7F7F7F.toInt()))
        assertFalse(MonochromeThreshold.isDark(0xFF808080.toInt()))
        // Pesos de Rec. 601: el verde pesa más que el rojo y el azul.
        assertFalse(MonochromeThreshold.isDark(0xFF00FF00.toInt()))
        assertTrue(MonochromeThreshold.isDark(0xFFFF0000.toInt()))
    }
}
