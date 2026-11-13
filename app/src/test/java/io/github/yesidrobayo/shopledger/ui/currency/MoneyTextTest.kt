package io.github.yesidrobayo.shopledger.ui.currency

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import io.github.yesidrobayo.shopledger.format.MoneyParts
import io.github.yesidrobayo.shopledger.format.SignPosition
import io.github.yesidrobayo.shopledger.ui.theme.CurrencySymbolFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El texto de un monto con partes escritas a mano: el orden de la región, el signo tipográfico, el
 * espacio duro y el símbolo aislado en sus propios tramos, fuera de las cifras.
 */
class MoneyTextTest {
    private fun parts(
        symbolFirst: Boolean,
        sign: SignPosition,
        negative: Boolean = true,
        space: Boolean = true,
    ) = MoneyParts(
        negative = negative,
        number = "1.200,00",
        symbol = "\u00A4",
        symbolFirst = symbolFirst,
        symbolSpace = space,
        signPosition = sign,
        currencyCode = TEST_CODE,
        currencyName = "test currency",
    )

    private val genericSign =
        ResolvedSymbol(listOf(SymbolRun("\u00A4", SymbolFont.APP)), usesIsoCode = false)

    private fun visible(text: AnnotatedString): String =
        text.text.filterNot { it == LEFT_TO_RIGHT_ISOLATE || it == POP_DIRECTIONAL_ISOLATE }

    @Test
    fun theSignGoesWhereTheRegionPutsItWithTheSymbolBefore() {
        assertEquals(
            "\u2212\u00A4\u00A01.200,00",
            visible(moneyText(parts(true, SignPosition.LEADING), genericSign)),
        )
        assertEquals(
            "\u00A4\u00A0\u22121.200,00",
            visible(moneyText(parts(true, SignPosition.NEXT_TO_NUMBER), genericSign)),
        )
        assertEquals(
            "\u00A4\u2212\u00A01.200,00",
            visible(moneyText(parts(true, SignPosition.NEXT_TO_SYMBOL), genericSign)),
        )
        assertEquals(
            "\u00A4\u00A01.200,00\u2212",
            visible(moneyText(parts(true, SignPosition.TRAILING), genericSign)),
        )
    }

    @Test
    fun theSignGoesWhereTheRegionPutsItWithTheSymbolAfter() {
        assertEquals(
            "\u22121.200,00\u00A0\u00A4",
            visible(moneyText(parts(false, SignPosition.LEADING), genericSign)),
        )
        assertEquals(
            "1.200,00\u2212\u00A0\u00A4",
            visible(moneyText(parts(false, SignPosition.NEXT_TO_NUMBER), genericSign)),
        )
        assertEquals(
            "1.200,00\u00A0\u2212\u00A4",
            visible(moneyText(parts(false, SignPosition.NEXT_TO_SYMBOL), genericSign)),
        )
        assertEquals(
            "1.200,00\u00A0\u00A4\u2212",
            visible(moneyText(parts(false, SignPosition.TRAILING), genericSign)),
        )
    }

    @Test
    fun aLedgerRowPutsThePlusWhereTheRegionPutsTheSignAndNeverOnANegative() {
        assertEquals(
            "+¤ 1.200,00",
            visible(
                moneyText(
                    parts(true, SignPosition.LEADING, negative = false),
                    genericSign,
                    plus = true,
                ),
            ),
        )
        assertEquals(
            "1.200,00 ¤+",
            visible(
                moneyText(
                    parts(false, SignPosition.TRAILING, negative = false),
                    genericSign,
                    plus = true,
                ),
            ),
        )
        // Un negativo lleva su menos aunque se pida el signo de suma.
        assertEquals(
            "−¤ 1.200,00",
            visible(moneyText(parts(true, SignPosition.LEADING), genericSign, plus = true)),
        )
    }

    @Test
    fun aPositiveAmountHasNoSignAndARegionWithoutSpaceGetsNone() {
        val text =
            moneyText(
                parts(true, SignPosition.LEADING, negative = false, space = false),
                genericSign,
            )
        assertEquals("\u00A41.200,00", visible(text))
    }

    @Test
    fun theSymbolIsIsolatedAndTheDigitsStayOutsideAnySpan() {
        val symbol =
            ResolvedSymbol(
                listOf(
                    SymbolRun("X", SymbolFont.APP),
                    SymbolRun("\u060B", SymbolFont.CURRENCY_SYMBOLS),
                ),
                usesIsoCode = false,
            )
        val text = moneyText(parts(true, SignPosition.LEADING), symbol)
        assertEquals("\u2212\u2066X\u060B\u2069\u00A01.200,00", text.text)
        val spans = text.spanStyles
        assertEquals(1, spans.size)
        assertEquals(CurrencySymbolFamily, spans.single().item.fontFamily)
        assertEquals("\u060B", text.text.substring(spans.single().start, spans.single().end))
    }

    @Test
    fun aSymbolOfTheSystemFontUsesTheDefaultFamily() {
        val symbol = ResolvedSymbol(listOf(SymbolRun("Дж", SymbolFont.SYSTEM)), usesIsoCode = false)
        val span = moneyText(parts(false, SignPosition.LEADING), symbol).spanStyles.single()
        assertEquals(FontFamily.Default, span.item.fontFamily)
    }

    @Test
    fun theIsoCodeIsAlwaysSeparatedFromTheDigits() {
        val iso = ResolvedSymbol(listOf(SymbolRun(TEST_CODE, SymbolFont.APP)), usesIsoCode = true)
        val text =
            moneyText(parts(true, SignPosition.LEADING, negative = false, space = false), iso)
        assertEquals("${TEST_CODE}\u00A01.200,00", visible(text))
        assertTrue(text.spanStyles.isEmpty())
    }
}
