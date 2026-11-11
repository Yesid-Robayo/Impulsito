package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** La dirección del documento nunca sale en el texto de una copia entregada. */
class HandoffCopyTest {
    @Test
    fun `its text keeps the generation and hides the document`() {
        val copy = HandoffCopy(
            fileName = "copia-a.slcopy",
            sizeBytes = 2_400_000,
            writtenAt = Instant.parse("2026-11-07T15:00:00Z"),
            generation = 9,
            documentUri = "content://example.documents/document/A",
            lastCheckAt = null,
        )

        assertEquals("HandoffCopy(generation=9)", copy.toString())
        assertFalse(copy.toString().contains("content://"))
    }
}
