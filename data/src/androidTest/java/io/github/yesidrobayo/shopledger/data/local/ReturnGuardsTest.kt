package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de las devoluciones de venta y a proveedor: cabeceras que nacen
 * activas y solo cambian al anularse, líneas que no cambian ni se borran, una línea vendida que no
 * devuelve más de lo vendido y una venta que no se anula con una devolución activa. Todos los
 * montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class ReturnGuardsTest : GuardProbeTest() {
    @Test
    fun aSaleReturnIsBornActiveBalancedAndFromAnActiveSale() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val valid = saleReturnRow(saleId)
        val voidedSale = insert("sales", saleRow(priceListId))
        execute(update("sales", voidedSale, *voiding("ENTRY_ERROR")))
        for (broken in listOf(
            valid.with("status" to "VOIDED"),
            valid.with("void_reason" to "UNDO"),
            valid.with("resolution" to "CASH"),
            valid.with("net_refund" to 5_000.5),
            valid.with("total_refund" to 5_951L),
            valid.with("cost_returned" to 1_000_000_000_000_001L),
            valid.with("note" to "N".repeat(201)),
            valid.with("business_date" to "2026-02-30"),
            valid.with("created_at" to null),
            // Solo de una venta activa, y de una que existe.
            valid.with("sale_id" to voidedSale),
            valid.with("sale_id" to MISSING_ID),
        )) {
            rejected(SALE_RETURNS, broken.insertInto("sale_returns"))
        }
        val id = insert("sale_returns", valid.with("resolution" to "EXCHANGE"))
        insert(
            "sale_returns",
            valid.with("resolution" to "STORE_CREDIT", "note" to "N".repeat(200)),
        )

        rejected(SALE_RETURNS, valid.withId(id).insertOrReplaceInto("sale_returns"))
        assertEquals(2L, count("SELECT COUNT(*) FROM sale_returns"))
    }

    @Test
    fun aSaleReturnOnlyChangesToBeVoidedOnceWithAReason() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val id = insert("sale_returns", saleReturnRow(saleId))
        for (broken in listOf(
            arrayOf(*voiding("ENTRY_ERROR"), "void_reason" to null),
            arrayOf(*voiding("CUSTOMER_RETURN")),
            arrayOf(*voiding("ENTRY_ERROR"), "voided_at" to null),
            arrayOf(*voiding("ENTRY_ERROR"), "total_refund" to 1L),
            arrayOf(*voiding("ENTRY_ERROR"), "money_account_id" to 1L),
            arrayOf(*voiding("ENTRY_ERROR"), "void_note" to "N".repeat(201)),
            arrayOf("note" to "Otra nota"),
        )) {
            rejected(SALE_RETURNS_VOID_ONLY, update("sale_returns", id, *broken))
        }

        execute(update("sale_returns", id, *voiding("UNDO")))
        rejected(SALE_RETURNS_VOID_ONLY, update("sale_returns", id, "status" to "ACTIVE"))
        rejected(SALE_RETURNS_VOID_ONLY, update("sale_returns", id, *voiding("OTHER")))
        rejected("guard:sale_returns_no_delete", "DELETE FROM sale_returns WHERE id = $id")
        assertEquals(1L, count("SELECT COUNT(*) FROM sale_returns WHERE status = 'VOIDED'"))
    }

    @Test
    fun aSaleWithAnActiveReturnIsNotVoided() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val returnId = insert("sale_returns", saleReturnRow(saleId))

        // Se devuelve el resto, no se anula.
        rejected(SALES_VOID_ONLY, update("sales", saleId, *voiding("CUSTOMER_RETURN")))

        // Con la devolución anulada, la venta sí se anula.
        execute(update("sale_returns", returnId, *voiding("ENTRY_ERROR")))
        execute(update("sales", saleId, *voiding("CUSTOMER_RETURN")))
        assertEquals(1L, count("SELECT COUNT(*) FROM sales WHERE status = 'VOIDED'"))
    }

    @Test
    fun aSoldLineNeverReturnsMoreThanWasSoldAndItsLinesNeverChange() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        // Dos unidades vendidas.
        val saleLineId = insert("sale_lines", saleLineRow(saleId, productId, priceListId))
        val first = insert("sale_returns", saleReturnRow(saleId))
        val valid = saleReturnLineRow(first, saleLineId)
        for (broken in listOf(
            valid.with("quantity_milli" to 0L),
            valid.with("quantity_milli" to 1_500.5),
            valid.with("restock" to 2L),
            valid.with("line_total" to 5_949L),
            valid.with("line_cost" to 3_000.5),
            valid.with("tax_amount" to -1L),
            valid.with("quantity_milli" to 2_001L),
        )) {
            rejected(RETURN_LINES, broken.insertInto("sale_return_lines"))
        }
        val id = insert("sale_return_lines", valid.with("quantity_milli" to 1_500L))
        // Entre todas las devoluciones activas: quedan 500.
        val second = insert("sale_returns", saleReturnRow(saleId))
        rejected(
            RETURN_LINES,
            saleReturnLineRow(second, saleLineId)
                .with("quantity_milli" to 501L)
                .insertInto("sale_return_lines"),
        )
        insert(
            "sale_return_lines",
            saleReturnLineRow(second, saleLineId).with("quantity_milli" to 500L, "restock" to 0L),
        )
        // Una devolución anulada no cuenta: lo suyo se puede volver a devolver.
        execute(update("sale_returns", first, *voiding("ENTRY_ERROR")))
        val third = insert("sale_returns", saleReturnRow(saleId))
        insert(
            "sale_return_lines",
            saleReturnLineRow(third, saleLineId).with("quantity_milli" to 1_000L),
        )

        rejected(RETURN_LINES, valid.withId(id).insertOrReplaceInto("sale_return_lines"))
        rejected(RETURN_LINES_APPEND_ONLY, update("sale_return_lines", id, "restock" to 0L))
        rejected(RETURN_LINES_APPEND_ONLY, "DELETE FROM sale_return_lines WHERE id = $id")
        assertEquals(3L, count("SELECT COUNT(*) FROM sale_return_lines"))
    }

    @Test
    fun aPurchaseReturnIsBornActiveAndCreditNeedsASupplier() = runBlocking {
        val supplierId = insert("suppliers", supplierRow())
        val valid = purchaseReturnRow()
        for (broken in listOf(
            valid.with("status" to "VOIDED"),
            valid.with("voided_at" to CREATED_AT),
            valid.with("resolution" to "REFUND"),
            valid.with("total" to 20_000.5),
            valid.with("total" to -1L),
            valid.with("note" to ""),
            valid.with("business_date" to "2026-13-01"),
            valid.with("occurred_at" to null),
            // Un saldo a favor es con un proveedor.
            valid.with("resolution" to "SUPPLIER_CREDIT"),
        )) {
            rejected(PURCHASE_RETURNS, broken.insertInto("purchase_returns"))
        }
        val id = insert("purchase_returns", valid)
        insert(
            "purchase_returns",
            valid.with("resolution" to "SUPPLIER_CREDIT", "supplier_id" to supplierId),
        )

        rejected(PURCHASE_RETURNS, valid.withId(id).insertOrReplaceInto("purchase_returns"))
        assertEquals(2L, count("SELECT COUNT(*) FROM purchase_returns"))
    }

    @Test
    fun aPurchaseReturnOnlyChangesToBeVoidedOnceWithAReason() = runBlocking {
        val purchaseId = insert("purchases", purchaseRow())
        val id = insert("purchase_returns", purchaseReturnRow().with("purchase_id" to purchaseId))
        for (broken in listOf(
            arrayOf(*voiding("SUPPLIER_RETURN")),
            arrayOf(*voiding("ENTRY_ERROR"), "void_reason" to null),
            arrayOf(*voiding("ENTRY_ERROR"), "purchase_id" to null),
            arrayOf(*voiding("ENTRY_ERROR"), "resolution" to "SUPPLIER_CREDIT"),
            arrayOf("total" to 1L),
        )) {
            rejected(PURCHASE_RETURNS_VOID_ONLY, update("purchase_returns", id, *broken))
        }

        execute(update("purchase_returns", id, *voiding("ENTRY_ERROR")))
        rejected(PURCHASE_RETURNS_VOID_ONLY, update("purchase_returns", id, *voiding("UNDO")))
        rejected(
            "guard:purchase_returns_no_delete",
            "DELETE FROM purchase_returns WHERE id = $id",
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM purchase_returns WHERE status = 'VOIDED'"))
    }

    @Test
    fun purchaseReturnLinesKeepTheirLimitsAndNeverChange() = runBlocking {
        val returnId = insert("purchase_returns", purchaseReturnRow())
        val valid = purchaseReturnLineRow(returnId, productId)
        for (broken in listOf(
            valid.with("quantity_milli" to 0L),
            valid.with("quantity_milli" to 1_000_000_001L),
            valid.with("unit_cost" to 1_000_000_000_001L),
            valid.with("line_total" to 20_000.5),
            valid.with("line_total" to null),
        )) {
            rejected(PURCHASE_RETURN_LINES, broken.insertInto("purchase_return_lines"))
        }
        val id = insert("purchase_return_lines", valid)

        rejected(
            PURCHASE_RETURN_LINES,
            valid.withId(id).insertOrReplaceInto("purchase_return_lines"),
        )
        rejected(
            PURCHASE_RETURN_LINES_APPEND_ONLY,
            update("purchase_return_lines", id, "unit_cost" to 1L),
        )
        rejected(
            PURCHASE_RETURN_LINES_APPEND_ONLY,
            "DELETE FROM purchase_return_lines WHERE id = $id",
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM purchase_return_lines"))
    }

    /** Una devolución de una unidad con su impuesto: 5.000 más 950. */
    private fun saleReturnRow(saleId: Long): Row = row(
        "sale_id" to saleId,
        "resolution" to "REFUND",
        "net_refund" to 5_000L,
        "tax_refund" to 950L,
        "total_refund" to 5_950L,
        "cost_returned" to 3_000L,
        "money_account_id" to null,
        "replacement_sale_id" to null,
        "note" to null,
        "occurred_at" to CREATED_AT,
        "business_date" to BUSINESS_DATE,
        "created_at" to CREATED_AT,
        "status" to "ACTIVE",
        "voided_at" to null,
        "void_reason" to null,
        "void_note" to null,
    )

    /** La línea de [saleReturnRow]: una unidad que vuelve al stock. */
    private fun saleReturnLineRow(saleReturnId: Long, saleLineId: Long): Row = row(
        "sale_return_id" to saleReturnId,
        "sale_line_id" to saleLineId,
        "quantity_milli" to 1_000L,
        "net_amount" to 5_000L,
        "tax_amount" to 950L,
        "line_total" to 5_950L,
        "line_cost" to 3_000L,
        "restock" to 1L,
    )

    /** Una devolución de una caja a un proveedor sin nombrar, que devuelve el dinero. */
    private fun purchaseReturnRow(): Row = row(
        "purchase_id" to null,
        "supplier_id" to null,
        "resolution" to "CASH_REFUND",
        "total" to 20_000L,
        "money_account_id" to null,
        "note" to null,
        "occurred_at" to CREATED_AT,
        "business_date" to BUSINESS_DATE,
        "created_at" to CREATED_AT,
        "status" to "ACTIVE",
        "voided_at" to null,
        "void_reason" to null,
        "void_note" to null,
    )

    /** La línea de [purchaseReturnRow]: doce unidades base a 1.667. */
    private fun purchaseReturnLineRow(purchaseReturnId: Long, productId: Long): Row = row(
        "purchase_return_id" to purchaseReturnId,
        "purchase_line_id" to null,
        "product_id" to productId,
        "lot_id" to null,
        "quantity_milli" to 12_000L,
        "unit_cost" to 1_667L,
        "line_total" to 20_000L,
    )

    private companion object {
        const val SALE_RETURNS = "guard:sale_returns"
        const val SALE_RETURNS_VOID_ONLY = "guard:sale_returns_void_only"
        const val SALES_VOID_ONLY = "guard:sales_void_only"
        const val RETURN_LINES = "guard:sale_return_lines"
        const val RETURN_LINES_APPEND_ONLY = "guard:sale_return_lines_append_only"
        const val PURCHASE_RETURNS = "guard:purchase_returns"
        const val PURCHASE_RETURNS_VOID_ONLY = "guard:purchase_returns_void_only"
        const val PURCHASE_RETURN_LINES = "guard:purchase_return_lines"
        const val PURCHASE_RETURN_LINES_APPEND_ONLY = "guard:purchase_return_lines_append_only"
        const val MISSING_ID = 999L
    }
}
