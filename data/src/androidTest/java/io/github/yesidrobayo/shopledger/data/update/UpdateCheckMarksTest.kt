package io.github.yesidrobayo.shopledger.data.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las dos fechas de la búsqueda de versión en el teléfono: cada una se anota sin borrar la otra,
 * y un archivo dañado o enorme es como no haber buscado nunca, sin fallar.
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckMarksTest {
    private val folder =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "marks-test")
    private val marks = UpdateCheckMarks({ folder }, Dispatchers.IO)
    private val file = File(folder, UpdateCheckMarks.FILE_NAME)

    @Before
    fun cleanFolder() {
        folder.deleteRecursively()
        assertTrue(folder.mkdirs())
    }

    @After
    fun removeFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun withoutTheFileNothingHappenedYet() = runBlocking {
        assertEquals(UpdateCheckMarks.Marks.NONE, marks.read())
    }

    @Test
    fun eachDateIsKeptWithoutErasingTheOther() = runBlocking {
        val checked = Instant.parse("2026-11-01T10:00:00Z")
        val reminded = Instant.parse("2026-11-10T08:00:00Z")

        assertTrue(marks.markCheck(checked))
        assertTrue(marks.markReminder(reminded))

        assertEquals(UpdateCheckMarks.Marks(checked, reminded), marks.read())
        assertTrue(File(folder, UpdateCheckMarks.FILE_NAME + ".tmp").exists().not())
    }

    @Test
    fun aDamagedOrHugeFileIsLikeNeverHavingChecked() = runBlocking {
        file.writeText("{ esto no es")
        assertEquals(UpdateCheckMarks.Marks.NONE, marks.read())

        file.writeText("""{"last_check_at":"ayer","last_reminder_at":-5}""")
        assertEquals(UpdateCheckMarks.Marks.NONE, marks.read())

        file.writeText(" ".repeat(8 * 1024))
        assertEquals(UpdateCheckMarks.Marks.NONE, marks.read())
    }

    @Test
    fun aDamagedFileIsReplacedByTheNextMark() = runBlocking {
        file.writeText("{ esto no es")
        val checked = Instant.parse("2026-11-01T10:00:00Z")

        assertTrue(marks.markCheck(checked))

        assertEquals(UpdateCheckMarks.Marks(checked, null), marks.read())
    }
}
