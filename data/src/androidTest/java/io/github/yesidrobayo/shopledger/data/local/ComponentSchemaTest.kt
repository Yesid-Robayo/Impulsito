package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los índices y las llaves de combos, campos propios, etiquetas, imágenes y componentes vendidos,
 * tal como quedan en la base, comparados con el modelo de datos.
 *
 * Lo que más importa: cada par de las tablas de vínculo es único, la receta, los valores y los
 * vínculos se van en cascada, un componente vendido o en una receta protege a su producto, y la
 * foto de un producto se pone en nulo al borrarla.
 */
@RunWith(AndroidJUnit4::class)
internal class ComponentSchemaTest : CatalogDatabaseTest() {
    @Test
    fun indexesAreTheOnesOfTheDataModel() = runBlocking {
        val expected =
            mapOf(
                "product_components" to
                    listOf(
                        "index_product_components_component_product_id (component_product_id)",
                        "unique index_product_components_parent_product_id_component_product_id " +
                            "(parent_product_id, component_product_id)",
                    ),
                "custom_field_definitions" to
                    listOf(
                        "index_custom_field_definitions_normalized_name (normalized_name)",
                    ),
                "product_custom_values" to
                    listOf(
                        "index_product_custom_values_field_id (field_id)",
                        "unique index_product_custom_values_product_id_field_id " +
                            "(product_id, field_id)",
                    ),
                "tags" to listOf("unique index_tags_normalized_name (normalized_name)"),
                "product_tags" to
                    listOf(
                        "unique index_product_tags_product_id_tag_id (product_id, tag_id)",
                        "index_product_tags_tag_id (tag_id)",
                    ),
                "media_assets" to
                    listOf(
                        "index_media_assets_orphaned_at (orphaned_at)",
                        "unique index_media_assets_sha256 (sha256)",
                    ),
                "sale_line_components" to
                    listOf(
                        "index_sale_line_components_component_product_id " +
                            "(component_product_id)",
                        "index_sale_line_components_sale_line_id (sale_line_id)",
                    ),
            )

        for ((table, indexes) in expected) assertEquals(table, indexes, database.indexesOf(table))
    }

    @Test
    fun foreignKeysCascadeOrRestrictAsTheDataModelSays() = runBlocking {
        val expected =
            mapOf(
                "product_components" to
                    listOf(
                        "component_product_id -> products RESTRICT",
                        "parent_product_id -> products CASCADE",
                    ),
                "custom_field_definitions" to emptyList(),
                "product_custom_values" to
                    listOf(
                        "field_id -> custom_field_definitions CASCADE",
                        "product_id -> products CASCADE",
                    ),
                "tags" to emptyList(),
                "product_tags" to
                    listOf("product_id -> products CASCADE", "tag_id -> tags CASCADE"),
                "media_assets" to emptyList(),
                "sale_line_components" to
                    listOf(
                        "component_product_id -> products RESTRICT",
                        "sale_line_id -> sale_lines RESTRICT",
                    ),
                // La unidad del producto llega con su tabla.
                "products" to
                    listOf(
                        "category_id -> categories SET NULL",
                        "default_supplier_id -> suppliers SET NULL",
                        "parent_product_id -> products RESTRICT",
                        "photo_media_id -> media_assets SET NULL",
                        "tax_rate_id -> tax_rates SET NULL",
                    ),
            )

        for ((table, keys) in expected) assertEquals(table, keys, database.foreignKeysOf(table))
    }
}
