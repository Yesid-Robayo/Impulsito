package io.github.yesidrobayo.shopledger.data.local.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El generador con un esquema inventado, que trae lo que el esquema 1 no tiene: una llave de dos
 * columnas, un índice descendente, un `DEFAULT` y una llave foránea de dos columnas. Así, el día
 * que una versión los use, el catálogo ya los describe bien.
 */
class SchemaCatalogGeneratorTest {
    @Test
    fun `reads Room's schema as the pragmas of SQLite would describe it`() {
        val schema = RoomSchemaReader.read(EXPORTED)

        assertEquals(2, schema.version)
        assertEquals("abc123", schema.identityHash)
        assertEquals(listOf("box_items", "boxes"), schema.tables.map { it.name })
        val items = schema.tables.first()
        assertFalse(items.autoIncrement)
        assertTrue(items.hasPrimaryKeyIndex)
        // Por nombre, con su lugar en la llave y su DEFAULT tal cual.
        assertEquals(
            listOf(
                CatalogColumn("box_code", "TEXT", true, null, 1),
                CatalogColumn("box_line", "INTEGER", true, null, 0),
                CatalogColumn("label", "TEXT", false, "'none'", 0),
                CatalogColumn("line", "INTEGER", true, null, 2),
            ),
            items.columns,
        )
        assertEquals(
            listOf(
                CatalogIndex(
                    "index_box_items_label_line",
                    unique = false,
                    listOf(CatalogIndexColumn("label", false), CatalogIndexColumn("line", true)),
                ),
            ),
            items.indices,
        )
        assertEquals(
            listOf(
                CatalogForeignKey(
                    listOf("box_code", "box_line"),
                    "boxes",
                    listOf("code", "line"),
                    "NO ACTION",
                    "CASCADE",
                ),
            ),
            items.foreignKeys,
        )
        val boxes = schema.tables.last()
        assertTrue(boxes.autoIncrement)
        assertFalse(boxes.hasPrimaryKeyIndex)
    }

    @Test
    fun `writes every field, and the same text twice`() {
        val schema = RoomSchemaReader.read(EXPORTED)
        val catalog =
            SchemaVersionCatalog(
                schema.version,
                schema.identityHash,
                schema.tables,
                listOf(CatalogTrigger("guard_boxes_insert", "boxes", TRIGGER_WITH_DOLLAR)),
            )

        val text = SchemaCatalogGenerator.renderVersion(catalog)

        assertEquals(text, SchemaCatalogGenerator.renderVersion(catalog))
        assertTrue(text.contains("internal fun schemaCatalogVersion2(): SchemaVersionCatalog ="))
        assertTrue(text.contains("private fun boxItemsTable(): CatalogTable ="))
        assertTrue(
            text.contains("""column("label", "TEXT", notNull = false, defaultValue = "'none'")"""),
        )
        assertTrue(
            text.contains("""column("line", "INTEGER", notNull = true, primaryKeyPosition = 2)"""),
        )
        assertTrue(text.contains("""key("line", descending = true)"""))
        assertTrue(text.contains("""columns = listOf("box_code", "box_line"),"""))
        // Un $ del texto de una guarda no abre una plantilla de Kotlin.
        assertTrue(text.contains("""SELECT '${'$'}{'${'$'}'}'"""))
        assertTrue(text.lines().all { it.length <= 100 || it.startsWith("                ") })
    }

    @Test
    fun `refuses a guard that a raw string would change`() {
        for (sql in listOf("A\n  B ", "  A\n  B", "A\n\"\"\"", "A\n\tB")) {
            val trigger = CatalogTrigger("g", "t", sql)
            val catalog = SchemaVersionCatalog(1, "x", emptyList(), listOf(trigger))
            assertThrows(IllegalArgumentException::class.java) {
                SchemaCatalogGenerator.renderVersion(catalog)
            }
        }
    }

    @Test
    fun `refuses a schema with views or without its fields`() {
        val withView = EXPORTED.replace("\"setupQueries\"", "\"views\": [{}], \"setupQueries\"")
        assertThrows(IllegalArgumentException::class.java) { RoomSchemaReader.read(withView) }
        assertThrows(IllegalArgumentException::class.java) {
            RoomSchemaReader.read("""{"formatVersion": 1, "database": {"version": 1}}""")
        }
        assertThrows(IllegalArgumentException::class.java) { RoomSchemaReader.read("{") }
    }

    @Test
    fun `reads every kind of JSON value`() {
        assertEquals(
            mapOf(
                "a" to listOf(1L, -2.5, true, false, null),
                "b" to "x\"\\/\b\u000C\n\r\t\u00e9",
                "c" to emptyMap<String, Any?>(),
                "d" to emptyList<Any?>(),
            ),
            JsonText.parse(
                """ { "a": [1, -2.5, true, false, null], "c": {}, "d": [], """ +
                    """ "b": "x\"\\\/\b\f\n\r\t\u00e9" } """,
            ),
        )
        val broken = listOf("", "[1,", "{\"a\" 1}", "\"a", "[1] 2", "{\"a\":1,\"a\":2}", "nul")
        for (text in broken + "\"\\x\"") {
            assertThrows(text, IllegalArgumentException::class.java) { JsonText.parse(text) }
        }
    }

    private companion object {
        const val TRIGGER_WITH_DOLLAR =
            "CREATE TRIGGER guard_boxes_insert BEFORE INSERT ON boxes\n" +
                "BEGIN SELECT '\$'; END"

        /** Un esquema exportado inventado, con la forma del JSON de Room 3. */
        val EXPORTED =
            """
            {
              "formatVersion": 1,
              "database": {
                "version": 2,
                "identityHash": "abc123",
                "entities": [
                  {
                    "tableName": "boxes",
                    "createSql": "CREATE TABLE IF NOT EXISTS `${'$'}{TABLE_NAME}` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `code` TEXT NOT NULL, `line` INTEGER NOT NULL)",
                    "fields": [
                      {"fieldPath": "id", "columnName": "id", "affinity": "INTEGER", "notNull": true},
                      {"fieldPath": "code", "columnName": "code", "affinity": "TEXT", "notNull": true},
                      {"fieldPath": "line", "columnName": "line", "affinity": "INTEGER", "notNull": true}
                    ],
                    "primaryKey": {"autoGenerate": true, "columnNames": ["id"]}
                  },
                  {
                    "tableName": "box_items",
                    "createSql": "CREATE TABLE IF NOT EXISTS `${'$'}{TABLE_NAME}` (`box_code` TEXT NOT NULL, `line` INTEGER NOT NULL, `box_line` INTEGER NOT NULL, `label` TEXT DEFAULT 'none', PRIMARY KEY(`box_code`, `line`))",
                    "fields": [
                      {"fieldPath": "boxCode", "columnName": "box_code", "affinity": "TEXT", "notNull": true},
                      {"fieldPath": "line", "columnName": "line", "affinity": "INTEGER", "notNull": true},
                      {"fieldPath": "boxLine", "columnName": "box_line", "affinity": "INTEGER", "notNull": true},
                      {"fieldPath": "label", "columnName": "label", "affinity": "TEXT", "defaultValue": "'none'"}
                    ],
                    "primaryKey": {"autoGenerate": false, "columnNames": ["box_code", "line"]},
                    "indices": [
                      {"name": "index_box_items_label_line", "unique": false, "columnNames": ["label", "line"], "orders": ["ASC", "DESC"], "createSql": ""}
                    ],
                    "foreignKeys": [
                      {"table": "boxes", "onDelete": "CASCADE", "onUpdate": "NO ACTION", "columns": ["box_code", "box_line"], "referencedColumns": ["code", "line"]}
                    ]
                  }
                ],
                "setupQueries": []
              }
            }
            """.trimIndent()
    }
}
