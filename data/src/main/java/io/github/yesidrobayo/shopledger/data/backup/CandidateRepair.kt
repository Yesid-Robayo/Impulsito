package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaGuards
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Prepara los datos de una candidata ya validada y migrada, antes de reemplazar la base activa:
 * deja en un valor conocido lo que la app no sabría leer, y, si la persona lo confirmó, recalcula
 * los saldos de cabecera desde el libro y las líneas.
 *
 * - **Nunca edita un libro.** Solo cambia saldos de cabecera, el costo de cada producto, los totales
 *   de ventas y compras, alertas, la paleta y el tipo de un método de pago. Una cadena de saldos
 *   posteriores rota se queda como está: el saldo de cabecera es la suma del libro, y eso es lo que
 *   muestran pantallas y reportes.
 * - **Los totales, solo sobre la candidata.** Las guardas de ventas y compras impiden cambiar un
 *   documento salvo para anularlo. En la misma transacción se retiran esas dos, se recalculan los
 *   totales desde las líneas, que no se tocan, y se vuelven a crear con el texto exacto del
 *   catálogo. La base activa nunca pierde una guarda.
 * - **Todo o nada.** Una sola transacción: si algo falla, la candidata queda como estaba.
 * - **Se vuelve a validar.** Al terminar, la candidata pasa otra vez por el esquema, la revisión de
 *   integridad y los datos. Una guarda recreada con otro texto la rechaza el esquema.
 *
 * Una paleta desconocida pasa a la inicial y un tipo de método de pago desconocido pasa a "otro"
 * siempre, sin pedir nada: un color nunca deja sin restaurar una copia, y sin esto las guardas
 * rechazarían el siguiente cambio de ajustes o de ese método. La copia del tipo que guarda cada pago
 * no se toca, porque es parte del documento: se lee como "otro".
 *
 * @param ioContext contexto del trabajo, fuera del hilo principal.
 * @param validator el que vuelve a revisar la candidata al terminar.
 */
internal class CandidateRepair(
    private val ioContext: CoroutineContext,
    private val validator: CandidateValidator = CandidateValidator(ioContext),
) {
    private val driver = BundledSQLiteDriver()

    /**
     * Prepara [candidate] y la vuelve a validar con [context].
     *
     * @param recalculate si la persona confirmó recalcular los saldos de cabecera.
     * @param now el instante con que se dan por resueltas las alertas que ya no corresponden.
     */
    suspend fun prepare(
        candidate: File,
        context: CandidateContext,
        recalculate: Boolean,
        now: Instant,
    ): CandidateValidation {
        val applied = withContext(ioContext) {
            try {
                driver.open(candidate.path, SQLITE_OPEN_READWRITE).use { connection ->
                    connection.inTransaction {
                        normalizeUnknownValues(this)
                        if (recalculate) recalculate(this, now.toEpochMilliseconds())
                    }
                }
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                failure
            }
        }
        if (applied != null) {
            return when {
                SqliteFailure.isCorruption(applied) ->
                    CandidateValidation.Refused(CandidateRejection.INTEGRITY_FAILED)

                // Una guarda que rechaza un saldo recalculado: el saldo no sale del libro.
                SqliteFailure.primaryCode(applied) == SQLITE_CONSTRAINT ->
                    CandidateValidation.Refused(CandidateRejection.REPAIR_INCOMPLETE)

                else -> CandidateValidation.Failed(applied)
            }
        }
        val after = validator.validate(candidate, context)
        return if (
            recalculate && after is CandidateValidation.Checked &&
            after.verdict is CandidateVerdict.NeedsRepair
        ) {
            CandidateValidation.Refused(CandidateRejection.REPAIR_INCOMPLETE)
        } else {
            after
        }
    }

    private fun normalizeUnknownValues(connection: SQLiteConnection) {
        val palettes = ColorPalette.entries.joinToString { "'${it.name}'" }
        connection.execSQL(
            "UPDATE app_settings SET color_palette = '${ColorPalette.INDIGO_MUSTARD.name}' " +
                "WHERE color_palette NOT IN ($palettes)",
        )
        val kinds = PaymentMethodKind.entries.joinToString { "'${it.name}'" }
        connection.execSQL(
            "UPDATE payment_methods SET kind = '${PaymentMethodKind.OTHER.name}' " +
                "WHERE kind NOT IN ($kinds)",
        )
    }

    private fun recalculate(connection: SQLiteConnection, nowMillis: Long) {
        val catalogVersion = connection.singleLong("PRAGMA user_version").toInt()
        check(catalogVersion == AppDatabase.SCHEMA_VERSION) {
            "La candidata se repara ya migrada a la versión de la app"
        }
        for (sql in HEADER_REPAIRS) connection.execSQL(sql)
        for (product in CostReplays.of(connection).filter { it.drifted }) {
            connection.prepare(
                "UPDATE products SET average_cost = ?, stock_value = ? WHERE id = ?",
            ).use { statement ->
                statement.bindLong(1, requireNotNull(product.replayedAverageCost))
                statement.bindLong(2, requireNotNull(product.replayedStockValue))
                statement.bindLong(3, product.id)
                statement.step()
            }
        }
        val staleAlerts =
            InvariantQueries.MODEL.single { it.label == STALE_ALERTS }.sql.removeSuffix(";")
        connection.prepare(
            "UPDATE low_stock_alerts SET resolved_at = ? WHERE id IN ($staleAlerts)",
        ).use { statement ->
            statement.bindLong(1, nowMillis)
            statement.step()
        }
        for (name in DOCUMENT_GUARDS) SchemaGuards.drop(connection, name)
        for (sql in DOCUMENT_REPAIRS) connection.execSQL(sql)
        for (name in DOCUMENT_GUARDS) SchemaGuards.create(connection, SchemaGuards.named(name))
    }

    private inline fun SQLiteConnection.inTransaction(block: SQLiteConnection.() -> Unit) {
        execSQL("PRAGMA foreign_keys = ON")
        execSQL("PRAGMA trusted_schema = OFF")
        execSQL("PRAGMA synchronous = FULL")
        execSQL("BEGIN IMMEDIATE")
        try {
            block()
            execSQL("COMMIT")
        } catch (failure: Throwable) {
            try {
                execSQL("ROLLBACK")
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private companion object {
        /** El código de SQLite de una restricción, como el aborto de una guarda. */
        const val SQLITE_CONSTRAINT = 19

        const val STALE_ALERTS = "I-07"

        /** Las guardas que impiden cambiar un documento salvo para anularlo. */
        val DOCUMENT_GUARDS = listOf("guard_sales_void_only", "guard_purchases_void_only")

        /**
         * Los saldos de cabecera desde su libro. Cada sentencia cambia solo las filas que no
         * coinciden: una fila sana no se escribe.
         */
        val HEADER_REPAIRS =
            listOf(
                """
                UPDATE products SET on_hand_milli = t.target FROM (
                  SELECT p.id AS product_id,
                         CASE WHEN p.stock_mode = 'TRACKED' THEN COALESCE(SUM(m.quantity_milli), 0) ELSE 0 END AS target
                  FROM products p LEFT JOIN stock_movements m ON m.product_id = p.id
                  GROUP BY p.id
                ) t
                WHERE t.product_id = products.id AND products.on_hand_milli <> t.target
                """.trimIndent(),
                """
                UPDATE stock_balances SET on_hand_milli = COALESCE((
                  SELECT SUM(m.quantity_milli) FROM stock_movements m
                  WHERE m.product_id = stock_balances.product_id AND m.location_id = stock_balances.location_id
                    AND COALESCE(m.lot_id, 0) = stock_balances.lot_key
                ), 0)
                WHERE on_hand_milli <> COALESCE((
                  SELECT SUM(m.quantity_milli) FROM stock_movements m
                  WHERE m.product_id = stock_balances.product_id AND m.location_id = stock_balances.location_id
                    AND COALESCE(m.lot_id, 0) = stock_balances.lot_key
                ), 0)
                """.trimIndent(),
                """
                INSERT INTO stock_balances (product_id, location_id, lot_key, on_hand_milli)
                SELECT m.product_id, m.location_id, COALESCE(m.lot_id, 0), SUM(m.quantity_milli)
                FROM stock_movements m
                WHERE NOT EXISTS (
                  SELECT 1 FROM stock_balances b
                  WHERE b.product_id = m.product_id AND b.location_id = m.location_id
                    AND b.lot_key = COALESCE(m.lot_id, 0)
                )
                GROUP BY m.product_id, m.location_id, COALESCE(m.lot_id, 0)
                HAVING SUM(m.quantity_milli) <> 0
                """.trimIndent(),
                ledgerBalance(
                    "customers",
                    "balance",
                    "customer_ledger_entries",
                    "customer_id",
                    "amount",
                ),
                ledgerBalance(
                    "suppliers",
                    "balance",
                    "supplier_ledger_entries",
                    "supplier_id",
                    "amount",
                ),
                ledgerBalance(
                    "money_accounts",
                    "balance",
                    "money_movements",
                    "money_account_id",
                    "amount",
                ),
                ledgerBalance(
                    "customers",
                    "points_balance",
                    "loyalty_ledger_entries",
                    "customer_id",
                    "points",
                ),
            )

        /** Los totales de cada venta y de cada compra desde sus líneas, que no se tocan. */
        val DOCUMENT_REPAIRS =
            listOf(
                """
                UPDATE sales SET total = l.total, net_total = l.net, tax_total = l.tax,
                                 total_cost = l.cost, order_adjustment = l.adjustment
                FROM (
                  SELECT sale_id, SUM(line_total) AS total, SUM(net_amount) AS net, SUM(tax_amount) AS tax,
                         SUM(line_cost) AS cost, SUM(order_adjustment_share) AS adjustment
                  FROM sale_lines
                  GROUP BY sale_id
                ) l
                WHERE l.sale_id = sales.id
                  AND (sales.total <> l.total OR sales.net_total <> l.net OR sales.tax_total <> l.tax
                    OR sales.total_cost <> l.cost OR sales.order_adjustment <> l.adjustment)
                """.trimIndent(),
                """
                UPDATE purchases SET total = l.total, tax_total = l.tax, net_total = l.total - l.tax,
                                     cost_total = l.cost
                FROM (
                  SELECT purchase_id, SUM(line_total) AS total, SUM(tax_amount) AS tax, SUM(cost_amount) AS cost
                  FROM purchase_lines
                  GROUP BY purchase_id
                ) l
                WHERE l.purchase_id = purchases.id
                  AND (purchases.total <> l.total OR purchases.tax_total <> l.tax
                    OR purchases.net_total <> l.total - l.tax OR purchases.cost_total <> l.cost)
                """.trimIndent(),
            )

        /** El saldo [column] de [table] como la suma de [amount] en su libro [ledger]. */
        fun ledgerBalance(
            table: String,
            column: String,
            ledger: String,
            key: String,
            amount: String,
        ): String = """
            UPDATE $table SET $column = COALESCE((SELECT SUM(e.$amount) FROM $ledger e WHERE e.$key = $table.id), 0)
            WHERE $column <> COALESCE((SELECT SUM(e.$amount) FROM $ledger e WHERE e.$key = $table.id), 0)
        """.trimIndent()
    }
}
