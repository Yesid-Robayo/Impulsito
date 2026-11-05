package io.github.yesidrobayo.shopledger.data.seed

import androidx.room3.PooledConnection
import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import kotlin.time.Instant

/**
 * Las comprobaciones de invariantes sobre una base generada: las consultas del modelo de datos, que
 * deben devolver cero filas, y las que no se escriben en SQL, que corren aquí con las reglas de
 * `:domain`.
 *
 * Solo leen. Cada resultado es la cantidad de filas que rompen el invariante, por su nombre en el
 * modelo de datos: nunca un dato de las filas.
 */
internal object SeedInvariants {
    /**
     * Corre todas las comprobaciones y devuelve, por nombre, cuántas filas rompe cada una. Una base
     * sana da cero en todas.
     */
    suspend fun check(database: AppDatabase, zone: BusinessTimeZone): Map<String, Long> {
        val results = LinkedHashMap<String, Long>()
        val tracked =
            database.useReaderConnection { connection ->
                for (query in QUERIES) results[query.label] = connection.rowCount(query.sql)
                results[BUSINESS_DATE] = businessDateFailures(connection, zone)
                results[FOREIGN_KEYS] = connection.rowCount("PRAGMA foreign_key_check")
                results[INTEGRITY] =
                    connection.usePrepared("PRAGMA integrity_check") { statement ->
                        var problems = 0L
                        while (statement.step()) if (statement.getText(0) != "ok") problems++
                        problems
                    }
                trackedProducts(connection)
            }
        results[COST_REPLAY] = costReplayFailures(database, tracked)
        return results
    }

    private suspend fun PooledConnection.rowCount(sql: String): Long =
        usePrepared(sql) { statement ->
            var rows = 0L
            while (statement.step()) rows++
            rows
        }

    /** Lo que guarda cada producto con libro, para compararlo con su reproducción. */
    private suspend fun trackedProducts(connection: PooledConnection): List<StoredCost> =
        connection.usePrepared(
            "SELECT id, initial_cost, average_cost, stock_value, on_hand_milli FROM products " +
                "WHERE stock_mode = 'TRACKED' ORDER BY id",
        ) { statement ->
            buildList {
                while (statement.step()) {
                    add(
                        StoredCost(
                            id = statement.getLong(0),
                            initialCost = statement.getLong(1),
                            averageCost = statement.getLong(2),
                            stockValue = statement.getLong(3),
                            onHandMilli = statement.getLong(4),
                        ),
                    )
                }
            }
        }

    /**
     * El costo promedio, el valor del stock y el saldo de cada producto con libro, contra los que
     * da reproducir su libro con la regla del costo. Cuenta los productos que no coinciden.
     */
    private suspend fun costReplayFailures(
        database: AppDatabase,
        products: List<StoredCost>,
    ): Long {
        val movements = database.stockMovementDao()
        var failures = 0L
        for (product in products) {
            val ledger =
                movements.findByProduct(product.id).map { row ->
                    CostMovement(
                        id = row.id,
                        type = StockMovementType.valueOf(row.type),
                        quantity = QuantityMilli(row.quantityMilli),
                        unitCost = Money(row.unitCost),
                        totalCost = Money(row.totalCost),
                        reversesMovementId = row.reversesMovementId,
                    )
                }
            val replay = WeightedAverageCost.replayCostState(Money(product.initialCost), ledger)
            val matches =
                replay is CostReplayResult.Replayed &&
                    replay.state.averageCost.minor == product.averageCost &&
                    replay.state.stockValue.minor == product.stockValue &&
                    replay.state.onHand.milli == product.onHandMilli
            if (!matches) failures++
        }
        return failures
    }

    /**
     * La fecha de negocio de cada fila con fecha, contra la que da su instante en la zona del
     * negocio. La zona no cambia durante la generación, así que todas deben coincidir.
     */
    private suspend fun businessDateFailures(
        connection: PooledConnection,
        zone: BusinessTimeZone,
    ): Long {
        var failures = 0L
        for (table in DATED_TABLES) {
            connection.usePrepared("SELECT occurred_at, business_date FROM $table") { statement ->
                while (statement.step()) {
                    val expected =
                        BusinessDate.of(Instant.fromEpochMilliseconds(statement.getLong(0)), zone)
                    if (expected.toString() != statement.getText(1)) failures++
                }
            }
        }
        return failures
    }

    /** Del modelo de datos: el costo de cada producto sale de reproducir su libro. */
    const val COST_REPLAY = "I-03"

    /** Del modelo de datos: la fecha de negocio sale del instante en la zona del negocio. */
    const val BUSINESS_DATE = "I-08"

    const val FOREIGN_KEYS = "foreign_key_check"
    const val INTEGRITY = "integrity_check"

    private val DATED_TABLES =
        listOf(
            "sales",
            "purchases",
            "expenses",
            "stock_movements",
            "money_movements",
            "customer_ledger_entries",
            "supplier_ledger_entries",
            "loyalty_ledger_entries",
            "product_price_history",
        )

    /** Las consultas del modelo de datos, copiadas tal cual. Cada una debe devolver cero filas. */
    val QUERIES: List<SeedInvariantQuery> =
        listOf(
            SeedInvariantQuery(
                "I-01",
                """
                SELECT p.id
                FROM products p
                LEFT JOIN (
                  SELECT product_id, SUM(quantity_milli) AS total
                  FROM stock_movements
                  GROUP BY product_id
                ) m ON m.product_id = p.id
                WHERE p.on_hand_milli <> COALESCE(m.total, 0)
                   OR (p.stock_mode <> 'TRACKED' AND p.on_hand_milli <> 0);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-02",
                """
                SELECT id
                FROM (
                  SELECT id, quantity_milli, balance_after_milli,
                         LAG(balance_after_milli, 1, 0) OVER (PARTITION BY product_id ORDER BY id) AS previous_balance
                  FROM stock_movements
                )
                WHERE balance_after_milli <> previous_balance + quantity_milli;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-04",
                """
                SELECT s.id
                FROM sales s
                JOIN (
                  SELECT sale_id, SUM(line_total) AS total, SUM(net_amount) AS net, SUM(tax_amount) AS tax,
                         SUM(line_cost) AS cost, SUM(order_adjustment_share) AS adjustment
                  FROM sale_lines
                  GROUP BY sale_id
                ) l ON l.sale_id = s.id
                WHERE s.total <> l.total OR s.net_total <> l.net OR s.tax_total <> l.tax
                   OR s.total_cost <> l.cost OR s.order_adjustment <> l.adjustment
                   OR s.total <> s.net_total + s.tax_total;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-04, venta sin líneas",
                """
                SELECT s.id
                FROM sales s
                WHERE NOT EXISTS (SELECT 1 FROM sale_lines l WHERE l.sale_id = s.id);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-05, líneas DIRECT",
                """
                SELECT sl.id
                FROM sale_lines sl
                LEFT JOIN stock_movements m
                  ON m.sale_line_id = sl.id AND m.type = 'SALE' AND m.sale_line_component_id IS NULL
                WHERE sl.stock_effect = 'DIRECT'
                GROUP BY sl.id
                HAVING COUNT(m.id) = 0 OR SUM(m.quantity_milli) <> -sl.quantity_milli;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-05, componentes con stock",
                """
                SELECT c.id
                FROM sale_line_components c
                LEFT JOIN stock_movements m
                  ON m.sale_line_id = c.sale_line_id AND m.sale_line_component_id = c.id AND m.type = 'SALE'
                WHERE c.tracked = 1
                GROUP BY c.id
                HAVING COUNT(m.id) = 0 OR SUM(m.quantity_milli) <> -c.quantity_milli;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-05, filas que no corresponden",
                """
                SELECT m.id
                FROM stock_movements m
                JOIN sale_lines sl ON sl.id = m.sale_line_id
                WHERE m.type = 'SALE'
                  AND (sl.stock_effect = 'NONE'
                    OR (sl.stock_effect = 'DIRECT' AND m.sale_line_component_id IS NOT NULL)
                    OR (sl.stock_effect = 'COMPONENTS' AND m.sale_line_component_id IS NULL));
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-06",
                """
                SELECT m.id
                FROM stock_movements m
                JOIN sale_lines sl ON sl.id = m.sale_line_id
                JOIN sales s       ON s.id = sl.sale_id
                LEFT JOIN stock_movements v ON v.reverses_movement_id = m.id AND v.type = 'SALE_VOID'
                WHERE m.type = 'SALE' AND (s.status = 'VOIDED') <> (v.id IS NOT NULL);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-07",
                """
                SELECT a.id
                FROM low_stock_alerts a
                JOIN products p ON p.id = a.product_id
                WHERE a.resolved_at IS NULL
                  AND (p.stock_mode <> 'TRACKED' OR p.on_hand_milli > p.min_stock_milli OR p.archived_at IS NOT NULL);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-10",
                """
                SELECT p.id
                FROM products p
                LEFT JOIN (
                  SELECT product_id, SUM(on_hand_milli) AS total FROM stock_balances GROUP BY product_id
                ) b ON b.product_id = p.id
                WHERE p.on_hand_milli <> COALESCE(b.total, 0);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-11",
                """
                SELECT b.id
                FROM stock_balances b
                LEFT JOIN (
                  SELECT product_id, location_id, COALESCE(lot_id, 0) AS lot_key, SUM(quantity_milli) AS total
                  FROM stock_movements
                  GROUP BY product_id, location_id, lot_key
                ) m ON m.product_id = b.product_id AND m.location_id = b.location_id AND m.lot_key = b.lot_key
                WHERE b.on_hand_milli <> COALESCE(m.total, 0);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-11, saldos que faltan",
                """
                SELECT m.product_id, m.location_id, m.lot_key
                FROM (
                  SELECT product_id, location_id, COALESCE(lot_id, 0) AS lot_key, SUM(quantity_milli) AS total
                  FROM stock_movements
                  GROUP BY product_id, location_id, lot_key
                ) m
                LEFT JOIN stock_balances b
                  ON b.product_id = m.product_id AND b.location_id = m.location_id AND b.lot_key = m.lot_key
                WHERE b.id IS NULL AND m.total <> 0;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-12",
                """
                SELECT m.id
                FROM stock_movements m
                JOIN products p ON p.id = m.product_id
                WHERE m.quantity_milli <> 0 AND (p.tracks_lots = 1) <> (m.lot_id IS NOT NULL);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-12, solo el lote sin asignar queda negativo",
                """
                SELECT b.id
                FROM stock_balances b
                JOIN stock_lots l ON l.id = b.lot_key
                WHERE b.on_hand_milli < 0 AND l.is_unassigned = 0;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-13",
                """
                SELECT s.id
                FROM sales s
                LEFT JOIN sale_payments p ON p.sale_id = s.id
                GROUP BY s.id
                HAVING s.amount_due <> s.total + s.tip_amount + s.cash_rounding_amount
                    OR COUNT(p.id) = 0 OR SUM(p.amount) <> s.amount_due;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-14, saldo",
                """
                SELECT c.id
                FROM customers c
                LEFT JOIN (
                  SELECT customer_id, SUM(amount) AS total FROM customer_ledger_entries GROUP BY customer_id
                ) e ON e.customer_id = c.id
                WHERE c.balance <> COALESCE(e.total, 0);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-14, cadena",
                """
                SELECT id
                FROM (
                  SELECT id, amount, balance_after,
                         LAG(balance_after, 1, 0) OVER (PARTITION BY customer_id ORDER BY id) AS previous_balance
                  FROM customer_ledger_entries
                )
                WHERE balance_after <> previous_balance + amount;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-15, saldo",
                """
                SELECT c.id
                FROM suppliers c
                LEFT JOIN (
                  SELECT supplier_id, SUM(amount) AS total FROM supplier_ledger_entries GROUP BY supplier_id
                ) e ON e.supplier_id = c.id
                WHERE c.balance <> COALESCE(e.total, 0);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-15, cadena",
                """
                SELECT id
                FROM (
                  SELECT id, amount, balance_after,
                         LAG(balance_after, 1, 0) OVER (PARTITION BY supplier_id ORDER BY id) AS previous_balance
                  FROM supplier_ledger_entries
                )
                WHERE balance_after <> previous_balance + amount;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-16, saldo",
                """
                SELECT c.id
                FROM money_accounts c
                LEFT JOIN (
                  SELECT money_account_id, SUM(amount) AS total FROM money_movements GROUP BY money_account_id
                ) e ON e.money_account_id = c.id
                WHERE c.balance <> COALESCE(e.total, 0);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-16, cadena",
                """
                SELECT id
                FROM (
                  SELECT id, amount, balance_after,
                         LAG(balance_after, 1, 0) OVER (PARTITION BY money_account_id ORDER BY id) AS previous_balance
                  FROM money_movements
                )
                WHERE balance_after <> previous_balance + amount;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-17",
                """
                SELECT transfer_group_id
                FROM money_movements
                WHERE type IN ('TRANSFER_OUT', 'TRANSFER_IN')
                GROUP BY transfer_group_id
                HAVING COUNT(*) <> 2 OR SUM(type = 'TRANSFER_OUT') <> 1;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-18, saldo",
                """
                SELECT c.id
                FROM customers c
                LEFT JOIN (
                  SELECT customer_id, SUM(points) AS total FROM loyalty_ledger_entries GROUP BY customer_id
                ) e ON e.customer_id = c.id
                WHERE c.points_balance <> COALESCE(e.total, 0);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-18, cadena",
                """
                SELECT id
                FROM (
                  SELECT id, points, balance_after,
                         LAG(balance_after, 1, 0) OVER (PARTITION BY customer_id ORDER BY id) AS previous_balance
                  FROM loyalty_ledger_entries
                )
                WHERE balance_after <> previous_balance + points;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-19",
                """
                SELECT rl.sale_line_id
                FROM sale_return_lines rl
                JOIN sale_returns r ON r.id = rl.sale_return_id AND r.status = 'ACTIVE'
                JOIN sale_lines sl  ON sl.id = rl.sale_line_id
                JOIN sales s        ON s.id = sl.sale_id
                GROUP BY rl.sale_line_id
                HAVING SUM(rl.quantity_milli) > sl.quantity_milli OR MIN(s.status) <> 'ACTIVE';
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-20",
                """
                SELECT s.id
                FROM sales s
                JOIN (
                  SELECT sale_id, SUM(amount) AS on_account FROM sale_payments
                  WHERE method_kind_snapshot = 'ON_ACCOUNT'
                  GROUP BY sale_id
                ) p ON p.sale_id = s.id
                LEFT JOIN (
                  SELECT sale_id, SUM(amount) AS charged FROM customer_ledger_entries
                  WHERE type = 'CREDIT_SALE'
                  GROUP BY sale_id
                ) e ON e.sale_id = s.id
                WHERE s.customer_id IS NULL OR p.on_account <> COALESCE(e.charged, 0);
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-21",
                """
                SELECT transfer_line_id
                FROM stock_movements
                WHERE type IN ('TRANSFER_OUT', 'TRANSFER_IN')
                GROUP BY transfer_line_id
                HAVING COUNT(*) <> 2 OR SUM(quantity_milli) <> 0;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-22, niveles y ciclos",
                """
                SELECT a.parent_product_id
                FROM product_components a
                JOIN product_components b ON b.parent_product_id = a.component_product_id
                JOIN product_components c ON c.parent_product_id = b.component_product_id;
                """.trimIndent(),
            ),
            SeedInvariantQuery(
                "I-22, solo hojas",
                """
                SELECT c.id
                FROM sale_line_components c
                JOIN products p ON p.id = c.component_product_id
                WHERE p.stock_mode = 'COMPONENTS';
                """.trimIndent(),
            ),
        )
}

/** Una consulta de invariante: su nombre en el modelo de datos y su texto. */
internal class SeedInvariantQuery(val label: String, val sql: String)

/** El costo que guarda un producto con libro. */
private class StoredCost(
    val id: Long,
    val initialCost: Long,
    val averageCost: Long,
    val stockValue: Long,
    val onHandMilli: Long,
)
