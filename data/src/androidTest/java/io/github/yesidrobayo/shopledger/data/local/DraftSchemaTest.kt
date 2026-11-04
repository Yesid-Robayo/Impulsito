package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los índices y las llaves de las ventas en espera y de los extras, tal como quedan en la base,
 * comparados con el modelo de datos. La espera se va con sus líneas, y una línea con su producto y
 * con su línea principal. La unidad de la línea llega con su tabla.
 */
@RunWith(AndroidJUnit4::class)
internal class DraftSchemaTest : CatalogDatabaseTest() {
    @Test
    fun indexesAreTheOnesOfTheDataModel() = runBlocking {
        val expected =
            mapOf(
                "sale_drafts" to
                    listOf(
                        "index_sale_drafts_customer_id (customer_id)",
                        "index_sale_drafts_price_list_id (price_list_id)",
                    ),
                "sale_draft_lines" to
                    listOf(
                        "index_sale_draft_lines_draft_id (draft_id)",
                        "index_sale_draft_lines_parent_line_id (parent_line_id)",
                        "index_sale_draft_lines_product_id (product_id)",
                        "index_sale_draft_lines_unit_id (unit_id)",
                    ),
                "product_modifiers" to
                    listOf(
                        "index_product_modifiers_modifier_product_id (modifier_product_id)",
                        "index_product_modifiers_product_id (product_id)",
                    ),
            )

        for ((table, indexes) in expected) assertEquals(table, indexes, database.indexesOf(table))
    }

    @Test
    fun foreignKeysCascadeOrRestrictAsTheDataModelSays() = runBlocking {
        val expected =
            mapOf(
                "sale_drafts" to
                    listOf(
                        "customer_id -> customers SET NULL",
                        "price_list_id -> price_lists SET NULL",
                    ),
                "sale_draft_lines" to
                    listOf(
                        "draft_id -> sale_drafts CASCADE",
                        "parent_line_id -> sale_draft_lines CASCADE",
                        "product_id -> products CASCADE",
                        "unit_id -> units RESTRICT",
                    ),
                "product_modifiers" to
                    listOf(
                        "modifier_product_id -> products RESTRICT",
                        "product_id -> products CASCADE",
                    ),
            )

        for ((table, keys) in expected) assertEquals(table, keys, database.foreignKeysOf(table))
    }
}
