package io.github.yesidrobayo.shopledger.data.local

import android.os.Looper
import androidx.room3.migration.Migration
import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.RescueCause
import io.github.yesidrobayo.shopledger.data.local.migration.CheckedMigration
import io.github.yesidrobayo.shopledger.data.local.migration.MigrationSnapshot
import io.github.yesidrobayo.shopledger.data.local.migration.TEMPLATE_MIGRATIONS
import io.github.yesidrobayo.shopledger.data.local.migration.TemplateDatabase
import io.github.yesidrobayo.shopledger.data.local.migration.TemplateMigration1To2
import io.github.yesidrobayo.shopledger.data.local.migration.openTemplate
import io.github.yesidrobayo.shopledger.data.local.migration.seedVersion1
import io.github.yesidrobayo.shopledger.data.local.migration.snapshot
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La compuerta del arranque ante una base de una versión anterior: la app copia y migra, fuera del
 * hilo principal; un trabajo espera sin abrir Room; y una migración que falla, o una copia que no
 * se hizo, llevan al rescate con la base intacta.
 *
 * La base real sigue en el esquema 1, así que la compuerta se prueba con la base ficticia de las
 * migraciones, que tiene tres versiones y sus saltos de verdad, abierta con Room por el mismo camino
 * que la app. Los casos con la base real están en `DataGraphTest`.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseGateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val file = testDatabaseFile(context, "database-gate-test.db")
    private val markerDirectory = File(context.noBackupFilesDir, "database-gate-test")
    private val files = DatabaseFiles(file, markerDirectory)
    private val log = MemoryLog()
    private val steps = mutableListOf<String>()
    private val opened = mutableListOf<TemplateDatabase>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }

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
        markerDirectory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        executor.shutdown()
        deleteDatabaseFiles(file)
        markerDirectory.deleteRecursively()
    }

    @Test
    fun theAppCopiesFirstAndMigratesOffTheMainThread() = runBlocking {
        val before = createSeeded(version = 1)

        val outcome = gate().pass(GateCaller.APP_IN_FOREGROUND)

        assertTrue(outcome is GateOutcome.Opened)
        // La copia, con la versión de la base, antes de que Room abra y migre, y las dos en el
        // contexto del disco.
        assertEquals(listOf("copia de 1 en $IO_THREAD", "abre en $IO_THREAD"), steps)
        inspectWithoutRoom(file) { connection ->
            val after = connection.snapshot()
            assertEquals(TemplateDatabase.LATEST_VERSION.toLong(), after.userVersion)
            assertEquals(before.products, after.products)
            assertEquals(before.movements, after.movements)
        }
        assertTrue(files.initializedMarker.isFile)
        val (level, line) = log.lines.single()
        assertEquals(LogLevel.INFO, level)
        assertTrue(line, line.startsWith("Base migrada: versión=1, app=3, duración="))
    }

    @Test
    fun aWorkerLeavesTheMigrationToTheAppWithoutOpeningRoom() = runBlocking {
        val before = createSeeded(version = 1)
        val gate = gate()

        assertEquals(GateOutcome.MigrationPending, gate.pass(GateCaller.BACKGROUND_WORK))

        // Ni copia ni Room: la base, su huella y sus guardas siguen como estaban.
        assertTrue(steps.isEmpty())
        assertEquals(before, inspectWithoutRoom(file) { it.snapshot() })
        assertFalse(files.initializedMarker.exists())
        assertEquals(
            LogLevel.INFO to "Migración pendiente, que espera a la app: versión=1, app=3",
            log.lines.single(),
        )

        // La app, en primer plano, la migra después.
        assertTrue(gate.pass(GateCaller.APP_IN_FOREGROUND) is GateOutcome.Opened)
        assertEquals(3L, inspectWithoutRoom(file) { it.longValue("PRAGMA user_version") })
    }

    @Test
    fun aFailedMigrationGoesToTheRescueAndKeepsTheVersion() = runBlocking {
        val before = createSeeded(version = 1)
        val failing =
            object : CheckedMigration(startVersion = 2) {
                override suspend fun migrateSchema(connection: SQLiteConnection) {
                    connection.execSQL("DROP TABLE stock_movements")
                    throw InjectedFailure()
                }
            }

        val outcome =
            gate(migrations = listOf(TemplateMigration1To2, failing))
                .pass(GateCaller.APP_IN_FOREGROUND)

        assertEquals(GateOutcome.Rescue(RescueCause.MigrationFailed), outcome)
        // Room revirtió la cadena entera: también el salto de 1 a 2, que había terminado.
        assertEquals(before, inspectWithoutRoom(file) { it.snapshot() })
        assertEquals(
            LogLevel.ERROR to "La migración falló, y la base sigue en su versión: versión=1, " +
                "app=3, error=" + InjectedFailure::class.qualifiedName,
            log.lines.single(),
        )
    }

    @Test
    fun withoutThePreviousCopyNothingMigrates() = runBlocking {
        val before = createSeeded(version = 2)

        val outcome =
            gate(copy = PreMigrationResult.Failed).pass(GateCaller.APP_IN_FOREGROUND)

        assertEquals(GateOutcome.Rescue(RescueCause.PreMigrationCopyFailed), outcome)
        assertEquals(listOf("copia de 2 en $IO_THREAD"), steps)
        assertEquals(before, inspectWithoutRoom(file) { it.snapshot() })
        assertEquals(
            LogLevel.ERROR to "Sin copia previa, no se migra: versión=2, app=3",
            log.lines.single(),
        )
    }

    @Test
    fun theSameVersionOpensWithoutACopy() = runBlocking {
        createSeeded(version = 3)

        assertTrue(gate().pass(GateCaller.BACKGROUND_WORK) is GateOutcome.Opened)

        assertEquals(listOf("abre en $IO_THREAD"), steps)
        assertTrue(files.initializedMarker.isFile)
        assertTrue(log.lines.isEmpty())
    }

    @Test
    fun aDatabaseFromANewerAppIsNotOpened() = runBlocking {
        val before = createSeeded(version = 3)

        val outcome = gate(schemaVersion = 2).pass(GateCaller.APP_IN_FOREGROUND)

        assertEquals(GateOutcome.Rescue(RescueCause.NewerVersion), outcome)
        assertTrue(steps.isEmpty())
        assertEquals(before, inspectWithoutRoom(file) { it.snapshot() })
    }

    @Test
    fun aNegativeVersionIsNotADatabaseOfTheApp() = runBlocking {
        BundledSQLiteDriver().open(file.path).use { it.execSQL("PRAGMA user_version = -1") }

        val outcome = gate().pass(GateCaller.APP_IN_FOREGROUND)

        assertEquals(GateOutcome.Rescue(RescueCause.OpenFailed), outcome)
        assertTrue(steps.isEmpty())
        assertEquals(
            LogLevel.ERROR to "La base no abrió: versión=-1, app=3",
            log.lines.single(),
        )
    }

    @Test
    fun aDatabaseThatRoomRefusesGoesToTheRescue() = runBlocking {
        // Una base en la versión 1 sin el salto de 1 a 2: Room se niega a abrirla.
        val before = createSeeded(version = 1)

        val outcome = gate(
            migrations = TEMPLATE_MIGRATIONS.drop(1),
        ).pass(GateCaller.APP_IN_FOREGROUND)

        assertEquals(GateOutcome.Rescue(RescueCause.MigrationFailed), outcome)
        assertEquals(before, inspectWithoutRoom(file) { it.snapshot() })
    }

    @Test
    fun aNewDatabaseThatDoesNotOpenLeavesNoMark() = runBlocking {
        val outcome =
            DatabaseGate(files, 3, executor.asCoroutineDispatcher(), log, recordingCopy()) {
                throw InjectedFailure()
            }.pass(GateCaller.APP_IN_FOREGROUND)

        assertEquals(GateOutcome.Rescue(RescueCause.OpenFailed), outcome)
        assertFalse(files.initializedMarker.exists())
        assertEquals(
            LogLevel.ERROR to "La base no abrió: error=" + InjectedFailure::class.qualifiedName,
            log.lines.single(),
        )
    }

    @Test
    fun aMarkThatCannotBeWrittenDoesNotKeepThePersonOut() = runBlocking {
        createSeeded(version = 3)
        // La carpeta de la marca es un archivo: no se puede crear la marca dentro.
        markerDirectory.parentFile?.mkdirs()
        assertTrue(markerDirectory.createNewFile())

        val outcome = gate().pass(GateCaller.APP_IN_FOREGROUND)

        assertTrue(outcome is GateOutcome.Opened)
        assertEquals(
            LogLevel.WARN to "No se pudo dejar la marca de base creada: error=" +
                IOException::class.java.name,
            log.lines.single(),
        )
    }

    @Test
    fun theVersionIsReadThroughTheWalNotFromTheHeaderBytes() {
        BundledSQLiteDriver().open(file.path).use { connection ->
            connection.execSQL("PRAGMA journal_mode = WAL")
            connection.execSQL("PRAGMA user_version = 1")
            connection.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            // Sin pasar el -wal al archivo: el cambio de versión queda solo en el -wal mientras
            // esta conexión siga abierta.
            connection.execSQL("PRAGMA wal_autocheckpoint = 0")
            connection.execSQL("PRAGMA user_version = 2")

            assertEquals(1, headerUserVersion(file))
            assertEquals(2, readUserVersion(file))
        }
    }

    @Test
    fun readingTheVersionNeverCreatesAFile() {
        assertThrows(SQLiteException::class.java) { readUserVersion(file) }

        assertFalse(file.exists())
    }

    private fun gate(
        schemaVersion: Int = TemplateDatabase.LATEST_VERSION,
        migrations: List<Migration> = TEMPLATE_MIGRATIONS,
        copy: PreMigrationResult = PreMigrationResult.Ready,
    ): DatabaseGate<TemplateDatabase> = DatabaseGate(
        files = files,
        schemaVersion = schemaVersion,
        ioContext = executor.asCoroutineDispatcher(),
        log = log,
        preMigrationCopy = recordingCopy(copy),
    ) { target ->
        steps += "abre en ${currentThreadName()}"
        val database = openTemplate(context, target, migrations)
        try {
            // La primera consulta es la que abre y migra.
            database.useReaderConnection { it.longValue("PRAGMA user_version") }
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
        database.also { opened += it }
    }

    private fun recordingCopy(result: PreMigrationResult = PreMigrationResult.Ready) =
        PreMigrationCopy { version ->
            steps += "copia de $version en ${currentThreadName()}"
            result
        }

    private fun currentThreadName(): String {
        assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
        return Thread.currentThread().name
    }

    /** Crea la base ficticia en [version] con sus filas de prueba, y devuelve lo que guardó. */
    private suspend fun createSeeded(version: Int): MigrationSnapshot {
        helper.createDatabase(version).use { it.seedVersion1() }
        return inspectWithoutRoom(file) { it.snapshot() }
    }

    /** El `user_version` tal como está en los bytes del archivo, sin abrirlo con SQLite. */
    private fun headerUserVersion(target: File): Int {
        val header = ByteArray(HEADER_BYTES)
        target.inputStream().use { input -> check(input.read(header) == HEADER_BYTES) }
        return (60 until 64).fold(0) { value, index ->
            (value shl 8) or
                (header[index].toInt() and 0xFF)
        }
    }

    /** Guarda cada línea en memoria. */
    private class MemoryLog : EventLog {
        private val written = mutableListOf<Pair<LogLevel, String>>()

        val lines: List<Pair<LogLevel, String>> get() = synchronized(written) { written.toList() }

        override fun write(level: LogLevel, line: LogLine) {
            synchronized(written) { written += level to line.text }
        }
    }

    /** Una falla a propósito, distinta de cualquiera que lance SQLite o Room. */
    private class InjectedFailure : RuntimeException("Falla a propósito")

    private companion object {
        const val IO_THREAD = "database-gate-test-io"
        const val HEADER_BYTES = 100
    }
}
