package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Las sumas del flujo de caja proyectado, en una sola consulta. Solo lee.
 *
 * `today`, `shortUntil` y `longUntil` son fechas `AAAA-MM-DD`. Solo cuentan las cuentas vigentes
 * en la moneda del negocio. Lo que se acredita sale de `money_movements.available_date`, sin los
 * movimientos anulados ni sus anulaciones: es lo mismo que está en los saldos y todavía no se
 * puede usar. Lo que vence por cobrar y por pagar son los cargos a crédito con vencimiento en el
 * horizonte, sin los anulados.
 */
@Dao
internal interface CashFlowDao {
    /** Las sumas, de nuevo con cada cambio en las tablas que lee. */
    @Query(CASH_FLOW_SQL)
    fun observe(today: String, shortUntil: String, longUntil: String): Flow<CashFlowRow>
}

/** Una lectura de [CashFlowDao.observe], en unidad menor de la moneda del negocio. */
internal data class CashFlowRow(
    @ColumnInfo(name = "account_count") val accountCount: Long,
    @ColumnInfo(name = "balance") val balance: Long,
    @ColumnInfo(name = "not_yet_available") val notYetAvailable: Long,
    @ColumnInfo(name = "short_settlements") val shortSettlements: Long,
    @ColumnInfo(name = "long_settlements") val longSettlements: Long,
    @ColumnInfo(name = "short_receivables") val shortReceivables: Long,
    @ColumnInfo(name = "long_receivables") val longReceivables: Long,
    @ColumnInfo(name = "short_payables") val shortPayables: Long,
    @ColumnInfo(name = "long_payables") val longPayables: Long,
)

/** Las cuentas que entran: vigentes y en la moneda del negocio. */
private const val BOOK_ACCOUNT =
    "a.archived_at IS NULL AND a.currency_code = (SELECT currency_code FROM app_settings " +
        "WHERE id = 1)"

/** Los movimientos de esas cuentas que siguen en pie: ni anulados ni anulaciones. */
private const val LIVE_MOVEMENT =
    "FROM money_movements m JOIN money_accounts a ON a.id = m.money_account_id " +
        "WHERE " + BOOK_ACCOUNT + " AND m.reverses_movement_id IS NULL " +
        "AND NOT EXISTS (SELECT 1 FROM money_movements v WHERE v.reverses_movement_id = m.id)"

private const val CUSTOMER_DUE =
    "FROM customer_ledger_entries e WHERE e.type = 'CREDIT_SALE' AND NOT EXISTS " +
        "(SELECT 1 FROM customer_ledger_entries v WHERE v.reverses_entry_id = e.id) " +
        "AND e.due_date BETWEEN :today AND "

private const val SUPPLIER_DUE =
    "FROM supplier_ledger_entries e WHERE e.type = 'CREDIT_PURCHASE' AND NOT EXISTS " +
        "(SELECT 1 FROM supplier_ledger_entries v WHERE v.reverses_entry_id = e.id) " +
        "AND e.due_date BETWEEN :today AND "

/** La consulta de [CashFlowDao.observe]. */
internal const val CASH_FLOW_SQL =
    "SELECT (SELECT COUNT(*) FROM money_accounts a WHERE " + BOOK_ACCOUNT +
        ") AS account_count, " +
        "(SELECT COALESCE(SUM(a.balance), 0) FROM money_accounts a WHERE " + BOOK_ACCOUNT +
        ") AS balance, " +
        "(SELECT COALESCE(SUM(m.amount), 0) " + LIVE_MOVEMENT +
        " AND m.available_date > :today) AS not_yet_available, " +
        "(SELECT COALESCE(SUM(m.amount), 0) " + LIVE_MOVEMENT +
        " AND m.available_date > :today AND m.available_date <= :shortUntil) " +
        "AS short_settlements, " +
        "(SELECT COALESCE(SUM(m.amount), 0) " + LIVE_MOVEMENT +
        " AND m.available_date > :today AND m.available_date <= :longUntil) " +
        "AS long_settlements, " +
        "(SELECT COALESCE(SUM(e.amount), 0) " + CUSTOMER_DUE + ":shortUntil) " +
        "AS short_receivables, " +
        "(SELECT COALESCE(SUM(e.amount), 0) " + CUSTOMER_DUE + ":longUntil) " +
        "AS long_receivables, " +
        "(SELECT COALESCE(SUM(e.amount), 0) " + SUPPLIER_DUE + ":shortUntil) AS short_payables, " +
        "(SELECT COALESCE(SUM(e.amount), 0) " + SUPPLIER_DUE + ":longUntil) AS long_payables"
