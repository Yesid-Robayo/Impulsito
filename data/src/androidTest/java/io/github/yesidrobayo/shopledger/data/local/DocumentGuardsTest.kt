package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de los documentos y sus líneas: compras, ventas y gastos. Un
 * documento nace activo, cuadra sus totales, solo cambia al anularse, con un motivo de su clase, y
 * nunca se borra. Sus líneas no cambian ni se borran. Cada guarda rechaza lo que debe, con su
 * mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class DocumentGuardsTest : GuardProbeTest() {
    @Test
    fun aPurchaseIsBornActiveAndBalanced() = runBlocking {
        val valid = purchaseRow()
        val supplierId = insert("suppliers", supplierRow())
        for (broken in listOf(
            valid.with("total" to 20_001L),
            valid.with("amount_paid" to 20_001L),
            // Pagada en parte, la deuda necesita un proveedor.
            valid.with("amount_paid" to 5_000L),
            valid.with("status" to "VOIDED"),
            valid.with("voided_at" to LATER),
            valid.with("void_reason" to "UNDO"),
            valid.with("fx_currency_code" to "XTS"),
            valid.with("fx_currency_code" to "xts", "fx_rate_micro" to 4_150_000_000L),
            valid.with("due_date" to "2026-02-30"),
            valid.with("net_total" to 20_000.5),
            valid.with("invoice_reference" to "F".repeat(41)),
        )) {
            rejected(PURCHASES, broken.insertInto("purchases"))
        }

        val id = insert("purchases", valid)
        insert("purchases", valid.with("amount_paid" to 5_000L, "supplier_id" to supplierId))
        insert(
            "purchases",
            valid.with(
                "fx_currency_code" to "XTS",
                "fx_rate_micro" to 4_150_000_000L,
            ),
        )
        // INSERT OR REPLACE borraría la compra sin pasar por la guarda de borrado.
        rejected(PURCHASES, valid.with("total" to 1L).withId(id).insertOrReplaceInto("purchases"))
        assertEquals(20_000L, count("SELECT total FROM purchases WHERE id = $id"))
    }

    @Test
    fun aPurchaseOnlyChangesToBeVoidedOnceWithAReason() = runBlocking {
        val id = insert("purchases", purchaseRow())
        for (broken in listOf(
            arrayOf(*voiding("ENTRY_ERROR"), "void_reason" to null),
            arrayOf(*voiding("CUSTOMER_RETURN")),
            arrayOf(*voiding("ENTRY_ERROR"), "void_note" to "N".repeat(201)),
            arrayOf(*voiding("ENTRY_ERROR"), "voided_at" to null),
            arrayOf(*voiding("ENTRY_ERROR"), "total" to 1L),
            arrayOf(*voiding("ENTRY_ERROR"), "business_date" to "2026-01-02"),
            arrayOf("invoice_reference" to "F-0002"),
        )) {
            rejected(PURCHASES_VOID_ONLY, update("purchases", id, *broken))
        }

        execute(update("purchases", id, *voiding("SUPPLIER_RETURN")))
        // Anulada, no vuelve a activa ni se anula otra vez.
        rejected(PURCHASES_VOID_ONLY, update("purchases", id, "status" to "ACTIVE"))
        rejected(PURCHASES_VOID_ONLY, update("purchases", id, *voiding("OTHER")))
        rejected("guard:purchases_no_delete", "DELETE FROM purchases WHERE id = $id")
        assertEquals(1L, count("SELECT COUNT(*) FROM purchases WHERE status = 'VOIDED'"))
    }

    @Test
    fun purchaseLinesAreNeverEditedNorDeleted() = runBlocking {
        val purchaseId = insert("purchases", purchaseRow())
        val valid = purchaseLineRow(purchaseId, productId)
        for (broken in listOf(
            valid.with("quantity_milli" to 0L),
            valid.with("unit_factor_milli" to 1_000_000_001L),
            valid.with("tax_rate_bp" to 10_001L),
            valid.with("cost_amount" to -1L),
            valid.with("unit_cost" to 20_000.5),
            valid.with("tax_name_snapshot" to ""),
        )) {
            rejected("guard:purchase_lines", broken.insertInto("purchase_lines"))
        }
        val id = insert("purchase_lines", valid)

        rejected("guard:purchase_lines", valid.withId(id).insertOrReplaceInto("purchase_lines"))
        rejected(LINES_APPEND_ONLY_PURCHASE, update("purchase_lines", id, "unit_cost" to 1L))
        rejected(LINES_APPEND_ONLY_PURCHASE, "DELETE FROM purchase_lines WHERE id = $id")
        assertEquals(20_000L, count("SELECT unit_cost FROM purchase_lines WHERE id = $id"))
    }

    @Test
    fun aSaleIsBornActiveAndBalanced() = runBlocking {
        val valid = saleRow(priceListId)
        for (broken in listOf(
            valid.with("total" to 11_901L),
            valid.with("amount_due" to 12_900L),
            valid.with("status" to "VOIDED"),
            valid.with("voided_at" to LATER),
            valid.with("void_note" to "Nota"),
            valid.with("cash_rounding_amount" to -1_000_000_000_001L),
            valid.with("tip_amount" to -1L),
            valid.with("note" to "N".repeat(201)),
            valid.with("business_date" to "01/01/2026"),
            valid.with("total_cost" to 6_000.5),
        )) {
            rejected(SALES, broken.insertInto("sales"))
        }

        val id = insert("sales", valid)
        rejected(SALES, valid.with("total" to 1L).withId(id).insertOrReplaceInto("sales"))
        assertEquals(11_900L, count("SELECT total FROM sales WHERE id = $id"))
    }

    @Test
    fun aSaleOnlyChangesToBeVoidedOnceWithAReason() = runBlocking {
        val id = insert("sales", saleRow(priceListId))
        for (broken in listOf(
            arrayOf(*voiding("ENTRY_ERROR"), "void_reason" to null),
            arrayOf(*voiding("SUPPLIER_RETURN")),
            arrayOf(*voiding("ENTRY_ERROR"), "voided_at" to null),
            arrayOf(*voiding("ENTRY_ERROR"), "amount_due" to 1L),
            arrayOf(*voiding("ENTRY_ERROR"), "note" to "Otra nota"),
            arrayOf(*voiding("ENTRY_ERROR"), "price_list_id" to insertPriceList("otra")),
            arrayOf("note" to "Otra nota"),
        )) {
            rejected(SALES_VOID_ONLY, update("sales", id, *broken))
        }

        execute(update("sales", id, *voiding("CUSTOMER_RETURN")))
        rejected(SALES_VOID_ONLY, update("sales", id, "status" to "ACTIVE"))
        rejected(SALES_VOID_ONLY, update("sales", id, *voiding("UNDO")))
        rejected("guard:sales_no_delete", "DELETE FROM sales WHERE id = $id")
        assertEquals(1L, count("SELECT COUNT(*) FROM sales WHERE status = 'VOIDED'"))
    }

    @Test
    fun saleLinesMatchTheStockModeOfTheirProductAndNeverChange() = runBlocking {
        val saleId = insert("sales", saleRow(priceListId))
        val valid = saleLineRow(saleId, productId, priceListId)
        val service = insert("products", productRow("domicilio").with("stock_mode" to "UNTRACKED"))
        for (broken in listOf(
            // El efecto en el stock es el del modo del producto.
            valid.with("stock_effect" to "NONE"),
            valid.with("product_id" to service),
            valid.with("stock_effect" to "LATER"),
            valid.with("line_total" to 11_901L),
            valid.with("product_name_snapshot" to ""),
            valid.with("quantity_milli" to 0L),
            valid.with("unit_price" to 1_000_000_000_001L),
            valid.with("tax_rate_bp" to -1L),
        )) {
            rejected("guard:sale_lines", broken.insertInto("sale_lines"))
        }
        val id = insert("sale_lines", valid)
        insert("sale_lines", valid.with("product_id" to service, "stock_effect" to "NONE"))

        rejected("guard:sale_lines", valid.withId(id).insertOrReplaceInto("sale_lines"))
        rejected(LINES_APPEND_ONLY_SALE, update("sale_lines", id, "unit_price" to 1L))
        rejected(LINES_APPEND_ONLY_SALE, "DELETE FROM sale_lines WHERE id = $id")
        assertEquals(2L, count("SELECT COUNT(*) FROM sale_lines"))
    }

    @Test
    fun anExpenseIsBornActiveAndOnlyChangesToBeVoided() = runBlocking {
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Empaques"))
        val valid = expenseRow(categoryId)
        for (broken in listOf(
            valid.with("amount" to 0L),
            valid.with("amount" to 45_000.5),
            valid.with("amount" to 1_000_000_000_000_001L),
            valid.with("status" to "VOIDED"),
            valid.with("void_reason" to "UNDO"),
            valid.with("description" to "D".repeat(201)),
            valid.with("business_date" to "2026-00-10"),
        )) {
            rejected(EXPENSES, broken.insertInto("expenses"))
        }
        val id = insert("expenses", valid)
        rejected(EXPENSES, valid.with("amount" to 1L).withId(id).insertOrReplaceInto("expenses"))

        for (broken in listOf(
            arrayOf(*voiding("ENTRY_ERROR"), "void_reason" to null),
            arrayOf(*voiding("CUSTOMER_RETURN")),
            arrayOf(*voiding("ENTRY_ERROR"), "amount" to 1L),
            arrayOf("description" to "Bolsas"),
        )) {
            rejected(EXPENSES_VOID_ONLY, update("expenses", id, *broken))
        }
        execute(update("expenses", id, *voiding("UNDO")))
        rejected(EXPENSES_VOID_ONLY, update("expenses", id, *voiding("OTHER")))
        rejected("guard:expenses_no_delete", "DELETE FROM expenses WHERE id = $id")
        assertEquals(45_000L, count("SELECT amount FROM expenses WHERE status = 'VOIDED'"))
    }

    private companion object {
        const val PURCHASES = "guard:purchases"
        const val PURCHASES_VOID_ONLY = "guard:purchases_void_only"
        const val LINES_APPEND_ONLY_PURCHASE = "guard:purchase_lines_append_only"
        const val SALES = "guard:sales"
        const val SALES_VOID_ONLY = "guard:sales_void_only"
        const val LINES_APPEND_ONLY_SALE = "guard:sale_lines_append_only"
        const val EXPENSES = "guard:expenses"
        const val EXPENSES_VOID_ONLY = "guard:expenses_void_only"
    }
}
