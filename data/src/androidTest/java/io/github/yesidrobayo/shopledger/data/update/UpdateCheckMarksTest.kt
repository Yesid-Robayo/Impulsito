package io.github.yesidrobayo.shopledger.data.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.update.KnownUpdate
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun theDailyCheckIsOnUntilTurnedOff() = runBlocking {
        assertTrue(marks.read().automatic)

        assertTrue(marks.setAutomatic(false))
        assertFalse(marks.read().automatic)

        assertTrue(marks.setAutomatic(true))
        assertTrue(marks.read().automatic)
    }

    @Test
    fun theNotifiedAndKnownVersionsAreKeptWithTheDates() = runBlocking {
        val checked = Instant.parse("2026-11-18T10:00:00Z")

        assertTrue(marks.markCheck(checked))
        assertTrue(marks.markNotified(8))
        assertTrue(marks.setKnown(KnownUpdate(8, "1.0.8")))

        val read = marks.read()
        assertEquals(checked, read.lastCheckAt)
        assertEquals(8L, read.lastNotifiedVersionCode)
        assertEquals(KnownUpdate(8, "1.0.8"), read.known)

        assertTrue(marks.setKnown(null))
        assertNull(marks.read().known)
        assertEquals(8L, marks.read().lastNotifiedVersionCode)
    }

    @Test
    fun aStrangeKnownVersionOrSwitchIsIgnored() = runBlocking {
        file.writeText(
            """{"automatic":"no","known_version_code":8,"known_version_name":"${"x".repeat(
                65,
            )}"}""",
        )
        val read = marks.read()
        assertTrue(read.automatic)
        assertNull(read.known)

        file.writeText("""{"known_version_code":-1,"known_version_name":"1.0.8"}""")
        assertNull(marks.read().known)
    }
}
