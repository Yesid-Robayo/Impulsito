package io.github.yesidrobayo.shopledger.data.local.migration

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los saltos de la base real: hay uno por versión, y la primera versión llega a la actual.
 *
 * Con el esquema 1 la lista está vacía y la cadena no tiene saltos, así que estas pruebas pasan sin
 * migrar nada. Están para el día en que la versión suba: un salto que falte o sobre, o una versión
 * vieja que no llegue a la actual, las hace fallar sin que haga falta escribirlas de nuevo.
 */
@RunWith(AndroidJUnit4::class)
class AppMigrationsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = testDatabaseFile(instrumentation.targetContext, "app-migrations-test.db")

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

    @Test
    fun everyVersionHasExactlyOneStep() {
        assertOneStepPerVersion(AppMigrations.all, FIRST_SCHEMA_VERSION, AppDatabase.SCHEMA_VERSION)
    }

    @Test
    fun firstSchemaReachesTheCurrentOne() = runBlocking {
        helper.createDatabase(FIRST_SCHEMA_VERSION).close()

        // Valida tablas, columnas, llaves e índices contra el JSON de la versión actual.
        helper.runMigrationsAndValidate(AppDatabase.SCHEMA_VERSION, AppMigrations.all).use {
            assertEquals(AppDatabase.SCHEMA_VERSION.toLong(), it.longValue("PRAGMA user_version"))
        }
    }

    private companion object {
        /** La versión con que nació la base: desde ella, toda base debe llegar a la actual. */
        const val FIRST_SCHEMA_VERSION = 1
    }
}
