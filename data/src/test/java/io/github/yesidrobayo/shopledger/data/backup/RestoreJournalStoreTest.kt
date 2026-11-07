package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El diario del reemplazo se escribe con temporal y renombrado, y se lee al pie de la letra: lo que
 * no es exactamente un diario de esta versión es ilegible, nunca uno mal entendido.
 */
class RestoreJournalStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val files by lazy {
        RestoreFiles(
            DatabaseFiles(
                File(folder.root, "databases/shopledger.db").absoluteFile,
                File(folder.root, "no_backup").absoluteFile,
            ),
        )
    }
    private val operations = mutableListOf<String>()
    private val store by lazy {
        RestoreJournalStore(
            files,
            DurableFileOps(directorySync = {
                check(it.isDirectory)
            }, listener = { operations += it }),
        )
    }

    private val journal =
        RestoreJournal(
            state = RestoreJournalState.SWAPPED,
            expectedSha256 = "ab".repeat(32),
            expectedChangeSeq = 120,
            expectedRecordCount = 485_000,
            hadActiveDb = true,
            safetyCopyOk = false,
            shaVerified = true,
        )

    @Test
    fun `a written journal reads back the same and leaves no temporary`() {
        assertEquals(RestoreJournalRead.Absent, store.read())

        store.write(journal)

        assertEquals(RestoreJournalRead.Present(journal), store.read())
        assertFalse(files.journalTemporary.exists())
        assertEquals(
            listOf(
                "write restore/journal.tmp",
                "rename restore/journal.tmp -> restore/journal.json",
            ),
            operations,
        )
        assertEquals(
            "{\"state\":\"SWAPPED\",\"expected_sha256\":\"${"ab".repeat(32)}\"," +
                "\"expected_change_seq\":120,\"expected_record_count\":485000," +
                "\"had_active_db\":true,\"safety_copy_ok\":false,\"sha_verified\":true}",
            files.journal.readText(),
        )
    }

    @Test
    fun `every state reads back`() {
        for (state in RestoreJournalState.entries) {
            store.write(journal.copy(state = state))
            assertEquals(RestoreJournalRead.Present(journal.copy(state = state)), store.read())
        }
    }

    @Test
    fun `anything that is not exactly a journal is unreadable`() {
        val valid = files.journal.also { store.write(journal) }.readText()
        val broken =
            listOf(
                "",
                "{",
                "[]",
                valid.replace("SWAPPED", "HALFWAY"),
                valid.replace("\"ab", "\"AB"),
                valid.replace(":120,", ":-1,"),
                valid.replace(":120,", ":1.5,"),
                valid.replace(":120,", ":\"120\","),
                valid.replace(":true,\"safety", ":1,\"safety"),
                valid.replace(",\"sha_verified\":true", ""),
                valid.removeSuffix("}") + ",\"extra\":1}",
                valid.removeSuffix("}") + ",\"state\":\"DONE\"}",
                " ".repeat(5_000) + valid,
            )
        for (text in broken) {
            files.journal.writeText(text)
            assertEquals(text.take(60), RestoreJournalRead.Unreadable, store.read())
        }
    }

    @Test
    fun `an unreadable journal is set aside and deleting is the last step`() {
        files.journal.apply { requireNotNull(parentFile).mkdirs() }.writeText("{")

        store.setAsideUnreadable()

        assertFalse(files.journal.exists())
        assertEquals("{", files.journalUnreadable.readText())
        assertEquals(RestoreJournalRead.Absent, store.read())
        store.write(journal)
        store.delete()
        assertFalse(files.journal.exists())
        assertTrue(files.journalUnreadable.exists())
    }

    @Test
    fun `a journal with impossible values cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) { journal.copy(expectedSha256 = "x") }
        assertThrows(IllegalArgumentException::class.java) { journal.copy(expectedChangeSeq = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            journal.copy(expectedRecordCount = -1)
        }
    }
}
