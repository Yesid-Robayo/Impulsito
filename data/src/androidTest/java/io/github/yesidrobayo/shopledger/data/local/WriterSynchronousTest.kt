package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que la app confirma queda en disco: la conexión de escritura corre con `synchronous = FULL`.
 *
 * El valor se lee donde de verdad se escribe, dentro de `withWriteTransaction`, y no en una
 * conexión cualquiera. En WAL, Room y el SQLite empaquetado dejan cada conexión en `NORMAL`, que
 * puede perder la última confirmación si el teléfono se apaga de golpe. Estas pruebas fallan si
 * Room cambia eso, o si la escritora se abre o se reabre sin pasar por [AppDatabase.open].
 */
@RunWith(AndroidJUnit4::class)
class WriterSynchronousTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "writer-synchronous-test.db")
    private val opened = mutableListOf<AppDatabase>()

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
    }

    @After
    fun cleanUp() {
        // Cerrar dos veces no hace nada: Room solo cierra la primera.
        opened.forEach { it.close() }
        deleteDatabaseFiles(file)
    }

    @Test
    fun writerIsFullInsideTheFirstWriteTransaction() = runBlocking {
        val database = openDatabase()

        // La primera escritura de la app, y el valor leído en la misma transacción que la hace.
        val synchronous =
            database.withWriteTransaction {
                executeSQL(insertPriceListSql("first", "FIXED", basePriceListId = null))
                longValue("PRAGMA synchronous")
            }

        assertEquals(SYNCHRONOUS_FULL, synchronous)
        assertEquals(1L, countPriceLists(database))
    }

    @Test
    fun writerStaysFullAfterCommitsFailuresAndReaders() = runBlocking {
        val database = openDatabase()
        repeat(COMMITS) { index ->
            database.withWriteTransaction {
                executeSQL(insertPriceListSql("list-$index", "FIXED", basePriceListId = null))
            }
        }
        // Una transacción que falla a mitad se revierte, y la escritora vuelve al grupo sin
        // cerrarse ni reabrirse.
        try {
            database.withWriteTransaction<Unit> {
                executeSQL(insertPriceListSql("rolled-back", "FIXED", basePriceListId = null))
                throw InjectedFailure()
            }
            fail("La falla inyectada debía salir de la transacción")
        } catch (expected: InjectedFailure) {
            // Es la falla que se inyectó: la transacción se revirtió.
        }
        // Las cuatro lectoras abiertas a la vez, después de la escritora.
        database.onEveryReader { it.longValue("PRAGMA synchronous") }

        val synchronous = database.withWriteTransaction { longValue("PRAGMA synchronous") }

        assertEquals(SYNCHRONOUS_FULL, synchronous)
        assertEquals(COMMITS.toLong(), countPriceLists(database))
    }

    @Test
    fun readersStayNormalAndReadOnly() = runBlocking {
        val database = openDatabase()

        val readers =
            database.onEveryReader { connection ->
                ReaderSettings(
                    synchronous = connection.longValue("PRAGMA synchronous"),
                    queryOnly = connection.longValue("PRAGMA query_only"),
                )
            }

        // El ajuste es de cada conexión: el de la escritora no llega a las lectoras. No lo
        // necesitan, porque Room las abre en solo lectura y nunca confirman nada.
        val expected = ReaderSettings(synchronous = SYNCHRONOUS_NORMAL, queryOnly = QUERY_ONLY_ON)
        assertEquals(List(WAL_READERS) { expected }, readers)
    }

    @Test
    fun reopenedDatabaseGetsFullAgain() = runBlocking {
        val first = openDatabase()
        first.withWriteTransaction {
            executeSQL(insertPriceListSql("kept", "FIXED", basePriceListId = null))
        }
        first.close()

        // Otra instancia trae otra escritora: una base con datos, sin crear el esquema.
        val second = openDatabase()
        val synchronous = second.withWriteTransaction { longValue("PRAGMA synchronous") }

        assertEquals(SYNCHRONOUS_FULL, synchronous)
        assertEquals(1L, countPriceLists(second))
    }

    @Test
    fun fileDoesNotRememberFull() = runBlocking {
        val database = openDatabase()
        database.withWriteTransaction {
            executeSQL(insertPriceListSql("kept", "FIXED", basePriceListId = null))
        }
        database.close()

        // Una conexión propia del driver, fuera de Room, empieza en NORMAL sobre una base en WAL:
        // si alguna vez escribe en esta base, tiene que fijar FULL ella misma.
        val outsideRoom =
            inspectWithoutRoom(file) { connection ->
                connection.texts("PRAGMA journal_mode") to
                    connection.longValue("PRAGMA synchronous")
            }

        assertEquals(listOf("wal") to SYNCHRONOUS_NORMAL, outsideRoom)
    }

    @Test
    fun buildAloneLeavesTheWriterAtNormal() = runBlocking {
        // Contraprueba: sin open, la escritora queda como la deja Room. Así se sabe que el 2 de
        // las demás pruebas lo pone open, y no el driver ni Room.
        val database = AppDatabase.build(context, file, Dispatchers.IO).also { opened += it }

        val synchronous = database.withWriteTransaction { longValue("PRAGMA synchronous") }

        assertEquals(SYNCHRONOUS_NORMAL, synchronous)
    }

    private suspend fun openDatabase(): AppDatabase =
        AppDatabase.open(context, file, Dispatchers.IO).also { opened += it }

    /** Las listas que escribió la prueba, sin la inicial que nace con la base. */
    private suspend fun countPriceLists(database: AppDatabase): Long =
        database.useReaderConnection {
            it.longValue("SELECT COUNT(*) FROM price_lists WHERE system_key IS NULL")
        }

    private data class ReaderSettings(val synchronous: Long, val queryOnly: Long)

    private class InjectedFailure : RuntimeException("Falla inyectada por la prueba")

    private companion object {
        // Valores que devuelve PRAGMA synchronous: 1 es NORMAL y 2 es FULL.
        const val SYNCHRONOUS_NORMAL = 1L
        const val SYNCHRONOUS_FULL = 2L
        const val QUERY_ONLY_ON = 1L
        const val COMMITS = 5
    }
}
