package io.github.yesidrobayo.shopledger.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VoidableDocumentTest {
    @Test
    fun `the stored void reasons never change`() {
        assertEquals(
            listOf("ENTRY_ERROR", "CUSTOMER_RETURN", "SUPPLIER_RETURN", "UNDO", "OTHER"),
            VoidReason.entries.map { it.name },
        )
    }

    @Test
    fun `each document admits exactly its own reasons`() {
        // La tabla de motivos admitidos por documento, escrita a mano.
        val expected = mapOf(
            VoidableDocument.SALE to setOf(
                VoidReason.ENTRY_ERROR,
                VoidReason.CUSTOMER_RETURN,
                VoidReason.UNDO,
                VoidReason.OTHER,
            ),
            VoidableDocument.PURCHASE to setOf(
                VoidReason.ENTRY_ERROR,
                VoidReason.SUPPLIER_RETURN,
                VoidReason.UNDO,
                VoidReason.OTHER,
            ),
            VoidableDocument.EXPENSE to setOf(
                VoidReason.ENTRY_ERROR,
                VoidReason.UNDO,
                VoidReason.OTHER,
            ),
            VoidableDocument.PURCHASE_RETURN to setOf(
                VoidReason.ENTRY_ERROR,
                VoidReason.UNDO,
                VoidReason.OTHER,
            ),
        )
        for (document in VoidableDocument.entries) {
            val allowed = VoidReason.entries.filter { document.allows(it) }.toSet()
            assertEquals(document.name, expected.getValue(document), allowed)
        }
    }
}
