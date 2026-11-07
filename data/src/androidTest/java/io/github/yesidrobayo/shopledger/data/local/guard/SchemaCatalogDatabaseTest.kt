package io.github.yesidrobayo.shopledger.data.local.guard

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.backup.CandidateSchema
import io.github.yesidrobayo.shopledger.data.backup.masterRows
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_REGION
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.local.texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El catálogo del esquema frente a una base de verdad: la que crea la app, leída sin Room en
 * `sqlite_master` y con los pragma de SQLite, es exactamente la del catálogo de su versión. Tablas
 * e índices se comparan por estructura, y las guardas por texto exacto.
 *
 * También prueba la comparación misma: una tabla reconstruida, que SQLite guarda con otro texto,
 * sigue siendo igual, y cualquier objeto que sobre, falte o cambie es una diferencia.
 */
@RunWith(AndroidJUnit4::class)
class SchemaCatalogDatabaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val file = testDatabaseFile(context, "schema-catalog-test.db")

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
    fun aNewDatabaseIsExactlyTheCatalogOfItsVersion() {
        createWithTheApp()
        val catalog = SchemaCatalog.current

        inspectWithoutRoom(file) { connection ->
            assertEquals(emptyList<String>(), CandidateSchema.differences(connection, catalog))
            assertEquals(catalog.version.toLong(), connection.longValue("PRAGMA user_version"))
            assertEquals(
                listOf(catalog.identityHash),
                connection.texts("SELECT identity_hash FROM room_master_table"),
            )
            // Ninguna vista, tabla virtual ni índice automático: todo lo que hay está catalogado.
            val rows = connection.masterRows()
            assertEquals(setOf("table", "index", "trigger"), rows.map { it.type }.toSet())
            assertTrue(rows.none { it.name.startsWith(SchemaCatalog.AUTOINDEX_PREFIX) })
            assertEquals(
                catalog.triggers.map { it.name },
                rows.filter { it.type == "trigger" }.map { it.name },
            )
        }
        assertEquals(catalog, SchemaCatalog.forVersion(AppDatabase.SCHEMA_VERSION))
        assertNull(SchemaCatalog.forVersion(0))
    }

    @Test
    fun aDatabaseCreatedFromTheExportedSchemaGetsItsGuardsWhenTheAppOpensIt() = runBlocking {
        helper.createDatabase(AppDatabase.SCHEMA_VERSION).close()

        // Room crea las tablas desde el JSON, sin guardas: es la base que deja una migración antes
        // de que la app la abra.
        val beforeOpening =
            inspectWithoutRoom(file) { CandidateSchema.differences(it, SchemaCatalog.current) }
        assertEquals(
            SchemaCatalog.current.triggers.map { "falta la guarda ${it.name}" },
            beforeOpening,
        )

        AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK) { FIXED_REGION }.close()

        inspectWithoutRoom(file) {
            assertEquals(
                emptyList<String>(),
                CandidateSchema.differences(it, SchemaCatalog.current),
            )
        }
    }

    @Test
    fun aRebuiltTableIsStoredWithOtherTextAndStillMatchesByStructure() {
        createWithTheApp()

        inspectWithoutRoom(file) { connection ->
            val before = connection.tableSql("tags")
            connection.rebuildTags()
            val after = connection.tableSql("tags")

            // SQLite guarda el nombre nuevo entre comillas dobles: el texto ya no es el de Room.
            assertTrue(before, before.startsWith("CREATE TABLE `tags`"))
            assertTrue(after, after.startsWith("CREATE TABLE \"tags\""))
            assertEquals(
                emptyList<String>(),
                CandidateSchema.differences(connection, SchemaCatalog.current),
            )
        }
    }

    @Test
    fun anythingThatIsNotInTheCatalogIsADifference() {
        createWithTheApp()
        val guard = SchemaCatalog.current.triggers.first { it.table == "tags" }

        // Una guarda con un solo espacio de más ya es otra guarda.
        val widened = guard.sql.replace("BEGIN SELECT", "BEGIN  SELECT")
        assertNotEquals(guard.sql, widened)

        inspectWithoutRoom(file) { connection ->
            fun assertDifferent(change: List<String>, undo: List<String>) {
                for (sql in change) connection.execSQL(sql)
                assertNotEquals(
                    change.toString(),
                    emptyList<String>(),
                    CandidateSchema.differences(connection, SchemaCatalog.current),
                )
                for (sql in undo) connection.execSQL(sql)
                assertEquals(
                    undo.toString(),
                    emptyList<String>(),
                    CandidateSchema.differences(connection, SchemaCatalog.current),
                )
            }

            fun assertDifferent(change: String, undo: String) =
                assertDifferent(listOf(change), listOf(undo))

            assertDifferent("CREATE VIEW tags_view AS SELECT * FROM tags", "DROP VIEW tags_view")
            assertDifferent(
                "CREATE VIRTUAL TABLE tags_search USING fts5(name)",
                "DROP TABLE tags_search",
            )
            assertDifferent(
                "CREATE TABLE tags_extra (id INTEGER PRIMARY KEY)",
                "DROP TABLE tags_extra",
            )
            assertDifferent(
                "ALTER TABLE tags ADD COLUMN note TEXT",
                "ALTER TABLE tags DROP COLUMN note",
            )
            assertDifferent("CREATE INDEX tags_name ON tags (name)", "DROP INDEX tags_name")
            assertDifferent(
                "CREATE INDEX tags_partial ON tags (name) WHERE name <> ''",
                "DROP INDEX tags_partial",
            )
            assertDifferent(
                "DROP INDEX index_tags_normalized_name",
                "CREATE UNIQUE INDEX index_tags_normalized_name ON tags (normalized_name)",
            )
            assertDifferent(
                "CREATE TRIGGER tags_spy AFTER INSERT ON tags BEGIN SELECT 1; END",
                "DROP TRIGGER tags_spy",
            )
            assertDifferent(
                listOf("DROP TRIGGER ${guard.name}", widened),
                listOf("DROP TRIGGER ${guard.name}", guard.sql),
            )
            assertDifferent("DROP TRIGGER ${guard.name}", guard.sql)
        }
    }

    /** Crea la base por el camino de la app, con sus guardas y sus filas iniciales. */
    private fun createWithTheApp() = runBlocking {
        AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK) { FIXED_REGION }.close()
    }

    private fun SQLiteConnection.tableSql(table: String): String =
        texts("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$table'").single()

    /**
     * Reconstruye `tags` como lo haría una migración: tabla nueva, copia, borrado de la vieja y
     * renombrado. Las guardas que nombran la tabla impiden renombrarla, así que se retiran antes y
     * se vuelven a poner después, como hace la app al abrir.
     */
    private fun SQLiteConnection.rebuildTags() {
        val indices =
            texts("SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'tags'")
        execSQL("PRAGMA foreign_keys = OFF")
        for (guard in SchemaGuards.all) SchemaGuards.drop(this, guard.name)
        execSQL(tableSql("tags").replace("CREATE TABLE `tags`", "CREATE TABLE `tags_new`"))
        execSQL("INSERT INTO tags_new SELECT * FROM tags")
        execSQL("DROP TABLE tags")
        execSQL("ALTER TABLE tags_new RENAME TO tags")
        for (index in indices) execSQL(index)
        execSQL("PRAGMA foreign_keys = ON")
        SchemaGuards.sync(this)
    }
}
