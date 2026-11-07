package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck

/**
 * Una consulta de invariante: su nombre en el modelo de datos, la comprobación del validador a la
 * que pertenece y su texto. Sobre una base sana devuelve cero filas, y cada fila que devuelve es un
 * caso que rompe el invariante.
 */
internal class InvariantQuery(val label: String, val check: CandidateCheck, val sql: String)

/**
 * Las consultas de invariantes que corren sobre una base candidata, al crear cada copia y al
 * restaurarla, y sobre las bases que genera el código de pruebas.
 *
 * Su clase sale del invariante. Los saldos de cabecera y los totales de un documento se recalculan
 * desde el libro y las líneas, así que son reparables. El libro no se reescribe nunca: una cadena
 * de saldos posteriores rota, o una relación entre filas que no se puede derivar, solo se cuenta.
 * Una venta sin líneas tampoco se puede recalcular, porque no hay nada de qué sumar, y por eso
 * también solo se cuenta.
 *
 * La reproducción del costo de cada producto no se escribe en SQL: corre con la regla de costo, en
 * el validador.
 */
internal object InvariantQueries {
    /**
     * Las consultas del modelo de datos, copiadas tal cual, en su orden. Las corre el validador y
     * las revisa el generador de datos de prueba.
     */
    val MODEL: List<InvariantQuery> =
        listOf(
            InvariantQuery(
                "I-01",
                CandidateCheck.STOCK_ON_HAND,
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
            InvariantQuery(
                "I-02",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-04",
                CandidateCheck.COST_AND_TOTALS,
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
            InvariantQuery(
                "I-04, venta sin líneas",
                CandidateCheck.UNDERIVABLE_COHERENCE,
                """
                SELECT s.id
                FROM sales s
                WHERE NOT EXISTS (SELECT 1 FROM sale_lines l WHERE l.sale_id = s.id);
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-04, compras",
                CandidateCheck.COST_AND_TOTALS,
                """
                SELECT p.id
                FROM purchases p
                JOIN (
                  SELECT purchase_id, SUM(line_total) AS total, SUM(tax_amount) AS tax, SUM(cost_amount) AS cost
                  FROM purchase_lines
                  GROUP BY purchase_id
                ) l ON l.purchase_id = p.id
                WHERE p.total <> l.total OR p.tax_total <> l.tax OR p.cost_total <> l.cost
                   OR p.net_total <> l.total - l.tax OR p.total <> p.net_total + p.tax_total;
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-05, líneas DIRECT",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-05, componentes con stock",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-05, filas que no corresponden",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-06",
                CandidateCheck.UNDERIVABLE_COHERENCE,
                """
                SELECT m.id
                FROM stock_movements m
                JOIN sale_lines sl ON sl.id = m.sale_line_id
                JOIN sales s       ON s.id = sl.sale_id
                LEFT JOIN stock_movements v ON v.reverses_movement_id = m.id AND v.type = 'SALE_VOID'
                WHERE m.type = 'SALE' AND (s.status = 'VOIDED') <> (v.id IS NOT NULL);
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-07",
                CandidateCheck.HEADER_BALANCES,
                """
                SELECT a.id
                FROM low_stock_alerts a
                JOIN products p ON p.id = a.product_id
                WHERE a.resolved_at IS NULL
                  AND (p.stock_mode <> 'TRACKED' OR p.on_hand_milli > p.min_stock_milli OR p.archived_at IS NOT NULL);
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-10",
                CandidateCheck.HEADER_BALANCES,
                """
                SELECT p.id
                FROM products p
                LEFT JOIN (
                  SELECT product_id, SUM(on_hand_milli) AS total FROM stock_balances GROUP BY product_id
                ) b ON b.product_id = p.id
                WHERE p.on_hand_milli <> COALESCE(b.total, 0);
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-11",
                CandidateCheck.HEADER_BALANCES,
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
            InvariantQuery(
                "I-11, saldos que faltan",
                CandidateCheck.HEADER_BALANCES,
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
            InvariantQuery(
                "I-12",
                CandidateCheck.UNDERIVABLE_COHERENCE,
                """
                SELECT m.id
                FROM stock_movements m
                JOIN products p ON p.id = m.product_id
                WHERE m.quantity_milli <> 0 AND (p.tracks_lots = 1) <> (m.lot_id IS NOT NULL);
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-12, solo el lote sin asignar queda negativo",
                CandidateCheck.UNDERIVABLE_COHERENCE,
                """
                SELECT b.id
                FROM stock_balances b
                JOIN stock_lots l ON l.id = b.lot_key
                WHERE b.on_hand_milli < 0 AND l.is_unassigned = 0;
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-13",
                CandidateCheck.UNDERIVABLE_COHERENCE,
                """
                SELECT s.id
                FROM sales s
                LEFT JOIN sale_payments p ON p.sale_id = s.id
                GROUP BY s.id
                HAVING s.amount_due <> s.total + s.tip_amount + s.cash_rounding_amount
                    OR COUNT(p.id) = 0 OR SUM(p.amount) <> s.amount_due;
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-14, saldo",
                CandidateCheck.HEADER_BALANCES,
                """
                SELECT c.id
                FROM customers c
                LEFT JOIN (
                  SELECT customer_id, SUM(amount) AS total FROM customer_ledger_entries GROUP BY customer_id
                ) e ON e.customer_id = c.id
                WHERE c.balance <> COALESCE(e.total, 0);
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-14, cadena",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-15, saldo",
                CandidateCheck.HEADER_BALANCES,
                """
                SELECT c.id
                FROM suppliers c
                LEFT JOIN (
                  SELECT supplier_id, SUM(amount) AS total FROM supplier_ledger_entries GROUP BY supplier_id
                ) e ON e.supplier_id = c.id
                WHERE c.balance <> COALESCE(e.total, 0);
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-15, cadena",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-16, saldo",
                CandidateCheck.HEADER_BALANCES,
                """
                SELECT c.id
                FROM money_accounts c
                LEFT JOIN (
                  SELECT money_account_id, SUM(amount) AS total FROM money_movements GROUP BY money_account_id
                ) e ON e.money_account_id = c.id
                WHERE c.balance <> COALESCE(e.total, 0);
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-16, cadena",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-17",
                CandidateCheck.UNDERIVABLE_COHERENCE,
                """
                SELECT transfer_group_id
                FROM money_movements
                WHERE type IN ('TRANSFER_OUT', 'TRANSFER_IN')
                GROUP BY transfer_group_id
                HAVING COUNT(*) <> 2 OR SUM(type = 'TRANSFER_OUT') <> 1;
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-18, saldo",
                CandidateCheck.HEADER_BALANCES,
                """
                SELECT c.id
                FROM customers c
                LEFT JOIN (
                  SELECT customer_id, SUM(points) AS total FROM loyalty_ledger_entries GROUP BY customer_id
                ) e ON e.customer_id = c.id
                WHERE c.points_balance <> COALESCE(e.total, 0);
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-18, cadena",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-19",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-20",
                CandidateCheck.UNDERIVABLE_COHERENCE,
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
            InvariantQuery(
                "I-21",
                CandidateCheck.UNDERIVABLE_COHERENCE,
                """
                SELECT transfer_line_id
                FROM stock_movements
                WHERE type IN ('TRANSFER_OUT', 'TRANSFER_IN')
                GROUP BY transfer_line_id
                HAVING COUNT(*) <> 2 OR SUM(quantity_milli) <> 0;
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-22, niveles y ciclos",
                CandidateCheck.UNDERIVABLE_COHERENCE,
                """
                SELECT a.parent_product_id
                FROM product_components a
                JOIN product_components b ON b.parent_product_id = a.component_product_id
                JOIN product_components c ON c.parent_product_id = b.component_product_id;
                """.trimIndent(),
            ),
            InvariantQuery(
                "I-22, solo hojas",
                CandidateCheck.UNDERIVABLE_COHERENCE,
                """
                SELECT c.id
                FROM sale_line_components c
                JOIN products p ON p.id = c.component_product_id
                WHERE p.stock_mode = 'COMPONENTS';
                """.trimIndent(),
            ),
        )
}
