package io.github.yesidrobayo.shopledger.data.local

import android.content.Context
import androidx.room3.PooledConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.io.File

// Apoyo común de las pruebas de la base. Todos los datos son inventados.

/** Archivo de base propio de una prueba, con ruta absoluta, en la carpeta de bases de la app. */
internal fun testDatabaseFile(context: Context, name: String): File =
    context.getDatabasePath(name).absoluteFile

/** Borra la base y sus archivos auxiliares, para que cada prueba empiece sin nada. */
internal fun deleteDatabaseFiles(file: File) {
    for (suffix in listOf("", "-wal", "-shm", "-journal")) {
        File(file.path + suffix).delete()
    }
}

/**
 * Abre el archivo con el SQLite empaquetado y sin crearlo: si no existiera, fallaría en vez de
 * dejar una base vacía. Sirve para mirar lo que quedó en disco sin pasar por Room.
 */
internal fun <T> inspectWithoutRoom(file: File, block: (SQLiteConnection) -> T): T =
    BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READWRITE).use(block)

internal fun SQLiteConnection.longValue(sql: String): Long = prepare(sql).use { statement ->
    check(statement.step()) { "La consulta no devolvió filas" }
    statement.getLong(0)
}

internal fun SQLiteConnection.texts(sql: String): List<String> = prepare(sql).use { statement ->
    buildList { while (statement.step()) add(statement.getText(0)) }
}

internal suspend fun PooledConnection.longValue(sql: String): Long = usePrepared(sql) { statement ->
    check(statement.step()) { "La consulta no devolvió filas" }
    statement.getLong(0)
}

internal suspend fun PooledConnection.texts(sql: String): List<String> =
    usePrepared(sql) { statement ->
        buildList { while (statement.step()) add(statement.getText(0)) }
    }

/** Tablas propias de la base, sin las internas de SQLite, de Room ni de Android. */
internal const val OWN_TABLES_SQL =
    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
        "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name"

/** Inserta una lista de precios con columnas explícitas. `null` en la base la deja sin base. */
internal fun insertPriceListSql(
    normalizedName: String,
    kind: String,
    basePriceListId: Long?,
): String {
    val base = basePriceListId?.toString() ?: "NULL"
    val adjustment = if (basePriceListId == null) "NULL" else "-500"
    return "INSERT INTO price_lists (system_key, name, normalized_name, kind, " +
        "base_price_list_id, adjustment_bp, archived_at, created_at, updated_at) " +
        "VALUES (NULL, '$normalizedName', '$normalizedName', '$kind', $base, $adjustment, NULL, " +
        "$CREATED_AT, $CREATED_AT)"
}

/** Un instante fijo cualquiera, en milisegundos UTC. */
private const val CREATED_AT = 1_767_225_600_000L
