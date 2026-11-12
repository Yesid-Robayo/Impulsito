package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationProblem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El segundo destino en el teléfono, con la base real y una carpeta local en lugar de la del
 * selector: escribe con cualquier `link_state`, solo con cambios, conserva 3 (o 5 siguiendo a la
 * copia principal), nunca toca un archivo ajeno, y con una carpeta que falta, sin clave o con una
 * copia fatal no escribe ni poda, sin tocar el estado del respaldo. La muerte del proceso tras cada
 * paso, también dos seguidas, nunca deja una copia parcial con nombre final.
 */
@RunWith(AndroidJUnit4::class)
class SecondDestinationWriterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val phone = BackupFixture(context, "second-destination")
    private val root = File(context.cacheDir, "second-destination-test")
    private val folderDir = File(root, "carpeta")
    private val staging = File(root, "staging")
    private val store =
        SecondDestinationStore({ File(root, SecondDestinationStore.FILE_NAME) }, Dispatchers.IO)
    private var encryption = LocalEncryption.NONE
    private var forced: LocalCopy? = null
    private var dieAfter: Set<String> = emptySet()

    @Before
    fun open() = runBlocking<Unit> {
        root.deleteRecursively()
        folderDir.mkdirs()
        phone.open()
        store.save(
            SecondDestinationState(
                treeUri = "content://prueba/arbol",
                folderUri = "content://prueba/carpeta",
            ),
        )
    }

    @After
    fun close() {
        root.deleteRecursively()
        phone.close()
    }

    @Test
    fun itWritesWithoutAConnectedDestinationAndOnlyWithChanges() = runBlocking<Unit> {
        assertEquals(BackupLinkState.NOT_CONNECTED.name, phone.state().linkState)
        phone.addProduct("Pan de prueba")

        assertEquals(SecondDestinationRun.Written, writer().writeOwnCopy())

        val saved = saved()
        val names = finalNames()
        assertEquals(1, names.size)
        assertEquals(names.toSet(), saved.owned)
        assertEquals(phone.state().changeSeq, saved.lastCopySeq)
        assertTrue(saved.lastCopyAt != null)
        assertTrue("La copia es un gzip entero", isWholeGzip(File(folderDir, names.single())))
        assertTrue("Nada queda en la preparación", staging.listFiles().isNullOrEmpty())

        assertEquals(SecondDestinationRun.NoChanges, writer().writeOwnCopy())
        // El estado del respaldo no cambió: esta copia no es la del destino.
        assertEquals(null, phone.state().failureCode)
        assertTrue(phone.state().changeSeq > phone.state().verifiedSeq)
    }

    @Test
    fun anEmptyDatabaseIsNotCopied() = runBlocking<Unit> {
        assertEquals(SecondDestinationRun.Empty, writer().writeOwnCopy())
        assertTrue(finalNames().isEmpty())
    }

    @Test
    fun itKeepsThreeAndNeverTouchesAForeignFile() = runBlocking<Unit> {
        File(folderDir, "notas.txt").writeText("de la persona")
        val foreign = foreignCopyName()
        File(folderDir, foreign).writeBytes(ByteArray(10))
        repeat(5) {
            phone.addProduct("Producto $it")
            phone.advance(1.hours)
            assertEquals(SecondDestinationRun.Written, writer().writeOwnCopy())
        }

        assertEquals(3, saved().owned.size)
        assertEquals(saved().owned, finalNames().filter { it != foreign }.toSet())
        assertTrue(File(folderDir, "notas.txt").exists())
        assertTrue(File(folderDir, foreign).exists())
    }

    @Test
    fun followingTheMainCopyItKeepsFiveAtMost() = runBlocking<Unit> {
        store.save(saved().copy(followMainBackup = true))
        // Tres días con dos copias cada uno, y uno más atrás.
        repeat(7) {
            phone.addProduct("Producto $it")
            phone.advance(if (it % 2 == 0) 1.hours else 1.days)
            assertEquals(SecondDestinationRun.Written, writer().writeOwnCopy())
        }

        assertEquals(SecondDestinationProblemFree, saved().problem)
        assertTrue(saved().owned.size <= 5)
        assertEquals(saved().owned, finalNames().toSet())
    }

    @Test
    fun aMissingFolderIsSaidApartAndTouchesNothing() = runBlocking<Unit> {
        phone.addProduct("Pan de prueba")
        folderDir.deleteRecursively()

        assertEquals(
            SecondDestinationRun.Problem(SecondDestinationProblem.FOLDER_MISSING),
            writer().writeOwnCopy(),
        )
        assertEquals(SecondDestinationProblem.FOLDER_MISSING, saved().problem)
        assertEquals(null, saved().lastCopySeq)
        assertEquals(null, phone.state().failureCode)

        // La carpeta vuelve, como una tarjeta que se pone de nuevo: escribe y limpia el problema.
        folderDir.mkdirs()
        assertEquals(SecondDestinationRun.Written, writer().writeOwnCopy())
        assertEquals(null, saved().problem)
    }

    @Test
    fun withoutTheKeyNothingIsWrittenInClear() = runBlocking<Unit> {
        phone.addProduct("Pan de prueba")
        encryption = LocalEncryption.NONE.copy(active = true, keyAvailable = false)

        assertEquals(
            SecondDestinationRun.Problem(SecondDestinationProblem.KEY_UNAVAILABLE),
            writer().writeOwnCopy(),
        )
        assertTrue(finalNames().isEmpty())
    }

    @Test
    fun aFatalCopyIsNotWrittenNorPruned() = runBlocking<Unit> {
        repeat(4) {
            phone.addProduct("Producto $it")
            phone.advance(1.hours)
            writer().writeOwnCopy()
        }
        val before = finalNames().toSet()
        phone.addProduct("Uno más")
        forced = LocalCopy.Built(File(staging, "no-importa"), 1, "00", false, 5, 5, 9)

        assertEquals(
            SecondDestinationRun.Problem(SecondDestinationProblem.FATAL_SKIPPED),
            writer().writeOwnCopy(),
        )
        assertEquals(before, finalNames().toSet())
    }

    @Test
    fun theMainCopyGoesToTheFolderOnlyWhenFollowing() = runBlocking<Unit> {
        val file = File(root, "principal.db.gz").also { it.writeBytes(gzipOf(ByteArray(100))) }
        val sha = sha256(file.readBytes())

        assertEquals(
            SecondDestinationRun.NoFolder,
            writer().writeMainCopy(file, file.length(), sha, 3),
        )
        store.save(saved().copy(followMainBackup = true))
        assertEquals(
            SecondDestinationRun.Written,
            writer().writeMainCopy(file, file.length(), sha, 3),
        )
        assertEquals(3L, saved().lastCopySeq)
        // Un archivo que no coincide al releer no queda con su nombre.
        assertEquals(
            SecondDestinationRun.Problem(SecondDestinationProblem.WRITE_FAILED),
            writer().writeMainCopy(file, file.length(), "ff".repeat(32), 4),
        )
        assertEquals(1, finalNames().size)
        assertTrue(tempNames().isEmpty())
    }

    @Test
    fun deathAfterEachStepNeverLeavesAPartialCopyWithItsFinalName() = runBlocking<Unit> {
        val steps =
            listOf(
                SecondDestinationWriter.PENDING,
                SecondDestinationWriter.CREATED,
                SecondDestinationWriter.WRITTEN,
                SecondDestinationWriter.RENAMED,
                SecondDestinationWriter.RECORDED,
            )
        val cases = steps.map { setOf(it) } + steps.zipWithNext { a, b -> setOf(a, b) }
        for (case in cases) {
            root.deleteRecursively()
            folderDir.mkdirs()
            store.save(SecondDestinationState("content://prueba/arbol", "content://prueba/carpeta"))
            phone.addProduct("Producto ${case.joinToString()}")
            phone.advance(1.hours)

            // Muere en el primer paso del caso; si son dos, la siguiente ejecución muere en el otro.
            for (step in case) {
                dieAfter = setOf(step)
                try {
                    writer().writeOwnCopy()
                } catch (_: Death) {
                    Unit
                }
                assertNoPartialFinal(case)
                // Volver a abrir lleva tiempo: la copia siguiente nunca tiene el mismo instante.
                phone.advance(1.seconds)
            }
            dieAfter = emptySet()
            // Muerto tras anotar, la copia ya está entera y anotada: no hay nada más que copiar.
            val expected =
                if (case.last() == SecondDestinationWriter.RECORDED) {
                    SecondDestinationRun.NoChanges
                } else {
                    SecondDestinationRun.Written
                }
            assertEquals("$case", expected, writer().writeOwnCopy())
            assertNoPartialFinal(case)
            assertTrue("$case: sin temporales", tempNames().isEmpty())
            assertEquals("$case", saved().owned, finalNames().toSet())
        }
    }

    // Apoyo

    // Un Error, no una excepción: como la muerte real, ningún catch del código lo atrapa.
    private class Death : Error()

    private fun writer() = SecondDestinationWriter(
        store = store,
        folderFor = { DirectoryFolder(folderDir) },
        build = { directory ->
            forced ?: LocalCopyBuilder(
                snapshots = SnapshotCreator(phone.file, Dispatchers.IO),
                sealer = SnapshotSealer(Dispatchers.IO),
                validation = SnapshotValidation(CandidateValidator(Dispatchers.IO)),
                packager = BackupPackager(Dispatchers.IO),
                headGeneration = { phone.ports.backupState.get()?.headGeneration ?: 0 },
                deviceId = { DEVICE },
                clock = phone.clock,
                schemaVersion = AppDatabase.SCHEMA_VERSION,
            ).build(directory)
        },
        staging = { staging },
        state = { phone.ports.backupState.get() },
        encryption = { encryption },
        schemaVersion = 1,
        deviceId = { DEVICE },
        clock = phone.clock,
        ioContext = Dispatchers.IO,
        listener = { step -> if (step in dieAfter) throw Death() },
    )

    private suspend fun saved(): SecondDestinationState =
        (store.load() as SecondDestinationLoad.Loaded).state

    private fun finalNames(): List<String> =
        folderDir.list().orEmpty().filter { BackupFileName.parse(it) != null }

    private fun tempNames(): List<String> =
        folderDir.list().orEmpty().filter { it.endsWith(SecondDestinationWriter.TEMPORARY_SUFFIX) }

    private fun assertNoPartialFinal(case: Set<String>) {
        for (name in finalNames()) {
            assertTrue("$case: $name está entera", isWholeGzip(File(folderDir, name)))
        }
    }

    private fun isWholeGzip(file: File): Boolean = try {
        GZIPInputStream(FileInputStream(file)).use { it.readBytes() }
        true
    } catch (_: IOException) {
        false
    }

    private fun gzipOf(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun foreignCopyName(): String = BackupFileName(
        generation = 1,
        schemaVersion = 1,
        createdAt = requireNotNull(CopyInstant.parse("20201010T101010Z")),
        devicePrefix = "ffffffff",
        format = BackupFileFormat.GZIP,
    ).fileName

    /** Una carpeta local con la misma forma que la del selector: solo nombres, nunca rutas. */
    private class DirectoryFolder(private val dir: File) : SecondDestinationFolder {
        override fun list(): Map<String, Long> {
            if (!dir.isDirectory) throw FolderMissingException()
            return dir.listFiles().orEmpty().associate { it.name to it.length() }
        }

        override fun create(name: String) {
            if (!dir.isDirectory) throw FolderMissingException()
            if (!File(dir, name).createNewFile()) throw IOException("Ya existe")
        }

        override fun write(name: String, input: InputStream) {
            FileOutputStream(File(dir, name)).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }

        override fun read(name: String): InputStream = FileInputStream(File(dir, name))

        override fun rename(from: String, to: String): Boolean =
            !File(dir, to).exists() && File(dir, from).renameTo(File(dir, to))

        override fun delete(name: String): Boolean = File(dir, name).delete()
    }

    private companion object {
        const val DEVICE = "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd"

        /** Ningún problema anotado. */
        val SecondDestinationProblemFree: SecondDestinationProblem? = null
    }
}
