package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La guarda del libro de stock con cada uno de sus 18 tipos: el signo de la cantidad, el origen que
 * lleva cada tipo y solo ese, y el compensatorio, que apunta a una fila del tipo que anula, en la
 * misma parte y con la cantidad opuesta, una sola vez.
 *
 * Cada origen es una fila real: una línea de venta, una de compra, una de devolución de venta, una
 * de devolución a proveedor, una línea de transferencia, un lote de producción y una sesión de
 * conteo.
 */
@RunWith(AndroidJUnit4::class)
internal class StockMovementTypesGuardsTest : GuardProbeTest() {
    private lateinit var origins: Map<String, Long>
    private var countSessionId = 0L

    @Before
    fun createOrigins() = runBlocking {
        val transferId = insert("stock_transfers", transferRow(LOCATION_ID, otherLocation()))
        val batchId = insert("production_batches", batchRow(productId))
        countSessionId = insert("count_sessions", countSessionRow())
        val saleLine = insertSaleWithLine()
        val saleReturnId = database.saleReturnDao().insert(saleReturn(saleLine.saleId))
        val purchaseReturnId = database.purchaseReturnDao().insert(purchaseReturn())
        origins =
            mapOf(
                "sale_line_id" to saleLine.id,
                "purchase_line_id" to insertPurchaseWithLine().id,
                "sale_return_line_id" to
                    database.saleReturnLineDao().insert(saleReturnLine(saleReturnId, saleLine.id)),
                "purchase_return_line_id" to
                    database.purchaseReturnLineDao().insert(
                        purchaseReturnLine(purchaseReturnId, productId),
                    ),
                "transfer_line_id" to
                    insert("stock_transfer_lines", transferLineRow(transferId, productId)),
                "production_batch_id" to batchId,
            )
    }

    @Test
    fun everyTypeTakesTheSignOfItsQuantity() = runBlocking {
        for ((type, sign) in SIGNS) {
            val valid = rowOf(type, sign)
            when {
                // Un ajuste sube o baja, pero siempre mueve algo.
                type == "ADJUSTMENT" ->
                    rejected(MOVEMENTS, valid.with("quantity_milli" to 0L).sql())

                sign == ZERO -> rejected(MOVEMENTS, valid.with("quantity_milli" to 1_000L).sql())

                else -> rejected(MOVEMENTS, valid.with("quantity_milli" to -sign * QUANTITY).sql())
            }
            insert("stock_movements", valid)
        }
        insert("stock_movements", rowOf("ADJUSTMENT", NEGATIVE).with("reason" to "DAMAGED"))
        assertEquals(SIGNS.size + 1L, count("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun everyTypeCarriesItsOwnOriginAndNoOther() = runBlocking {
        for ((type, sign) in SIGNS) {
            val valid = rowOf(type, sign)
            val own = ORIGIN_OF[type]
            if (own != null) rejected(MOVEMENTS, valid.with(own to null).sql())
            for ((column, id) in origins) {
                if (column != own) rejected(MOVEMENTS, valid.with(column to id).sql())
            }
            // La sesión de conteo solo va en un ajuste de conteo.
            if (type != "ADJUSTMENT") {
                rejected(MOVEMENTS, valid.with("count_session_id" to countSessionId).sql())
            }
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test
    fun aCountAdjustmentCarriesItsSessionAndOnlyThatReason() = runBlocking {
        val count = rowOf("ADJUSTMENT", NEGATIVE).with("reason" to "COUNT_CORRECTION")
        rejected(
            MOVEMENTS,
            count.with("reason" to "DAMAGED", "count_session_id" to countSessionId).sql(),
        )
        insert("stock_movements", count.with("count_session_id" to countSessionId))
        // Un conteo también puede subir el stock, y un ajuste de conteo sin sesión sigue valiendo.
        insert(
            "stock_movements",
            count.with("quantity_milli" to QUANTITY, "count_session_id" to countSessionId),
        )
        insert("stock_movements", count)
        assertEquals(
            2L,
            count("SELECT COUNT(*) FROM stock_movements WHERE count_session_id = $countSessionId"),
        )
    }

    @Test
    fun aTransferMovesNoValue() = runBlocking {
        for (type in listOf("TRANSFER_OUT", "TRANSFER_IN")) {
            val valid = rowOf(type, SIGNS.getValue(type))
            rejected(MOVEMENTS, valid.with("total_cost" to 1_200L).sql())
            insert("stock_movements", valid)
        }
        assertEquals(0L, count("SELECT SUM(total_cost) FROM stock_movements"))
    }

    @Test
    fun eachCompensationVoidsItsTypeInTheSamePartOnce() = runBlocking {
        for ((voidType, originals) in VOIDS) {
            for (originalType in originals) {
                val sign = SIGNS.getValue(originalType)
                val originalId = insert("stock_movements", rowOf(originalType, sign))
                // El compensatorio lleva la cantidad opuesta y apunta a la original.
                val undo = rowOf(voidType, -sign).with("reverses_movement_id" to originalId)
                for (broken in listOf(
                    // Sin la fila que anula, con una que no existe, con otra cantidad o con el
                    // mismo signo de la original.
                    undo.with("reverses_movement_id" to null),
                    undo.with("reverses_movement_id" to MISSING_ID),
                    undo.with("quantity_milli" to -sign * QUANTITY * 2),
                    undo.with("quantity_milli" to sign * QUANTITY),
                    // En otra ubicación: la parte del stock sería otra.
                    undo.with("location_id" to otherLocation()),
                )) {
                    rejected(MOVEMENTS, broken.sql())
                }
                insert("stock_movements", undo)
                // Una fila se anula una sola vez.
                rejected(MOVEMENTS, undo.sql())
            }
        }
        val compensations = VOIDS.values.sumOf { it.size }.toLong()
        assertEquals(
            compensations,
            count("SELECT COUNT(*) FROM stock_movements WHERE reverses_movement_id IS NOT NULL"),
        )
    }

    @Test
    fun aCompensationOnlyVoidsTheTypesItCorresponds() = runBlocking {
        for ((voidType, originals) in VOIDS) {
            for (originalType in SIGNS.keys - originals.toSet()) {
                val sign = SIGNS.getValue(originalType)
                // Una corrección de costo no mueve unidades: nada la compensa.
                if (sign == ZERO) continue
                val originalId = insert("stock_movements", rowOf(originalType, sign))
                val undo = rowOf(voidType, -sign).with("reverses_movement_id" to originalId)
                rejected(MOVEMENTS, undo.sql())
            }
        }
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM stock_movements WHERE reverses_movement_id IS NOT NULL"),
        )
    }

    /**
     * Una fila válida de [type] con el signo [sign]: su origen si lo lleva, el motivo si es un
     * ajuste, y costo cero si es una transferencia o una corrección de costo.
     */
    private fun rowOf(type: String, sign: Int): Row {
        val quantity = sign * QUANTITY
        var row =
            movementRow(productId).with(
                "type" to type,
                "quantity_milli" to quantity,
                "total_cost" to if (type in NO_VALUE) 0L else quantity * UNIT_COST / 1_000,
            )
        ORIGIN_OF[type]?.let { column -> row = row.with(column to origins.getValue(column)) }
        if (type == "ADJUSTMENT") row = row.with("reason" to "COUNT_CORRECTION")
        return row
    }

    /** Una segunda ubicación, la misma en cada llamada. */
    private suspend fun otherLocation(): Long {
        if (count("SELECT COUNT(*) FROM locations") == 1L) {
            execute(locationRow("bodega").insertInto("locations"))
        }
        return count("SELECT max(id) FROM locations")
    }

    private fun Row.sql(): String = insertInto("stock_movements")

    private companion object {
        const val MOVEMENTS = "guard:stock_movements"
        const val MISSING_ID = 999L
        const val QUANTITY = 1_000L
        const val UNIT_COST = 1_200L
        const val POSITIVE = 1
        const val NEGATIVE = -1
        const val ZERO = 0

        /** El signo de cada tipo que no compensa: el compensatorio lleva el opuesto del que anula. */
        val SIGNS =
            linkedMapOf(
                "OPENING_BALANCE" to POSITIVE,
                "PURCHASE" to POSITIVE,
                "SALE" to NEGATIVE,
                "ADJUSTMENT" to POSITIVE,
                "COST_CORRECTION" to ZERO,
                "SALE_RETURN" to POSITIVE,
                "PURCHASE_RETURN" to NEGATIVE,
                "PRODUCTION_OUT" to NEGATIVE,
                "PRODUCTION_IN" to POSITIVE,
                "TRANSFER_OUT" to NEGATIVE,
                "TRANSFER_IN" to POSITIVE,
            )

        /** Los compensatorios, y los tipos que anula cada uno. */
        val VOIDS =
            linkedMapOf(
                "PURCHASE_VOID" to listOf("PURCHASE"),
                "SALE_VOID" to listOf("SALE"),
                "ADJUSTMENT_VOID" to listOf("ADJUSTMENT", "OPENING_BALANCE"),
                "SALE_RETURN_VOID" to listOf("SALE_RETURN"),
                "PURCHASE_RETURN_VOID" to listOf("PURCHASE_RETURN"),
                "PRODUCTION_VOID" to listOf("PRODUCTION_OUT", "PRODUCTION_IN"),
                "TRANSFER_VOID" to listOf("TRANSFER_OUT", "TRANSFER_IN"),
            )

        /** El origen de cada tipo, que lleva también su compensatorio. */
        val ORIGIN_OF =
            mapOf(
                "PURCHASE" to "purchase_line_id",
                "PURCHASE_VOID" to "purchase_line_id",
                "SALE" to "sale_line_id",
                "SALE_VOID" to "sale_line_id",
                "SALE_RETURN" to "sale_return_line_id",
                "SALE_RETURN_VOID" to "sale_return_line_id",
                "PURCHASE_RETURN" to "purchase_return_line_id",
                "PURCHASE_RETURN_VOID" to "purchase_return_line_id",
                "PRODUCTION_OUT" to "production_batch_id",
                "PRODUCTION_IN" to "production_batch_id",
                "PRODUCTION_VOID" to "production_batch_id",
                "TRANSFER_OUT" to "transfer_line_id",
                "TRANSFER_IN" to "transfer_line_id",
                "TRANSFER_VOID" to "transfer_line_id",
            )

        /** Los tipos que no mueven valor. */
        val NO_VALUE =
            setOf("COST_CORRECTION", "TRANSFER_OUT", "TRANSFER_IN", "TRANSFER_VOID")
    }
}
