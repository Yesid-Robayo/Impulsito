package io.github.yesidrobayo.shopledger.data.local.guard

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El catálogo del esquema coincide con sus dos fuentes: las tablas, con el JSON que Room exporta de
 * cada versión, y las guardas de la versión actual, letra por letra, con [SchemaGuards].
 *
 * Si una entidad, un índice, una llave o una guarda cambia y nadie regenera el catálogo, esta
 * prueba falla, y `check` con ella. Se arregla con `.\gradlew.bat :data:generateSchemaCatalog`.
 */
class SchemaCatalogTest {
    private val sources = SchemaCatalogSources.fromSystemProperties()
    private val exported = sources.exportedSchemas()

    @Test
    fun `the committed catalog is exactly what the generator writes`() {
        for ((file, expected) in sources.generatedFiles()) {
            assertTrue("Falta ${file.name}: $REGENERATE", file.isFile)
            assertEquals(
                "${file.name} no coincide con el esquema: $REGENERATE",
                expected,
                SchemaCatalogSources.normalized(file.readText()),
            )
        }
    }

    @Test
    fun `there is one entry per exported version of the app database`() {
        assertEquals(exported.map { it.version }, SchemaCatalog.versions.map { it.version })
        assertEquals(AppDatabase.SCHEMA_VERSION, SchemaCatalog.current.version)
        assertNull(SchemaCatalog.forVersion(0))
        assertNull(SchemaCatalog.forVersion(AppDatabase.SCHEMA_VERSION + 1))
    }

    @Test
    fun `every version has the tables, indices and keys that Room exported`() {
        for (schema in exported) {
            val catalog = requireNotNull(SchemaCatalog.forVersion(schema.version))
            assertEquals("Huella de ${schema.version}", schema.identityHash, catalog.identityHash)
            assertEquals("Tablas de ${schema.version}", schema.tables, catalog.tables)
        }
    }

    @Test
    fun `the current guards have the exact text that SchemaGuards installs`() {
        val triggers = SchemaCatalog.current.triggers

        assertEquals(SchemaGuards.all.size, triggers.size)
        for (guard in SchemaGuards.all) {
            val trigger = requireNotNull(SchemaCatalog.current.trigger(guard.name)) { guard.name }
            assertEquals(guard.name, guard.table, trigger.table)
            // El texto que instala, que es el que guarda SQLite: el de una guarda que espera algo
            // es distinto del que trae el modelo de datos.
            assertEquals(guard.name, guard.sql.removeSuffix(";"), trigger.sql)
            assertTrue(guard.name, guard.matches(trigger.sql))
        }
    }

    @Test
    fun `the first version has the 65 tables, and every guard protects one of them`() {
        val first = requireNotNull(SchemaCatalog.forVersion(1))

        assertEquals(65, first.tables.size)
        assertEquals(first.tables.map { it.name }.toSet(), first.triggers.map { it.table }.toSet())
    }

    @Test
    fun `every name lives once, and every key points to a catalogued table and column`() {
        for (catalog in SchemaCatalog.versions) {
            val names =
                catalog.tables.map { it.name } +
                    catalog.tables.flatMap { table -> table.indices.map { it.name } } +
                    catalog.triggers.map { it.name }
            assertEquals("Nombres repetidos en ${catalog.version}", names.size, names.toSet().size)
            for (name in names) {
                assertFalse(name, name.startsWith("sqlite_"))
                assertFalse(name, name in SchemaCatalog.INTERNAL_TABLES)
            }
            for (table in catalog.tables) {
                val columns = table.columns.map { it.name }.toSet()
                for (index in table.indices) {
                    assertTrue(index.name, index.columns.all { it.name in columns })
                }
                for (key in table.foreignKeys) {
                    val target = requireNotNull(catalog.table(key.referencedTable)) { table.name }
                    val targetColumns = target.columns.map { it.name }
                    assertTrue(table.name, key.columns.all { it in columns })
                    assertTrue(table.name, key.referencedColumns.all { it in targetColumns })
                }
            }
        }
    }

    @Test
    fun `everything is in canonical order, so the generator always writes the same text`() {
        for (catalog in SchemaCatalog.versions) {
            assertEquals(catalog.tables.sortedBy { it.name }, catalog.tables)
            assertEquals(catalog.triggers.sortedBy { it.name }, catalog.triggers)
            for (table in catalog.tables) {
                assertEquals(table.columns.sortedBy { it.name }, table.columns)
                assertEquals(table.indices.sortedBy { it.name }, table.indices)
            }
        }
        val first = sources.generatedFiles()
        assertEquals(first, sources.generatedFiles())
    }

    @Test
    fun `no table of the first version needs an automatic index`() {
        // Todas tienen una llave de una sola columna INTEGER: SQLite no crea ningún
        // sqlite_autoindex_*, así que la lista permitida no admite ninguno.
        for (table in requireNotNull(SchemaCatalog.forVersion(1)).tables) {
            assertFalse(table.name, table.hasPrimaryKeyIndex)
        }
    }

    @Test
    fun `the migration template database is not part of the catalog`() {
        // La base ficticia de la plantilla de migraciones tiene tres versiones, con tablas de los
        // mismos nombres que las reales. Su JSON vive al lado y no debe entrar.
        val template =
            File(requireNotNull(System.getProperty(SchemaCatalogSources.SCHEMA_DIR_PROPERTY)))
                .resolveSibling(TEMPLATE_SCHEMAS)
        val templateSchemas =
            template.listFiles().orEmpty().map { RoomSchemaReader.read(it.readText()) }
        val hashes = SchemaCatalog.versions.map { it.identityHash }.toSet()

        assertEquals(listOf(1, 2, 3), templateSchemas.map { it.version }.sorted())
        for (schema in templateSchemas) assertFalse(schema.identityHash in hashes)
    }

    private companion object {
        const val REGENERATE = "corre .\\gradlew.bat :data:generateSchemaCatalog"
        const val TEMPLATE_SCHEMAS =
            "io.github.yesidrobayo.shopledger.data.local.migration.TemplateDatabase"
    }
}
