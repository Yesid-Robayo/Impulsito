package io.github.yesidrobayo.shopledger.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
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
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La entrada de la capa de datos: crearla no toca el disco, abre la base una sola vez y fuera del
 * hilo principal, entrega los puertos conectados a esa base, y una base que no abre queda intacta.
 */
@RunWith(AndroidJUnit4::class)
class DataGraphTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "data-graph-test.db")
    private val log = MemoryLog()

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
    }

    @After
    fun cleanUp() {
        deleteDatabaseFiles(file)
    }

    @Test
    fun creatingTheGraphDoesNotTouchTheDisk() {
        DataGraph(context, Dispatchers.IO, FIXED_CLOCK, { file }, log)

        assertFalse(file.exists())
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
                        file
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
        assertTrue(graph.log is AndroidEventLog)
    }

    @Test
    fun openCreatesANewDatabaseWithItsInitialRows() = runBlocking {
        val access = graph().open()

        val ports = (access as DataAccess.Ready).ports
        assertEquals(null, ports.settings.get().profile.businessName)
        assertTrue(file.isFile)
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
                    { file },
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
    fun aDatabaseThatDoesNotOpenIsKeptAndTriedAgain() = runBlocking {
        writeNewerDatabase()
        val graph = graph()

        assertEquals(DataAccess.Unavailable, graph.open())
        assertEquals(DataAccess.Unavailable, graph.open())

        assertEquals(
            listOf(
                LogLevel.ERROR to "La base no abrió: error=java.lang.IllegalStateException",
                LogLevel.ERROR to "La base no abrió: error=java.lang.IllegalStateException",
            ),
            log.lines,
        )
        val kept =
            inspectWithoutRoom(file) { connection ->
                connection.longValue("SELECT count(*) FROM notes") to
                    connection.longValue("PRAGMA user_version")
            }
        assertEquals(NOTE_ROWS to (AppDatabase.SCHEMA_VERSION + 1).toLong(), kept)

        // Con la base ajena fuera, la siguiente apertura vuelve a intentarlo y abre.
        deleteDatabaseFiles(file)
        assertTrue(graph.open() is DataAccess.Ready)
    }

    private fun graph(): DataGraph = DataGraph(context, Dispatchers.IO, FIXED_CLOCK, { file }, log)

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
    }
}
