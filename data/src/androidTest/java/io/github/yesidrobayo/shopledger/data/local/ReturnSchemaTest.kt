package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los índices y las llaves de las devoluciones de venta y a proveedor, tal como quedan en la base,
 * comparados con el modelo de datos. Cada llave usa `RESTRICT`: nada con historia se borra. La
 * llave hacia el lote llega con su tabla.
 */
@RunWith(AndroidJUnit4::class)
internal class ReturnSchemaTest : CatalogDatabaseTest() {
    @Test
    fun indexesAreTheOnesOfTheDataModel() = runBlocking {
        val expected =
            mapOf(
                "sale_returns" to
                    listOf(
                        "index_sale_returns_replacement_sale_id (replacement_sale_id)",
                        "index_sale_returns_sale_id (sale_id)",
                        "index_sale_returns_status_business_date (status, business_date)",
                    ),
                "sale_return_lines" to
                    listOf(
                        "index_sale_return_lines_sale_line_id (sale_line_id)",
                        "index_sale_return_lines_sale_return_id (sale_return_id)",
                    ),
                "purchase_returns" to
                    listOf(
                        "index_purchase_returns_purchase_id (purchase_id)",
                        "index_purchase_returns_status_business_date (status, business_date)",
                        "index_purchase_returns_supplier_id (supplier_id)",
                    ),
                "purchase_return_lines" to
                    listOf(
                        "index_purchase_return_lines_product_id (product_id)",
                        "index_purchase_return_lines_purchase_return_id (purchase_return_id)",
                    ),
            )

        for ((table, indexes) in expected) assertEquals(table, indexes, database.indexesOf(table))
    }

    @Test
    fun foreignKeysRestrictAndPointToTheTablesThatExist() = runBlocking {
        val expected =
            mapOf(
                "sale_returns" to
                    listOf(
                        "money_account_id -> money_accounts RESTRICT",
                        "replacement_sale_id -> sales RESTRICT",
                        "sale_id -> sales RESTRICT",
                    ),
                "sale_return_lines" to
                    listOf(
                        "sale_line_id -> sale_lines RESTRICT",
                        "sale_return_id -> sale_returns RESTRICT",
                    ),
                "purchase_returns" to
                    listOf(
                        "money_account_id -> money_accounts RESTRICT",
                        "purchase_id -> purchases RESTRICT",
                        "supplier_id -> suppliers RESTRICT",
                    ),
                "purchase_return_lines" to
                    listOf(
                        "lot_id -> stock_lots RESTRICT",
                        "product_id -> products RESTRICT",
                        "purchase_line_id -> purchase_lines RESTRICT",
                        "purchase_return_id -> purchase_returns RESTRICT",
                    ),
            )

        for ((table, keys) in expected) assertEquals(table, keys, database.foreignKeysOf(table))
    }
}
