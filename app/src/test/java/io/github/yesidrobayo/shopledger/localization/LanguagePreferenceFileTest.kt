package io.github.yesidrobayo.shopledger.localization

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El archivo de idioma en una carpeta del PC. La sincronización de la carpeta es del sistema, así
 * que aquí solo se cuenta: en el teléfono la prueban las pruebas en dispositivo.
 */
class LanguagePreferenceFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val synced = mutableListOf<File>()
    private val directory by lazy { folder.newFolder("no_backup") }
    private val file by lazy { LanguagePreferenceFile({ directory }, { synced += it }) }

    @Test
    fun withoutAFileNothingWasChosen() {
        assertEquals(LanguageRead.Missing, file.read())
        assertEquals(UiLanguage.System, file.read().language)
    }

    @Test
    fun aWrittenLanguageReadsBack() {
        file.write(chosen("pt"))

        assertEquals(LanguageRead.Found(chosen("pt")), file.read())
        assertEquals("pt\n", File(directory, "ui-language").readText())
    }

    @Test
    fun systemIsWrittenAsItsWord() {
        file.write(chosen("es"))
        file.write(UiLanguage.System)

        assertEquals(LanguageRead.Found(UiLanguage.System), file.read())
        assertEquals("system\n", File(directory, "ui-language").readText())
    }

    @Test
    fun writingReplacesThePreviousLanguageAndLeavesNoTemporary() {
        file.write(chosen("es"))
        file.write(chosen("pt"))

        assertEquals(LanguageRead.Found(chosen("pt")), file.read())
        assertArrayEquals(arrayOf("ui-language"), directory.list())
    }

    @Test
    fun theFolderIsSyncedAfterEachWrite() {
        file.write(chosen("es"))
        file.write(UiLanguage.System)

        assertEquals(listOf(directory, directory), synced)
    }

    @Test
    fun aMissingFolderIsCreated() {
        val missing = File(folder.root, "created")
        val inMissing = LanguagePreferenceFile({ missing }, { synced += it })

        inMissing.write(chosen("es"))

        assertEquals(LanguageRead.Found(chosen("es")), inMissing.read())
    }

    @Test
    fun aFileThatIsNotALanguageIsInvalid() {
        File(directory, "ui-language").writeText("<html>")

        assertEquals(LanguageRead.Invalid, file.read())
        assertEquals(UiLanguage.System, file.read().language)
    }

    @Test
    fun aFileLongerThanATagIsInvalid() {
        File(
            directory,
            "ui-language",
        ).writeText("es" + " ".repeat(LanguagePreferenceFile.MAX_BYTES))

        assertEquals(LanguageRead.Invalid, file.read())
    }

    @Test
    fun aFileOfExactlyTheLimitIsRead() {
        val padded = "es" + " ".repeat(LanguagePreferenceFile.MAX_BYTES - 2)
        File(directory, "ui-language").writeText(padded)

        assertEquals(LanguageRead.Found(chosen("es")), file.read())
    }

    @Test
    fun bytesThatAreNotTextAreInvalid() {
        File(directory, "ui-language").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))

        assertEquals(LanguageRead.Invalid, file.read())
    }

    @Test
    fun aFolderInPlaceOfTheFileCountsAsMissing() {
        File(directory, "ui-language").mkdirs()

        assertEquals(LanguageRead.Missing, file.read())
    }

    @Test
    fun aFailedWriteKeepsThePreviousLanguage() {
        file.write(chosen("es"))
        // Una carpeta con contenido en el lugar del temporal impide escribirlo.
        val blocker = File(directory, LanguagePreferenceFile.TEMPORARY_NAME).apply { mkdirs() }
        File(blocker, "inside").writeText("x")

        val failed = runCatching { file.write(chosen("pt")) }.exceptionOrNull()

        assertTrue(failed is IOException)
        assertEquals(LanguageRead.Found(chosen("es")), file.read())
        assertEquals(listOf(directory), synced)
    }

    @Test
    fun aFailedFolderSyncIsReportedAndLeavesNoTemporary() {
        val failing =
            LanguagePreferenceFile({ directory }, { throw IOException("sin sincronizar") })

        val failed = runCatching { failing.write(chosen("es")) }.exceptionOrNull()

        assertTrue(failed is IOException)
        assertFalse(File(directory, LanguagePreferenceFile.TEMPORARY_NAME).exists())
    }

    private fun chosen(tag: String): UiLanguage = requireNotNull(UiLanguage.of(tag))
}
