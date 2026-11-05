package io.github.yesidrobayo.shopledger.data.local.guard

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

// Compara una base con el catálogo de su versión, como lo hará la revisión de una copia: tablas e
// índices por estructura, con los pragma de SQLite, y guardas por texto exacto.
//
// Vive en las pruebas porque solo lee bases que la app creó. Leer una copia hostil pide más
// cuidado: por ejemplo, no pedir el pragma de una tabla virtual antes de rechazarla.

/** Una fila de `sqlite_master`. */
internal data class MasterRow(
    val type: String,
    val name: String,
    val table: String,
    val sql: String?,
)

internal fun SQLiteConnection.masterRows(): List<MasterRow> =
    prepare("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name").use {
        buildList {
            while (it.step()) {
                add(MasterRow(it.getText(0), it.getText(1), it.getText(2), it.textOrNull(3)))
            }
        }
    }

/**
 * Lo que la base tiene distinto de [catalog], una frase por diferencia. Vacía: la base es
 * exactamente la de esa versión.
 */
internal fun SQLiteConnection.differencesFrom(catalog: SchemaVersionCatalog): List<String> {
    val rows = masterRows()
    val differences = mutableListOf<String>()
    val present = mutableSetOf<String>()
    for (row in rows) {
        present += "${row.type}:${row.name}"
        when (row.type) {
            "table" -> {
                val sql = row.sql.orEmpty()
                when {
                    VIRTUAL_TABLE.containsMatchIn(sql) -> differences += "tabla virtual ${row.name}"

                    row.name in SchemaCatalog.INTERNAL_TABLES -> Unit

                    else -> {
                        val expected = catalog.table(row.name)
                        if (expected == null) {
                            differences += "tabla de más ${row.name}"
                        } else {
                            differences += tableDifferences(expected, sql)
                        }
                    }
                }
            }

            "index" -> {
                val table = catalog.table(row.table)
                val allowed =
                    if (row.name.startsWith(SchemaCatalog.AUTOINDEX_PREFIX)) {
                        table?.hasPrimaryKeyIndex == true
                    } else {
                        table?.indices?.any { it.name == row.name } == true
                    }
                if (!allowed) differences += "índice de más ${row.name}"
            }

            "trigger" -> {
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
        if ("table:${table.name}" !in present) differences += "falta la tabla ${table.name}"
        for (index in table.indices) {
            if ("index:${index.name}" !in present) differences += "falta el índice ${index.name}"
        }
    }
    for (trigger in catalog.triggers) {
        if ("trigger:${trigger.name}" !in present) differences += "falta la guarda ${trigger.name}"
    }
    return differences
}

/** La tabla [expected] tal como la describen los pragma, frente a la del catálogo. */
private fun SQLiteConnection.tableDifferences(expected: CatalogTable, sql: String): List<String> {
    val name = expected.name
    val differences = mutableListOf<String>()
    val columns =
        rows(
            "SELECT name, type, \"notnull\", dflt_value, pk, hidden FROM pragma_table_xinfo(?)",
            name,
        ) {
            if (it.getLong(5) != 0L) differences += "$name.${it.getText(0)} es generada u oculta"
            CatalogColumn(
                name = it.getText(0),
                type = it.getText(1),
                notNull = it.getLong(2) == 1L,
                defaultValue = it.textOrNull(3),
                primaryKeyPosition = it.getLong(4).toInt(),
            )
        }.sortedBy { it.name }
    val indices =
        rows("SELECT name, \"unique\", origin, partial FROM pragma_index_list(?)", name) { index ->
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
            val id = it.getLong(0).toString()
            listOf(id, it.getText(1), it.getText(2), it.textOrNull(3).orEmpty()) +
                listOf(it.getText(4), it.getText(5))
        }.groupBy { it[0] }
            .values
            .map { parts ->
                CatalogForeignKey(
                    columns = parts.map { it[2] },
                    referencedTable = parts.first()[1],
                    referencedColumns = parts.map { it[3] },
                    onUpdate = parts.first()[4],
                    onDelete = parts.first()[5],
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
    if (actual != expected) differences += "la tabla $name es otra: $actual"
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

private fun <T> SQLiteConnection.rows(
    sql: String,
    argument: String,
    read: (SQLiteStatement) -> T,
): List<T> = prepare(sql).use { statement ->
    statement.bindText(1, argument)
    buildList { while (statement.step()) add(read(statement)) }
}

private fun SQLiteStatement.textOrNull(column: Int): String? =
    if (isNull(column)) null else getText(column)

private val VIRTUAL_TABLE = Regex("""^\s*CREATE\s+VIRTUAL\s+TABLE""", RegexOption.IGNORE_CASE)
private val AUTOINCREMENT = Regex("""\bAUTOINCREMENT\b""", RegexOption.IGNORE_CASE)
