package io.github.yesidrobayo.shopledger.data.local.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La forma del esquema que genera Room, leída del catálogo, que sale del JSON que Room exporta: cada
 * llave foránea con `ON UPDATE NO ACTION`, cada índice con el nombre `index_<tabla>_<columnas>` del
 * modelo de datos, y `AUTOINCREMENT` en toda tabla salvo las dos de una sola fila. La prueba del
 * dispositivo del catálogo comprueba que la base que crea la app es exactamente este catálogo.
 */
class RoomSchemaShapeTest {
    private val catalog = SchemaCatalog.current

    @Test
    fun `the schema has the sixty five tables of the data model`() {
        assertEquals(65, catalog.tables.size)
    }

    @Test
    fun `every foreign key leaves updates alone`() {
        for (table in catalog.tables) {
            for (key in table.foreignKeys) {
                assertEquals("${table.name} → ${key.referencedTable}", "NO ACTION", key.onUpdate)
                assertTrue(
                    "${table.name} → ${key.referencedTable}: ${key.onDelete}",
                    key.onDelete in setOf("RESTRICT", "SET NULL", "CASCADE", "NO ACTION"),
                )
            }
        }
    }

    @Test
    fun `every index is named after its table and columns`() {
        for (table in catalog.tables) {
            for (index in table.indices) {
                val expected = "index_${table.name}_" + index.columns.joinToString("_") { it.name }
                assertEquals(table.name, expected, index.name)
            }
        }
    }

    @Test
    fun `only the two single row tables go without autoincrement`() {
        val withoutAutoIncrement = catalog.tables.filterNot { it.autoIncrement }.map { it.name }
        assertEquals(listOf("app_settings", "backup_state"), withoutAutoIncrement)
        for (name in withoutAutoIncrement) {
            val key = requireNotNull(catalog.table(name)).columns.single {
                it.primaryKeyPosition > 0
            }
            assertEquals(name, "id", key.name)
            assertEquals(name, "INTEGER", key.type)
            assertTrue(name, key.notNull)
        }
    }
}
