package io.github.yesidrobayo.shopledger.domain.model.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Los tipos de la preparación de las ranuras: el nombre propuesto, el que sigue y sus límites. */
class SlotSetupModelTest {
    @Test
    fun `the picker proposes one fixed name per slot`() {
        assertEquals("shopledger-backup-a.slcopy", BackupSlot.A.newFileName)
        assertEquals("shopledger-backup-b.slcopy", BackupSlot.B.newFileName)
        assertEquals("shopledger-backup-c.slcopy", BackupSlot.C.newFileName)
    }

    @Test
    fun `the next file is the first one still missing`() {
        val look =
            look(SlotFileStatus.CREATED, SlotFileStatus.MISSING, SlotFileStatus.MISSING)
        assertEquals(BackupSlot.B, look.next)
    }

    @Test
    fun `a slot that still opens is not asked for again`() {
        val look = look(SlotFileStatus.READY, SlotFileStatus.READY, SlotFileStatus.MISSING)
        assertEquals(BackupSlot.C, look.next)
    }

    @Test
    fun `with the three files there is no next one`() {
        val look = look(SlotFileStatus.READY, SlotFileStatus.CREATED, SlotFileStatus.CREATED)
        assertNull(look.next)
    }

    @Test
    fun `the look always lists the three slots in order`() {
        val files = BackupSlot.entries.map { SlotSetupFile(it, SlotFileStatus.MISSING, null) }
        assertThrows(IllegalArgumentException::class.java) {
            SlotSetupLook("Drive", files.reversed(), otherDestination = false, done = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SlotSetupLook("Drive", files.take(2), otherDestination = false, done = false)
        }
    }

    @Test
    fun `a file name chosen by the person never reaches a log line`() {
        val text = SlotSetupFile(BackupSlot.A, SlotFileStatus.CREATED, "invented-name").toString()
        assertFalse(text.contains("invented-name"))
    }

    @Test
    fun `only a repeated file says which slot already holds it`() {
        assertNull(SlotFileOutcome.Rejected(SlotFileRejection.NOT_EMPTY).sameAs)
        assertEquals(
            BackupSlot.A,
            SlotFileOutcome.Rejected(SlotFileRejection.SAME_FILE, BackupSlot.A).sameAs,
        )
    }

    private fun look(vararg statuses: SlotFileStatus) = SlotSetupLook(
        providerLabel = "Drive",
        files = BackupSlot.entries.mapIndexed { index, slot ->
            SlotSetupFile(slot, statuses[index], null)
        },
        otherDestination = false,
        done = false,
    )
}
