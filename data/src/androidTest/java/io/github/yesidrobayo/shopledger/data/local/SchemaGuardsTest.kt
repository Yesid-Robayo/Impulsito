package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.PooledConnection
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.guard.Awaiting
import io.github.yesidrobayo.shopledger.data.local.guard.Guard
import io.github.yesidrobayo.shopledger.data.local.guard.GuardSync
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El catálogo de guardas y su sincronización: una base nueva nace con exactamente las guardas del
 * catálogo, abrirla otra vez no cambia nada, una apertura repara las que faltan, las de otro texto
 * y las que sobran, y una sincronización que falla a mitad deja las guardas que había.
 *
 * Las bases se tocan sin Room, con el SQLite empaquetado, como las dejaría una restauración, una
 * migración o un error: Room no deja escribir triggers por sus DAO.
 */
@RunWith(AndroidJUnit4::class)
internal class SchemaGuardsTest : CatalogDatabaseTest() {
    @Test
    fun aNewDatabaseHasExactlyTheCatalogGuardsWithTheirText() = runBlocking {
        val installed = database.useReaderConnection { it.triggers() }

        // Ningún otro trigger: Room no deja ninguno en la base.
        assertEquals(SchemaGuards.all.map { it.name }.toSet(), installed.keys)
        for (guard in SchemaGuards.all) {
            assertEquals(guard.name, guard.sql.removeSuffix(";"), installed[guard.name])
        }
        // Cada tabla tiene sus guardas, y cada guarda protege una tabla que existe.
        assertEquals(SCHEMA_TABLES.toSet(), SchemaGuards.all.map { it.table }.toSet())
    }

    @Test
    fun onlyTheGuardsThatWaitForSomethingMissingAreInstalledDifferent() = runBlocking {
        val awaiting = SchemaGuards.all.filter { it.awaiting.isNotEmpty() }

        assertEquals(AWAITING, awaiting.map { it.name }.toSet())
        for (guard in SchemaGuards.all) {
            if (guard in awaiting) {
                assertNotEquals(guard.name, guard.catalogSql, guard.sql)
            } else {
                assertEquals(guard.name, guard.catalogSql, guard.sql)
            }
        }
        // Lo que esperan sigue faltando. Quien crea la tabla o la columna borra su espera, y la
        // guarda vuelve a su texto completo.
        val waitedFor = awaiting.flatMap { guard -> guard.awaiting.flatMap { it.waitsFor } }.toSet()
        assertEquals(AWAITING_FOR, waitedFor)
        database.useReaderConnection { connection ->
            for (item in waitedFor) {
                val table = item.substringBefore('.')
                val column = item.substringAfter('.', missingDelimiterValue = "")
                val tables =
                    connection.longValue("SELECT COUNT(*) FROM sqlite_master WHERE name = '$table'")
                if (column.isEmpty()) {
                    assertEquals("$item ya existe: su guarda vuelve a su texto", 0L, tables)
                } else {
                    // La tabla existe y le falta la columna.
                    assertEquals("Falta la tabla de $item", 1L, tables)
                    val columns =
                        connection.longValue(
                            "SELECT COUNT(*) FROM pragma_table_info('$table') " +
                                "WHERE name = '$column'",
                        )
                    assertEquals("$item ya existe: su guarda vuelve a su texto", 0L, columns)
                }
            }
        }
    }

    @Test
    fun reopeningTheDatabaseChangesNothing() = runBlocking {
        val before = database.useReaderConnection { it.longValue("PRAGMA schema_version") }
        database.close()

        database = AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK)
        val after = database.useReaderConnection { it.longValue("PRAGMA schema_version") }
        database.close()
        val again = inspectWithoutRoom(file) { SchemaGuards.sync(it) }
        val last = inspectWithoutRoom(file) { it.longValue("PRAGMA schema_version") }

        // Ninguna guarda se volvió a crear: el esquema no cambió de versión.
        assertEquals(before, after)
        assertTrue("La segunda sincronización cambió $again", again.changedNothing)
        assertEquals(before, last)
        database = AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK)
    }

    @Test
    fun openingRestoresMissingAndChangedGuardsAndDropsExtraOnes() = runBlocking {
        database.close()
        inspectWithoutRoom(file) { it.tamper() }

        // Una sincronización dice lo que cambió.
        val sync = inspectWithoutRoom(file) { SchemaGuards.sync(it) }
        assertEquals(expectedRepair(), sync)

        // Y una apertura de la app hace lo mismo, sin tocar los triggers que no son guardas.
        inspectWithoutRoom(file) { it.tamper() }
        database = AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK)
        val installed = database.useReaderConnection { it.triggers() }

        assertEquals(SchemaGuards.all.map { it.name }.toSet() + FOREIGN_TRIGGER, installed.keys)
        for (guard in SchemaGuards.all) {
            assertEquals(guard.name, guard.sql.removeSuffix(";"), installed[guard.name])
        }
    }

    @Test
    fun aSyncThatFailsHalfwayLeavesTheGuardsThatWereThere() = runBlocking {
        database.close()
        inspectWithoutRoom(file) { connection ->
            connection.tamper()
            val before = connection.triggers()
            // La última guarda no se puede crear: su tabla no existe. Antes de llegar a ella, la
            // sincronización ya borró la que sobra, recreó la de otro texto y creó la que faltaba.
            val broken =
                Guard(
                    "guard_missing_insert",
                    "missing",
                    BROKEN_GUARD_SQL,
                    BROKEN_GUARD_SQL,
                )

            assertThrows(SQLiteException::class.java) {
                SchemaGuards.sync(connection, SchemaGuards.all + broken)
            }

            assertEquals(before, connection.triggers())
            assertFalse("Quedó una transacción abierta", connection.inTransaction())
        }

        // La siguiente apertura las deja todas.
        database = AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK)
        val installed = database.useReaderConnection { it.triggers() }
        assertTrue(installed.keys.containsAll(SchemaGuards.all.map { it.name }))
    }

    @Test
    fun syncAlsoWorksInsideATransaction() = runBlocking {
        database.close()
        inspectWithoutRoom(file) { connection ->
            // Al crear la base, Room la corre dentro de su propia transacción.
            connection.execSQL("BEGIN IMMEDIATE")
            SchemaGuards.drop(connection, "guard_sales_no_delete")
            val sync = SchemaGuards.sync(connection)
            assertTrue(connection.inTransaction())
            connection.execSQL("COMMIT")

            assertEquals(listOf("guard_sales_no_delete"), sync.created)
            assertEquals(
                SchemaGuards.named("guard_sales_no_delete").sql.removeSuffix(";"),
                connection.triggers()["guard_sales_no_delete"],
            )
        }
        database = AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK)
    }

    @Test
    fun aGuardIsFoundByItsNameAndAWrongNameFails() {
        assertEquals("sales", SchemaGuards.named("guard_sales_void_only").table)
        assertThrows(IllegalArgumentException::class.java) { SchemaGuards.named("guard_nothing") }
    }

    @Test
    fun aWaitingPartMustBeExactlyWhereItSays() {
        val sql = "CREATE TRIGGER guard_t BEFORE INSERT ON t\nWHEN NOT IFNULL((\n" +
            "  NEW.a = 1\n  AND NEW.b = 2 AND NEW.b = 2\n), 0)\nBEGIN SELECT 1; END;"

        assertEquals(
            sql.replace("\n  NEW.a = 1", ""),
            Awaiting.Line("guard_t", "NEW.a", listOf("t.a")).applyTo(sql),
        )
        assertEquals(
            sql.replace("NEW.a = 1", "NEW.a = 3"),
            Awaiting.Line("guard_t", "NEW.a", listOf("t.a"), "  NEW.a = 3").applyTo(sql),
        )
        assertEquals(
            sql.replace("\n  NEW.a = 1", ""),
            Awaiting.Columns("guard_t", "t", listOf("a")).applyTo(sql),
        )
        assertEquals(listOf("t.a"), Awaiting.Columns("guard_t", "t", listOf("a")).waitsFor)
        assertEquals(
            sql.replace(" = 1", ""),
            Awaiting.Part("guard_t", " = 1", listOf("t.a")).applyTo(sql),
        )
        // Si la parte no está, o no está una sola vez, la lista de esperas ya no es la de la
        // guarda: el catálogo no se carga.
        for (misplaced in listOf(
            Awaiting.Line("guard_t", "NEW.c", listOf("t.c")),
            Awaiting.Line("guard_t", "NEW.", listOf("t.a")),
            Awaiting.Columns("guard_t", "t", listOf("c")),
            Awaiting.Part("guard_t", "NEW.b = 2", listOf("t.b")),
            Awaiting.Part("guard_t", "NEW.c", listOf("t.c")),
        )) {
            try {
                misplaced.applyTo(sql)
                fail("Debía fallar: $misplaced")
            } catch (expected: IllegalArgumentException) {
                // Lo esperado.
            }
        }
    }

    /**
     * Lo que encuentra una apertura en una base tocada a mano: una guarda que falta, una con otro
     * texto, una que sobra y un trigger que no es guarda.
     */
    private fun SQLiteConnection.tamper() {
        SchemaGuards.drop(this, "guard_sales_no_delete")
        SchemaGuards.drop(this, "guard_stock_movements_no_update")
        execSQL(
            "CREATE TRIGGER guard_stock_movements_no_update BEFORE UPDATE ON stock_movements " +
                "BEGIN SELECT 1; END",
        )
        execSQL("CREATE TRIGGER guard_extra BEFORE INSERT ON sales BEGIN SELECT 1; END")
        if (FOREIGN_TRIGGER !in triggers()) {
            execSQL(
                "CREATE TRIGGER $FOREIGN_TRIGGER AFTER INSERT ON categories BEGIN SELECT 1; END",
            )
        }
    }

    private fun expectedRepair() = GuardSync(
        created = listOf("guard_sales_no_delete"),
        replaced = listOf("guard_stock_movements_no_update"),
        dropped = listOf("guard_extra"),
    )

    private companion object {
        /** Un trigger propio de otra cosa: la sincronización solo maneja los `guard_*`. */
        const val FOREIGN_TRIGGER = "audit_categories"

        const val BROKEN_GUARD_SQL =
            "CREATE TRIGGER guard_missing_insert BEFORE INSERT ON missing BEGIN SELECT 1; END;"

        /** Las guardas que hoy se instalan sin una parte, porque falta lo que nombran. */
        val AWAITING =
            setOf(
                "guard_app_settings_insert",
                "guard_app_settings_update",
                "guard_products_insert",
                "guard_products_update",
                "guard_stock_movements_insert",
                "guard_purchases_insert",
                "guard_sales_insert",
                "guard_sales_void_only",
                "guard_customer_ledger_entries_insert",
                "guard_expenses_insert",
            )

        /** Lo que esperan: tablas, y columnas de los ajustes que llegan con la región. */
        val AWAITING_FOR =
            setOf(
                "product_units",
                "stock_lots",
                "sale_returns",
            ) + listOf(
                "currency_code",
                "currency_decimals",
                "currency_symbol",
                "currency_locked_at",
                "cash_rounding",
                "format_locale",
                "business_time_zone",
                "week_start_day",
                "csv_separator",
                "tax_mode",
                "purchase_tax_recoverable",
                "default_rounding_step",
                "tips_enabled",
                "reference_currency_code",
                "reference_currency_decimals",
                "cost_stale_days",
                "expiry_notice_days",
                "loyalty_enabled",
                "loyalty_amount_per_point",
                "loyalty_point_value",
                "revenue_limit_amount",
                "revenue_limit_months",
                "daily_summary_time",
                "receipt_business_details",
                "receipt_footer_text",
                "fiscal_portal_url",
                "discreet_mode_enabled",
                "media_on_metered",
                "update_check_enabled",
            ).map { "app_settings.$it" }
    }
}

/** Todos los triggers de la base, con su texto, por nombre. */
private fun SQLiteConnection.triggers(): Map<String, String> =
    prepare("SELECT name, sql FROM sqlite_master WHERE type = 'trigger'").use { statement ->
        buildMap { while (statement.step()) put(statement.getText(0), statement.getText(1)) }
    }

private suspend fun PooledConnection.triggers(): Map<String, String> =
    usePrepared("SELECT name, sql FROM sqlite_master WHERE type = 'trigger'") { statement ->
        buildMap { while (statement.step()) put(statement.getText(0), statement.getText(1)) }
    }
