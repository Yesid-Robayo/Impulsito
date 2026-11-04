package io.github.yesidrobayo.shopledger.domain.model.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los símbolos que el formato regional entrega al lector de números. Un símbolo que se pudiera leer
 * de dos formas haría ambiguo lo que escribe la persona, así que se rechaza al construirlo.
 */
class NumberSymbolsTest {
    private val pointGroups = DigitGrouping(separator = '.', primarySize = 3, secondarySize = 3)

    @Test
    fun `valid symbols keep what they were given`() {
        val symbols =
            NumberSymbols(decimalSeparator = ',', minusSign = '\u2212', grouping = pointGroups)

        assertEquals(',', symbols.decimalSeparator)
        assertEquals('\u2212', symbols.minusSign)
        assertEquals(pointGroups, symbols.grouping)
    }

    @Test
    fun `a format without grouping is allowed`() {
        assertEquals(
            null,
            NumberSymbols(decimalSeparator = '.', minusSign = '-', grouping = null).grouping,
        )
    }

    @Test
    fun `the decimal separator cannot be a digit, a sign, the percent sign nor a space`() {
        for (decimal in listOf('5', '+', '%', '-', '\u2212', '\u2012', ' ')) {
            assertThrows(IllegalArgumentException::class.java) {
                NumberSymbols(decimalSeparator = decimal, minusSign = '\u2012', grouping = null)
            }
        }
    }

    @Test
    fun `the minus sign cannot be a digit, a space, the plus sign nor the percent sign`() {
        for (minus in listOf('7', ' ', '+', '%')) {
            assertThrows(IllegalArgumentException::class.java) {
                NumberSymbols(decimalSeparator = ',', minusSign = minus, grouping = null)
            }
        }
    }

    @Test
    fun `the decimal separator cannot be read as the grouping separator`() {
        assertThrows(IllegalArgumentException::class.java) {
            NumberSymbols(decimalSeparator = '.', minusSign = '\u2212', grouping = pointGroups)
        }
        // Un apóstrofo agrupa, y el otro apóstrofo se lee igual: tampoco puede ser el decimal.
        assertThrows(IllegalArgumentException::class.java) {
            NumberSymbols(
                decimalSeparator = '\u2019',
                minusSign = '\u2212',
                grouping = DigitGrouping('\'', 3, 3),
            )
        }
    }

    @Test
    fun `the grouping separator cannot be a digit nor a sign`() {
        for (separator in listOf('1', '+', '%', '-', '\u2212')) {
            assertThrows(IllegalArgumentException::class.java) {
                NumberSymbols(
                    decimalSeparator = ',',
                    minusSign = '\u2212',
                    grouping = DigitGrouping(separator, 3, 3),
                )
            }
        }
    }

    @Test
    fun `the grouping separator cannot be read as the minus sign`() {
        assertThrows(IllegalArgumentException::class.java) {
            NumberSymbols(
                decimalSeparator = '.',
                minusSign = '\u2019',
                grouping = DigitGrouping('\'', 3, 3),
            )
        }
    }

    @Test
    fun `the decimal separator and the minus sign cannot be the region percent sign`() {
        assertThrows(IllegalArgumentException::class.java) {
            NumberSymbols(',', '\u2212', grouping = null, percentSign = ',')
        }
        assertThrows(IllegalArgumentException::class.java) {
            NumberSymbols(',', minusSign = '\u066A', grouping = null, percentSign = '\u066A')
        }
        // El del teclado sigue siendo un signo de porcentaje aunque la región tenga otro.
        assertThrows(IllegalArgumentException::class.java) {
            NumberSymbols(',', minusSign = '%', grouping = null, percentSign = '\u066A')
        }
    }

    @Test
    fun `the percent sign cannot be a digit, a space, the plus sign nor a minus sign`() {
        for (percent in listOf('3', ' ', '+', '-', '\u2212', '\u2012')) {
            assertThrows(IllegalArgumentException::class.java) {
                NumberSymbols(',', '\u2012', grouping = null, percentSign = percent)
            }
        }
    }

    @Test
    fun `the grouping separator cannot be read as the percent sign`() {
        assertThrows(IllegalArgumentException::class.java) {
            NumberSymbols('.', '\u2212', DigitGrouping('\'', 3, 3), percentSign = '\u2019')
        }
    }

    @Test
    fun `the region percent sign and the keyboard one are both percent signs`() {
        val ownPercent = NumberSymbols('.', '\u2212', grouping = null, percentSign = '\u066A')

        assertTrue(ownPercent.isPercent('\u066A'))
        assertTrue(ownPercent.isPercent('%'))
        assertFalse(ownPercent.isPercent('#'))
        assertEquals('%', NumberSymbols('.', '-', grouping = null).percentSign)
    }

    @Test
    fun `the region minus, the keyboard hyphen and the typographic minus are all minus signs`() {
        val ownMinus = NumberSymbols(decimalSeparator = ',', minusSign = '\u2012', grouping = null)

        assertTrue(ownMinus.isMinus('\u2012'))
        assertTrue(ownMinus.isMinus('-'))
        assertTrue(ownMinus.isMinus('\u2212'))
        assertFalse(ownMinus.isMinus('+'))
        assertFalse(ownMinus.isMinus('~'))
    }

    @Test
    fun `a group has at least one digit`() {
        assertThrows(IllegalArgumentException::class.java) { DigitGrouping('.', 0, 3) }
        assertThrows(IllegalArgumentException::class.java) { DigitGrouping('.', 3, 0) }
        assertEquals(2, DigitGrouping(',', 3, 2).secondarySize)
    }

    @Test
    fun `a space separator reads any space the person can type or paste`() {
        val spaces = DigitGrouping('\u00A0', 3, 3)

        for (space in listOf(
            ' ',
            '\u00A0',
            '\u202F',
            '\u2009',
            '\t',
        )) {
            assertTrue(spaces.reads(space))
        }
        assertFalse(spaces.reads('.'))
        assertFalse(spaces.reads('\''))
    }

    @Test
    fun `an apostrophe separator reads the keyboard and the typographic apostrophe`() {
        val keyboard = DigitGrouping('\'', 3, 3)
        val typographic = DigitGrouping('\u2019', 3, 3)

        for (grouping in listOf(keyboard, typographic)) {
            assertTrue(grouping.reads('\''))
            assertTrue(grouping.reads('\u2019'))
            assertFalse(grouping.reads(' '))
            assertFalse(grouping.reads('.'))
        }
    }

    @Test
    fun `any other separator reads only itself`() {
        assertTrue(pointGroups.reads('.'))
        assertFalse(pointGroups.reads(','))
        assertFalse(pointGroups.reads(' '))
    }
}
