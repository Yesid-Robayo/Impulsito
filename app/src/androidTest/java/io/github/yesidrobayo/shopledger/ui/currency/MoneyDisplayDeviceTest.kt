package io.github.yesidrobayo.shopledger.ui.currency

import android.annotation.SuppressLint
import android.graphics.Paint
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.format.FormatLocale
import io.github.yesidrobayo.shopledger.format.MoneyFormatter
import io.github.yesidrobayo.shopledger.format.TEST_CURRENCY
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los montos dibujados en el teléfono, con las fuentes del APK y la del sistema: ningún cuadro
 * vacío, cifras tabulares con cualquier símbolo, la línea base de Figtree, el símbolo de derecha a
 * izquierda en su lugar y el código ISO cuando ninguna fuente tiene el símbolo.
 *
 * Mide con el mismo motor de texto de las pantallas, sin herramientas de interfaz.
 */
@RunWith(AndroidJUnit4::class)
class MoneyDisplayDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resources = context.resources
    private val density = Density(context)
    private val measurer =
        TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr, cacheSize = 0)
    private val chain = CurrencySymbolFonts.chain(resources)
    private val figtree = ownCodePoints(R.font.figtree_variable)
    private val currencySymbols = ownCodePoints(R.font.currency_symbols_600)
    private val style: TextStyle = AppTheme.amounts.row.copy(textAlign = TextAlign.End)
    private val columnWidthPx = with(density) {
        AppTheme.amounts.row.fontSize.toPx() * COLUMN_EMS
    }.toInt()

    /** Cada caso, con el símbolo antes de las cifras y después, en dos formatos regionales. */
    private val formats = listOf("en", "fr")

    @Test
    fun theTwoWeightsOfTheSubsetDrawTheSameSymbolsAsInTheTests() {
        assertEquals(currencySymbols, ownCodePoints(R.font.currency_symbols_500))
        assertEquals(49, ((figtree + currencySymbols) intersect CURRENCY_SIGNS.toSet()).size)
    }

    @Test
    fun everySymbolIsDrawnByAFontThatHasItAndNeverAsAnEmptyBox() {
        for (symbol in allCases()) {
            val resolved = chain.resolve(symbol, TEST_CURRENCY.code)
            if (resolved.usesIsoCode) {
                val missing = CurrencySymbolChain.codePointsOf(symbol).any { !drawable(it) }
                assertTrue("${label(symbol)} sin fuente", missing)
                continue
            }
            for (run in resolved.runs) {
                for (codePoint in CurrencySymbolChain.codePointsOf(run.text)) {
                    val covered =
                        when (run.font) {
                            SymbolFont.APP -> codePoint in figtree
                            SymbolFont.CURRENCY_SYMBOLS -> codePoint in currencySymbols
                            SymbolFont.SYSTEM -> systemHas(codePoint)
                        }
                    assertTrue("${label(symbol)} en ${run.font}", covered)
                }
            }
        }
    }

    @Test
    fun theOwnFontsDrawFiftyFiveOfTheFiftySevenSignsWithoutTheSystem() {
        val ownOnly = CurrencySymbolFonts.chain(resources, system = GlyphCoverage { false })
        val iso =
            CURRENCY_SIGNS.filter {
                ownOnly.resolve(String(Character.toChars(it)), TEST_CURRENCY.code).usesIsoCode
            }
        assertEquals(listOf(0x07FE, 0x07FF), iso)
    }

    @Test
    fun whenThePhoneHasNoGlyphEitherTheIsoCodeIsShown() {
        // El signo de NKo: ni Figtree, ni el subconjunto, ni los emuladores de las pruebas lo traen.
        assumeFalse("Este teléfono sí dibuja U+07FE", systemHas(0x07FE))
        val parts = formatter("en", "\u07FE").parts(Money(120_000))
        val text = moneyText(parts, chain.resolve(parts.symbol, parts.currencyCode))
        assertEquals("${TEST_CURRENCY.code}\u00A01,200.00", visible(text))
    }

    @Test
    fun aSymbolWithoutAnyFontBecomesTheIsoCodeEvenIfPartOfItHasOne() {
        val noSystem = CurrencySymbolFonts.chain(resources, system = GlyphCoverage { false })
        val resolved = noSystem.resolve("Дж", TEST_CURRENCY.code)
        assertTrue(resolved.usesIsoCode)
        assertEquals(TEST_CURRENCY.code, resolved.text)
    }

    @Test
    fun theDigitsStayTabularWithEverySymbolBeforeAndAfter() {
        for (tag in formats) {
            for (symbol in allCases()) {
                val layout = column(tag, symbol)
                val shift = abs(lineWidth(layout, 0) - lineWidth(layout, 1))
                assertTrue("$tag ${label(symbol)}: $shift px", shift <= TOLERANCE_PX)
            }
        }
    }

    /**
     * Se mide una línea, como se dibuja un monto. En API 29, un texto de dos líneas con una corrida de
     * `currency_symbols` baja la primera 4 px; en una sola línea no cambia nada.
     */
    @Test
    fun theOwnFontsKeepTheBaselineAndOnlyGrowByTheInkOfALowSymbol() {
        val fontSizePx = with(density) { style.fontSize.toPx() }
        val failures = mutableListOf<String>()
        for (tag in formats) {
            val reference = line(tag, "\u00A4")
            for (symbol in allCases()) {
                val resolved = chain.resolve(symbol, TEST_CURRENCY.code)
                if (resolved.runs.any { it.font == SymbolFont.SYSTEM }) continue
                val layout = line(tag, symbol)
                val baseline = layout.firstBaseline - reference.firstBaseline
                val height = layout.size.height - reference.size.height
                if (abs(baseline) >
                    TOLERANCE_PX
                ) {
                    failures += "$tag ${label(symbol)} línea base $baseline"
                }
                if (height < 0 || height > MAX_INK_PADDING_EM * fontSizePx + TOLERANCE_PX) {
                    failures += "$tag ${label(symbol)} alto $height"
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun theIsolationKeepsARightToLeftSymbolBeforeTheDigits() {
        for (symbol in RIGHT_TO_LEFT) {
            val (symbolInk, digitsInk) = boxes(amount("en", symbol, Money(120_000)))
            assertTrue(
                "${label(symbol)}: $symbolInk, cifras $digitsInk",
                symbolInk.last < digitsInk.first,
            )
            val (afterSymbol, afterDigits) = boxes(amount("fr", symbol, Money(120_000)))
            assertTrue(
                "${label(symbol)} después: $afterSymbol, cifras $afterDigits",
                afterDigits.last < afterSymbol.first,
            )
        }
    }

    @Test
    fun withoutTheIsolationTheArabicLabelWouldBeDrawnAfterTheDigits() {
        // El control: si la medida no viera el problema, la prueba de arriba no probaría nada.
        val symbol = RIGHT_TO_LEFT.last()
        val isolated = amount("en", symbol, Money(120_000))
        val bare =
            AnnotatedString(
                isolated.text.filterNot {
                    it == LEFT_TO_RIGHT_ISOLATE ||
                        it == POP_DIRECTIONAL_ISOLATE
                },
            )
        val (symbolInk, digitsInk) = boxes(bare)
        assertTrue("$symbolInk, cifras $digitsInk", symbolInk.first > digitsInk.last)
    }

    @Test
    fun theIsolationAddsNoWidthAndNoHeight() {
        for (symbol in listOf("\u00A4", "\u20B5", "\u060B")) {
            val isolated = amount("en", symbol, Money(120_000))
            val bare =
                AnnotatedString(
                    isolated.text.filterNot {
                        it == LEFT_TO_RIGHT_ISOLATE ||
                            it == POP_DIRECTIONAL_ISOLATE
                    },
                    isolated.spanStyles.map { range ->
                        // Sin el LRI, cada tramo empieza un carácter antes.
                        AnnotatedString.Range(range.item, range.start - 1, range.end - 1)
                    },
                )
            val withIsolation = measure(isolated)
            val without = measure(bare)
            assertEquals(label(symbol), without.size.height, withIsolation.size.height)
            assertEquals(
                label(symbol),
                lineWidth(without, 0),
                lineWidth(withIsolation, 0),
                TOLERANCE_PX,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun formatter(tag: String, symbol: String?): MoneyFormatter = MoneyFormatter(
        requireNotNull(FormatLocale.of(tag)),
        TEST_CURRENCY,
        2,
        symbol,
        Locale.ENGLISH,
    )

    private fun amount(tag: String, symbol: String, value: Money): AnnotatedString {
        val parts = formatter(tag, symbol).parts(value)
        return moneyText(parts, chain.resolve(parts.symbol, parts.currencyCode))
    }

    /** Dos líneas, `111…` sobre `888…`, con el mismo símbolo, alineadas a la derecha. */
    private fun column(tag: String, symbol: String): TextLayoutResult = measurer.measure(
        text =
            buildAnnotatedString {
                append(amount(tag, symbol, Money(ONES)))
                append("\n")
                append(amount(tag, symbol, Money(EIGHTS)))
            },
        style = style,
        softWrap = false,
        constraints = Constraints.fixedWidth(columnWidthPx),
    )

    /** Un monto en una línea, alineado a la derecha, como en una fila. */
    private fun line(tag: String, symbol: String): TextLayoutResult = measurer.measure(
        text = amount(tag, symbol, Money(EIGHTS)),
        style = style,
        softWrap = false,
        constraints = Constraints.fixedWidth(columnWidthPx),
    )

    private fun measure(text: AnnotatedString): TextLayoutResult = measurer.measure(
        text = text,
        style = style.copy(textAlign = TextAlign.Start),
        softWrap = false,
    )

    /**
     * Las columnas de píxeles con tinta del símbolo y de las cifras, de izquierda a derecha. Se mide
     * pintando: con texto bidireccional, las cajas de `getBoundingBox` no siempre son las dibujadas.
     * El resto del texto se pinta transparente, que no cambia la forma de las letras.
     */
    private fun boxes(text: AnnotatedString): Pair<IntRange, IntRange> {
        val chars = text.text
        val open = chars.indexOf(LEFT_TO_RIGHT_ISOLATE)
        val symbol: (Int) -> Boolean =
            if (open >= 0) {
                { it in open + 1 until chars.indexOf(POP_DIRECTIONAL_ISOLATE) }
            } else {
                {
                    !chars[it].isDigit() && chars[it] !in NUMBER_PUNCTUATION &&
                        !Character.isSpaceChar(chars[it])
                }
            }
        return ink(text, symbol) to ink(text) { chars[it].isDigit() }
    }

    private fun ink(text: AnnotatedString, visible: (Int) -> Boolean): IntRange {
        val masked =
            buildAnnotatedString {
                append(text)
                for (index in text.text.indices) {
                    if (!visible(index)) {
                        addStyle(
                            SpanStyle(color = Color.Transparent),
                            index,
                            index + 1,
                        )
                    }
                }
            }
        val layout = measurer.measure(
            masked,
            style.copy(textAlign = TextAlign.Start, color = Color.Black),
            softWrap = false,
        )
        val bitmap = ImageBitmap(layout.size.width, layout.size.height)
        TextPainter.paint(Canvas(bitmap), layout)
        val pixels = bitmap.toPixelMap()
        val columns = (0 until pixels.width).filter { x ->
            (0 until pixels.height).any { y ->
                pixels[x, y].alpha >
                    INK_ALPHA
            }
        }
        return columns.first()..columns.last()
    }

    private fun lineWidth(layout: TextLayoutResult, line: Int): Float =
        layout.getLineRight(line) - layout.getLineLeft(line)

    private fun visible(text: AnnotatedString): String =
        text.text.filterNot { it == LEFT_TO_RIGHT_ISOLATE || it == POP_DIRECTIONAL_ISOLATE }

    private fun drawable(codePoint: Int): Boolean =
        codePoint in figtree || codePoint in currencySymbols || systemHas(codePoint)

    private fun systemHas(codePoint: Int): Boolean =
        Paint().hasGlyph(String(Character.toChars(codePoint)))

    // openRawResource lee cualquier recurso que sea un archivo, y los .ttf de res/font lo son.
    @SuppressLint("ResourceType")
    private fun ownCodePoints(fontId: Int): Set<Int> =
        CmapReader.readCodePoints(resources.openRawResource(fontId).use { it.readBytes() })

    private fun label(symbol: String): String =
        CurrencySymbolChain.codePointsOf(symbol).joinToString(" ") {
            "U+" + Integer.toHexString(it).uppercase().padStart(4, '0')
        }

    private fun allCases(): List<String> =
        CURRENCY_SIGNS.map { String(Character.toChars(it)) } + WRITTEN_SYMBOLS

    private companion object {
        /** Medio píxel: menos que eso no se ve, y más ya desalinea una columna. */
        const val TOLERANCE_PX = 0.5f

        /** Lo que no es símbolo en el texto de un monto, además de las cifras y los espacios. */
        const val NUMBER_PUNCTUATION = ".,\u2212"

        /** Desde cuánta opacidad un píxel cuenta como tinta: el suavizado deja bordes casi transparentes. */
        const val INK_ALPHA = 0.25f

        /** Lo que puede crecer el alto por un glifo que baja más que Figtree, como `௹`, en em. */
        const val MAX_INK_PADDING_EM = 0.1f

        /** Ancho de la columna de montos, en em del estilo: cabe el monto más ancho de la prueba. */
        const val COLUMN_EMS = 14f

        const val ONES = 11_111_111L
        const val EIGHTS = 88_888_888L

        /**
         * Los 57 puntos de código de la categoría Unicode `Sc` en el plano básico, según Unicode 15.1.
         * Van escritos y no calculados, porque Android antiguo trae datos de Unicode viejos.
         */
        val CURRENCY_SIGNS: List<Int> =
            listOf(0x0024, 0x00A2, 0x00A3, 0x00A4, 0x00A5, 0x058F, 0x060B, 0x07FE, 0x07FF) +
                listOf(0x09F2, 0x09F3, 0x09FB, 0x0AF1, 0x0BF9, 0x0E3F, 0x17DB) +
                (0x20A0..0x20C0).toList() +
                listOf(0xA838, 0xFDFC, 0xFE69, 0xFF04, 0xFFE0, 0xFFE1, 0xFFE5, 0xFFE6)

        /**
         * Símbolos inventados, como los que una persona puede escribir: letras latinas con un signo,
         * letras cirílicas y letras árabes que se unen.
         */
        val WRITTEN_SYMBOLS: List<String> = listOf("XY\u20B5", "Дж", "بج")

        /** Los que se escriben de derecha a izquierda: dos signos y una etiqueta inventada. */
        val RIGHT_TO_LEFT: List<String> = listOf("\u060B", "\uFDFC", "بج")
    }
}
