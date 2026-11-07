package io.github.yesidrobayo.shopledger.data

import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.PreMigrationCopy
import io.github.yesidrobayo.shopledger.data.local.PreMigrationResult
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.log.AndroidEventLog
import io.github.yesidrobayo.shopledger.data.repository.AT
import io.github.yesidrobayo.shopledger.data.transaction.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La entrada de la capa de datos: crearla no toca el disco, abre la base una sola vez y fuera del
 * hilo principal, entrega los puertos conectados a esa base, y una base que no se puede abrir sin
 * riesgo queda intacta y lleva al rescate.
 *
 * La compuerta del arranque se ve aquí con la base real: la marca de base creada, una base que
 * falta, una dañada y una de otra versión, y un trabajo que espera. Sus casos de migración, con una
 * base de varias versiones, están en `DatabaseGateTest`.
 */
@RunWith(AndroidJUnit4::class)
class DataGraphTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "data-graph-test.db")
    private val markerDirectory = File(context.noBackupFilesDir, "data-graph-test")
    private val files = DatabaseFiles(file, markerDirectory)
    private val log = MemoryLog()

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
        markerDirectory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        deleteDatabaseFiles(file)
        markerDirectory.deleteRecursively()
    }

    @Test
    fun creatingTheGraphDoesNotTouchTheDisk() {
        DataGraph(context, Dispatchers.IO, FIXED_CLOCK, { files }, log)

        assertFalse(file.exists())
        assertFalse(markerDirectory.exists())
        assertTrue(log.lines.isEmpty())
    }

    @Test
    fun theDatabasePathIsAskedOnlyWhenOpeningAndOnTheDiskContext() = runBlocking {
        // Pedir la carpeta de bases toca el disco: Android la crea si falta. Crear el grafo ocurre
        // en el hilo principal al arrancar, así que la ruta solo se pide al abrir.
        val executor = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }
        try {
            val askedFrom = mutableListOf<String>()
            val graph =
                DataGraph(
                    context,
                    executor.asCoroutineDispatcher(),
                    FIXED_CLOCK,
                    {
                        askedFrom += Thread.currentThread().name
                        files
                    },
                    log,
                )
            assertTrue(askedFrom.isEmpty())

            graph.open()
            graph.open()

            assertEquals(listOf(IO_THREAD), askedFrom)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun theAppGraphUsesTheBusinessDatabaseFile() {
        // Solo se construye: abrirla crearía la base real de la app de pruebas.
        val graph = DataGraph(context, Dispatchers.IO, FIXED_CLOCK)

        assertEquals("shopledger.db", DatabaseFiles.DATABASE_NAME)
        assertEquals(
            context.getDatabasePath(DatabaseFiles.DATABASE_NAME).absoluteFile,
            DatabaseFiles.databaseFile(context),
        )
        // La marca vive donde el sistema nunca respalda ni transfiere nada.
        val appFiles = DatabaseFiles.of(context)
        assertEquals(DatabaseFiles.databaseFile(context), appFiles.database)
        assertEquals(
            File(context.noBackupFilesDir, "db_initialized"),
            appFiles.initializedMarker,
        )
        assertTrue(graph.log is AndroidEventLog)
    }

    @Test
    fun openCreatesANewDatabaseWithItsInitialRows() = runBlocking {
        val access = graph().open()

        val ports = (access as DataAccess.Ready).ports
        assertEquals(null, ports.settings.get().profile.businessName)
        assertTrue(file.isFile)
        assertTrue(files.initializedMarker.isFile)
        assertEquals(0L, files.initializedMarker.length())
        assertEquals(LogLevel.INFO, log.lines.single().first)
        assertTrue(log.lines.single().second.startsWith("Base abierta: duración="))
    }

    @Test
    fun openTwiceGivesTheSamePorts() = runBlocking {
        val graph = graph()

        val first = graph.open() as DataAccess.Ready
        val second = graph.open() as DataAccess.Ready

        assertSame(first, second)
        assertSame(first.ports, second.ports)
        assertEquals(1, log.lines.size)
    }

    @Test
    fun concurrentOpensShareOneDatabase() = runBlocking {
        val graph = graph()

        val accesses = List(8) { async(Dispatchers.Default) { graph.open() } }.awaitAll()

        val ports = accesses.map { (it as DataAccess.Ready).ports }.toSet()
        assertEquals(1, ports.size)
        assertEquals(1, log.lines.size)
    }

    @Test
    fun theDatabaseOpensOnTheGivenContext() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }
        try {
            val io = executor.asCoroutineDispatcher()
            val writerThread = CompletableDeferred<String>()
            val graph =
                DataGraph(
                    context,
                    io,
                    FIXED_CLOCK,
                    { files },
                    object : EventLog {
                        override fun write(level: LogLevel, line: LogLine) {
                            writerThread.complete(Thread.currentThread().name)
                        }
                    },
                )

            graph.open()

            // La línea de la apertura se escribe al terminar de abrir, en el mismo contexto.
            assertEquals(IO_THREAD, withTimeout(5_000) { writerThread.await() })
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun thePortsShareTheDatabaseAndCountEachChange() = runBlocking {
        val ports = (graph().open() as DataAccess.Ready).ports
        val profile = BusinessProfile(businessName = "Tienda de prueba", BasisPoints(2_500))

        ports.transactions.write {
            ports.settings.updateProfile(profile, AT)
            TxResult.Commit(Unit)
        }

        assertEquals(profile, ports.settings.get().profile)
        // La transacción de la app contó el cambio en la fila del respaldo, en el mismo archivo.
        val changeSeq =
            inspectWithoutRoom(file) { it.longValue("SELECT change_seq FROM backup_state") }
        assertEquals(1L, changeSeq)
    }

    @Test
    fun aDatabaseFromANewerAppIsKeptAndTriedAgain() = runBlocking {
        writeNewerDatabase()
        val graph = graph()

        assertEquals(DataAccess.Rescue(RescueCause.NewerVersion), graph.open())
        assertEquals(DataAccess.Rescue(RescueCause.NewerVersion), graph.openForWork())

        val newer = AppDatabase.SCHEMA_VERSION + 1
        val line =
            "La base es de una versión más nueva de la app, y no se abre: versión=$newer, " +
                "app=${AppDatabase.SCHEMA_VERSION}"
        assertEquals(List(2) { LogLevel.ERROR to line }, log.lines)
        val kept =
            inspectWithoutRoom(file) { connection ->
                connection.longValue("SELECT count(*) FROM notes") to
                    connection.longValue("PRAGMA user_version")
            }
        assertEquals(NOTE_ROWS to newer.toLong(), kept)

        // Con la base ajena fuera, y sin la marca de que existió, la siguiente apertura vuelve a
        // intentarlo y crea la suya.
        deleteDatabaseFiles(file)
        assertTrue(graph.open() is DataAccess.Ready)
    }

    @Test
    fun filesThatCannotBeAskedGoToTheRescue() = runBlocking {
        val graph =
            DataGraph(
                context,
                Dispatchers.IO,
                FIXED_CLOCK,
                { throw IllegalStateException("Falla a propósito") },
                log,
            )

        assertEquals(DataAccess.Rescue(RescueCause.OpenFailed), graph.open())
        assertEquals(
            LogLevel.ERROR to "La base no abrió: error=java.lang.IllegalStateException",
            log.lines.single(),
        )
    }

    @Test
    fun aDamagedDatabaseGoesToTheRescueUntouched() = runBlocking {
        val damaged = "esto no es una base de datos de prueba ".repeat(200).toByteArray()
        file.writeBytes(damaged)
        files.markInitialized()
        val graph = graph()

        assertEquals(DataAccess.Rescue(RescueCause.OpenFailed), graph.open())

        assertArrayEquals(damaged, file.readBytes())
        assertEquals(
            LogLevel.ERROR to "La base no abrió: error=" + SQLiteException::class.java.name,
            log.lines.single(),
        )
    }

    @Test
    fun aMissingDatabaseThatExistedBeforeIsNeverCreatedAgain() = runBlocking {
        files.markInitialized()
        val graph = graph()

        assertEquals(DataAccess.Rescue(RescueCause.DatabaseMissing), graph.open())
        assertEquals(DataAccess.Rescue(RescueCause.DatabaseMissing), graph.openForWork())

        // Room no se abrió: no hay base, ni vacía, ni sus archivos auxiliares.
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            assertFalse(File(file.path + suffix).exists())
        }
        assertEquals(
            List(2) { LogLevel.ERROR to "Falta la base, que ya había sido creada, y no se abre" },
            log.lines,
        )
    }

    @Test
    fun anEmptyFileWhereTheBusinessWasGoesToTheRescue() = runBlocking {
        files.markInitialized()
        assertTrue(file.createNewFile())

        assertEquals(DataAccess.Rescue(RescueCause.DatabaseMissing), graph().open())

        // Room no creó su esquema en el archivo vacío.
        assertEquals(0L, file.length())
    }

    @Test
    fun anEmptyFileOfACreationThatDidNotFinishIsCreatedAgain() = runBlocking {
        // Sin la marca, un archivo vacío es una primera creación que no terminó.
        assertTrue(file.createNewFile())

        assertTrue(graph().open() is DataAccess.Ready)

        assertTrue(files.initializedMarker.isFile)
    }

    @Test
    fun theMarkIsLeftWhenTheProcessDiedBeforeWritingIt() = runBlocking {
        // La base se creó, y el proceso murió antes de dejar la marca.
        AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK).close()
        assertFalse(files.initializedMarker.exists())

        assertTrue(graph().open() is DataAccess.Ready)

        assertTrue(files.initializedMarker.isFile)
    }

    @Test
    fun aWorkerNeverMigratesAndTheAppDoesNotWithoutItsCopy() = runBlocking {
        AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK).close()
        files.markInitialized()
        // La app de la prueba compila una versión más: la base quedó en la anterior.
        val graph = graph(schemaVersion = AppDatabase.SCHEMA_VERSION + 1)

        assertEquals(WorkAccess.MigrationPending, graph.openForWork())
        // Hoy la app todavía no hace la copia previa, así que no migra.
        assertEquals(DataAccess.Rescue(RescueCause.PreMigrationCopyFailed), graph.open())

        val versions =
            "versión=${AppDatabase.SCHEMA_VERSION}, app=${AppDatabase.SCHEMA_VERSION + 1}"
        assertEquals(
            listOf(
                LogLevel.INFO to "Migración pendiente, que espera a la app: $versions",
                LogLevel.ERROR to "Sin copia previa, no se migra: $versions",
            ),
            log.lines,
        )
        assertEquals(
            AppDatabase.SCHEMA_VERSION.toLong(),
            inspectWithoutRoom(file) { it.longValue("PRAGMA user_version") },
        )
    }

    @Test
    fun aWorkerWaitsWhileTheAppCopiesAndThenGetsTheSamePorts() = runBlocking {
        AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK).close()
        files.markInitialized()
        val copyStarted = CompletableDeferred<Unit>()
        val releaseCopy = CompletableDeferred<Unit>()
        // La copia de la prueba espera a que la suelten. Después Room abre la base, que ya está en
        // la versión que compila: así la prueba ve la espera sin tener una segunda versión.
        val graph =
            graph(schemaVersion = AppDatabase.SCHEMA_VERSION + 1) {
                PreMigrationCopy {
                    copyStarted.complete(Unit)
                    releaseCopy.await()
                    PreMigrationResult.Ready
                }
            }

        val app = async(Dispatchers.Default) { graph.open() }
        withTimeout(WAIT_MILLIS) { copyStarted.await() }
        val worker = async(Dispatchers.Default) { graph.openForWork() }
        delay(SETTLE_MILLIS)
        assertFalse("El trabajo pasó mientras la app copiaba", worker.isCompleted)

        releaseCopy.complete(Unit)
        val opened = withTimeout(WAIT_MILLIS) { app.await() } as DataAccess.Ready

        assertSame(opened, withTimeout(WAIT_MILLIS) { worker.await() })
    }

    private fun graph(
        schemaVersion: Int = AppDatabase.SCHEMA_VERSION,
        preMigrationCopy: (DatabaseFiles) -> PreMigrationCopy = {
            PreMigrationCopy { PreMigrationResult.Failed }
        },
    ): DataGraph = DataGraph(
        context,
        Dispatchers.IO,
        FIXED_CLOCK,
        { files },
        log,
        schemaVersion,
        preMigrationCopy,
    )

    /** Una base de una versión más nueva de la app, con sus filas: Room no la sabe abrir. */
    private fun writeNewerDatabase() {
        BundledSQLiteDriver().open(file.path).use { connection ->
            connection.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
            repeat(NOTE_ROWS.toInt()) { row ->
                connection.execSQL("INSERT INTO notes (body) VALUES ('nota de prueba $row')")
            }
            connection.execSQL("PRAGMA user_version = ${AppDatabase.SCHEMA_VERSION + 1}")
        }
    }

    /** Guarda cada línea en memoria. Puede escribirse desde varios hilos a la vez. */
    private class MemoryLog : EventLog {
        private val written = mutableListOf<Pair<LogLevel, String>>()

        val lines: List<Pair<LogLevel, String>> get() = synchronized(written) { written.toList() }

        override fun write(level: LogLevel, line: LogLine) {
            synchronized(written) { written += level to line.text }
        }
    }

    private companion object {
        const val IO_THREAD = "data-graph-test-io"
        const val NOTE_ROWS = 3L
        const val WAIT_MILLIS = 10_000L

        /** Lo que se le da al trabajo para pasar, si la compuerta lo dejara. */
        const val SETTLE_MILLIS = 500L
    }
}
