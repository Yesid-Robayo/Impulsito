package io.github.yesidrobayo.shopledger.ui.currency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que dibujan por sí solas las fuentes del APK, leído de su tabla `cmap`. */
class CmapReaderTest {
    private val signs = CURRENCY_SIGNS_BMP.toSet()
    private val figtree = fontCodePoints("figtree_variable")
    private val currency = fontCodePoints("currency_symbols_600")

    @Test
    fun theListHasTheFiftySevenCurrencySignsOfUnicode() {
        assertEquals(57, signs.size)
        // La JVM de las pruebas trae datos de Unicode más nuevos que Android antiguo.
        for (codePoint in CURRENCY_SIGNS_BMP.filter(Character::isDefined)) {
            assertEquals(
                label(codePoint),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.getType(codePoint),
            )
        }
    }

    @Test
    fun figtreeHasSevenCurrencySignsAndWhatTheAmountsNeed() {
        assertEquals(setOf(0x24, 0xA2, 0xA3, 0xA4, 0xA5, 0x20AC, 0x20B9), figtree intersect signs)
        // El signo menos, el espacio duro y las letras del código ISO, que reemplaza a un símbolo sin fuente.
        for (codePoint in listOf(0x2212, 0x00A0) + ('A'.code..'Z'.code)) {
            assertTrue("Falta ${label(codePoint)}", codePoint in figtree)
        }
        // Lo que no trae, y por eso el formateador lo cambia o lo quita antes de dibujar.
        for (codePoint in listOf(0x202F, 0x2009, 0x200E, 0x200F)) {
            assertFalse("Sobra ${label(codePoint)}", codePoint in figtree)
        }
    }

    @Test
    fun bothWeightsOfTheCurrencySubsetDrawTheSameSymbols() {
        assertEquals(currency, fontCodePoints("currency_symbols_500"))
    }

    @Test
    fun withFigtreeTheSubsetLeavesOnlyTheFullWidthFormsAndTheNkoSignsUncovered() {
        val covered = (figtree + currency) intersect signs
        assertEquals(49, covered.size)
        assertEquals(
            setOf(0x07FE, 0x07FF, 0xFE69, 0xFF04, 0xFFE0, 0xFFE1, 0xFFE5, 0xFFE6),
            signs - covered,
        )
    }
}
