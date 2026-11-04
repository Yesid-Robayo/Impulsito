package io.github.yesidrobayo.shopledger.domain.model

import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentStatusTest {
    private val voiding = Voiding(Instant.fromEpochMilliseconds(1_000L), VoidReason.UNDO, null)

    @Test
    fun `the stored names never change`() {
        // Se guardan por nombre en la base: cambiar uno dejaría filas que ninguna constante lee.
        assertEquals(listOf("ACTIVE", "VOIDED"), DocumentStatus.entries.map { it.name })
    }

    @Test
    fun `a document without voiding data is active`() {
        assertEquals(DocumentStatus.ACTIVE, DocumentStatus.of(null))
    }

    @Test
    fun `a document with voiding data is voided`() {
        assertEquals(DocumentStatus.VOIDED, DocumentStatus.of(voiding))
    }
}
