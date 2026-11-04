package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El JSON exportado del esquema y la base que crea la app describen lo mismo.
 *
 * Las pruebas de migración crean cada versión vieja desde su JSON con [MigrationTestHelper]. Si el
 * JSON no coincidiera con lo que la app crea, esas pruebas probarían otra base. También deja
 * comprobado que el ayudante funciona con el SQLite empaquetado, el mismo de la app.
 */
@RunWith(AndroidJUnit4::class)
class ExportedSchemaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val file = testDatabaseFile(context, "exported-schema-test.db")

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = file,
            driver = BundledSQLiteDriver(),
            databaseClass = AppDatabase::class,
        )

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
    }

    @After
    fun cleanUp() {
        deleteDatabaseFiles(file)
    }

    // En Room 3, createDatabase y runMigrationsAndValidate son funciones suspend.
    @Test
    fun databaseCreatedFromTheJsonOpensInTheApp() = runBlocking {
        helper.createDatabase(AppDatabase.SCHEMA_VERSION).close()

        // Room compara la huella del esquema guardada en la base con la del código compilado, y
        // se niega a abrirla si no coinciden.
        val database = AppDatabase.build(context, file, Dispatchers.IO)
        val tables =
            try {
                database.useReaderConnection { it.texts(OWN_TABLES_SQL) }
            } finally {
                database.close()
            }

        assertEquals(SCHEMA_TABLES, tables)
    }

    @Test
    fun databaseCreatedByTheAppMatchesTheJson() = runBlocking {
        val database = AppDatabase.build(context, file, Dispatchers.IO)
        try {
            database.useReaderConnection { it.longValue("PRAGMA user_version") }
        } finally {
            database.close()
        }

        // Valida tablas, columnas, llaves e índices contra el JSON de la misma versión.
        helper.runMigrationsAndValidate(AppDatabase.SCHEMA_VERSION, emptyList()).close()
    }
}
