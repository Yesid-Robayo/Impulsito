package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una base que la app no sabe abrir se rechaza, nunca se borra.
 *
 * La app no tiene migraciones destructivas: sin una ruta de migración, Room lanza
 * `IllegalStateException` y el archivo queda como estaba, con todas sus filas. Quien abre la base
 * atrapa esa excepción y lleva a la persona al rescate, con los datos intactos.
 */
@RunWith(AndroidJUnit4::class)
class RefusedDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "refused-database-test.db")

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
    }

    @After
    fun cleanUp() {
        deleteDatabaseFiles(file)
    }

    @Test
    fun databaseFromNewerVersionIsRefusedAndKept() {
        writeForeignDatabase(userVersion = AppDatabase.SCHEMA_VERSION + 1)

        assertRefused()

        assertForeignDatabaseIntact(userVersion = AppDatabase.SCHEMA_VERSION + 1)
    }

    @Test
    fun foreignDatabaseWithTheSameVersionIsRefusedAndKept() {
        writeForeignDatabase(userVersion = AppDatabase.SCHEMA_VERSION)

        assertRefused()

        assertForeignDatabaseIntact(userVersion = AppDatabase.SCHEMA_VERSION)
    }

    /** Una base que no creó esta versión de la app: otra tabla, con filas, y la versión dada. */
    private fun writeForeignDatabase(userVersion: Int) {
        BundledSQLiteDriver().open(file.path).use { connection ->
            connection.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
            repeat(FOREIGN_ROWS) { row ->
                connection.execSQL("INSERT INTO notes (body) VALUES ('nota de prueba $row')")
            }
            connection.execSQL("PRAGMA user_version = $userVersion")
        }
    }

    private fun assertRefused() {
        val database = AppDatabase.build(context, file, Dispatchers.IO)
        try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    database.useReaderConnection { it.longValue("PRAGMA user_version") }
                }
            }
        } finally {
            database.close()
        }
    }

    private fun assertForeignDatabaseIntact(userVersion: Int) {
        inspectWithoutRoom(file) { connection ->
            assertEquals(userVersion.toLong(), connection.longValue("PRAGMA user_version"))
            assertEquals(listOf("notes"), connection.texts(OWN_TABLES_SQL))
            assertEquals(FOREIGN_ROWS.toLong(), connection.longValue("SELECT COUNT(*) FROM notes"))
            assertEquals(listOf("ok"), connection.texts("PRAGMA integrity_check"))
        }
    }

    private companion object {
        const val FOREIGN_ROWS = 3
    }
}
