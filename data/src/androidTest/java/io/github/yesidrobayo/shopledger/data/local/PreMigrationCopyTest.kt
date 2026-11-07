package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.RescueCause
import io.github.yesidrobayo.shopledger.data.local.migration.MigrationSnapshot
import io.github.yesidrobayo.shopledger.data.local.migration.TEMPLATE_MIGRATIONS
import io.github.yesidrobayo.shopledger.data.local.migration.TemplateDatabase
import io.github.yesidrobayo.shopledger.data.local.migration.openTemplate
import io.github.yesidrobayo.shopledger.data.local.migration.seedVersion1
import io.github.yesidrobayo.shopledger.data.local.migration.snapshot
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La copia previa a migrar: existe antes de que Room migre, en `pre-migration/candidate.db`, entera,
 * en modo `DELETE` y con la versión de la base; una copia buena no la pisa una ya migrada; sin
 * espacio no se migra y se sabe cuánto falta; y una copia cortada en cualquier paso no deja un
 * archivo final a medias ni impide el arranque siguiente.
 *
 * Usa la base ficticia de las migraciones, con sus saltos de verdad, por la compuerta del arranque,
 * igual que la app: la base real sigue en el esquema 1 y no tiene saltos todavía.
 */
@RunWith(AndroidJUnit4::class)
class PreMigrationCopyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val file = testDatabaseFile(context, "pre-migration-copy-test.db")
    private val noBackupDirectory = File(context.noBackupFilesDir, "pre-migration-copy-test")
    private val files = DatabaseFiles(file, noBackupDirectory)
    private val candidate = files.preMigrationCandidate
    private val temporary = files.preMigrationTemporary
    private val log = MemoryLog()
    private val opened = mutableListOf<TemplateDatabase>()

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = file,
            driver = BundledSQLiteDriver(),
            databaseClass = TemplateDatabase::class,
        )

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
        noBackupDirectory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        deleteDatabaseFiles(file)
        noBackupDirectory.deleteRecursively()
    }

    @Test
    fun theCopyExistsBeforeMigratingWithEveryRowOfThePreviousVersion() = runBlocking {
        val before = createSeeded(version = 1)

        assertTrue(gate().pass(GateCaller.APP_IN_FOREGROUND) is GateOutcome.Opened)

        // La base migró, y la copia quedó en la versión de antes, con las mismas filas.
        assertEquals(3L, inspectWithoutRoom(file) { it.longValue("PRAGMA user_version") })
        assertEquals(before, inspectWithoutRoom(candidate) { it.snapshot() })
        assertEquals(
            listOf("ok"),
            inspectWithoutRoom(candidate) {
                it.texts("PRAGMA integrity_check")
            },
        )
        assertEquals(1, fileUserVersion(candidate))
        // En modo DELETE, como toda copia legítima: los bytes 18 y 19 valen 1.
        val header = candidate.readBytes().copyOf(20)
        assertEquals(1, header[18].toInt())
        assertEquals(1, header[19].toInt())
        assertNoTemporary()
        val copied = log.lines.first()
        assertEquals(LogLevel.INFO, copied.first)
        assertTrue(
            copied.second,
            copied.second.startsWith("Copia previa guardada: versión=1, bytes="),
        )
    }

    @Test
    fun aCopyThatIsNotOlderThanTheDatabaseIsKept() = runBlocking {
        createSeeded(version = 1)
        // Una copia de una migración anterior que falló: la base sigue en la 1, y la copia también.
        assertTrue(gate().pass(GateCaller.APP_IN_FOREGROUND) is GateOutcome.Opened)
        val kept = candidate.readBytes()
        opened.forEach { it.close() }
        opened.clear()
        deleteDatabaseFiles(file)
        createSeeded(version = 1)
        log.clear()

        assertTrue(gate().pass(GateCaller.APP_IN_FOREGROUND) is GateOutcome.Opened)

        assertArrayEquals(kept, candidate.readBytes())
        assertEquals(LogLevel.INFO to "Copia previa conservada: versión=1", log.lines.first())
    }

    @Test
    fun anAlreadyMigratedDatabaseNeverReplacesTheGoodCopy() = runBlocking {
        // La copia buena está en la 1, y la base ya migró a la 2. Una app que llega a la 3 la
        // reemplaza, porque es menor que la base: así se conserva solo la última copia previa.
        writeCopyAtVersion(1)
        createSeeded(version = 2)

        assertTrue(gate().pass(GateCaller.APP_IN_FOREGROUND) is GateOutcome.Opened)

        assertEquals(2, fileUserVersion(candidate))
    }

    @Test
    fun aCopyFromTheSameVersionIsNotReplacedByAMigratedOne() = runBlocking {
        // La copia está en la 2 y la base, que una migración anterior dejó en la 2, también.
        writeCopyAtVersion(2)
        val good = candidate.readBytes()
        createSeeded(version = 2)

        assertTrue(gate().pass(GateCaller.APP_IN_FOREGROUND) is GateOutcome.Opened)

        assertArrayEquals(good, candidate.readBytes())
    }

    @Test
    fun anUnreadableCopyIsReplaced() = runBlocking {
        candidate.parentFile?.mkdirs()
        candidate.writeText("no es una base de prueba")
        val before = createSeeded(version = 1)

        assertTrue(gate().pass(GateCaller.APP_IN_FOREGROUND) is GateOutcome.Opened)

        assertEquals(before, inspectWithoutRoom(candidate) { it.snapshot() })
    }

    @Test
    fun withoutSpaceNothingIsCopiedOrMigratedAndTheMissingBytesAreKnown() = runBlocking {
        val before = createSeeded(version = 1)
        val usable = 1_000L
        val copy = VacuumPreMigrationCopy(files, log, usableBytes = { usable })

        val outcome = gate(copy).pass(GateCaller.APP_IN_FOREGROUND)

        val pageBytes =
            inspectWithoutRoom(file) {
                it.longValue("PRAGMA page_size") *
                    (it.longValue("PRAGMA page_count") - it.longValue("PRAGMA freelist_count"))
            }
        val required = pageBytes + file.length() + File("${file.path}-wal").length() + MARGIN_BYTES
        assertEquals(
            GateOutcome.Rescue(RescueCause.NotEnoughSpace(required - usable)),
            outcome,
        )
        assertEquals(before, inspectWithoutRoom(file) { it.snapshot() })
        assertFalse(candidate.exists())
        assertNoTemporary()
        assertEquals(
            LogLevel.ERROR to "Sin espacio para la copia previa, no se migra: versión=1, app=3, " +
                "faltan_bytes=${required - usable}",
            log.lines.first(),
        )
    }

    @Test
    fun theLeftoverOfACopyThatDidNotFinishFreesItsSpace() = runBlocking {
        createSeeded(version = 1)
        writeLeftoverTemporary()
        val leftover = temporary.length() + File("${temporary.path}-journal").length()
        // Sin espacio libre, lo que falta es lo que pide menos el temporal que va a borrar.
        val probe =
            VacuumPreMigrationCopy(files, log, usableBytes = { 0L }).prepare(databaseVersion = 1)
        val required = (probe as PreMigrationResult.NotEnoughSpace).missingBytes + leftover
        val copy = VacuumPreMigrationCopy(files, log, usableBytes = { required - leftover })

        assertEquals(PreMigrationResult.Ready, copy.prepare(databaseVersion = 1))

        assertTrue(candidate.isFile)
        assertNoTemporary()
    }

    @Test
    fun aCopyCutAfterAnyStepLeavesNoFinalFileAndTheNextStartMigrates() = runBlocking {
        for (step in PreMigrationStep.entries) {
            deleteDatabaseFiles(file)
            noBackupDirectory.deleteRecursively()
            writeCopyAtVersion(1)
            val older = candidate.readBytes()
            val before = createSeeded(version = 2)
            val cut =
                VacuumPreMigrationCopy(files, log) { done ->
                    if (done == step) throw SimulatedDeath()
                }

            // La muerte simulada no es una excepción común: nada la atrapa, como al matar el
            // proceso. Nada borra el temporal ni cierra lo que quedó.
            assertThrows(SimulatedDeath::class.java) {
                runBlocking { gate(cut).pass(GateCaller.APP_IN_FOREGROUND) }
            }

            // La base no migró. Antes del renombrado sigue la copia anterior, entera; después, la
            // nueva, entera.
            assertEquals(step.name, before, inspectWithoutRoom(file) { it.snapshot() })
            if (step == PreMigrationStep.RENAMED) {
                assertEquals(step.name, 2, fileUserVersion(candidate))
            } else {
                assertArrayEquals(step.name, older, candidate.readBytes())
            }

            // El arranque siguiente borra el temporal, copia y migra.
            assertTrue(step.name, gate().pass(GateCaller.APP_IN_FOREGROUND) is GateOutcome.Opened)
            assertEquals(
                step.name,
                3L,
                inspectWithoutRoom(file) {
                    it.longValue("PRAGMA user_version")
                },
            )
            assertEquals(step.name, before, inspectWithoutRoom(candidate) { it.snapshot() })
            assertNoTemporary()
            opened.forEach { it.close() }
            opened.clear()
        }
    }

    @Test
    fun aCopyThatFailsItsCheckIsDeletedAndNothingMigrates() = runBlocking {
        val before = createSeeded(version = 1)
        // El temporal se daña justo después de copiar, antes de comprobarlo.
        val damaging =
            VacuumPreMigrationCopy(files, log) { done ->
                if (done == PreMigrationStep.COPIED) temporary.writeText("copia dañada de prueba")
            }

        val outcome = gate(damaging).pass(GateCaller.APP_IN_FOREGROUND)

        assertEquals(GateOutcome.Rescue(RescueCause.PreMigrationCopyFailed), outcome)
        assertEquals(before, inspectWithoutRoom(file) { it.snapshot() })
        assertFalse(candidate.exists())
        assertNoTemporary()
    }

    @Test
    fun theVersionOfAFileThatIsNotADatabaseIsZero() {
        val other = File(noBackupDirectory, "other").apply {
            parentFile?.mkdirs()
            writeText("SQLite format 2")
        }
        assertEquals(0, fileUserVersion(other))
        assertEquals(0, fileUserVersion(File(noBackupDirectory, "does-not-exist")))
    }

    private fun gate(
        copy: PreMigrationCopy = VacuumPreMigrationCopy(files, log),
    ): DatabaseGate<TemplateDatabase> = DatabaseGate(
        files = files,
        schemaVersion = TemplateDatabase.LATEST_VERSION,
        ioContext = Dispatchers.IO,
        log = log,
        preMigrationCopy = copy,
    ) { target ->
        val database = openTemplate(context, target, TEMPLATE_MIGRATIONS)
        try {
            database.useReaderConnection { it.longValue("PRAGMA user_version") }
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
        database.also { opened += it }
    }

    /** Crea la base ficticia en [version] con sus filas de prueba, y devuelve lo que guardó. */
    private suspend fun createSeeded(version: Int): MigrationSnapshot {
        helper.createDatabase(version).use { it.seedVersion1() }
        return inspectWithoutRoom(file) { it.snapshot() }
    }

    /** Deja en `candidate.db` una copia previa en [version], hecha por el mismo camino. */
    private suspend fun writeCopyAtVersion(version: Int) {
        createSeeded(version)
        assertEquals(
            PreMigrationResult.Ready,
            VacuumPreMigrationCopy(files, log).prepare(databaseVersion = version),
        )
        deleteDatabaseFiles(file)
        log.clear()
    }

    /** Lo que deja un proceso muerto a mitad de `VACUUM INTO`: un temporal truncado y su diario. */
    private fun writeLeftoverTemporary() {
        temporary.parentFile?.mkdirs()
        temporary.writeBytes(ByteArray(LEFTOVER_BYTES) { 7 })
        File("${temporary.path}-journal").writeBytes(ByteArray(LEFTOVER_BYTES) { 3 })
    }

    private fun assertNoTemporary() {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            assertFalse(suffix, File(temporary.path + suffix).exists())
        }
    }

    /** Un corte que nada atrapa, como la muerte del proceso. */
    private class SimulatedDeath : Error("Corte a propósito")

    private class MemoryLog : EventLog {
        private val written = mutableListOf<Pair<LogLevel, String>>()

        val lines: List<Pair<LogLevel, String>> get() = synchronized(written) { written.toList() }

        fun clear() = synchronized(written) { written.clear() }

        override fun write(level: LogLevel, line: LogLine) {
            synchronized(written) { written += level to line.text }
        }
    }

    private companion object {
        const val MARGIN_BYTES = 50L * 1024L * 1024L
        const val LEFTOVER_BYTES = 64 * 1024
    }
}
