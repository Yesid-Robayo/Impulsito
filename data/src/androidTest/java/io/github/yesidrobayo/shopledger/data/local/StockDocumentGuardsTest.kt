package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de transferencias, producción y conteos: cada documento nace activo y
 * solo cambia al anularse, sus líneas no cambian nunca, y una sesión de conteo solo cambia abierta.
 * Cada guarda rechaza lo que debe, con su mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class StockDocumentGuardsTest : GuardProbeTest() {
    private var warehouse = 0L

    @Before
    fun createSecondLocation() = runBlocking {
        warehouse = insert("locations", locationRow("bodega"))
    }

    @Test
    fun aTransferIsBornActiveBetweenTwoLocations() = runBlocking {
        val valid = transferRow(LOCATION_ID, warehouse)
        for (broken in listOf(
            valid.with(*voiding("ENTRY_ERROR")),
            valid.with("voided_at" to LATER),
            valid.with("void_reason" to "UNDO"),
            valid.with("to_location_id" to LOCATION_ID),
            valid.with("note" to "N".repeat(201)),
            valid.with("note" to ""),
            valid.with("business_date" to "2026-02-30"),
            valid.with("occurred_at" to "hoy"),
            valid.with("created_at" to null),
        )) {
            rejected(TRANSFERS, broken.insertInto("stock_transfers"))
        }
        val id = insert("stock_transfers", valid.with("note" to "N".repeat(200)))
        // Un INSERT OR REPLACE borraría la transferencia sin pasar por su guarda de borrado.
        rejected(TRANSFERS, valid.withId(id).insertOrReplaceInto("stock_transfers"))
        assertEquals(1L, count("SELECT COUNT(*) FROM stock_transfers"))
    }

    @Test
    fun aTransferOnlyChangesToBeVoidedOnceAndIsNeverDeleted() = runBlocking {
        val id = insert("stock_transfers", transferRow(LOCATION_ID, warehouse))
        for (broken in listOf(
            update("stock_transfers", id, "note" to "Otra nota"),
            update("stock_transfers", id, *voiding("CUSTOMER_RETURN")),
            update("stock_transfers", id, *voiding("OTHER"), "void_note" to "N".repeat(201)),
            update("stock_transfers", id, *voiding("UNDO"), "voided_at" to null),
            update("stock_transfers", id, *voiding("UNDO"), "to_location_id" to LOCATION_ID),
            update("stock_transfers", id, *voiding("UNDO"), "business_date" to "2026-01-02"),
        )) {
            rejected(TRANSFERS_VOID_ONLY, broken)
        }
        execute(update("stock_transfers", id, *voiding("UNDO")))
        // Anulada, ya no cambia, ni siquiera para anularse otra vez.
        rejected(TRANSFERS_VOID_ONLY, update("stock_transfers", id, *voiding("OTHER")))
        rejected("guard:stock_transfers_no_delete", "DELETE FROM stock_transfers WHERE id = $id")
        assertEquals(1L, count("SELECT COUNT(*) FROM stock_transfers WHERE status = 'VOIDED'"))
    }

    @Test
    fun aTransferLineMovesAPositiveQuantityAndNeverChanges() = runBlocking {
        val transferId = insert("stock_transfers", transferRow(LOCATION_ID, warehouse))
        val valid = transferLineRow(transferId, productId)
        for (broken in listOf(
            valid.with("quantity_milli" to 0L),
            valid.with("quantity_milli" to -4_000L),
            valid.with("quantity_milli" to 1_000_000_001L),
            valid.with("quantity_milli" to 4_000.5),
        )) {
            rejected(TRANSFER_LINES, broken.insertInto("stock_transfer_lines"))
        }
        insert("stock_transfer_lines", valid.with("quantity_milli" to 1L))
        val id = insert("stock_transfer_lines", valid.with("quantity_milli" to 1_000_000_000L))

        rejected(TRANSFER_LINES, valid.withId(id).insertOrReplaceInto("stock_transfer_lines"))
        rejected(
            TRANSFER_LINES_APPEND_ONLY,
            update(
                "stock_transfer_lines",
                id,
                "quantity_milli" to 1L,
            ),
        )
        rejected(TRANSFER_LINES_APPEND_ONLY, "DELETE FROM stock_transfer_lines WHERE id = $id")
        assertEquals(2L, count("SELECT COUNT(*) FROM stock_transfer_lines"))
    }

    @Test
    fun aProductionBatchIsBornActiveAndOnlyChangesToBeVoided() = runBlocking {
        val valid = batchRow(productId)
        for (broken in listOf(
            valid.with(*voiding("ENTRY_ERROR")),
            valid.with("void_note" to "Sin motivo"),
            valid.with("quantity_milli" to 0L),
            valid.with("quantity_milli" to 1_000_000_001L),
            valid.with("total_cost" to -1L),
            valid.with("total_cost" to 1_000_000_000_000_001L),
            valid.with("total_cost" to 17_500.5),
            valid.with("note" to "N".repeat(201)),
            valid.with("business_date" to "2026-13-01"),
        )) {
            rejected(BATCHES, broken.insertInto("production_batches"))
        }
        val id = insert("production_batches", valid.with("total_cost" to 0L))
        rejected(BATCHES, valid.withId(id).insertOrReplaceInto("production_batches"))

        for (broken in listOf(
            update("production_batches", id, "quantity_milli" to 5_000L),
            update("production_batches", id, *voiding("SUPPLIER_RETURN")),
            update("production_batches", id, *voiding("UNDO"), "total_cost" to 100L),
            update("production_batches", id, *voiding("UNDO"), "location_id" to warehouse),
        )) {
            rejected(BATCHES_VOID_ONLY, broken)
        }
        execute(update("production_batches", id, *voiding("ENTRY_ERROR")))
        rejected(BATCHES_VOID_ONLY, update("production_batches", id, *voiding("UNDO")))
        rejected(
            "guard:production_batches_no_delete",
            "DELETE FROM production_batches WHERE id = $id",
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM production_batches WHERE status = 'VOIDED'"))
    }

    @Test
    fun aProductionInputKeepsItsQuantityAndCostsAndNeverChanges() = runBlocking {
        val batchId = insert("production_batches", batchRow(productId))
        val wax = insert("products", productRow("cera"))
        val valid = batchLineRow(batchId, wax)
        for (broken in listOf(
            valid.with("quantity_milli" to 0L),
            valid.with("quantity_milli" to 1_000_000_001L),
            valid.with("unit_cost" to -1L),
            valid.with("unit_cost" to 1_000_000_000_001L),
            valid.with("line_cost" to -1L),
            valid.with("line_cost" to 1_000_000_000_000_001L),
            valid.with("line_cost" to 16_000.5),
        )) {
            rejected(BATCH_LINES, broken.insertInto("production_batch_lines"))
        }
        val id = insert("production_batch_lines", valid.with("unit_cost" to 0L, "line_cost" to 0L))

        rejected(BATCH_LINES, valid.withId(id).insertOrReplaceInto("production_batch_lines"))
        rejected(BATCH_LINES_APPEND_ONLY, update("production_batch_lines", id, "line_cost" to 1L))
        rejected(BATCH_LINES_APPEND_ONLY, "DELETE FROM production_batch_lines WHERE id = $id")
        assertEquals(1L, count("SELECT COUNT(*) FROM production_batch_lines"))
    }

    @Test
    fun aCountSessionIsBornOpenAndClosesOnce() = runBlocking {
        val valid = countSessionRow()
        for (broken in listOf(
            valid.with("status" to "APPLIED", "finished_at" to LATER),
            valid.with("status" to "DONE"),
            valid.with("finished_at" to LATER),
            valid.with("is_blind" to 2L),
            valid.with("note" to "N".repeat(201)),
            valid.with("started_at" to "ayer"),
            valid.with("business_date" to "2026-02-29"),
        )) {
            rejected(SESSIONS, broken.insertInto("count_sessions"))
        }
        val applied = insert("count_sessions", valid)
        val cancelled = insert("count_sessions", valid.with("is_blind" to 0L))

        for (broken in listOf(
            // Al cerrarse lleva su cierre, y abierta no lo lleva.
            update("count_sessions", applied, "status" to "APPLIED"),
            update("count_sessions", applied, "finished_at" to LATER),
            // La ubicación, la marca de a ciegas y el inicio no cambian.
            update("count_sessions", applied, "location_id" to warehouse),
            update("count_sessions", applied, "is_blind" to 0L),
            update("count_sessions", applied, "started_at" to LATER),
        )) {
            rejected(SESSIONS, broken)
        }
        execute(update("count_sessions", applied, "note" to "Conteo de cierre"))
        execute(update("count_sessions", applied, "status" to "APPLIED", "finished_at" to LATER))
        execute(
            update("count_sessions", cancelled, "status" to "CANCELLED", "finished_at" to LATER),
        )

        // Cerrada, ni se reabre, ni pasa a otro cierre, ni cambia su instante.
        rejected(
            SESSIONS,
            update(
                "count_sessions",
                applied,
                "status" to "OPEN",
                "finished_at" to null,
            ),
        )
        rejected(SESSIONS, update("count_sessions", cancelled, "status" to "APPLIED"))
        rejected(SESSIONS, update("count_sessions", applied, "finished_at" to MUCH_LATER))
        assertEquals(2L, count("SELECT COUNT(*) FROM count_sessions WHERE finished_at = $LATER"))
    }

    @Test
    fun aCountLineOnlyChangesWhileItsSessionIsOpen() = runBlocking {
        val sessionId = insert("count_sessions", countSessionRow())
        val valid = countLineRow(sessionId, productId)
        for (broken in listOf(
            valid.with("expected_milli" to 10_000.5),
            valid.with("expected_milli" to null),
            valid.with("counted_milli" to -1L),
            valid.with("counted_milli" to 8_000_000_001L),
            valid.with("counted_milli" to 9_500.5),
            valid.with("count_session_id" to MISSING_ID),
        )) {
            rejected(LINES, broken.insertInto("count_session_lines"))
        }
        // Lo esperado puede ser negativo: el stock puede quedar bajo cero.
        val id = insert("count_session_lines", valid.with("expected_milli" to -2_000L))
        insert("count_session_lines", valid.with("counted_milli" to 8_000_000_000L))
        execute(update("count_session_lines", id, "counted_milli" to 0L))
        rejected(LINES, update("count_session_lines", id, "counted_milli" to -5L))

        execute(update("count_sessions", sessionId, "status" to "APPLIED", "finished_at" to LATER))
        rejected(LINES, valid.insertInto("count_session_lines"))
        rejected(LINES, update("count_session_lines", id, "counted_milli" to 9_000L))
        assertEquals(0L, count("SELECT counted_milli FROM count_session_lines WHERE id = $id"))
    }

    private companion object {
        const val TRANSFERS = "guard:stock_transfers"
        const val TRANSFERS_VOID_ONLY = "guard:stock_transfers_void_only"
        const val TRANSFER_LINES = "guard:stock_transfer_lines"
        const val TRANSFER_LINES_APPEND_ONLY = "guard:stock_transfer_lines_append_only"
        const val BATCHES = "guard:production_batches"
        const val BATCHES_VOID_ONLY = "guard:production_batches_void_only"
        const val BATCH_LINES = "guard:production_batch_lines"
        const val BATCH_LINES_APPEND_ONLY = "guard:production_batch_lines_append_only"
        const val SESSIONS = "guard:count_sessions"
        const val LINES = "guard:count_session_lines"
        const val MISSING_ID = 999L
    }
}
