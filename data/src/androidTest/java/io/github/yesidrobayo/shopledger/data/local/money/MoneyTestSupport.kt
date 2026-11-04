package io.github.yesidrobayo.shopledger.data.local.money

import androidx.room3.withWriteTransaction
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.BUSINESS_DATE
import io.github.yesidrobayo.shopledger.data.local.Row
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.row
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

// Apoyo de las pruebas de cuentas y movimientos de dinero. Todos los nombres, monedas y montos son
// inventados. `XTS` es el código que ISO 4217 reserva para pruebas: ninguna moneda real.

/** Una cuenta que creó la persona, con nombre y en la moneda de pruebas, sin saldo. */
internal fun moneyAccount(
    name: String,
    kind: String = "BANK",
    currencyCode: String = TEST_CURRENCY,
    currencyDecimals: Long = 2,
): MoneyAccountEntity = MoneyAccountEntity(
    systemKey = null,
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    kind = kind,
    currencyCode = currencyCode,
    currencyDecimals = currencyDecimals,
    balance = 0,
    archivedAt = null,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** Un movimiento sin origen, con [balanceAfter] tal cual: cada prueba le pone el suyo. */
internal fun moneyMovement(
    accountId: Long,
    type: String,
    amount: Long,
    balanceAfter: Long,
    occurredAt: Long = CREATED_AT,
): MoneyMovementEntity = MoneyMovementEntity(
    moneyAccountId = accountId,
    type = type,
    amount = amount,
    balanceAfter = balanceAfter,
    availableDate = null,
    transferGroupId = null,
    fxRateMicro = null,
    salePaymentId = null,
    customerLedgerEntryId = null,
    supplierLedgerEntryId = null,
    expenseId = null,
    purchaseId = null,
    saleReturnId = null,
    purchaseReturnId = null,
    cashSessionId = null,
    reversesMovementId = null,
    note = null,
    occurredAt = occurredAt,
    businessDate = BUSINESS_DATE,
    createdAt = occurredAt,
)

/**
 * Agrega el movimiento y deja a su cuenta con su `balance_after`, en una sola transacción: lo que
 * hará el repositorio del libro de dinero.
 */
internal suspend fun AppDatabase.appendMoneyMovement(movement: MoneyMovementEntity): Long =
    withWriteTransaction {
        val id = moneyMovementDao().insert(movement)
        moneyAccountDao()
            .setBalance(movement.moneyAccountId, movement.balanceAfter, movement.createdAt)
        id
    }

/** La cuenta de [moneyAccount], escrita como SQL para las sondas. */
internal fun moneyAccountRow(name: String = "banco"): Row = row(
    "system_key" to null,
    "name" to name,
    "normalized_name" to name,
    "kind" to "BANK",
    "currency_code" to TEST_CURRENCY,
    "currency_decimals" to 2L,
    "balance" to 0L,
    "archived_at" to null,
    "created_at" to CREATED_AT,
    "updated_at" to CREATED_AT,
)

/** Un saldo inicial de 50.000 en la cuenta, sin origen, escrito como SQL. */
internal fun moneyMovementRow(accountId: Long): Row = row(
    "money_account_id" to accountId,
    "type" to "OPENING_BALANCE",
    "amount" to 50_000L,
    "balance_after" to 50_000L,
    "available_date" to null,
    "transfer_group_id" to null,
    "fx_rate_micro" to null,
    "sale_payment_id" to null,
    "customer_ledger_entry_id" to null,
    "supplier_ledger_entry_id" to null,
    "expense_id" to null,
    "purchase_id" to null,
    "sale_return_id" to null,
    "purchase_return_id" to null,
    "cash_session_id" to null,
    "reverses_movement_id" to null,
    "note" to null,
    "occurred_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "created_at" to CREATED_AT,
)

/** Un identificador de grupo de transferencia: un UUID inventado, de 36 caracteres. */
internal const val TRANSFER_GROUP = "8f14e45f-ceea-4e7a-9b1c-2d3e4f5a6b7c"

/** La moneda de pruebas de ISO 4217. */
internal const val TEST_CURRENCY = "XTS"

/**
 * Las consultas del modelo de datos que comprueban el libro de dinero, tal cual: el saldo de cada
 * cuenta es la suma de sus movimientos, cada `balance_after` es el anterior más su monto, y cada
 * transferencia tiene una salida y una entrada. Cada una debe devolver cero filas.
 */
internal const val MONEY_BALANCE_SQL =
    "SELECT c.id FROM money_accounts c LEFT JOIN (" +
        "SELECT money_account_id, SUM(amount) AS total FROM money_movements " +
        "GROUP BY money_account_id) e ON e.money_account_id = c.id " +
        "WHERE c.balance <> COALESCE(e.total, 0)"

internal const val MONEY_CHAIN_SQL =
    "SELECT id FROM (SELECT id, amount, balance_after, " +
        "LAG(balance_after, 1, 0) OVER (PARTITION BY money_account_id ORDER BY id) " +
        "AS previous_balance " +
        "FROM money_movements) WHERE balance_after <> previous_balance + amount"

internal const val TRANSFER_PAIRS_SQL =
    "SELECT transfer_group_id FROM money_movements " +
        "WHERE type IN ('TRANSFER_OUT', 'TRANSFER_IN') GROUP BY transfer_group_id " +
        "HAVING COUNT(*) <> 2 OR SUM(type = 'TRANSFER_OUT') <> 1"
