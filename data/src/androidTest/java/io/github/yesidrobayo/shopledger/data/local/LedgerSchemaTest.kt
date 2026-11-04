package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los índices y las llaves de ventas, compras y libro de stock, tal como quedan en la base.
 *
 * El JSON exportado sale del mismo código, así que no los comprueba: esta prueba los compara con
 * el modelo de datos. Lo que más importa: el índice `(sale_id, product_id)` no es único, porque un
 * producto se repite en una venta, y el de `reverses_movement_id` sí, porque una fila se anula una
 * sola vez. Cada llave usa `RESTRICT`: nada con historia se borra. Las llaves hacia tablas que
 * todavía no existen las agrega, con esta lista, la tarea que crea cada tabla.
 */
@RunWith(AndroidJUnit4::class)
internal class LedgerSchemaTest : CatalogDatabaseTest() {
    @Test
    fun indexesAreTheOnesOfTheDataModel() = runBlocking {
        val expected =
            mapOf(
                "purchases" to
                    listOf(
                        "index_purchases_due_date (due_date)",
                        "index_purchases_status_business_date (status, business_date)",
                        "index_purchases_supplier_id (supplier_id)",
                    ),
                "purchase_lines" to
                    listOf(
                        "index_purchase_lines_product_id (product_id)",
                        "index_purchase_lines_purchase_id (purchase_id)",
                    ),
                "sales" to
                    listOf(
                        "index_sales_customer_id (customer_id)",
                        "index_sales_occurred_at_id (occurred_at, id)",
                        "index_sales_status_business_date (status, business_date)",
                    ),
                "sale_lines" to
                    listOf(
                        "index_sale_lines_product_id (product_id)",
                        "index_sale_lines_sale_id_product_id (sale_id, product_id)",
                    ),
                "stock_movements" to
                    listOf(
                        "index_stock_movements_product_id (product_id)",
                        "index_stock_movements_purchase_line_id (purchase_line_id)",
                        "unique index_stock_movements_reverses_movement_id (reverses_movement_id)",
                        "index_stock_movements_sale_line_id (sale_line_id)",
                        "index_stock_movements_type_business_date (type, business_date)",
                    ),
            )

        for ((table, indexes) in expected) assertEquals(table, indexes, database.indexesOf(table))
    }

    @Test
    fun foreignKeysRestrictAndPointToTheTablesThatExist() = runBlocking {
        val expected =
            mapOf(
                "purchases" to listOf("supplier_id -> suppliers RESTRICT"),
                "purchase_lines" to
                    listOf("product_id -> products RESTRICT", "purchase_id -> purchases RESTRICT"),
                "sales" to listOf("price_list_id -> price_lists RESTRICT"),
                "sale_lines" to
                    listOf(
                        "parent_line_id -> sale_lines RESTRICT",
                        "price_list_id -> price_lists RESTRICT",
                        "product_id -> products RESTRICT",
                        "sale_id -> sales RESTRICT",
                    ),
                "stock_movements" to
                    listOf(
                        "product_id -> products RESTRICT",
                        "purchase_line_id -> purchase_lines RESTRICT",
                        "reverses_movement_id -> stock_movements RESTRICT",
                        "sale_line_component_id -> sale_line_components RESTRICT",
                        "sale_line_id -> sale_lines RESTRICT",
                    ),
            )

        for ((table, keys) in expected) assertEquals(table, keys, database.foreignKeysOf(table))
    }
}
