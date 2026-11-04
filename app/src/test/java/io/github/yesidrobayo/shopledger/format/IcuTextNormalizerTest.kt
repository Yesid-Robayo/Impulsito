package io.github.yesidrobayo.shopledger.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que ICU puede devolver y la fuente de la app no dibuja. Los textos de entrada imitan salidas
 * reales de ICU, escritas con escapes para que se vea cada carácter.
 */
class IcuTextNormalizerTest {
    @Test
    fun `the minus sign of a number becomes the typographic minus`() {
        assertEquals("\u22121.200,00", IcuTextNormalizer.normalizeNumber("-1.200,00"))
    }

    @Test
    fun `a typographic minus from the region stays as it is`() {
        val text = "\u22121\u00A0200,00"
        assertEquals(text, IcuTextNormalizer.normalizeNumber(text))
    }

    @Test
    fun `narrow and thin spaces become no break spaces`() {
        val text = "1\u202F234\u2009567,00"
        assertEquals("1\u00A0234\u00A0567,00", IcuTextNormalizer.normalizeNumber(text))
    }

    @Test
    fun `direction marks around a sign are removed`() {
        assertEquals("\u221212.5", IcuTextNormalizer.normalizeNumber("\u200E-12.5"))
        assertEquals("\u221212.5", IcuTextNormalizer.normalizeNumber("\u061C-12.5"))
        assertEquals("12.5\u2212", IcuTextNormalizer.normalizeNumber("12.5\u200F-"))
    }

    @Test
    fun `text keeps its hyphens and only fixes spaces and marks`() {
        assertEquals("2026-11-01", IcuTextNormalizer.normalizeText("2026-11-01"))
        assertEquals("10:42\u00A0PM", IcuTextNormalizer.normalizeText("10:42\u202FPM"))
        assertEquals("1 nov", IcuTextNormalizer.normalizeText("\u200F1 nov"))
    }

    @Test
    fun `plain text and the empty text pass through unchanged`() {
        assertEquals("", IcuTextNormalizer.normalizeNumber(""))
        assertEquals("1,234.56", IcuTextNormalizer.normalizeNumber("1,234.56"))
        assertEquals("1\u00A0234", IcuTextNormalizer.normalizeText("1\u00A0234"))
    }

    @Test
    fun `the value shown when there is none is a dash`() {
        assertEquals("\u2014", NO_VALUE_TEXT)
    }
}
