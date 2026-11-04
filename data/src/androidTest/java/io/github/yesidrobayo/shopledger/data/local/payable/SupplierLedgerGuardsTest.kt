package io.github.yesidrobayo.shopledger.data.local.payable

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.GuardProbeTest
import io.github.yesidrobayo.shopledger.data.local.purchaseRow
import io.github.yesidrobayo.shopledger.data.local.supplierRow
import io.github.yesidrobayo.shopledger.data.local.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas del libro de proveedores. Cada guarda rechaza lo que debe, con su
 * mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class SupplierLedgerGuardsTest : GuardProbeTest() {
    @Test
    fun eachEntryKeepsTheSignAndTheOriginOfItsType() = runBlocking {
        val supplierId = insert("suppliers", supplierRow())
        val purchaseId =
            insert(
                "purchases",
                purchaseRow().with("supplier_id" to supplierId, "amount_paid" to 0L),
            )
        val opening = supplierEntryRow(supplierId)
        val credit =
            opening.with(
                "type" to "CREDIT_PURCHASE",
                "amount" to 20_000L,
                "purchase_id" to purchaseId,
            )
        val payment = opening.with("type" to "PAYMENT", "amount" to -10_000L)
        val returnCredit =
            opening.with("type" to "RETURN_CREDIT", "amount" to -1_000L, "purchase_return_id" to 1L)
        for (broken in listOf(
            // Nunca 0, y el signo de cada tipo.
            opening.with("amount" to 0L),
            credit.with("amount" to -20_000L),
            payment.with("amount" to 10_000L),
            returnCredit.with("amount" to 1_000L),
            // El origen de cada tipo, y solo el suyo.
            credit.with("purchase_id" to null),
            opening.with("purchase_id" to purchaseId),
            payment.with("purchase_id" to purchaseId),
            returnCredit.with("purchase_return_id" to null),
            opening.with("purchase_return_id" to 1L),
            opening.with("reverses_entry_id" to 1L),
            // Un ajuste siempre explica su motivo.
            opening.with("type" to "ADJUSTMENT"),
            opening.with("type" to "ADJUSTMENT", "amount" to -1L),
            // Tipos, enteros, fechas y largos.
            opening.with("type" to "LOAN"),
            opening.with("amount" to 30_000.5),
            opening.with("balance_after" to "treinta mil"),
            opening.with("due_date" to "2026-06-31"),
            opening.with("business_date" to "2026/01/01"),
            opening.with("note" to "N".repeat(201)),
            opening.with("created_at" to null),
        )) {
            rejected(ENTRIES, broken.insertInto(TABLE))
        }

        for (valid in listOf(
            opening.with("amount" to -30_000L, "balance_after" to -30_000L),
            credit.with("due_date" to "2026-01-31"),
            payment,
            returnCredit,
            opening.with("type" to "ADJUSTMENT", "amount" to -1L, "note" to "Redondeo"),
        )) {
            insert(TABLE, valid)
        }
        assertEquals(5L, count("SELECT COUNT(*) FROM supplier_ledger_entries"))
    }

    @Test
    fun aCompensationPointsToAnEntryOfTheSameSupplierOnlyOnce() = runBlocking {
        val supplierId = insert("suppliers", supplierRow())
        val otherId = insert("suppliers", supplierRow("mercado central"))
        val opening = supplierEntryRow(supplierId)
        val paymentId = insert(TABLE, opening.with("type" to "PAYMENT", "amount" to -10_000L))
        val otherPaymentId =
            insert(
                TABLE,
                supplierEntryRow(otherId).with("type" to "PAYMENT", "amount" to -10_000L),
            )
        val undo =
            opening.with(
                "type" to "ENTRY_VOID",
                "amount" to 10_000L,
                "reverses_entry_id" to paymentId,
            )
        for (broken in listOf(
            undo.with("amount" to 9_999L),
            undo.with("amount" to -10_000L),
            undo.with("reverses_entry_id" to otherPaymentId),
            undo.with("reverses_entry_id" to MISSING_ID),
            undo.with("reverses_entry_id" to null),
        )) {
            rejected(ENTRIES, broken.insertInto(TABLE))
        }

        val undoId = insert(TABLE, undo)
        rejected(ENTRIES, undo.insertInto(TABLE))
        rejected(
            ENTRIES,
            undo.with("amount" to -10_000L, "reverses_entry_id" to undoId).insertInto(TABLE),
        )
        assertEquals(3L, count("SELECT COUNT(*) FROM supplier_ledger_entries"))
    }

    @Test
    fun theSupplierLedgerOnlyGrows() = runBlocking {
        val supplierId = insert("suppliers", supplierRow())
        val opening = supplierEntryRow(supplierId)
        val id = insert(TABLE, opening)

        rejected(APPEND_ONLY, update(TABLE, id, "amount" to 31_000L))
        rejected(APPEND_ONLY, update(TABLE, id, "due_date" to "2026-02-01"))
        rejected(APPEND_ONLY, "DELETE FROM supplier_ledger_entries WHERE id = $id")
        rejected(ENTRIES, opening.with("amount" to 1_000L).withId(id).insertOrReplaceInto(TABLE))

        assertEquals(30_000L, count("SELECT amount FROM supplier_ledger_entries WHERE id = $id"))
        assertEquals(1L, count("SELECT COUNT(*) FROM supplier_ledger_entries"))
    }

    private companion object {
        const val MISSING_ID = 999L
        const val TABLE = "supplier_ledger_entries"
        const val ENTRIES = "guard:supplier_ledger_entries"
        const val APPEND_ONLY = "guard:supplier_ledger_entries_append_only"
    }
}
