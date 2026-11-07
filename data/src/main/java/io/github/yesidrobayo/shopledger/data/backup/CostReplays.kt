package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost

/**
 * El costo de cada producto con stock, guardado y reproducido desde su libro con la regla de costo
 * de la app, la misma que lo calculó al escribir.
 *
 * Lee el libro una sola vez, ordenado por producto y por `id`, y reproduce un producto a la vez:
 * nunca tiene en memoria más que el libro de un producto.
 */
internal object CostReplays {
    /** Lo que guarda y lo que da reproducir el libro de cada producto con stock, por `id`. */
    fun of(connection: SQLiteConnection): List<ProductCost> {
        val stored = connection.prepare(PRODUCTS_SQL).use { statement ->
            buildList {
                while (statement.step()) {
                    add(
                        StoredProductCost(
                            id = statement.getLong(0),
                            initialCost = statement.getLong(1),
                            averageCost = statement.getLong(2),
                            stockValue = statement.getLong(3),
                        ),
                    )
                }
            }
        }
        if (stored.isEmpty()) return emptyList()
        val byId = stored.associateBy { it.id }
        val ledgers = mutableMapOf<Long, MutableList<CostMovement>>()
        val unreadable = mutableSetOf<Long>()
        connection.prepare(MOVEMENTS_SQL).use { statement ->
            while (statement.step()) {
                val productId = statement.getLong(0)
                if (productId !in byId) continue
                // Un tipo desconocido es daño, y lo cuenta la revisión de tipos: aquí solo impide
                // reproducir ese producto.
                val type = StockMovementType.entries.firstOrNull { it.name == statement.getText(2) }
                if (type == null) {
                    unreadable += productId
                    continue
                }
                val reverses = if (statement.isNull(6)) null else statement.getLong(6)
                ledgers.getOrPut(productId) { mutableListOf() } +=
                    CostMovement(
                        id = statement.getLong(1),
                        type = type,
                        quantity = QuantityMilli(statement.getLong(3)),
                        unitCost = Money(statement.getLong(4)),
                        totalCost = Money(statement.getLong(5)),
                        reversesMovementId = reverses,
                    )
            }
        }
        return stored.map { product ->
            val replay =
                if (product.id in unreadable) {
                    null
                } else {
                    WeightedAverageCost.replayCostState(
                        Money(product.initialCost),
                        ledgers[product.id].orEmpty(),
                    ) as? CostReplayResult.Replayed
                }
            ProductCost(
                id = product.id,
                storedAverageCost = product.averageCost,
                storedStockValue = product.stockValue,
                replayedAverageCost = replay?.state?.averageCost?.minor,
                replayedStockValue = replay?.state?.stockValue?.minor,
            )
        }
    }

    private class StoredProductCost(
        val id: Long,
        val initialCost: Long,
        val averageCost: Long,
        val stockValue: Long,
    )

    private const val PRODUCTS_SQL =
        "SELECT id, initial_cost, average_cost, stock_value FROM products " +
            "WHERE stock_mode = 'TRACKED' ORDER BY id"

    private const val MOVEMENTS_SQL =
        "SELECT product_id, id, type, quantity_milli, unit_cost, total_cost, " +
            "reverses_movement_id FROM stock_movements ORDER BY product_id, id"
}

/**
 * El costo de un producto con stock. Los valores reproducidos son nulos si su libro no se pudo
 * reproducir: una fila imposible, una cifra que no cabe o un tipo desconocido.
 */
internal class ProductCost(
    val id: Long,
    val storedAverageCost: Long,
    val storedStockValue: Long,
    val replayedAverageCost: Long?,
    val replayedStockValue: Long?,
) {
    /** Si el libro se pudo reproducir. */
    val replayed: Boolean get() = replayedAverageCost != null

    /** Si se reprodujo y lo guardado no coincide: se puede recalcular. */
    val drifted: Boolean
        get() = replayed &&
            (replayedAverageCost != storedAverageCost || replayedStockValue != storedStockValue)
}
