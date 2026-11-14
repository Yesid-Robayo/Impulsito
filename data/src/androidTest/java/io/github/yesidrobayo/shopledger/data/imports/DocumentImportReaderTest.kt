package io.github.yesidrobayo.shopledger.data.imports

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportLimits
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leer un documento para importar en el teléfono, con archivos inventados en la caché de la
 * prueba: el nombre que da el proveedor, el tamaño que declara y un documento que ya no está. La
 * base no participa: leer nunca la toca.
 */
@RunWith(AndroidJUnit4::class)
internal class DocumentImportReaderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    @Before
    fun createFolder() {
        folder = File(context.cacheDir, "import-test").apply { mkdirs() }
    }

    @After
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun aCsvDocumentIsReadWithTheNameItsProviderGives() = runBlocking {
        val file = File(folder, "data.csv").apply {
            writeText("\uFEFFnombre;precio\r\nArroz;1,50\r\n")
        }
        val reader =
            reader(
                facts = DocumentImportReader.DocumentFacts("productos\u202E.csv", file.length()),
            )

        val read = reader.read(Uri.fromFile(file).toString(), null, CsvSeparator.COMMA) {}

        val sheet = (read as ImportFileRead.Read).sheet
        assertEquals(listOf("nombre", "precio"), sheet.header)
        assertEquals(CsvSeparator.SEMICOLON, sheet.separator)
        assertEquals("productos.csv", read.fileName)
    }

    @Test
    fun aDocumentWhoseProviderDeclaresTooManyBytesIsRejectedWithoutOpeningIt() = runBlocking {
        // El archivo no existe: si se abriera, el resultado sería otro.
        val missing = File(folder, "missing.csv")
        val reader =
            reader(facts = DocumentImportReader.DocumentFacts("big.csv", 21L * 1024 * 1024))

        val read = reader.read(Uri.fromFile(missing).toString(), null, CsvSeparator.COMMA) {}

        assertEquals(ImportFileRead.Rejected(ImportRejection.TOO_LARGE), read)
    }

    @Test
    fun aDocumentThatLiesAboutItsSizeIsCutWhileReading() = runBlocking {
        val file = File(folder, "lies.csv").apply { writeText("a\n" + "1\n".repeat(100)) }
        val limits = ImportLimits(maxFileBytes = 50)
        val reader =
            DocumentImportReader(
                resolver = context.contentResolver,
                ioContext = Dispatchers.IO,
                limits = limits,
                factsOf = { DocumentImportReader.DocumentFacts(null, 10) },
            )

        val read = reader.read(Uri.fromFile(file).toString(), null, CsvSeparator.COMMA) {}

        assertEquals(ImportFileRead.Rejected(ImportRejection.TOO_LARGE), read)
    }

    @Test
    fun aDocumentThatIsGoneIsUnreadable() = runBlocking {
        val read =
            DocumentImportReader(context.contentResolver, Dispatchers.IO)
                .read(Uri.fromFile(File(folder, "gone.csv")).toString(), null, CsvSeparator.COMMA) {
                }

        assertEquals(ImportFileRead.Rejected(ImportRejection.UNREADABLE), read)
    }

    @Test
    fun aProviderThatDeniesTheFactsStillLetsTheReadDecide() = runBlocking {
        val file = File(folder, "denied.csv").apply { writeText("a\n1\n") }
        val reader =
            DocumentImportReader(
                resolver = context.contentResolver,
                ioContext = Dispatchers.IO,
                factsOf = { throw SecurityException("sin permiso") },
            )

        val read = reader.read(Uri.fromFile(file).toString(), null, CsvSeparator.COMMA) {}

        assertEquals(null, (read as ImportFileRead.Read).fileName)
    }

    @Test
    fun theRealProviderQueryOfAFileHasNoFactsAndTheFileIsNotCopied() = runBlocking {
        val file = File(folder, "plain.csv").apply { writeText("a\n1\n") }
        val before = folder.list()?.toSet()

        val read =
            DocumentImportReader(context.contentResolver, Dispatchers.IO)
                .read(Uri.fromFile(file).toString(), CsvSeparator.SEMICOLON, CsvSeparator.COMMA) {}

        assertEquals(null, (read as ImportFileRead.Read).fileName)
        assertEquals(before, folder.list()?.toSet())
        assertFalse(
            File(context.cacheDir, "exports").listFiles().orEmpty().any {
                it.name ==
                    "plain.csv"
            },
        )
    }

    private fun reader(facts: DocumentImportReader.DocumentFacts) = DocumentImportReader(
        resolver = context.contentResolver,
        ioContext = Dispatchers.IO,
        factsOf = { facts },
    )
}
