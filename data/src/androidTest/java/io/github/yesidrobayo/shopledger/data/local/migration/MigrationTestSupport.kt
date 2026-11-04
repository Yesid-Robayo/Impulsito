package io.github.yesidrobayo.shopledger.data.local.migration

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.texts
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals

// Apoyo de las pruebas de migración. Todos los datos son inventados.

/** Un precio por encima de 2^53: un `Double` en el camino lo redondearía, y la prueba lo vería. */
internal const val PRICE_ABOVE_DOUBLE_PRECISION = 9_007_199_254_740_993L

/** El `id` más alto que entregó la secuencia de productos: un producto que se borró. */
internal const val DELETED_PRODUCT_ID = 4L

/**
 * Llena la base ficticia en la versión 1 con lo que una migración no debe perder: dinero en el
 * límite de un `Long`, saldos negativos, un movimiento anulado por su compensatorio y un producto
 * borrado que dejó la secuencia más alta que el último `id`. El saldo de cada producto es la suma
 * de su libro. Después crea las guardas, como una apertura anterior.
 */
internal fun SQLiteConnection.seedVersion1() {
    execSQL(
        "INSERT INTO products (id, name, category_id, sale_price, average_cost, on_hand_milli) " +
            "VALUES (1, 'Producto de prueba uno', NULL, $PRICE_ABOVE_DOUBLE_PRECISION, 1234567, " +
            "8250)",
    )
    execSQL(
        "INSERT INTO products (id, name, category_id, sale_price, average_cost, on_hand_milli) " +
            "VALUES (2, 'Producto de prueba dos', NULL, 0, 0, -1500)",
    )
    execSQL(
        "INSERT INTO products (id, name, category_id, sale_price, average_cost, on_hand_milli) " +
            "VALUES (3, 'Producto de prueba tres', NULL, ${Long.MAX_VALUE}, ${Long.MAX_VALUE}, 0)",
    )
    execSQL(
        "INSERT INTO products (id, name, category_id, sale_price, average_cost, on_hand_milli) " +
            "VALUES ($DELETED_PRODUCT_ID, 'Producto de prueba borrado', NULL, 1, 1, 0)",
    )
    execSQL("DELETE FROM products WHERE id = $DELETED_PRODUCT_ID")
    val movements =
        listOf(
            "1, 10500, 1234567, 12962953, NULL, '2026-01-31'",
            "1, -2250, 1234567, -2777775, NULL, '2026-02-01'",
            // El compensatorio de la fila anterior: cantidad opuesta y apunta a ella.
            "1, 2250, 1234567, 2777775, 2, '2026-02-02'",
            "1, -2250, 1234567, -2777775, NULL, '2026-02-02'",
            "2, -1500, 0, 0, NULL, '2026-02-03'",
        )
    movements.forEachIndexed { index, values ->
        execSQL(
            "INSERT INTO stock_movements (product_id, quantity_milli, unit_cost, total_cost, " +
                "reverses_movement_id, business_date, occurred_at) " +
                "VALUES ($values, ${OCCURRED_AT + index})",
        )
    }
    syncTemplateGuards()
}

/** Un instante fijo cualquiera, en milisegundos UTC. */
private const val OCCURRED_AT = 1_767_225_600_000L

/** Las columnas de la versión 1, las que toda versión posterior debe conservar tal cual. */
internal const val PRODUCTS_V1_SQL =
    "SELECT id, name, category_id, sale_price, average_cost, on_hand_milli FROM products " +
        "ORDER BY id"

internal const val MOVEMENTS_V1_SQL =
    "SELECT id, product_id, quantity_milli, unit_cost, total_cost, reverses_movement_id, " +
        "business_date, occurred_at FROM stock_movements ORDER BY id"

/** Productos cuyo saldo no es la suma de su libro. Debe quedar vacío. */
internal const val UNBALANCED_PRODUCTS_SQL =
    "SELECT p.id FROM products p WHERE p.on_hand_milli <> " +
        "(SELECT COALESCE(SUM(m.quantity_milli), 0) FROM stock_movements m " +
        "WHERE m.product_id = p.id)"

/**
 * Todas las filas de la consulta, con su tipo de SQLite. Un número que no sea entero falla: el
 * dinero y las cantidades nunca son de punto flotante.
 */
internal fun SQLiteConnection.rows(sql: String): List<List<Any?>> = prepare(sql).use { statement ->
    buildList {
        while (statement.step()) {
            add(
                (0 until statement.getColumnCount()).map { column ->
                    when (val type = statement.getColumnType(column)) {
                        SQLITE_DATA_INTEGER -> statement.getLong(column)
                        SQLITE_DATA_TEXT -> statement.getText(column)
                        SQLITE_DATA_NULL -> null
                        else -> error("Tipo $type inesperado en ${statement.getColumnName(column)}")
                    }
                },
            )
        }
    }
}

/** Lo que una migración debe conservar o dejar como estaba, leído sin pasar por Room. */
internal data class MigrationSnapshot(
    val userVersion: Long,
    val identityHash: String?,
    val schema: List<List<Any?>>,
    val products: List<List<Any?>>,
    val movements: List<List<Any?>>,
    val sequences: List<List<Any?>>,
)

internal fun SQLiteConnection.snapshot(): MigrationSnapshot = MigrationSnapshot(
    userVersion = longValue("PRAGMA user_version"),
    identityHash = texts(
        "SELECT identity_hash FROM room_master_table WHERE id = 42",
    ).singleOrNull(),
    schema = rows("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name"),
    products = rows(PRODUCTS_V1_SQL),
    movements = rows(MOVEMENTS_V1_SQL),
    sequences = rows("SELECT name, seq FROM sqlite_sequence ORDER BY name"),
)

/** La huella que el JSON exportado de [version] le da a la base ficticia. */
internal fun templateIdentityHash(context: Context, version: Int): String {
    val path = "${TemplateDatabase::class.java.name}/$version.json"
    val json = context.assets.open(path).bufferedReader().use { it.readText() }
    return JSONObject(json).getJSONObject("database").getString("identityHash")
}

/**
 * Abre la base ficticia como la app abre la suya: SQLite empaquetado, WAL y sin cierre automático.
 * Al abrir sincroniza las guardas, como hará la base real.
 */
internal fun openTemplate(
    context: Context,
    file: File,
    migrations: List<Migration>,
): TemplateDatabase = Room
    .databaseBuilder(context, TemplateDatabase::class.java, file.path)
    .setDriver(BundledSQLiteDriver())
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .setQueryCoroutineContext(Dispatchers.IO)
    .addMigrations(*migrations.toTypedArray())
    .addCallback(
        object : RoomDatabase.Callback() {
            override suspend fun onOpen(connection: SQLiteConnection) {
                connection.syncTemplateGuards()
            }
        },
    ).build()

/** Abre, hace la primera consulta, que es cuando Room migra, y cierra. */
internal suspend fun openAndMigrate(context: Context, file: File, migrations: List<Migration>) {
    val database = openTemplate(context, file, migrations)
    try {
        database.useReaderConnection { it.longValue("PRAGMA user_version") }
    } finally {
        database.close()
    }
}

/**
 * Columnas, en su orden, índices y llaves de cada tabla propia, sin depender del texto con que se
 * creó ni del orden en que se crearon los índices.
 */
internal fun SQLiteConnection.structure(): List<List<Any?>> = rows(
    "SELECT m.name, 'column', c.cid, c.name, c.type, c.\"notnull\", c.dflt_value, c.pk " +
        "FROM sqlite_master m, pragma_table_info(m.name) c WHERE m.type = 'table' " +
        "AND m.name NOT LIKE 'sqlite_%' AND m.name <> 'room_master_table' " +
        "UNION ALL SELECT m.name, 'index', NULL, il.name, il.\"unique\", il.origin, " +
        "(SELECT group_concat(ii.name, ',') FROM pragma_index_info(il.name) ii), il.partial " +
        "FROM sqlite_master m, pragma_index_list(m.name) il WHERE m.type = 'table' " +
        "AND m.name NOT LIKE 'sqlite_%' AND m.name <> 'room_master_table' " +
        "UNION ALL SELECT m.name, 'foreign key', NULL, fk.\"from\", fk.\"table\", fk.\"to\", " +
        "fk.on_update, fk.on_delete " +
        "FROM sqlite_master m, pragma_foreign_key_list(m.name) fk WHERE m.type = 'table' " +
        "AND m.name NOT LIKE 'sqlite_%' AND m.name <> 'room_master_table' " +
        "ORDER BY 1, 2, 4",
)

/**
 * Exige un solo salto por versión, de [firstVersion] a [latestVersion], cada uno a la siguiente.
 * Sin un salto, una base de esa versión no abriría; con dos, no se sabría cuál corre.
 */
internal fun assertOneStepPerVersion(
    migrations: List<Migration>,
    firstVersion: Int,
    latestVersion: Int,
) {
    assertEquals(
        (firstVersion until latestVersion).map { it to it + 1 },
        migrations.map { it.startVersion to it.endVersion },
    )
}
