package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de las ventas en espera y de los extras: cada guarda rechaza lo que
 * debe, con su mensaje, y deja pasar lo válido. Todos los nombres y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class DraftGuardsTest : GuardProbeTest() {
    @Test
    fun aDraftKeepsItsLabelLength() = runBlocking {
        val valid = draftRow()
        for (broken in listOf(
            valid.with("label" to ""),
            valid.with("label" to "M".repeat(41)),
            valid.with("created_at" to null),
            valid.with("updated_at" to "hoy"),
        )) {
            rejected(DRAFTS, broken.insertInto("sale_drafts"))
        }
        val id = insert("sale_drafts", valid.with("label" to "M".repeat(40)))
        insert("sale_drafts", valid.with("label" to null))

        rejected(DRAFTS, update("sale_drafts", id, "label" to ""))
        rejected(DRAFTS, update("sale_drafts", id, "updated_at" to null))
        execute(update("sale_drafts", id, "label" to null, "updated_at" to LATER))
        assertEquals(2L, count("SELECT COUNT(*) FROM sale_drafts WHERE label IS NULL"))
    }

    @Test
    fun aDraftLineKeepsItsQuantityPriceAndDiscount() = runBlocking {
        val draftId = insert("sale_drafts", draftRow())
        val valid = draftLineRow(draftId)
        for (broken in listOf(
            valid.with("entered_quantity_milli" to 0L),
            valid.with("entered_quantity_milli" to 1_000_000_001L),
            valid.with("entered_quantity_milli" to 1_500.5),
            valid.with("unit_price" to -1L),
            valid.with("unit_price" to 1_000_000_000_001L),
            valid.with("line_discount" to null),
            valid.with("line_discount" to 1_000_000_000_000_001L),
        )) {
            rejected(DRAFT_LINES, broken.insertInto("sale_draft_lines"))
        }
        // Sin precio: el de la lista al retomar.
        val id = insert("sale_draft_lines", valid.with("unit_price" to null))
        insert("sale_draft_lines", valid.with("unit_price" to 0L, "line_discount" to 0L))

        rejected(DRAFT_LINES, update("sale_draft_lines", id, "entered_quantity_milli" to 0L))
        rejected(DRAFT_LINES, update("sale_draft_lines", id, "unit_price" to 2_500.5))
        execute(update("sale_draft_lines", id, "unit_price" to 1_000_000_000_000L))
        assertEquals(2L, count("SELECT COUNT(*) FROM sale_draft_lines"))
    }

    @Test
    fun aModifierIsAnotherProductWithItsGroupLength() = runBlocking {
        val extra = insert("products", productRow("queso extra"))
        val valid = modifierRow(productId, extra)
        for (broken in listOf(
            modifierRow(productId, productId),
            valid.with("group_name" to ""),
            valid.with("group_name" to "G".repeat(41)),
            valid.with("sort_order" to 1.5),
            valid.with("created_at" to null),
        )) {
            rejected(MODIFIERS, broken.insertInto("product_modifiers"))
        }
        val id = insert("product_modifiers", valid.with("group_name" to "G".repeat(40)))

        rejected(MODIFIERS, update("product_modifiers", id, "modifier_product_id" to productId))
        rejected(MODIFIERS, update("product_modifiers", id, "updated_at" to "hoy"))
        execute(update("product_modifiers", id, "group_name" to null, "sort_order" to 3L))
        assertEquals(3L, count("SELECT sort_order FROM product_modifiers WHERE id = $id"))
    }

    private fun draftRow(): Row = row(
        "label" to "Mesa 4",
        "customer_id" to null,
        "price_list_id" to null,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    private fun draftLineRow(draftId: Long): Row = row(
        "draft_id" to draftId,
        "product_id" to productId,
        "unit_id" to UNIT_ID,
        "entered_quantity_milli" to 2_000L,
        "unit_price" to 2_500L,
        "line_discount" to 1_000_000_000_000_000L,
        "parent_line_id" to null,
    )

    private fun modifierRow(productId: Long, modifierId: Long): Row = row(
        "product_id" to productId,
        "modifier_product_id" to modifierId,
        "group_name" to "Adiciones",
        "sort_order" to 0L,
        "created_at" to CREATED_AT,
        "updated_at" to CREATED_AT,
    )

    private companion object {
        const val DRAFTS = "guard:sale_drafts"
        const val DRAFT_LINES = "guard:sale_draft_lines"
        const val MODIFIERS = "guard:product_modifiers"
    }
}
