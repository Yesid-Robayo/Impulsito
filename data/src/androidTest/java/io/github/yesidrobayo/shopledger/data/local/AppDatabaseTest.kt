package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La base tal como la construye la app: el SQLite empaquetado, WAL y el esquema compilado.
 *
 * Las llaves foráneas no son un ajuste de Room por conexión: las activa el código que Room genera
 * para la base, en cada conexión que abre, y solo si alguna entidad declara una llave. Por eso se
 * comprueban en la escritora y en todas las lectoras, y con una fila huérfana de verdad.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "app-database-test.db")
    private lateinit var database: AppDatabase

    @Before
    fun buildDatabase() {
        deleteDatabaseFiles(file)
        database = AppDatabase.build(context, file, Dispatchers.IO)
    }

    @After
    fun closeDatabase() {
        database.close()
        deleteDatabaseFiles(file)
    }

    @Test
    fun emptyDatabaseOpensWithTheCompiledSchema() = runBlocking {
        val opened = database.useWriterConnection { connection ->
            OpenedDatabase(
                userVersion = connection.longValue("PRAGMA user_version"),
                journalMode = connection.texts("PRAGMA journal_mode"),
                integrity = connection.texts("PRAGMA integrity_check"),
                foreignKeyProblems = connection.texts("PRAGMA foreign_key_check"),
                tables = connection.texts(OWN_TABLES_SQL),
                priceLists = connection.longValue("SELECT COUNT(*) FROM price_lists"),
            )
        }

        assertTrue("La base no quedó en la ruta pedida", file.isFile)
        assertEquals(AppDatabase.SCHEMA_VERSION.toLong(), opened.userVersion)
        assertEquals(listOf("wal"), opened.journalMode)
        assertEquals(listOf("ok"), opened.integrity)
        assertEquals(emptyList<String>(), opened.foreignKeyProblems)
        assertEquals(SCHEMA_TABLES, opened.tables)
        // Solo la lista inicial, que nace con la base.
        assertEquals(1L, opened.priceLists)
    }

    @Test
    fun orphanRowFailsOnTheFirstConnection() = runBlocking {
        // La escritora es la primera conexión: la que crea la base.
        val failure =
            database.useWriterConnection { connection ->
                failureOf { connection.executeSQL(relativeListSql("orphan", MISSING_ID)) }
            }

        assertForeignKeyFailure(failure)
        assertEquals(0L, countPriceLists())
    }

    @Test
    fun foreignKeysAreOnInEveryConnection() = runBlocking {
        // Las lectoras primero: así la escritora llega después, por el camino de toda conexión
        // nueva y no por el de la primera.
        val readers = database.onEveryReader { it.longValue("PRAGMA foreign_keys") }
        val writer = database.useWriterConnection { it.longValue("PRAGMA foreign_keys") }
        val insideTransaction = database.withWriteTransaction { longValue("PRAGMA foreign_keys") }
        val orphanFailure =
            database.useWriterConnection { connection ->
                failureOf { connection.executeSQL(relativeListSql("orphan", MISSING_ID)) }
            }

        assertEquals(List(WAL_READERS) { FOREIGN_KEYS_ON }, readers)
        assertEquals(FOREIGN_KEYS_ON, writer)
        assertEquals(FOREIGN_KEYS_ON, insideTransaction)
        assertForeignKeyFailure(orphanFailure)
        assertEquals(0L, countPriceLists())
    }

    @Test
    fun validChildIsAcceptedAndItsParentCannotBeDeleted() = runBlocking {
        // Contraprueba: la fila con su padre entra, así que el rechazo de arriba es por la llave.
        database.useWriterConnection { connection ->
            connection.executeSQL(insertPriceListSql("base", "FIXED", basePriceListId = null))
        }
        val parentId =
            database.useReaderConnection {
                it.longValue("SELECT id FROM price_lists WHERE normalized_name = 'base'")
            }
        database.useWriterConnection { connection ->
            connection.executeSQL(relativeListSql("child", parentId))
        }

        val deleteFailure =
            database.useWriterConnection { connection ->
                failureOf { connection.executeSQL("DELETE FROM price_lists WHERE id = $parentId") }
            }

        assertForeignKeyFailure(deleteFailure)
        assertEquals(2L, countPriceLists())
    }

    /** Las listas que escribió la prueba, sin la inicial que nace con la base. */
    private suspend fun countPriceLists(): Long = database.useReaderConnection {
        it.longValue("SELECT COUNT(*) FROM price_lists WHERE system_key IS NULL")
    }

    private fun relativeListSql(name: String, baseId: Long): String =
        insertPriceListSql(name, "RELATIVE", basePriceListId = baseId)

    private data class OpenedDatabase(
        val userVersion: Long,
        val journalMode: List<String>,
        val integrity: List<String>,
        val foreignKeyProblems: List<String>,
        val tables: List<String>,
        val priceLists: Long,
    )

    private companion object {
        const val FOREIGN_KEYS_ON = 1L
        const val MISSING_ID = 999L

        /** Ejecuta el bloque y devuelve la excepción de SQLite que lanzó, o falla si no lanzó. */
        suspend fun failureOf(block: suspend () -> Unit): SQLiteException {
            try {
                block()
            } catch (expected: SQLiteException) {
                return expected
            }
            fail("La sentencia debía fallar por la llave foránea")
            error("fail siempre lanza")
        }

        fun assertForeignKeyFailure(failure: SQLiteException) {
            val message = failure.message.orEmpty()
            assertTrue("Falló por otra causa: $message", message.contains("FOREIGN KEY constraint"))
        }
    }
}
