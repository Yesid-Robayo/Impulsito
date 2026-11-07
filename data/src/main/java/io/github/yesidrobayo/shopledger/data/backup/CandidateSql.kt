package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection

// Apoyo de SQL del validador de copias. Los nombres de tablas y columnas salen siempre del
// catálogo de la app, nunca del archivo que se revisa, y aun así van entre comillas.

/** Cuántas filas devuelve [sql]. */
internal fun SQLiteConnection.countRows(sql: String): Long = prepare(sql).use { statement ->
    var rows = 0L
    while (statement.step()) rows++
    rows
}

/** El número de la primera columna de la única fila de [sql]. */
internal fun SQLiteConnection.singleLong(sql: String): Long = prepare(sql).use { statement ->
    check(statement.step()) { "La consulta no devolvió filas" }
    statement.getLong(0)
}

/** Cada valor distinto de la primera columna de [sql], con cuántas veces aparece en la segunda. */
internal fun SQLiteConnection.textCounts(sql: String): Map<String, Long> =
    prepare(sql).use { statement ->
        buildMap { while (statement.step()) put(statement.getText(0), statement.getLong(1)) }
    }

/** Si existe la tabla [name], sin preguntarle nada a la tabla misma. */
internal fun SQLiteConnection.hasTable(name: String): Boolean =
    prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
        statement.bindText(1, name)
        statement.step()
    }

/** [name] como identificador de SQL, entre comillas dobles y con las suyas duplicadas. */
internal fun identifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
