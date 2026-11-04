package io.github.yesidrobayo.shopledger.data.local.migration

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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La plantilla de migraciones, sobre la base ficticia: cada salto y la cadena completa conservan
 * cada fila tal cual, y la base migrada queda igual a una creada en la última versión.
 *
 * Es el modelo de las pruebas de cada salto de la base real. [MigrationTestHelper] crea la versión
 * de partida desde su JSON exportado, la prueba la llena con filas de cada tabla que el salto
 * toca, y el ayudante corre los saltos y valida el esquema contra el JSON de destino. Los datos los
 * valida la prueba: el ayudante no los mira.
 */
@RunWith(AndroidJUnit4::class)
class TemplateMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val file = testDatabaseFile(context, "template-migration-test.db")
    private val freshFile = testDatabaseFile(context, "template-migration-fresh-test.db")

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
        deleteDatabaseFiles(freshFile)
    }

    @After
    fun cleanUp() {
        deleteDatabaseFiles(file)
        deleteDatabaseFiles(freshFile)
    }

    @Test
    fun firstStepAddsColumnsAndKeepsEveryRow() = runBlocking {
        val before = createSeededVersion1()

        helper.runMigrationsAndValidate(2, listOf(TemplateMigration1To2)).use { migrated ->
            assertSameRows(before, migrated.snapshot())
            assertEquals(2L, migrated.longValue("PRAGMA user_version"))
            // Las columnas nuevas nacen con su valor por defecto en las filas que ya existían.
            assertEquals(
                listOf(0L, 0L, 0L),
                migrated.rows("SELECT reorder_point_milli FROM products ORDER BY id").flatten(),
            )
            assertEquals(
                5L,
                migrated.longValue("SELECT COUNT(*) FROM stock_movements WHERE note IS NULL"),
            )
            assertLedgerGuardStillBlocksUpdates(migrated)
        }
    }

    @Test
    fun secondStepRebuildsProductsKeepingIdsAndSequence() = runBlocking {
        helper.createDatabase(2).use { connection ->
            connection.seedVersion1()
            connection.execSQL("UPDATE products SET reorder_point_milli = 3000 WHERE id = 1")
        }
        val before = inspectWithoutRoom(file) { it.snapshot() }

        helper.runMigrationsAndValidate(3, listOf(TemplateMigration2To3)).use { migrated ->
            assertSameRows(before, migrated.snapshot())
            assertEquals(
                listOf(3000L, 0L, 0L),
                migrated.rows("SELECT reorder_point_milli FROM products ORDER BY id").flatten(),
            )
            assertEquals(emptyList<String>(), migrated.texts("SELECT name FROM categories"))
        }
    }

    @Test
    fun fullChainKeepsEveryRowExact() = runBlocking {
        val before = createSeededVersion1()

        helper
            .runMigrationsAndValidate(TemplateDatabase.LATEST_VERSION, TEMPLATE_MIGRATIONS)
            .use { migrated ->
                val after = migrated.snapshot()
                assertSameRows(before, after)
                assertEquals(TemplateDatabase.LATEST_VERSION.toLong(), after.userVersion)
                // Dinero en el límite de un Long, guardado como entero y no como real.
                assertEquals(
                    listOf(PRICE_ABOVE_DOUBLE_PRECISION, Long.MAX_VALUE),
                    migrated.rows(
                        "SELECT sale_price FROM products WHERE id IN (1, 3) ORDER BY id",
                    ).flatten(),
                )
                assertEquals(
                    listOf("integer"),
                    migrated.texts("SELECT DISTINCT typeof(sale_price) FROM products"),
                )
                // El saldo de cada producto sigue siendo la suma de su libro.
                assertEquals(emptyList<List<Any?>>(), migrated.rows(UNBALANCED_PRODUCTS_SQL))
                assertEquals(emptyList<List<Any?>>(), migrated.rows("PRAGMA foreign_key_check"))
                assertEquals(listOf("ok"), migrated.texts("PRAGMA integrity_check"))
                assertLedgerGuardStillBlocksUpdates(migrated)
            }
    }

    @Test
    fun deletedIdIsNeverHandedOutAgainAfterTheRebuild() = runBlocking {
        createSeededVersion1()

        helper
            .runMigrationsAndValidate(TemplateDatabase.LATEST_VERSION, TEMPLATE_MIGRATIONS)
            .use { migrated ->
                migrated.execSQL(
                    "INSERT INTO products (name, category_id, sale_price, average_cost, " +
                        "on_hand_milli) VALUES ('Producto de prueba nuevo', NULL, 1, 1, 0)",
                )
                assertEquals(
                    DELETED_PRODUCT_ID + 1,
                    migrated.longValue("SELECT MAX(id) FROM products"),
                )
            }
    }

    @Test
    fun migratedDatabaseMatchesAFreshOne() = runBlocking {
        createSeededVersion1()
        val migrated =
            helper
                .runMigrationsAndValidate(TemplateDatabase.LATEST_VERSION, TEMPLATE_MIGRATIONS)
                .use { it.structure() to it.texts(PRODUCTS_SQL_TEXT) }

        // Room crea la base nueva en la última versión, desde las entidades.
        openAndMigrate(context, freshFile, emptyList())
        val created =
            inspectWithoutRoom(freshFile) { it.structure() to it.texts(PRODUCTS_SQL_TEXT) }

        assertEquals(created.first, migrated.first)
        // El texto guardado sí cambia: el renombrado escribe el nombre entre comillas dobles, y
        // Room crea con acentos graves. Quien compare el esquema de una base migrada compara su
        // estructura, no el texto de sus tablas.
        assertTrue(created.second.single().startsWith("CREATE TABLE `products`"))
        assertTrue(migrated.second.single().startsWith("CREATE TABLE \"products\""))
    }

    @Test
    fun migratedDatabaseOpensInTheAppAndSyncsItsGuards() = runBlocking {
        createSeededVersion1()
        helper
            .runMigrationsAndValidate(TemplateDatabase.LATEST_VERSION, TEMPLATE_MIGRATIONS)
            .use { migrated ->
                // El salto borró la guarda que lee products. Queda sin ella hasta abrir.
                assertEquals(listOf(GUARD_LEDGER_NO_UPDATE), migrated.texts(GUARDS_SQL))
            }

        // Room compara la huella guardada con la de la versión compilada: si el salto dejara
        // otro esquema, se negaría a abrir.
        val database = openTemplate(context, file, TEMPLATE_MIGRATIONS)
        try {
            val guards = database.useReaderConnection { it.texts(GUARDS_SQL) }
            assertEquals(listOf(GUARD_LEDGER_NO_UPDATE, GUARD_LEDGER_PRODUCT), guards)
        } finally {
            database.close()
        }
        inspectWithoutRoom(file) { connection ->
            assertEquals(
                templateIdentityHash(context, TemplateDatabase.LATEST_VERSION),
                connection.snapshot().identityHash,
            )
        }
    }

    @Test
    fun everyVersionHasExactlyOneStep() {
        assertOneStepPerVersion(
            TEMPLATE_MIGRATIONS,
            TemplateDatabase.FIRST_VERSION,
            TemplateDatabase.LATEST_VERSION,
        )
    }

    /** Crea la versión 1 desde su JSON, la llena y devuelve lo que guardó, leído sin Room. */
    private suspend fun createSeededVersion1(): MigrationSnapshot {
        helper.createDatabase(TemplateDatabase.FIRST_VERSION).use { it.seedVersion1() }
        return inspectWithoutRoom(file) { it.snapshot() }
    }

    private fun assertSameRows(before: MigrationSnapshot, after: MigrationSnapshot) {
        assertEquals(before.products, after.products)
        assertEquals(before.movements, after.movements)
        // La secuencia sigue en el producto borrado, por encima del último `id` que existe.
        assertEquals(before.sequences, after.sequences)
        assertEquals(listOf("products", DELETED_PRODUCT_ID), after.sequences.first())
    }

    private fun assertLedgerGuardStillBlocksUpdates(connection: SQLiteConnection) {
        assertThrows(SQLiteException::class.java) {
            connection.execSQL("UPDATE stock_movements SET quantity_milli = 0 WHERE id = 1")
        }
    }

    private companion object {
        const val GUARDS_SQL = "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name"
        const val PRODUCTS_SQL_TEXT =
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'products'"
    }
}
