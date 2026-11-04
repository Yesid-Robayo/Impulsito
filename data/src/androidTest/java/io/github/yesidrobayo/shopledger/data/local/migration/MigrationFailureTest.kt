package io.github.yesidrobayo.shopledger.data.local.migration

import androidx.room3.migration.Migration
import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.local.texts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Qué deja una migración que falla: la base en la versión desde la que se abrió, intacta.
 *
 * Abre la base ficticia por el mismo camino que la app abre la suya, con Room, el SQLite
 * empaquetado y WAL, y mira el archivo después sin pasar por Room. Room corre todos los saltos
 * pendientes en una sola transacción `EXCLUSIVE` y cambia `user_version` dentro de ella: si un
 * salto lanza, revierte la cadena entera y relanza la misma excepción.
 *
 * Así, quien abre la base al arrancar recibe la excepción, lleva a la persona al rescate y no
 * pierde nada: la copia previa a migrar queda como segunda defensa, para el error que una
 * transacción no ve, el de un salto que pierde filas sin fallar.
 */
@RunWith(AndroidJUnit4::class)
class MigrationFailureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val file = testDatabaseFile(context, "migration-failure-test.db")

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
    }

    @After
    fun cleanUp() {
        deleteDatabaseFiles(file)
    }

    @Test
    fun failureHalfwayLeavesThePreviousVersionIntact() = runBlocking {
        val before = createSeeded(version = 2)
        val failure = InjectedMigrationFailure()

        val thrown =
            assertThrows(InjectedMigrationFailure::class.java) {
                runBlocking { openAndMigrate(context, file, listOf(failingAfterDrop(failure))) }
            }

        // La misma excepción del salto, sin envolver: quien abre la base debe atrapar cualquiera.
        assertSame(failure, thrown)
        assertIntact(before, version = 2)
    }

    @Test
    fun failureInALaterStepUndoesTheWholeChain() = runBlocking {
        val before = createSeeded(version = 1)

        assertThrows(InjectedMigrationFailure::class.java) {
            runBlocking {
                openAndMigrate(
                    context,
                    file,
                    listOf(TemplateMigration1To2, failingAfterDrop(InjectedMigrationFailure())),
                )
            }
        }

        // El salto de 1 a 2 había terminado bien, y también se revirtió: no queda en la 2.
        assertIntact(before, version = 1)
        inspectWithoutRoom(file) { connection ->
            assertFalse("reorder_point_milli" in connection.columnsOf("products"))
            assertFalse("note" in connection.columnsOf("stock_movements"))
        }
    }

    @Test
    fun fixedMigrationOpensAfterAFailure() = runBlocking {
        val before = createSeeded(version = 1)
        assertThrows(InjectedMigrationFailure::class.java) {
            runBlocking {
                openAndMigrate(
                    context,
                    file,
                    listOf(TemplateMigration1To2, failingAfterDrop(InjectedMigrationFailure())),
                )
            }
        }

        // La versión con el salto corregido abre la misma base: nada quedó a medias.
        openAndMigrate(context, file, TEMPLATE_MIGRATIONS)

        inspectWithoutRoom(file) { connection ->
            val after = connection.snapshot()
            assertEquals(TemplateDatabase.LATEST_VERSION.toLong(), after.userVersion)
            assertEquals(before.products, after.products)
            assertEquals(before.movements, after.movements)
            assertEquals(before.sequences, after.sequences)
        }
    }

    @Test
    fun brokenForeignKeyRollsBackTheMigration() = runBlocking {
        val before = createSeeded(version = 2, orphanCategory = true)

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { openAndMigrate(context, file, listOf(TemplateMigration2To3)) }
            }

        // El mensaje nombra la tabla y cuenta las filas, sin ningún valor de ellas.
        assertEquals(
            "La migración de 2 a 3 dejó llaves foráneas rotas en: products (1)",
            thrown.message,
        )
        assertIntact(before, version = 2)
    }

    @Test
    fun roomAloneKeepsBrokenForeignKeysAfterMigrating() = runBlocking {
        createSeeded(version = 2, orphanCategory = true)
        // El mismo salto sin la comprobación final, como lo correría Room solo.
        val unchecked =
            object : Migration(2, 3) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    TEMPLATE_STEPS_2_TO_3.forEach { connection.execSQL(it) }
                }
            }

        openAndMigrate(context, file, listOf(unchecked))

        // Room abrió la base en la versión 3 con un producto que apunta a una categoría que no
        // existe: por eso cada salto de la app termina con la comprobación.
        inspectWithoutRoom(file) { connection ->
            assertEquals(3L, connection.longValue("PRAGMA user_version"))
            assertEquals(1, connection.rows("PRAGMA foreign_key_check").size)
        }
    }

    @Test
    fun migrationRunsWithForeignKeysOff() = runBlocking {
        createSeeded(version = 2)
        var duringMigration = -1L
        val probe =
            object : CheckedMigration(startVersion = 2) {
                override suspend fun migrateSchema(connection: SQLiteConnection) {
                    duringMigration = connection.longValue("PRAGMA foreign_keys")
                    TEMPLATE_STEPS_2_TO_3.forEach { connection.execSQL(it) }
                }
            }

        val database = openTemplate(context, file, listOf(probe))
        val afterOpening =
            try {
                database.useReaderConnection { it.longValue("PRAGMA foreign_keys") }
            } finally {
                database.close()
            }

        // Apagadas al migrar, por eso borrar la tabla vieja no choca con las llaves del libro. Room
        // las enciende al terminar.
        assertEquals(0L, duringMigration)
        assertEquals(1L, afterOpening)
    }

    @Test
    fun guardNamingTheRebuiltTableBlocksTheRename() = runBlocking {
        val before = createSeeded(version = 2)
        val withoutDroppingTheGuard =
            object : CheckedMigration(startVersion = 2) {
                override suspend fun migrateSchema(connection: SQLiteConnection) {
                    TEMPLATE_STEPS_2_TO_3
                        .filterNot { it.startsWith("DROP TRIGGER") }
                        .forEach { connection.execSQL(it) }
                }
            }

        val thrown =
            assertThrows(SQLiteException::class.java) {
                runBlocking { openAndMigrate(context, file, listOf(withoutDroppingTheGuard)) }
            }

        // SQLite revisa todo el esquema al renombrar, y la guarda apunta a la tabla ya borrada.
        assertTrue(thrown.message.orEmpty().contains("error in trigger $GUARD_LEDGER_PRODUCT"))
        assertIntact(before, version = 2)
    }

    @Test
    fun missingStepIsRefusedAndKeepsData() = runBlocking {
        val before = createSeeded(version = 1)

        // Sin el salto de 1 a 2 no hay camino a la 3: Room se niega a abrir y no borra nada.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { openAndMigrate(context, file, listOf(TemplateMigration2To3)) }
        }

        assertIntact(before, version = 1)
    }

    /**
     * Crea la base en [version] desde su JSON, con las filas de prueba y sus guardas, y devuelve lo
     * que guardó. Con [orphanCategory], un producto apunta a una categoría que no existirá.
     */
    private suspend fun createSeeded(
        version: Int,
        orphanCategory: Boolean = false,
    ): MigrationSnapshot {
        helper.createDatabase(version).use { connection ->
            connection.seedVersion1()
            if (orphanCategory) {
                connection.execSQL("UPDATE products SET category_id = 99 WHERE id = 2")
            }
        }
        return inspectWithoutRoom(file) { it.snapshot() }
    }

    /** La base sigue en [version], con las mismas tablas, filas, secuencias, guardas y huella. */
    private fun assertIntact(before: MigrationSnapshot, version: Int) {
        inspectWithoutRoom(file) { connection ->
            val after = connection.snapshot()
            assertEquals(version.toLong(), after.userVersion)
            assertEquals(templateIdentityHash(context, version), after.identityHash)
            // El esquema guardado, incluidas las guardas, es el mismo texto: ni products_new ni
            // categories quedan a medio crear.
            assertEquals(before, after)
            assertEquals(listOf("ok"), connection.texts("PRAGMA integrity_check"))
        }
    }

    /**
     * El salto de 2 a 3 que falla en el peor momento: con `products` ya borrada y la tabla nueva
     * llena, antes de renombrarla.
     */
    private fun failingAfterDrop(failure: InjectedMigrationFailure) =
        object : CheckedMigration(startVersion = 2) {
            override suspend fun migrateSchema(connection: SQLiteConnection) {
                val untilDrop = TEMPLATE_STEPS_2_TO_3.indexOf(DROP_OLD_PRODUCTS) + 1
                TEMPLATE_STEPS_2_TO_3.take(untilDrop).forEach { connection.execSQL(it) }
                check(
                    connection.texts(
                        "SELECT name FROM sqlite_master WHERE name = 'products'",
                    ).isEmpty(),
                )
                throw failure
            }
        }

    private fun SQLiteConnection.columnsOf(table: String): List<String> =
        texts("SELECT name FROM pragma_table_info('$table')")

    /** Una falla a propósito, distinta de cualquiera que lance SQLite o Room. */
    private class InjectedMigrationFailure : RuntimeException("Falla a propósito")
}
