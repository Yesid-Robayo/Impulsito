package io.github.yesidrobayo.shopledger.data.local

import androidx.sqlite.SQLiteException
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

// Apoyo de las sondas de las guardas: filas válidas escritas como SQL, que cada sonda rompe en una
// sola columna. Por SQL, y no por los DAO, porque una sonda necesita lo que un DAO no deja
// escribir: un real en una columna de dinero, un nulo donde la entidad no lo admite o un INSERT OR
// REPLACE.
// Todos los nombres y montos son inventados.

/** Un valor que va tal cual en el SQL, como una subconsulta. */
internal class Sql(val text: String)

/** Una fila escrita como SQL, columna por columna, en el orden en que se dieron. */
internal class Row(private val values: Map<String, Any?>) {
    /** La misma fila con [changes] en lugar de sus valores. */
    fun with(vararg changes: Pair<String, Any?>): Row {
        for ((column, _) in changes) require(column in values) { "La fila no tiene $column" }
        return Row(values + changes)
    }

    /** La misma fila con un `id` dado, como la escribiría quien intenta reemplazar otra. */
    fun withId(id: Long): Row = Row(linkedMapOf<String, Any?>("id" to id) + values)

    fun insertInto(table: String): String = "INSERT INTO $table " + columnsAndValues()

    /** Un `INSERT OR REPLACE`, que borra la fila que choca sin pasar por su guarda de borrado. */
    fun insertOrReplaceInto(table: String): String =
        "INSERT OR REPLACE INTO $table " + columnsAndValues()

    private fun columnsAndValues(): String = values.keys.joinToString(", ", "(", ")") + " VALUES " +
        values.values.joinToString(", ", "(", ")") { literal(it) }
}

internal fun row(vararg values: Pair<String, Any?>): Row = Row(linkedMapOf(*values))

/** Un `UPDATE` de [table] con [changes], solo en la fila [id]. */
internal fun update(table: String, id: Long, vararg changes: Pair<String, Any?>): String =
    "UPDATE $table SET " + changes.joinToString(", ") { (column, value) ->
        "$column = ${literal(value)}"
    } + " WHERE id = $id"

private fun literal(value: Any?): String = when (value) {
    null -> "NULL"
    is Sql -> value.text
    is String -> "'" + value.replace("'", "''") + "'"
    is Int, is Long, is Double -> value.toString()
    else -> error("Valor que una sonda no sabe escribir: $value")
}

/** Una lista de precios fija, sin clave inicial. */
internal fun priceListRow(name: String = "mayorista"): Row = row(
    "system_key" to null,
    "name" to name,
    "normalized_name" to name,
    "kind" to "FIXED",
    "base_price_list_id" to null,
    "adjustment_bp" to null,
    "archived_at" to null,
    "created_at" to CREATED_AT,
    "updated_at" to CREATED_AT,
)

internal fun categoryRow(name: String = "bebidas"): Row = row(
    "name" to name,
    "normalized_name" to name,
    "target_margin_bp" to null,
    "created_at" to CREATED_AT,
    "updated_at" to CREATED_AT,
)

internal fun supplierRow(name: String = "distribuidora andina"): Row = row(
    "name" to name,
    "normalized_name" to name,
    "phone" to null,
    "email" to null,
    "notes" to null,
    "balance" to 0L,
    "payment_terms_days" to null,
    "archived_at" to null,
    "created_at" to CREATED_AT,
    "updated_at" to CREATED_AT,
)

/** Un producto con libro de stock, sin stock todavía y con precio. */
internal fun productRow(name: String = "arroz"): Row = row(
    "name" to name,
    "normalized_name" to name,
    "category_id" to null,
    "default_supplier_id" to null,
    "base_unit_id" to UNIT_ID,
    "stock_mode" to "TRACKED",
    "sale_price" to 2_500L,
    "average_cost" to 1_200L,
    "initial_cost" to 1_200L,
    "fixed_unit_cost" to 0L,
    "stock_value" to 0L,
    "on_hand_milli" to 0L,
    "min_stock_milli" to 0L,
    "barcode" to null,
    "parent_product_id" to null,
    "variant_axis_1" to null,
    "variant_axis_2" to null,
    "variant_value_1" to null,
    "variant_value_2" to null,
    "photo_media_id" to null,
    "tax_rate_id" to null,
    "replacement_cost" to 0L,
    "replacement_cost_at" to null,
    "target_margin_bp" to null,
    "reference_cost" to null,
    "reference_price" to null,
    "price_review_since" to null,
    "tracks_lots" to 0L,
    "expiry_notice_days" to null,
    "archived_at" to null,
    "created_at" to CREATED_AT,
    "updated_at" to CREATED_AT,
)

/** Un cambio de precio manual: con precio anterior y sin lote. */
internal fun priceChangeRow(productId: Long, priceListId: Long): Row = row(
    "product_id" to productId,
    "price_list_id" to priceListId,
    "old_price" to 2_500L,
    "new_price" to 2_700L,
    "source" to "MANUAL",
    "batch_id" to null,
    "bulk_mode" to null,
    "percent_bp" to null,
    "amount_delta" to null,
    "target_margin_bp" to null,
    "fx_rate_micro" to null,
    "rounding_step" to null,
    "occurred_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
)

/** Una fila del libro sin origen: el inventario inicial de diez unidades a 1.200. */
internal fun movementRow(productId: Long): Row = row(
    "product_id" to productId,
    "location_id" to LOCATION_ID,
    "lot_id" to null,
    "type" to "OPENING_BALANCE",
    "reason" to null,
    "quantity_milli" to 10_000L,
    "unit_cost" to 1_200L,
    "total_cost" to 12_000L,
    "average_cost_after" to 1_200L,
    "stock_value_after" to 12_000L,
    "balance_after_milli" to 10_000L,
    "reverses_movement_id" to null,
    "sale_line_id" to null,
    "sale_line_component_id" to null,
    "purchase_line_id" to null,
    "sale_return_line_id" to null,
    "purchase_return_line_id" to null,
    "transfer_line_id" to null,
    "production_batch_id" to null,
    "count_session_id" to null,
    "note" to null,
    "occurred_at" to CREATED_AT,
    "created_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
)

/** Una alerta activa con su aviso pendiente. */
internal fun alertRow(productId: Long): Row = row(
    "product_id" to productId,
    "on_hand_milli_at_trigger" to 2_000L,
    "min_stock_milli_at_trigger" to 3_000L,
    "notification_status" to "PENDING",
    "occurred_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "resolved_at" to null,
)

/** La compra de [purchase], escrita como SQL: pagada entera y sin proveedor. */
internal fun purchaseRow(): Row = row(
    "supplier_id" to null,
    "status" to "ACTIVE",
    "invoice_reference" to "F-0001",
    "net_total" to 20_000L,
    "tax_total" to 0L,
    "total" to 20_000L,
    "cost_total" to 20_000L,
    "amount_paid" to 20_000L,
    "money_account_id" to null,
    "due_date" to null,
    "location_id" to LOCATION_ID,
    "fx_currency_code" to null,
    "fx_rate_micro" to null,
    "occurred_at" to CREATED_AT,
    "created_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "voided_at" to null,
    "void_reason" to null,
    "void_note" to null,
)

/** La línea de [purchaseLine], escrita como SQL. */
internal fun purchaseLineRow(purchaseId: Long, productId: Long): Row = row(
    "purchase_id" to purchaseId,
    "product_id" to productId,
    "unit_id" to UNIT_ID,
    "unit_factor_milli" to 12_000L,
    "entered_quantity_milli" to 1_000L,
    "quantity_milli" to 12_000L,
    "unit_cost" to 20_000L,
    "line_total" to 20_000L,
    "tax_rate_bp" to 0L,
    "tax_name_snapshot" to null,
    "tax_amount" to 0L,
    "cost_amount" to 20_000L,
    "lot_id" to null,
    "reference_unit_cost" to null,
)

/** La venta de [sale], escrita como SQL. */
internal fun saleRow(priceListId: Long): Row = row(
    "status" to "ACTIVE",
    "net_total" to 10_000L,
    "tax_total" to 1_900L,
    "total" to 11_900L,
    "total_cost" to 6_000L,
    "total_replacement_cost" to 6_500L,
    "order_adjustment" to -500L,
    "tip_amount" to 1_000L,
    "cash_rounding_amount" to -100L,
    "amount_due" to 12_800L,
    "customer_id" to null,
    "price_list_id" to priceListId,
    "location_id" to LOCATION_ID,
    "cash_session_id" to null,
    "quote_id" to null,
    "order_id" to null,
    "note" to null,
    "occurred_at" to CREATED_AT,
    "created_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "voided_at" to null,
    "void_reason" to null,
    "void_note" to null,
)

/** La línea de [saleLine], escrita como SQL. */
internal fun saleLineRow(saleId: Long, productId: Long, priceListId: Long): Row = row(
    "sale_id" to saleId,
    "product_id" to productId,
    "unit_id" to UNIT_ID,
    "unit_factor_milli" to 1_000L,
    "entered_quantity_milli" to 2_000L,
    "quantity_milli" to 2_000L,
    "unit_price" to 5_250L,
    "unit_cost" to 3_000L,
    "line_discount" to 0L,
    "order_adjustment_share" to -500L,
    "net_amount" to 10_000L,
    "tax_rate_bp" to 1_900L,
    "tax_name_snapshot" to "Tasa general",
    "tax_amount" to 1_900L,
    "line_total" to 11_900L,
    "line_cost" to 6_000L,
    "unit_replacement_cost" to 3_250L,
    "line_replacement_cost" to 6_500L,
    "product_name_snapshot" to "Café",
    "stock_effect" to "DIRECT",
    "parent_line_id" to null,
    "price_list_id" to priceListId,
)

internal fun expenseCategoryRow(name: String? = "empaques", systemKey: String? = null): Row = row(
    "system_key" to systemKey,
    "name" to name,
    "normalized_name" to name,
    "is_system" to 0L,
    "is_fixed" to 0L,
    "archived_at" to null,
    "created_at" to CREATED_AT,
    "updated_at" to CREATED_AT,
)

/** Un gasto activo de 45.000. */
internal fun expenseRow(categoryId: Long): Row = row(
    "expense_category_id" to categoryId,
    "status" to "ACTIVE",
    "amount" to 45_000L,
    "description" to null,
    "money_account_id" to null,
    "recurring_expense_id" to null,
    "occurred_at" to CREATED_AT,
    "created_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "voided_at" to null,
    "void_reason" to null,
    "void_note" to null,
)

/** Las columnas de una anulación válida, para un `UPDATE` de un documento. */
internal fun voiding(reason: String): Array<Pair<String, Any?>> = arrayOf(
    "status" to "VOIDED",
    "voided_at" to LATER,
    "void_reason" to reason,
    "void_note" to null,
)

/**
 * Además de la base de las pruebas del libro, con su lista de precios y su producto con libro de
 * stock, cada sonda escribe filas por SQL y comprueba quién las rechaza.
 */
internal abstract class GuardProbeTest : LedgerDatabaseTest() {
    /** Inserta [row] en [table] y devuelve su id. */
    protected suspend fun insert(table: String, row: Row): Long {
        execute(row.insertInto(table))
        return count("SELECT max(id) FROM $table")
    }

    /** Exige que [sql] lo rechace la guarda que aborta con [guard], y no otro error. */
    protected suspend fun rejected(guard: String, sql: String) {
        try {
            execute(sql)
        } catch (expected: SQLiteException) {
            val message = expected.message.orEmpty()
            assertTrue("$sql\nfalló por otra causa: $message", message.endsWith(": $guard"))
            return
        }
        fail("Debía rechazarla $guard: $sql")
    }
}
