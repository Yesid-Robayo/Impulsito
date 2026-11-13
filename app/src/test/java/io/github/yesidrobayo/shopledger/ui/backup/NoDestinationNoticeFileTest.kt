package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.rule.backup.NoDestinationAcknowledgement
import java.io.File
import java.io.IOException
import kotlin.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El archivo de la aceptación del aviso sin destino, en una carpeta del PC: lo escrito se lee
 * igual, lo que no se entiende vale como ninguna aceptación, y borrarlo no falla si no había nada.
 */
class NoDestinationNoticeFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val directory by lazy { folder.newFolder("no_backup") }
    private val file by lazy { NoDestinationNoticeFile({ directory }) }
    private val accepted =
        NoDestinationAcknowledgement(Instant.fromEpochMilliseconds(1_794_582_000_000L), 120L)

    @Test
    fun withoutAFileNothingWasAccepted() {
        assertNull(file.read())
    }

    @Test
    fun aWrittenAcceptanceReadsBackAndLeavesNoTemporary() {
        file.write(accepted)

        assertEquals(accepted, file.read())
        assertEquals("1 1794582000000 120\n", File(directory, "no-destination-notice").readText())
        assertArrayEquals(arrayOf("no-destination-notice"), directory.list())
    }

    @Test
    fun anotherAcceptanceReplacesTheFirst() {
        file.write(accepted)
        val later =
            NoDestinationAcknowledgement(Instant.fromEpochMilliseconds(1_795_000_000_000L), 7L)

        file.write(later)

        assertEquals(later, file.read())
    }

    @Test
    fun clearingRemovesItAndDoesNotFailWithoutAFile() {
        file.clear()
        file.write(accepted)

        file.clear()

        assertNull(file.read())
        assertTrue(directory.list().isNullOrEmpty())
    }

    @Test
    fun whatIsNotTheExpectedLineCountsAsNoAcceptance() {
        val target = File(directory, "no-destination-notice")
        val broken =
            listOf("", "1 5", "2 1794582000000 120", "1 -5 120", "1 5 -1", "1 x 120", "<html>")
        for (text in broken) {
            target.writeText(text)
            assertNull(text, file.read())
        }
        target.writeText("1 1794582000000 120" + " ".repeat(64))
        assertNull(file.read())
    }

    @Test(expected = IOException::class)
    fun aFolderInPlaceOfTheFileCannotBeCleared() {
        File(directory, "no-destination-notice").apply { mkdirs() }
        File(directory, "no-destination-notice/inside").writeText("x")

        file.clear()
    }
}
