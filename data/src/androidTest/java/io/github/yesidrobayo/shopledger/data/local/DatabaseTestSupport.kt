package io.github.yesidrobayo.shopledger.data.local

import android.content.Context
import androidx.room3.PooledConnection
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

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

/** Las tablas que crea el esquema compilado, en el orden de [OWN_TABLES_SQL]. */
internal val SCHEMA_TABLES =
    listOf(
        "app_settings",
        "backup_state",
        "categories",
        "custom_field_definitions",
        "customer_ledger_entries",
        "customers",
        "expense_categories",
        "expenses",
        "low_stock_alerts",
        "loyalty_ledger_entries",
        "media_assets",
        "message_templates",
        "price_lists",
        "product_components",
        "product_custom_values",
        "product_price_history",
        "product_tags",
        "products",
        "purchase_lines",
        "purchases",
        "sale_line_components",
        "sale_lines",
        "sales",
        "stock_movements",
        "suppliers",
        "tags",
        "tax_rates",
    )

/** Cada índice de [table] como `nombre (columnas)`, con `unique` delante si lo es, por nombre. */
internal suspend fun RoomDatabase.indexesOf(table: String): List<String> = useReaderConnection {
    it.texts(
        "SELECT CASE il.\"unique\" WHEN 1 THEN 'unique ' ELSE '' END || il.name || ' (' || " +
            "(SELECT group_concat(ii.name, ', ') FROM " +
            "(SELECT name FROM pragma_index_info(il.name) ORDER BY seqno) ii) || ')' " +
            "FROM pragma_index_list('$table') il ORDER BY il.name",
    )
}

/** Cada llave de [table] como `columna -> tabla ACCIÓN`, por columna. */
internal suspend fun RoomDatabase.foreignKeysOf(table: String): List<String> = useReaderConnection {
    it.texts(
        "SELECT \"from\" || ' -> ' || \"table\" || ' ' || on_delete " +
            "FROM pragma_foreign_key_list('$table') ORDER BY \"from\"",
    )
}

/** Lectoras que Room abre en WAL si no se le pide otra cantidad. */
internal const val WAL_READERS = 4

/** Espera máxima a que las lectoras lleguen todas: si una no llega, la prueba falla sin colgarse. */
private const val READERS_TIMEOUT_MS = 10_000L

/**
 * Corre [block] en todas las lectoras a la vez y devuelve lo que dio cada una. Cada lectora
 * retiene su conexión hasta que llegan todas, así que son conexiones distintas.
 */
internal suspend fun <T> RoomDatabase.onEveryReader(
    block: suspend (PooledConnection) -> T,
): List<T> = coroutineScope {
    val arrived = AtomicInteger()
    val allArrived = CompletableDeferred<Unit>()
    List(WAL_READERS) {
        async(Dispatchers.IO) {
            useReaderConnection { connection ->
                val value = block(connection)
                if (arrived.incrementAndGet() == WAL_READERS) allArrived.complete(Unit)
                withTimeout(READERS_TIMEOUT_MS) { allArrived.await() }
                value
            }
        }
    }.awaitAll()
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
