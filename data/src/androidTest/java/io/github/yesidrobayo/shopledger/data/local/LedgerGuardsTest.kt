package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas del libro de stock y de las alertas de stock bajo. Cada guarda rechaza
 * lo que debe, con su mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class LedgerGuardsTest : GuardProbeTest() {
    @Test
    fun eachRowKeepsTheSignAndTheOriginOfItsType() = runBlocking {
        val opening = movementRow(productId)
        val sold = insertSaleWithLine()
        val sale =
            opening.with(
                "type" to "SALE",
                "quantity_milli" to -2_000L,
                "total_cost" to -2_400L,
                "balance_after_milli" to 8_000L,
                "stock_value_after" to 9_600L,
                "sale_line_id" to sold.id,
            )
        val damaged =
            opening.with("type" to "ADJUSTMENT", "reason" to "DAMAGED", "quantity_milli" to -1_000L)
        val untracked =
            insert("products", productRow("domicilio").with("stock_mode" to "UNTRACKED"))
        val lotted = insert("products", productRow("queso").with("tracks_lots" to 1L))
        for (broken in listOf(
            // El signo de cada tipo.
            opening.with("quantity_milli" to -10_000L),
            sale.with("quantity_milli" to 2_000L),
            opening.with("type" to "COST_CORRECTION", "quantity_milli" to 0L),
            opening.with("type" to "TRANSFER_IN", "transfer_line_id" to 1L),
            damaged.with("quantity_milli" to 1_000L),
            // El origen de cada tipo, y solo el suyo.
            sale.with("sale_line_id" to null),
            opening.with("sale_line_id" to sold.id),
            opening.with("type" to "PURCHASE"),
            opening.with("production_batch_id" to 1L),
            opening.with("count_session_id" to 1L),
            opening.with("sale_line_component_id" to 1L),
            // El motivo solo en un ajuste, y uno de otros con nota.
            opening.with("type" to "ADJUSTMENT"),
            opening.with("reason" to "DAMAGED"),
            damaged.with("reason" to "OTHER"),
            damaged.with("reason" to "BROKEN"),
            // Tipos, enteros, fechas y largos.
            opening.with("type" to "GIFT"),
            opening.with("quantity_milli" to 10_000.5),
            opening.with("unit_cost" to -1L),
            opening.with("stock_value_after" to 9_000_000_000_000_001L),
            opening.with("business_date" to "2026-13-01"),
            opening.with("note" to "N".repeat(201)),
            // Solo un producto con libro de stock tiene filas. Los lotes son todo o nada: sin lote
            // en un producto que los lleva, y ninguno en uno que no. Sin la tabla de lotes ningún
            // lote existe, así que tampoco lo lleva el producto que los usa.
            movementRow(untracked),
            movementRow(MISSING_ID),
            opening.with("lot_id" to 1L),
            movementRow(lotted),
            movementRow(lotted).with("lot_id" to 1L),
        )) {
            rejected(MOVEMENTS, broken.insertInto("stock_movements"))
        }

        insert("stock_movements", opening)
        insert("stock_movements", sale)
        insert("stock_movements", damaged)
        insert("stock_movements", damaged.with("reason" to "OTHER", "note" to "Se mojó"))
        insert(
            "stock_movements",
            opening.with(
                "type" to "ADJUSTMENT",
                "reason" to "OTHER",
                "note" to "Bono",
            ),
        )
        insert(
            "stock_movements",
            opening.with("type" to "COST_CORRECTION", "quantity_milli" to 0L, "total_cost" to 0L),
        )
        assertEquals(6L, count("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun aCompensationPointsToItsOwnRowOnlyOnce() = runBlocking {
        val opening = movementRow(productId)
        val openingId = insert("stock_movements", opening)
        val purchased = insertPurchaseWithLine()
        val purchaseId =
            insert(
                "stock_movements",
                opening.with(
                    "type" to "PURCHASE",
                    "quantity_milli" to 12_000L,
                    "purchase_line_id" to purchased.id,
                ),
            )
        val sold = insertSaleWithLine()
        val saleRowId =
            insert(
                "stock_movements",
                opening.with(
                    "type" to "SALE",
                    "quantity_milli" to -2_000L,
                    "sale_line_id" to sold.id,
                ),
            )
        val damagedId =
            insert(
                "stock_movements",
                opening.with(
                    "type" to "ADJUSTMENT",
                    "reason" to "DAMAGED",
                    "quantity_milli" to -1_000L,
                ),
            )
        val undoOpening =
            opening.with(
                "type" to "ADJUSTMENT_VOID",
                "quantity_milli" to -10_000L,
                "reverses_movement_id" to openingId,
            )
        val other = insert("products", productRow("lenteja"))
        for (broken in listOf(
            // Compensación cruzada, con el signo y la cantidad correctos: una anulación de venta no
            // compensa un ajuste, una de ajuste no compensa una venta, y una de compra no compensa
            // un inventario inicial.
            opening.with(
                "type" to "SALE_VOID",
                "quantity_milli" to 1_000L,
                "sale_line_id" to sold.id,
                "reverses_movement_id" to damagedId,
            ),
            opening.with(
                "type" to "ADJUSTMENT_VOID",
                "quantity_milli" to 2_000L,
                "reverses_movement_id" to saleRowId,
            ),
            opening.with(
                "type" to "PURCHASE_VOID",
                "quantity_milli" to -10_000L,
                "purchase_line_id" to purchased.id,
                "reverses_movement_id" to openingId,
            ),
            // La cantidad opuesta, en el mismo producto y la misma ubicación.
            undoOpening.with("quantity_milli" to -9_000L),
            undoOpening.with("product_id" to other),
            undoOpening.with("location_id" to LOCATION_ID + 1),
            // Una anulación sin fila que compensar, y una fila que no anula con una.
            undoOpening.with("reverses_movement_id" to null),
            undoOpening.with("reverses_movement_id" to MISSING_ID),
            opening.with("reverses_movement_id" to openingId),
        )) {
            rejected(MOVEMENTS, broken.insertInto("stock_movements"))
        }

        insert("stock_movements", undoOpening)
        // La misma fila no se compensa dos veces.
        rejected(MOVEMENTS, undoOpening.insertInto("stock_movements"))
        insert(
            "stock_movements",
            opening.with(
                "type" to "PURCHASE_VOID",
                "quantity_milli" to -12_000L,
                "purchase_line_id" to purchased.id,
                "reverses_movement_id" to purchaseId,
            ),
        )
        assertEquals(6L, count("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun theLedgerOnlyGrows() = runBlocking {
        val opening = movementRow(productId)
        val id = insert("stock_movements", opening)

        rejected(APPEND_ONLY, update("stock_movements", id, "quantity_milli" to 9_000L))
        rejected(APPEND_ONLY, update("stock_movements", id, "note" to "Corregido"))
        rejected(APPEND_ONLY, "DELETE FROM stock_movements WHERE id = $id")
        // INSERT OR REPLACE borraría la fila sin pasar por la guarda de borrado: la de inserción
        // rechaza el id que ya existe.
        rejected(
            MOVEMENTS,
            opening.with(
                "quantity_milli" to 1_000L,
            ).withId(id).insertOrReplaceInto("stock_movements"),
        )

        assertEquals(10_000L, count("SELECT quantity_milli FROM stock_movements WHERE id = $id"))
        assertEquals(1L, count("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun aProductHasOneActiveAlertAndAResolvedOneStaysResolved() = runBlocking {
        val valid = alertRow(productId)
        val first = insert("low_stock_alerts", valid)
        val untracked =
            insert("products", productRow("domicilio").with("stock_mode" to "UNTRACKED"))
        for (broken in listOf(
            // Segunda alerta activa del mismo producto.
            valid,
            // Una alerta nace activa, de un producto con libro de stock.
            alertRow(untracked),
            alertRow(MISSING_ID),
            valid.with(
                "product_id" to insert("products", productRow("té")),
            ).with("resolved_at" to LATER),
            valid.with("notification_status" to "SENT"),
            valid.with("business_date" to "2026-1-1"),
        )) {
            rejected(ALERTS, broken.insertInto("low_stock_alerts"))
        }

        rejected(ALERTS, update("low_stock_alerts", first, "product_id" to untracked))
        rejected(ALERTS, update("low_stock_alerts", first, "notification_status" to "SENT"))
        execute(update("low_stock_alerts", first, "notification_status" to "SHOWN"))
        execute(update("low_stock_alerts", first, "resolved_at" to LATER))
        // Resuelta, no se reabre ni cambia su instante.
        rejected(ALERTS, update("low_stock_alerts", first, "resolved_at" to null))
        rejected(ALERTS, update("low_stock_alerts", first, "resolved_at" to LATER + 1))
        // La siguiente caída sí crea otra.
        insert("low_stock_alerts", valid)
        assertEquals(1L, count("SELECT COUNT(*) FROM low_stock_alerts WHERE resolved_at IS NULL"))
    }

    private companion object {
        const val MISSING_ID = 999L
        const val MOVEMENTS = "guard:stock_movements"
        const val APPEND_ONLY = "guard:stock_movements_append_only"
        const val ALERTS = "guard:low_stock_alerts"
    }
}
