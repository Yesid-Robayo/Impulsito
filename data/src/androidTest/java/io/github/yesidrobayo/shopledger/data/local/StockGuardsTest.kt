package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de ubicaciones, lotes y saldos, y la condición de los lotes en la guarda
 * del libro de stock: cada guarda rechaza lo que debe, con su mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class StockGuardsTest : GuardProbeTest() {
    @Test
    fun aBalanceKeepsAnIntegerQuantityAndALotKeyOfZeroOrMore() = runBlocking {
        val valid = balanceRow(productId)
        for (broken in listOf(
            valid.with("lot_key" to -1L),
            valid.with("lot_key" to 1.5),
            valid.with("lot_key" to null),
            valid.with("lot_key" to "uno"),
            valid.with("on_hand_milli" to 10_000.5),
            valid.with("on_hand_milli" to "diez"),
            valid.with("on_hand_milli" to null),
        )) {
            rejected(BALANCES, broken.insertInto("stock_balances"))
        }

        // Negativo sí: vender nunca se bloquea por stock.
        val id = insert("stock_balances", valid.with("on_hand_milli" to -2_000L))
        insert("stock_balances", valid.with("lot_key" to 41L))
        rejected(BALANCES, update("stock_balances", id, "lot_key" to -5L))
        rejected(BALANCES, update("stock_balances", id, "on_hand_milli" to 0.5))
        execute(update("stock_balances", id, "on_hand_milli" to 3_000L))
        assertEquals(3_000L, count("SELECT on_hand_milli FROM stock_balances WHERE id = $id"))
    }

    @Test
    fun aLotKeepsItsCodeDateAndKindAndOnlyOneIsUnassigned() = runBlocking {
        val cheese = insert("products", productRow("queso").with("tracks_lots" to 1L))
        val valid = lotRow(cheese)
        val unassigned =
            valid.with("lot_code" to null, "is_unassigned" to 1L, "expiry_date" to null)
        for (broken in listOf(
            valid.with("lot_code" to "L".repeat(61)),
            valid.with("lot_code" to ""),
            valid.with("is_unassigned" to 2L),
            valid.with("expiry_date" to "2026-02-30"),
            valid.with("expiry_date" to "20/11/2026"),
            valid.with("expiry_notified_at" to "ayer"),
            valid.with("created_at" to null),
            valid.with("closed_at" to 1.5),
            // El lote sin asignar no lleva código ni vencimiento.
            unassigned.with("lot_code" to "L-09"),
            unassigned.with("expiry_date" to "2026-12-01"),
        )) {
            rejected(LOTS, broken.insertInto("stock_lots"))
        }

        val coded = insert("stock_lots", valid.with("lot_code" to "L".repeat(60)))
        val absorbing = insert("stock_lots", unassigned)
        // Uno solo por producto; otro producto tiene el suyo.
        rejected(LOTS, unassigned.insertInto("stock_lots"))
        insert(
            "stock_lots",
            lotRow(productId)
                .with("lot_code" to null, "is_unassigned" to 1L, "expiry_date" to null),
        )

        // Un lote no cambia de producto, ni deja de ser el sin asignar, ni pasa a serlo.
        rejected(LOTS, update("stock_lots", coded, "product_id" to productId))
        rejected(
            LOTS,
            update(
                "stock_lots",
                coded,
                "is_unassigned" to 1L,
                "lot_code" to null,
                "expiry_date" to null,
            ),
        )
        rejected(LOTS, update("stock_lots", absorbing, "is_unassigned" to 0L))
        rejected(LOTS, update("stock_lots", absorbing, "lot_code" to "L-10"))
        rejected(LOTS, update("stock_lots", coded, "expiry_date" to "2026-13-01"))
        execute(update("stock_lots", coded, "expiry_date" to null, "closed_at" to LATER))
        execute(update("stock_lots", absorbing, "expiry_notified_at" to LATER))
        assertEquals(3L, count("SELECT COUNT(*) FROM stock_lots"))
    }

    @Test
    fun aLocationKeepsItsLengthsKindAndNameOrKey() = runBlocking {
        val valid = locationRow("bodega")
        for (broken in listOf(
            valid.with("name" to "B".repeat(41), "normalized_name" to "b".repeat(41)),
            valid.with("kind" to "SHOP"),
            valid.with("kind" to null),
            valid.with("created_at" to "hoy"),
            valid.with("archived_at" to 1.5),
            // El nombre y su forma normalizada van juntos, y una ubicación sin clave tiene nombre.
            valid.with("normalized_name" to null),
            valid.with("name" to null),
            valid.with("name" to null, "normalized_name" to null),
            valid.with("system_key" to "K".repeat(41)),
            // La inicial nunca nace archivada.
            valid.with(
                "system_key" to "MAIN",
                "name" to null,
                "normalized_name" to null,
                "archived_at" to LATER,
            ),
        )) {
            rejected(LOCATIONS, broken.insertInto("locations"))
        }

        val own = insert("locations", valid.with("kind" to "STALL"))
        rejected(LOCATIONS, update("locations", own, "name" to ""))
        rejected(LOCATIONS, update("locations", own, "kind" to "SHOP"))
        execute(update("locations", own, "kind" to "STORE", "archived_at" to LATER))
        assertEquals(2L, count("SELECT COUNT(*) FROM locations"))
    }

    @Test
    fun aLocationNameIsUniqueOnlyAmongActiveOnes() = runBlocking {
        val valid = locationRow("bodega")
        val archived = insert("locations", valid.with("archived_at" to LATER))
        // Una archivada no reserva su nombre, y dos vigentes no lo repiten.
        val active = insert("locations", valid)
        rejected(LOCATIONS, valid.insertInto("locations"))

        rejected(LOCATIONS, update("locations", archived, "archived_at" to null))
        val other = insert("locations", locationRow("puesto"))
        rejected(LOCATIONS, update("locations", other, "normalized_name" to "bodega"))
        execute(update("locations", active, "archived_at" to LATER))
        execute(update("locations", archived, "archived_at" to null))
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM locations " +
                    "WHERE normalized_name = 'bodega' AND archived_at IS NULL",
            ),
        )
    }

    @Test
    fun theMainLocationIsNeverArchivedNorDeleted() = runBlocking {
        rejected(LOCATIONS, update("locations", LOCATION_ID, "archived_at" to LATER))
        rejected("guard:locations_system", "DELETE FROM locations WHERE id = $LOCATION_ID")

        // Renombrarla sí. Otra sin uso se archiva y se borra.
        execute(update("locations", LOCATION_ID, "name" to "Local", "normalized_name" to "local"))
        val other = insert("locations", locationRow("puesto"))
        execute(update("locations", other, "archived_at" to LATER))
        execute("DELETE FROM locations WHERE id = $other")
        assertEquals(
            1L,
            count("SELECT normalized_name = 'local' FROM locations WHERE id = $LOCATION_ID"),
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM locations"))
    }

    @Test
    fun aLedgerRowOfAProductWithLotsCarriesALotOfThatProduct() = runBlocking {
        val cheese = insert("products", productRow("queso").with("tracks_lots" to 1L))
        val own = insert("stock_lots", lotRow(cheese))
        val foreign = insert("stock_lots", lotRow(productId))
        val opening = movementRow(cheese)
        for (broken in listOf(
            // Sin lote, con un lote que no existe, o con el de otro producto.
            opening,
            opening.with("lot_id" to MISSING_ID),
            opening.with("lot_id" to foreign),
            // Un producto sin lotes no lleva ninguno, aunque exista y sea suyo.
            movementRow(productId).with("lot_id" to foreign),
        )) {
            rejected(MOVEMENTS, broken.insertInto("stock_movements"))
        }

        val first = insert("stock_movements", opening.with("lot_id" to own))
        // Una corrección de costo no mueve unidades: no lleva lote.
        insert(
            "stock_movements",
            opening.with("type" to "COST_CORRECTION", "quantity_milli" to 0L, "total_cost" to 0L),
        )
        // El compensatorio cae en el mismo lote que la fila que anula.
        val undo =
            opening.with(
                "type" to "ADJUSTMENT_VOID",
                "quantity_milli" to -10_000L,
                "total_cost" to -12_000L,
                "reverses_movement_id" to first,
            )
        val sibling = insert("stock_lots", lotRow(cheese).with("lot_code" to "L-02"))
        rejected(MOVEMENTS, undo.with("lot_id" to sibling).insertInto("stock_movements"))
        insert("stock_movements", undo.with("lot_id" to own))
        assertEquals(3L, count("SELECT COUNT(*) FROM stock_movements WHERE product_id = $cheese"))
    }

    /** Un saldo de la ubicación inicial, sin lote. */
    private fun balanceRow(productId: Long): Row = row(
        "product_id" to productId,
        "location_id" to LOCATION_ID,
        "lot_key" to 0L,
        "on_hand_milli" to 10_000L,
    )

    /** Un lote con código y vencimiento, sin línea de compra y abierto. */
    private fun lotRow(productId: Long): Row = row(
        "product_id" to productId,
        "lot_code" to "L-01",
        "is_unassigned" to 0L,
        "expiry_date" to "2026-11-20",
        "purchase_line_id" to null,
        "expiry_notified_at" to null,
        "created_at" to CREATED_AT,
        "closed_at" to null,
    )

    private companion object {
        const val BALANCES = "guard:stock_balances"
        const val LOTS = "guard:stock_lots"
        const val LOCATIONS = "guard:locations"
        const val MOVEMENTS = "guard:stock_movements"
        const val MISSING_ID = 999L
    }
}
