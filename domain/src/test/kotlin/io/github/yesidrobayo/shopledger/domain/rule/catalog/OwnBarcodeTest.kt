package io.github.yesidrobayo.shopledger.domain.rule.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** El código propio es `P` y el id con 8 cifras. */
class OwnBarcodeTest {
    @Test
    fun `the id is padded to eight digits after a P`() {
        assertEquals("P00000123", OwnBarcode.forProduct(123L))
        assertEquals("P00000001", OwnBarcode.forProduct(1L))
        assertEquals("P99999999", OwnBarcode.forProduct(99_999_999L))
    }

    @Test
    fun `a longer id is written whole`() {
        assertEquals("P123456789", OwnBarcode.forProduct(123_456_789L))
    }

    @Test
    fun `only a saved product has a code`() {
        assertThrows(IllegalArgumentException::class.java) { OwnBarcode.forProduct(0L) }
    }
}
