package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de combos, campos propios, etiquetas, imágenes y componentes vendidos.
 * Cada guarda rechaza lo que debe, con su mensaje, y deja pasar lo válido. Todos los nombres y
 * montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ComponentGuardsTest : GuardProbeTest() {
    @Test
    fun aComponentBelongsToAComboAndKeepsItsQuantity() = runBlocking {
        val combo = insert("products", comboRow("desayuno"))
        val valid = componentRow(combo, productId)
        for (broken in listOf(
            valid.with("quantity_milli" to 0L),
            valid.with("quantity_milli" to 1_000_000_001L),
            valid.with("quantity_milli" to 1_500.5),
            valid.with("created_at" to null),
            valid.with("updated_at" to "hoy"),
            // Un combo no se contiene a sí mismo, y solo un combo tiene receta.
            componentRow(combo, combo),
            componentRow(productId, combo),
        )) {
            rejected(COMPONENTS, broken.insertInto("product_components"))
        }
        val id = insert("product_components", valid.with("quantity_milli" to 1_000_000_000L))

        rejected(COMPONENTS, update("product_components", id, "quantity_milli" to 0L))
        rejected(COMPONENTS, update("product_components", id, "component_product_id" to combo))
        execute(update("product_components", id, "quantity_milli" to 1L, "updated_at" to LATER))
        assertEquals(1L, count("SELECT quantity_milli FROM product_components WHERE id = $id"))
    }

    @Test
    fun aComboHasAtMostTwoLevelsAndNoCycles() = runBlocking {
        val combo = insert("products", comboRow("almuerzo"))
        val inner = insert("products", comboRow("bebida del día"))
        val other = insert("products", comboRow("postre del día"))
        val top = insert("products", comboRow("menú familiar"))
        insert("product_components", componentRow(inner, productId))
        val otherLink = insert("product_components", componentRow(other, productId))
        // Dos niveles: el combo lleva otro combo, que lleva una hoja.
        insert("product_components", componentRow(combo, inner))

        for (broken in listOf(
            // Un tercer nivel por arriba: el combo ya tiene un nivel debajo de su componente.
            componentRow(top, combo),
            // Un tercer nivel por abajo: el combo interno ya cuelga de otro.
            componentRow(inner, other),
            // Un ciclo también es un tercer nivel.
            componentRow(inner, combo),
        )) {
            rejected(COMPONENTS, broken.insertInto("product_components"))
        }
        // Editar una receta tampoco abre un tercer nivel.
        rejected(
            COMPONENTS,
            update("product_components", otherLink, "component_product_id" to combo),
        )

        // Lo mismo, de dos niveles, sí pasa: el de arriba lleva la hoja directo.
        insert("product_components", componentRow(top, productId))
        assertEquals(4L, count("SELECT COUNT(*) FROM product_components"))
    }

    @Test
    fun customFieldsKeepTheirLengthsAndType() = runBlocking {
        val valid = customFieldRow("talla")
        for (broken in listOf(
            valid.with("name" to "C".repeat(41)),
            valid.with("name" to null),
            valid.with("normalized_name" to ""),
            valid.with("field_type" to "LIST"),
            valid.with("sort_order" to 1.5),
            valid.with("archived_at" to "ayer"),
            valid.with("created_at" to null),
        )) {
            rejected(CUSTOM_FIELDS, broken.insertInto("custom_field_definitions"))
        }
        val id = insert("custom_field_definitions", valid.with("name" to "C".repeat(40)))
        // Lo archivado no reserva su nombre, y entre vigentes no se repite.
        insert("custom_field_definitions", valid.with("archived_at" to LATER))
        rejected(CUSTOM_FIELDS, valid.insertInto("custom_field_definitions"))
        val other = insert("custom_field_definitions", customFieldRow("color"))
        rejected(
            CUSTOM_FIELDS,
            update("custom_field_definitions", other, "normalized_name" to "talla"),
        )

        rejected(CUSTOM_FIELDS, update("custom_field_definitions", id, "field_type" to "COLOR"))
        rejected(CUSTOM_FIELDS, update("custom_field_definitions", id, "normalized_name" to null))
        execute(
            update(
                "custom_field_definitions",
                id,
                "field_type" to "DATE",
                "archived_at" to LATER,
            ),
        )
        assertEquals(3L, count("SELECT COUNT(*) FROM custom_field_definitions"))
    }

    @Test
    fun customValuesAreNeutralTextUpToTwoHundred() = runBlocking {
        val fieldId = insert("custom_field_definitions", customFieldRow("cosecha"))
        val valid = customValueRow(productId, fieldId)
        for (broken in listOf(
            valid.with("value_text" to ""),
            valid.with("value_text" to "V".repeat(201)),
            valid.with("value_text" to null),
            valid.with("updated_at" to null),
        )) {
            rejected(CUSTOM_VALUES, broken.insertInto("product_custom_values"))
        }
        val id = insert("product_custom_values", valid.with("value_text" to "V".repeat(200)))

        rejected(CUSTOM_VALUES, update("product_custom_values", id, "value_text" to ""))
        execute(update("product_custom_values", id, "value_text" to "2026-03-15"))
        assertEquals(1L, count("SELECT COUNT(*) FROM product_custom_values"))
    }

    @Test
    fun tagsAndTheirLinksKeepTheirRules() = runBlocking {
        val valid = tagRow("temporada")
        for (broken in listOf(
            valid.with("name" to ""),
            valid.with("normalized_name" to "N".repeat(41)),
            valid.with("created_at" to 1.5),
        )) {
            rejected(TAGS, broken.insertInto("tags"))
        }
        val tagId = insert("tags", valid)
        rejected(TAGS, update("tags", tagId, "name" to null))
        execute(update("tags", tagId, "name" to "N".repeat(40), "updated_at" to LATER))

        val link = row("product_id" to productId, "tag_id" to tagId)
        // Un texto como '1' o un real como 1.0 la columna los vuelve enteros antes de la guarda:
        // la sonda usa valores que no se pueden convertir.
        rejected(PRODUCT_TAGS, link.with("product_id" to "uno").insertInto("product_tags"))
        rejected(PRODUCT_TAGS, link.with("tag_id" to 1.5).insertInto("product_tags"))
        val linkId = insert("product_tags", link)
        rejected(PRODUCT_TAGS, update("product_tags", linkId, "tag_id" to "dos"))
        assertEquals(1L, count("SELECT COUNT(*) FROM product_tags"))
    }

    @Test
    fun anImageIsAHashKindAndSizesWithinLimits() = runBlocking {
        val valid = mediaRow('a')
        for (broken in listOf(
            valid.with("sha256" to "a".repeat(63)),
            valid.with("sha256" to "A".repeat(64)),
            valid.with("sha256" to "g".repeat(64)),
            valid.with("sha256" to null),
            valid.with("kind" to "VIDEO"),
            valid.with("mime_type" to ""),
            valid.with("mime_type" to "m".repeat(41)),
            valid.with("width_px" to 0L),
            valid.with("height_px" to 20_001L),
            valid.with("size_bytes" to 0L),
            valid.with("size_bytes" to 2_097_153L),
            valid.with("created_at" to null),
            valid.with("orphaned_at" to "ayer"),
        )) {
            rejected(MEDIA, broken.insertInto("media_assets"))
        }
        val id =
            insert(
                "media_assets",
                valid.with("width_px" to 20_000L, "height_px" to 1L, "size_bytes" to 2_097_152L),
            )
        insert("media_assets", mediaRow('0').with("kind" to "BUSINESS_LOGO"))

        rejected(MEDIA, update("media_assets", id, "sha256" to "F".repeat(64)))
        rejected(MEDIA, update("media_assets", id, "kind" to "AUDIO"))
        execute(update("media_assets", id, "orphaned_at" to LATER))
        assertEquals(LATER, count("SELECT orphaned_at FROM media_assets WHERE id = $id"))
    }

    @Test
    fun aSoldComponentIsALeafOfAComboLineAndNeverChanges() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val combo = insert("products", comboRow("desayuno"))
        val comboLine = insert("sale_lines", comboLineRow(saleId, combo))
        val directLine = insert("sale_lines", saleLineRow(saleId, productId, priceListId))
        val service = insert("products", productRow("empaque").with("stock_mode" to "UNTRACKED"))
        val valid = soldComponentRow(comboLine, productId)
        for (broken in listOf(
            valid.with("quantity_milli" to 0L),
            valid.with("quantity_milli" to 1_000_000_001L),
            valid.with("tracked" to 2L),
            // Lleva libro si y solo si su producto lo lleva.
            valid.with("tracked" to 0L),
            soldComponentRow(comboLine, service),
            valid.with("unit_cost" to 3_000.5),
            valid.with("line_cost" to 1_000_000_000_000_001L),
            // Solo de una línea de combo, y nunca otro combo: los niveles ya vienen expandidos.
            soldComponentRow(directLine, productId),
            soldComponentRow(comboLine, combo).with("tracked" to 0L),
        )) {
            rejected(SOLD_COMPONENTS, broken.insertInto("sale_line_components"))
        }
        val id = insert("sale_line_components", valid)
        insert("sale_line_components", soldComponentRow(comboLine, service).with("tracked" to 0L))

        rejected(SOLD_COMPONENTS, valid.withId(id).insertOrReplaceInto("sale_line_components"))
        rejected(SOLD_APPEND_ONLY, update("sale_line_components", id, "unit_cost" to 1L))
        rejected(SOLD_APPEND_ONLY, "DELETE FROM sale_line_components WHERE id = $id")
        assertEquals(2L, count("SELECT COUNT(*) FROM sale_line_components"))
    }

    @Test
    fun aProductSoldOnlyAsAComponentKeepsItsModeAndUnit() = runBlocking {
        val flour = insert("products", productRow("harina").with("stock_mode" to "UNTRACKED"))
        // Sin historia, el modo cambia y vuelve.
        execute(update("products", flour, "stock_mode" to "TRACKED"))
        execute(update("products", flour, "stock_mode" to "UNTRACKED"))

        // Vendido solo como componente de un combo: no tiene libro ni línea propia.
        val saleId = insert("sales", saleRow(priceListId))
        val combo = insert("products", comboRow("pan dulce"))
        val comboLine = insert("sale_lines", comboLineRow(saleId, combo))
        insert("sale_line_components", soldComponentRow(comboLine, flour).with("tracked" to 0L))

        rejected(PRODUCTS, update("products", flour, "stock_mode" to "TRACKED"))
        rejected(PRODUCTS, update("products", flour, "base_unit_id" to 2L))
        execute(update("products", flour, "sale_price" to 3_100L, "updated_at" to LATER))
        assertEquals(3_100L, count("SELECT sale_price FROM products WHERE id = $flour"))
    }

    @Test
    fun deletingAPhotoLeavesItsProductWithoutOneThroughItsGuard() = runBlocking {
        val photo = insert("media_assets", mediaRow('b'))
        val withPhoto = insert("products", productRow("queso").with("photo_media_id" to photo))

        // La llave pone la foto en nulo con un UPDATE que pasa por la guarda del producto.
        execute("DELETE FROM media_assets WHERE id = $photo")
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM products WHERE id = $withPhoto AND photo_media_id IS NULL"),
        )
    }

    private fun comboRow(name: String): Row = productRow(name).with("stock_mode" to "COMPONENTS")

    private fun componentRow(parentId: Long, componentId: Long): Row = row(
        "parent_product_id" to parentId,
        "component_product_id" to componentId,
        "quantity_milli" to 1_000L,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    private fun customFieldRow(name: String): Row = row(
        "name" to name,
        "normalized_name" to name,
        "field_type" to "TEXT",
        "sort_order" to 0L,
        "archived_at" to null,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    private fun customValueRow(productId: Long, fieldId: Long): Row = row(
        "product_id" to productId,
        "field_id" to fieldId,
        "value_text" to "M",
        "updated_at" to CREATED_AT,
    )

    private fun tagRow(name: String): Row = row(
        "name" to name,
        "normalized_name" to name,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    private fun mediaRow(hashSeed: Char): Row = row(
        "sha256" to hashSeed.toString().repeat(64),
        "kind" to "PRODUCT_PHOTO",
        "mime_type" to "image/webp",
        "width_px" to 800L,
        "height_px" to 600L,
        "size_bytes" to 48_000L,
        "created_at" to CREATED_AT,
        "orphaned_at" to null,
    )

    private fun comboLineRow(saleId: Long, comboId: Long): Row =
        saleLineRow(saleId, comboId, priceListId).with("stock_effect" to "COMPONENTS")

    private fun soldComponentRow(saleLineId: Long, componentId: Long): Row = row(
        "sale_line_id" to saleLineId,
        "component_product_id" to componentId,
        "quantity_milli" to 2_000L,
        "tracked" to 1L,
        "unit_cost" to 3_000L,
        "line_cost" to 6_000L,
    )

    private companion object {
        const val COMPONENTS = "guard:product_components"
        const val CUSTOM_FIELDS = "guard:custom_field_definitions"
        const val CUSTOM_VALUES = "guard:product_custom_values"
        const val TAGS = "guard:tags"
        const val PRODUCT_TAGS = "guard:product_tags"
        const val MEDIA = "guard:media_assets"
        const val SOLD_COMPONENTS = "guard:sale_line_components"
        const val SOLD_APPEND_ONLY = "guard:sale_line_components_append_only"
        const val PRODUCTS = "guard:products"
    }
}
