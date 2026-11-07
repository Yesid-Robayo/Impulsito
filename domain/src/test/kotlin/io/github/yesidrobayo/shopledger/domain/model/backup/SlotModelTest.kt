package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los tipos de las ranuras: sus referencias guardadas, sus contenidos y sus límites. */
class SlotModelTest {
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `a stored file reference names its slot`() {
        assertEquals(BackupSlot.A, BackupSlot.fromFileRefOrNull("A"))
        assertEquals(BackupSlot.B, BackupSlot.fromFileRefOrNull("B"))
        assertEquals(BackupSlot.C, BackupSlot.fromFileRefOrNull("C"))
    }

    @Test
    fun `no reference, or a file name of another destination, names no slot`() {
        assertNull(BackupSlot.fromFileRefOrNull(null))
        assertNull(BackupSlot.fromFileRefOrNull("a"))
        assertNull(BackupSlot.fromFileRefOrNull("shopledger-20261101T203012Z-7.db.gz"))
    }

    @Test
    fun `only a confirmed copy is something a write can lose`() {
        assertTrue(SlotContent.Empty.isOpen)
        assertTrue(SlotContent.HalfWritten(writingGeneration = 3).isOpen)
        assertFalse(
            SlotContent.Holding(
                SlotCopy(3, at, valid = true, records = 0, catalogCount = 0),
            ).isOpen,
        )
    }

    @Test
    fun `the table gives each slot and lists the confirmed copies in letter order`() {
        val copyB = SlotCopy(2, at, valid = false, records = 5, catalogCount = 1)
        val copyC = SlotCopy(1, at, valid = true, records = 7, catalogCount = 2)
        val table =
            SlotTable(
                SlotContent.HalfWritten(3),
                SlotContent.Holding(copyB),
                SlotContent.Holding(copyC),
            )

        assertEquals(SlotContent.HalfWritten(3), table[BackupSlot.A])
        assertEquals(SlotContent.Holding(copyB), table[BackupSlot.B])
        assertEquals(SlotContent.Holding(copyC), table[BackupSlot.C])
        assertEquals(listOf(BackupSlot.B to copyB, BackupSlot.C to copyC), table.copies)
    }

    @Test
    fun `a generation is positive and a count is never negative`() {
        assertThrows(IllegalArgumentException::class.java) { SlotContent.HalfWritten(0) }
        assertThrows(IllegalArgumentException::class.java) { SlotCopy(0, at, true, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { SlotCopy(1, at, true, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SlotCopy(1, at, true, 0, -1) }
    }

    @Test
    fun `a new copy never carries negative counts`() {
        val marks = NewCopyMarks(valid = true, fatal = false)

        assertEquals(0L, SlotCandidate(marks, records = 0, catalogCount = 0).records)
        assertThrows(IllegalArgumentException::class.java) { SlotCandidate(marks, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SlotCandidate(marks, 0, -1) }
    }
}
