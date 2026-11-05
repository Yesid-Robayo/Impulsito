package io.github.yesidrobayo.shopledger.ui.currency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La cadena del símbolo con las fuentes reales del APK y una fuente del sistema inventada. */
class CurrencySymbolChainTest {
    private val figtree = fontCodePoints("figtree_variable")
    private val currency = fontCodePoints("currency_symbols_600")

    private fun chain(system: GlyphCoverage = GlyphCoverage { false }) = CurrencySymbolChain(
        GlyphCoverage {
            it in figtree
        },
        GlyphCoverage { it in currency },
        system,
    )

    private fun resolve(symbol: String, system: GlyphCoverage = GlyphCoverage { false }) =
        chain(system).resolve(symbol, TEST_CODE)

    @Test
    fun withoutTheSystemFontFiftyFiveSignsAreDrawnAndTwoBecomeTheIsoCode() {
        val resolved = CURRENCY_SIGNS_BMP.associateWith { resolve(String(Character.toChars(it))) }
        val iso = resolved.filterValues { it.usesIsoCode }.keys
        assertEquals(setOf(0x07FE, 0x07FF), iso)
        val fonts = resolved.values.filterNot {
            it.usesIsoCode
        }.map { result -> result.runs.map { it.font }.toSet() }
        assertEquals(55, fonts.size)
        assertTrue(fonts.all { SymbolFont.SYSTEM !in it })
    }

    @Test
    fun aSymbolOfFigtreeStaysInFigtree() {
        assertEquals(listOf(SymbolRun("\u20AC", SymbolFont.APP)), resolve("\u20AC").runs)
    }

    @Test
    fun theOwnGlyphGoesBeforeNfkcSoTheRupeeSignIsNotTwoLetters() {
        assertEquals(
            listOf(SymbolRun("\u20A8", SymbolFont.CURRENCY_SYMBOLS)),
            resolve("\u20A8").runs,
        )
        assertEquals(
            listOf(SymbolRun("\uFDFC", SymbolFont.CURRENCY_SYMBOLS)),
            resolve("\uFDFC").runs,
        )
    }

    @Test
    fun fullWidthSignsBecomeTheirNormalFormInTheFontThatHasIt() {
        assertEquals(listOf(SymbolRun("$", SymbolFont.APP)), resolve("\uFF04").runs)
        assertEquals(listOf(SymbolRun("\u00A5", SymbolFont.APP)), resolve("\uFFE5").runs)
        assertEquals(
            listOf(SymbolRun("\u20A9", SymbolFont.CURRENCY_SYMBOLS)),
            resolve("\uFFE6").runs,
        )
    }

    @Test
    fun aMixedSymbolSplitsIntoRunsPerFont() {
        assertEquals(
            listOf(
                SymbolRun("XY", SymbolFont.APP),
                SymbolRun("\u20B5", SymbolFont.CURRENCY_SYMBOLS),
            ),
            resolve("XY\u20B5").runs,
        )
    }

    @Test
    fun theSystemFontTakesOnlyWhatNoOwnFontHas() {
        val system = GlyphCoverage { true }
        assertEquals(listOf(SymbolRun("Дж", SymbolFont.SYSTEM)), resolve("Дж", system).runs)
        assertEquals(
            listOf(SymbolRun("A", SymbolFont.APP), SymbolRun("\u07FE", SymbolFont.SYSTEM)),
            resolve("A\u07FE", system).runs,
        )
    }

    @Test
    fun withoutTheGlyphAnywhereTheWholeSymbolBecomesTheIsoCode() {
        val resolved = resolve("A\u07FE")
        assertTrue(resolved.usesIsoCode)
        assertEquals(listOf(SymbolRun(TEST_CODE, SymbolFont.APP)), resolved.runs)
        assertEquals(TEST_CODE, resolved.text)
    }

    @Test
    fun aCharacterOutsideTheBasicPlaneIsOneCodePoint() {
        // Un signo de moneda del plano suplementario, como el de los números de Siyaq: ninguna fuente lo tiene.
        val resolved = resolve(String(Character.toChars(0x1ECB0)))
        assertTrue(resolved.usesIsoCode)
        assertFalse(resolve("\u20B5").usesIsoCode)
        assertEquals(
            listOf(0x1ECB0, 0x24),
            CurrencySymbolChain.codePointsOf(String(Character.toChars(0x1ECB0)) + "$"),
        )
    }
}
