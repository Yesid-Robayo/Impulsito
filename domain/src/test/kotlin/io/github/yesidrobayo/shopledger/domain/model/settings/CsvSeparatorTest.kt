package io.github.yesidrobayo.shopledger.domain.model.settings

import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvSeparatorTest {
    @Test
    fun `a region with a decimal comma starts with semicolon columns`() {
        assertEquals(CsvSeparator.SEMICOLON, CsvSeparator.defaultFor(DecimalSeparator.COMMA))
    }

    @Test
    fun `a region with a decimal point starts with comma columns`() {
        assertEquals(CsvSeparator.COMMA, CsvSeparator.defaultFor(DecimalSeparator.POINT))
    }

    @Test
    fun `each column separator writes its own character`() {
        assertEquals(';', CsvSeparator.SEMICOLON.char)
        assertEquals(',', CsvSeparator.COMMA.char)
        assertEquals('\t', CsvSeparator.TAB.char)
    }

    @Test
    fun `stored names never change`() {
        // Son los valores que admite la guarda de la base: renombrar uno dejaría ajustes sin leer.
        assertEquals(listOf("SEMICOLON", "COMMA", "TAB"), CsvSeparator.entries.map { it.name })
    }

    @Test
    fun `each decimal separator writes its own character`() {
        assertEquals('.', DecimalSeparator.POINT.char)
        assertEquals(',', DecimalSeparator.COMMA.char)
    }
}
