package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.github.yesidrobayo.shopledger.data.local.guard.CatalogColumn
import io.github.yesidrobayo.shopledger.data.local.guard.CatalogForeignKey
import io.github.yesidrobayo.shopledger.data.local.guard.CatalogIndex
import io.github.yesidrobayo.shopledger.data.local.guard.CatalogIndexColumn
import io.github.yesidrobayo.shopledger.data.local.guard.CatalogTable
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaCatalog
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaVersionCatalog

/**
 * Compara el esquema de una base con el catálogo de su versión: la lista permitida que va antes de
 * `integrity_check`, porque una base bien formada y hostil da `ok`.
 *
 * Corre en dos pasadas, y la segunda solo si la primera no encontró nada:
 *
 * 1. **Solo `sqlite_master`, sin ningún `pragma`.** Cada fila debe ser una tabla, un índice o una
 *    guarda del catálogo, o una tabla interna de SQLite, Room o Android; las guardas, con su texto
 *    exacto. Se rechaza toda vista, toda tabla virtual y toda tabla de más, como las internas de
 *    una tabla de búsqueda. Pedir el `pragma` de una tabla virtual ya ejecutaría su código, por eso
 *    esta pasada no pide ninguno.
 * 2. **La estructura, con `pragma_table_xinfo`, `pragma_index_list`, `pragma_index_xinfo` y
 *    `pragma_foreign_key_list`,** nunca con el texto de `sqlite_master`: una tabla reconstruida por
 *    una migración se guarda con otro texto y sigue siendo la misma. Ningún `pragma` muestra un
 *    `CHECK` ni un `AUTOINCREMENT`, así que esos dos se buscan en el texto de la tabla, sin comparar
 *    el resto del texto. Se rechaza toda columna generada u oculta, todo `DEFAULT` que el catálogo
 *    no tenga, todo índice parcial o de expresión y toda intercalación que no sea la binaria.
 *
 * Cada diferencia es una frase para las pruebas. Nunca va al registro: puede llevar nombres que
 * eligió quien armó el archivo.
 */
internal object CandidateSchema {
    /** Las diferencias de la base de [connection] con [catalog]. Vacía: la base es esa versión. */
    fun differences(connection: SQLiteConnection, catalog: SchemaVersionCatalog): List<String> {
        val rows = connection.masterRows()
        val listed = listedDifferences(rows, catalog)
        if (listed.isNotEmpty()) return listed
        val tables = rows.filter { it.type == TABLE && it.name !in SchemaCatalog.INTERNAL_TABLES }
        return tables.flatMap { row ->
            connection.tableDifferences(requireNotNull(catalog.table(row.name)), row.sql.orEmpty())
        }
    }

    /** La primera pasada: lo que hay en `sqlite_master`, por tipo, nombre y texto de las guardas. */
    private fun listedDifferences(
        rows: List<MasterRow>,
        catalog: SchemaVersionCatalog,
    ): List<String> {
        val differences = mutableListOf<String>()
        val present = mutableSetOf<String>()
        for (row in rows) {
            present += "${row.type}:${row.name}"
            when (row.type) {
                TABLE -> {
                    val sql = row.sql.orEmpty()
                    when {
                        VIRTUAL_TABLE.containsMatchIn(sql) ->
                            differences += "tabla virtual ${row.name}"

                        row.name in SchemaCatalog.INTERNAL_TABLES -> Unit

                        catalog.table(row.name) == null -> differences += "tabla de más ${row.name}"
                    }
                }

                INDEX -> {
                    val table = catalog.table(row.table)
                    val allowed =
                        if (row.name.startsWith(SchemaCatalog.AUTOINDEX_PREFIX)) {
                            table?.hasPrimaryKeyIndex == true
                        } else {
                            table?.indices?.any { it.name == row.name } == true
                        }
                    if (!allowed) differences += "índice de más ${row.name}"
                }

                TRIGGER -> {
                    val expected = catalog.trigger(row.name)
                    when {
                        expected == null -> differences += "trigger de más ${row.name}"

                        expected.table != row.table || expected.sql != row.sql ->
                            differences += "guarda ${row.name} con otro texto"
                    }
                }

                else -> differences += "${row.type} ${row.name}, que no es tabla, índice ni trigger"
            }
        }
        for (table in catalog.tables) {
            if ("$TABLE:${table.name}" !in present) differences += "falta la tabla ${table.name}"
            for (index in table.indices) {
                val key = "$INDEX:${index.name}"
                if (key !in present) differences += "falta el índice ${index.name}"
            }
        }
        for (trigger in catalog.triggers) {
            if ("$TRIGGER:${trigger.name}" !in present) {
                differences += "falta la guarda ${trigger.name}"
            }
        }
        return differences
    }

    /** La tabla [expected] tal como la describen los `pragma`, frente a la del catálogo. */
    private fun SQLiteConnection.tableDifferences(
        expected: CatalogTable,
        sql: String,
    ): List<String> {
        val name = expected.name
        val differences = mutableListOf<String>()
        if (CHECK_CONSTRAINT.containsMatchIn(sql)) differences += "$name tiene un CHECK"
        val columns =
            rows(
                "SELECT name, type, \"notnull\", dflt_value, pk, hidden FROM pragma_table_xinfo(?)",
                name,
            ) {
                if (it.getLong(5) !=
                    0L
                ) {
                    differences += "$name.${it.getText(0)} es generada u oculta"
                }
                CatalogColumn(
                    name = it.getText(0),
                    type = it.getText(1),
                    notNull = it.getLong(2) == 1L,
                    defaultValue = it.textOrNull(3),
                    primaryKeyPosition = it.getLong(4).toInt(),
                )
            }.sortedBy { it.name }
        val indices =
            rows(
                "SELECT name, \"unique\", origin, partial FROM pragma_index_list(?)",
                name,
            ) { index ->
                val indexName = index.getText(0)
                val origin = index.getText(2)
                if (index.getLong(3) != 0L) differences += "$indexName es parcial"
                if (origin == "pk") return@rows null
                if (origin != "c") differences += "$indexName no se creó con CREATE INDEX"
                CatalogIndex(
                    name = indexName,
                    unique = index.getLong(1) == 1L,
                    columns = indexColumns(indexName, differences),
                )
            }.filterNotNull().sortedBy { it.name }
        val foreignKeys =
            rows(
                "SELECT id, \"table\", \"from\", \"to\", on_update, on_delete, match " +
                    "FROM pragma_foreign_key_list(?) ORDER BY id, seq",
                name,
            ) {
                if (it.getText(6) != "NONE") differences += "$name tiene una llave con MATCH"
                ForeignKeyPart(
                    id = it.getLong(0),
                    table = it.getText(1),
                    from = it.getText(2),
                    to = it.textOrNull(3).orEmpty(),
                    onUpdate = it.getText(4),
                    onDelete = it.getText(5),
                )
            }.groupBy { it.id }
                .values
                .map { parts ->
                    CatalogForeignKey(
                        columns = parts.map { it.from },
                        referencedTable = parts.first().table,
                        referencedColumns = parts.map { it.to },
                        onUpdate = parts.first().onUpdate,
                        onDelete = parts.first().onDelete,
                    )
                }.sortedWith(compareBy({ it.columns.joinToString(",") }, { it.referencedTable }))
        val actual =
            CatalogTable(
                name = name,
                autoIncrement = AUTOINCREMENT.containsMatchIn(sql),
                columns = columns,
                indices = indices,
                foreignKeys = foreignKeys,
            )
        if (actual != expected) differences += "la tabla $name es otra"
        return differences
    }

    private fun SQLiteConnection.indexColumns(
        index: String,
        differences: MutableList<String>,
    ): List<CatalogIndexColumn> = rows(
        "SELECT cid, name, \"desc\", coll FROM pragma_index_xinfo(?) WHERE key = 1 ORDER BY seqno",
        index,
    ) {
        if (it.getLong(0) < 0L) differences += "$index es de expresión"
        if (it.getText(3) != "BINARY") differences += "$index usa otra intercalación"
        CatalogIndexColumn(it.textOrNull(1).orEmpty(), descending = it.getLong(2) == 1L)
    }

    private class ForeignKeyPart(
        val id: Long,
        val table: String,
        val from: String,
        val to: String,
        val onUpdate: String,
        val onDelete: String,
    )

    private const val TABLE = "table"
    private const val INDEX = "index"
    private const val TRIGGER = "trigger"

    private val VIRTUAL_TABLE = Regex("""^\s*CREATE\s+VIRTUAL\s+TABLE""", RegexOption.IGNORE_CASE)
    private val AUTOINCREMENT = Regex("""\bAUTOINCREMENT\b""", RegexOption.IGNORE_CASE)

    /**
     * Un `CHECK` como palabra, seguido de su paréntesis. Una columna como `last_human_check_at` no
     * coincide: el guion bajo es parte de la palabra.
     */
    private val CHECK_CONSTRAINT = Regex("""\bCHECK\s*\(""", RegexOption.IGNORE_CASE)
}

/** Una fila de `sqlite_master`. */
internal data class MasterRow(
    val type: String,
    val name: String,
    val table: String,
    val sql: String?,
)

/** Las filas de `sqlite_master`, ordenadas por tipo y nombre. */
internal fun SQLiteConnection.masterRows(): List<MasterRow> =
    prepare("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name").use {
        buildList {
            while (it.step()) {
                add(MasterRow(it.getText(0), it.getText(1), it.getText(2), it.textOrNull(3)))
            }
        }
    }

private fun <T> SQLiteConnection.rows(
    sql: String,
    argument: String,
    read: (SQLiteStatement) -> T,
): List<T> = prepare(sql).use { statement ->
    statement.bindText(1, argument)
    buildList { while (statement.step()) add(read(statement)) }
}

/** El texto de la columna [column], o `null` si vale nulo. */
internal fun SQLiteStatement.textOrNull(column: Int): String? =
    if (isNull(column)) null else getText(column)
