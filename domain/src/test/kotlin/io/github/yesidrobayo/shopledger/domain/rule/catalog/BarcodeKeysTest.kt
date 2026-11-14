package io.github.yesidrobayo.shopledger.domain.rule.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/** Un código se busca tal como llegó y, si es un UPC-A, también en su otra forma. */
class BarcodeKeysTest {
    @Test
    fun `a code is looked up trimmed and as it came`() {
        assertEquals(listOf("P00000123"), BarcodeKeys.forLookup("  P00000123 "))
        assertEquals(listOf("2900000123458"), BarcodeKeys.forLookup("2900000123458"))
    }

    @Test
    fun `a blank code has nothing to look up`() {
        assertEquals(emptyList<String>(), BarcodeKeys.forLookup("   "))
    }

    @Test
    fun `twelve digits are also looked up with a leading zero`() {
        assertEquals(
            listOf("012345678905", "0012345678905"),
            BarcodeKeys.forLookup("012345678905"),
        )
    }

    @Test
    fun `thirteen digits that start with zero are also looked up without it`() {
        assertEquals(
            listOf("0012345678905", "012345678905"),
            BarcodeKeys.forLookup("0012345678905"),
        )
    }

    @Test
    fun `other lengths and letters stay as they are`() {
        assertEquals(listOf("12345670"), BarcodeKeys.forLookup("12345670"))
        assertEquals(listOf("12345678901A"), BarcodeKeys.forLookup("12345678901A"))
        assertEquals(listOf("12345-789012"), BarcodeKeys.forLookup("12345-789012"))
    }
}
