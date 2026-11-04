package io.github.yesidrobayo.shopledger.data.local.payable

import androidx.room3.withWriteTransaction
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.BUSINESS_DATE
import io.github.yesidrobayo.shopledger.data.local.Row
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.row

// Apoyo de las pruebas del libro de proveedores. Todos los montos son inventados.

/** Una fila del libro del proveedor sin origen, con [balanceAfter] tal cual. */
internal fun supplierEntry(
    supplierId: Long,
    type: String,
    amount: Long,
    balanceAfter: Long,
    purchaseId: Long? = null,
    reversesEntryId: Long? = null,
    occurredAt: Long = CREATED_AT,
): SupplierLedgerEntryEntity = SupplierLedgerEntryEntity(
    supplierId = supplierId,
    type = type,
    amount = amount,
    balanceAfter = balanceAfter,
    purchaseId = purchaseId,
    purchaseReturnId = null,
    reversesEntryId = reversesEntryId,
    dueDate = null,
    note = null,
    occurredAt = occurredAt,
    businessDate = BUSINESS_DATE,
    createdAt = occurredAt,
)

/**
 * Agrega la fila al libro del proveedor y deja al proveedor con su `balance_after`, en una sola
 * transacción: lo que hará el repositorio del libro.
 */
internal suspend fun AppDatabase.appendSupplierEntry(entry: SupplierLedgerEntryEntity): Long =
    withWriteTransaction {
        val id = supplierLedgerEntryDao().insert(entry)
        supplierDao().setBalance(entry.supplierId, entry.balanceAfter, entry.createdAt)
        id
    }

/** Una deuda inicial de 30.000 con el proveedor, sin origen, escrita como SQL. */
internal fun supplierEntryRow(supplierId: Long): Row = row(
    "supplier_id" to supplierId,
    "type" to "OPENING_BALANCE",
    "amount" to 30_000L,
    "balance_after" to 30_000L,
    "purchase_id" to null,
    "purchase_return_id" to null,
    "reverses_entry_id" to null,
    "due_date" to null,
    "note" to null,
    "occurred_at" to CREATED_AT,
    "business_date" to BUSINESS_DATE,
    "created_at" to CREATED_AT,
)

/**
 * Las consultas del modelo de datos que comprueban el libro del proveedor, tal cual: lo que se le
 * debe es la suma del libro, y cada `balance_after` es el anterior más su monto. Cada una debe
 * devolver cero filas.
 */
internal const val SUPPLIER_BALANCE_SQL =
    "SELECT c.id FROM suppliers c LEFT JOIN (" +
        "SELECT supplier_id, SUM(amount) AS total FROM supplier_ledger_entries " +
        "GROUP BY supplier_id) e ON e.supplier_id = c.id " +
        "WHERE c.balance <> COALESCE(e.total, 0)"

internal const val SUPPLIER_CHAIN_SQL =
    "SELECT id FROM (SELECT id, amount, balance_after, " +
        "LAG(balance_after, 1, 0) OVER (PARTITION BY supplier_id ORDER BY id) " +
        "AS previous_balance " +
        "FROM supplier_ledger_entries) WHERE balance_after <> previous_balance + amount"
