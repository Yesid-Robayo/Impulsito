package io.github.yesidrobayo.shopledger.domain.rule.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogTextTest {
    @Test
    fun `a name is trimmed and normalized for comparing`() {
        assertEquals(
            CatalogName.Valid("Jabón Líquido", "jabon liquido"),
            CatalogText.name(" Jabón Líquido\t", 80),
        )
    }

    @Test
    fun `a name of marks alone is empty and a long one is too long`() {
        assertEquals(CatalogName.Empty, CatalogText.name("́", 80))
        assertEquals(CatalogName.TooLong, CatalogText.name("abc", 2))
    }

    @Test
    fun `length counts characters, not UTF-16 units`() {
        assertEquals(3, CatalogText.length("a😀b"))
        assertEquals(0, CatalogText.length(""))
    }

    @Test
    fun `a barcode is trimmed and blank is none`() {
        assertEquals("123", CatalogText.barcode(" 123 "))
        assertNull(CatalogText.barcode("  "))
        assertNull(CatalogText.barcode(null))
    }
}
