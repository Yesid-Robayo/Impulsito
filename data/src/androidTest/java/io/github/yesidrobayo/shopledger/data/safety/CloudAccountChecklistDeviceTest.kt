package io.github.yesidrobayo.shopledger.data.safety

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountKind
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountMarks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La lista de la cuenta en el teléfono: su propio archivo, escrito con temporal y renombrado, sin
 * cuenta ni fecha, y lo que pasa con un archivo que no se entiende o que no se puede escribir.
 * Nunca toca `consents.json`.
 */
@RunWith(AndroidJUnit4::class)
class CloudAccountChecklistDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "account-checklist-test")
    private val saved = File(directory, CloudAccountChecklistFile.FILE_NAME)
    private val file = CloudAccountChecklistFile({ directory }, Dispatchers.IO)

    @Before
    fun clean() {
        directory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun withoutAFileNothingIsMarked() = runBlocking {
        assertNull(file.load())
    }

    @Test
    fun savingWritesOnlyTheKindAndTheMarksAndReadsThemBack() = runBlocking {
        val marks =
            CloudAccountMarks(
                CloudAccountKind.WEBDAV,
                setOf(CloudAccountCheck.TWO_STEP, CloudAccountCheck.RECOVERY_PHONE),
            )

        assertTrue(file.save(marks))

        assertEquals(marks, file.load())
        val root = JSONObject(saved.readText())
        assertEquals(2, root.length())
        assertEquals("WEBDAV", root.getString(CloudAccountChecklistFile.KIND))
        assertEquals(2, root.getJSONArray(CloudAccountChecklistFile.CHECKED).length())
        // El temporal ya se renombró, y la aceptación de la política no se tocó.
        assertFalse(File(directory, saved.name + ".tmp").exists())
        assertFalse(File(directory, PolicyConsentFile.FILE_NAME).exists())
    }

    @Test
    fun savingAgainReplacesTheWholeList() = runBlocking {
        file.save(CloudAccountMarks(CloudAccountKind.WEBDAV, setOf(CloudAccountCheck.TWO_STEP)))

        val next = CloudAccountMarks(CloudAccountKind.DOCUMENT_SLOTS, emptySet())
        assertTrue(file.save(next))

        assertEquals(next, file.load())
    }

    @Test
    fun aFileThatIsNotUnderstoodCountsAsNothingMarked() = runBlocking {
        directory.mkdirs()
        for (content in listOf(
            "no es json",
            """{"kind":"OTHER","checked":["TWO_STEP"]}""",
            """{"kind":"WEBDAV"}""",
            """["WEBDAV"]""",
        )) {
            saved.writeText(content)
            assertNull(content, file.load())
        }
    }

    @Test
    fun anUnknownPointIsIgnoredAndNeverMarksAnother() = runBlocking {
        directory.mkdirs()
        saved.writeText("""{"kind":"DOCUMENT_SLOTS","checked":["TWO_STEP","FUTURE",3]}""")

        assertEquals(
            CloudAccountMarks(CloudAccountKind.DOCUMENT_SLOTS, setOf(CloudAccountCheck.TWO_STEP)),
            file.load(),
        )
    }

    @Test
    fun aFileThatIsTooLargeIsNotRead() = runBlocking {
        directory.mkdirs()
        saved.writeText("""{"kind":"WEBDAV","checked":[]}""" + " ".repeat(8 * 1024))

        assertNull(file.load())
    }

    @Test
    fun whenTheFolderCannotBeWrittenNothingIsSaved() = runBlocking<Unit> {
        // Un archivo donde iría la carpeta: no se puede crear ni escribir dentro.
        directory.parentFile?.mkdirs()
        directory.writeText("ocupado")

        val marks = CloudAccountMarks(CloudAccountKind.WEBDAV, setOf(CloudAccountCheck.TWO_STEP))
        assertFalse(file.save(marks))
        assertNull(file.load())
        directory.delete()
    }
}
