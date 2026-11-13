package io.github.yesidrobayo.shopledger.data.local.money

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.cash.CashSessionEntity
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerLedgerEntryEntity
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseEntity
import io.github.yesidrobayo.shopledger.data.local.payable.SupplierLedgerEntryEntity
import io.github.yesidrobayo.shopledger.data.local.payment.SalePaymentEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseEntity
import io.github.yesidrobayo.shopledger.data.local.returns.PurchaseReturnEntity
import io.github.yesidrobayo.shopledger.data.local.returns.SaleReturnEntity

/**
 * Un movimiento de una cuenta de dinero: lo que entró o salió de ella. Solo se agrega, nunca se
 * edita ni se borra. Anular agrega un `MOVEMENT_VOID` con el monto opuesto, que apunta al
 * movimiento que compensa. Una transferencia se anula igual, con una anulación por cada una de
 * sus dos partes, en la misma transacción.
 *
 * El orden del libro es el de `id`, que nunca se reutiliza, no el de `occurred_at`. El índice de
 * `money_account_id` lleva el `rowid`, así que da ese orden dentro de cada cuenta.
 *
 * - `amount` va en la unidad menor de la moneda **de su cuenta**, con signo y nunca 0: lo que
 *   entra suma y lo que sale resta.
 * - `balance_after` es el saldo de la cuenta al terminar este movimiento: el del anterior de la
 *   misma cuenta más `amount`, o `amount` si es el primero. Se escribe en la misma transacción que
 *   `money_accounts.balance`, que queda con ese valor.
 * - `transfer_group_id` une las dos filas de una transferencia, una que sale y una que entra, y
 *   solo lo llevan ellas. `fx_rate_micro` es la cotización si las dos cuentas tienen distinta
 *   moneda.
 * - Cada tipo lleva su origen: el pago de la venta, el abono del cliente, el pago al proveedor, el
 *   gasto, la compra, la devolución, la sesión de caja o el movimiento que anula.
 *   `reverses_movement_id` es único: un movimiento se anula una sola vez.
 * - Cuenta, abono del cliente, pago al proveedor, gasto, compra, devoluciones, pago de la venta,
 *   sesión de caja y movimiento anulado usan `RESTRICT`. Sin índice sobre el abono, el pago al
 *   proveedor, el gasto, la compra ni las devoluciones: casi siempre son nulos, y lo que apuntan no
 *   se borra. El de la sesión sirve para sumar lo esperado en su caja.
 * - `sale_payment_id` apunta al pago de la venta que cobró o que pagó la comisión. Su índice sirve
 *   para hallar los movimientos de un pago.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "money_movements",
    foreignKeys = [
        ForeignKey(
            entity = SalePaymentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_payment_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MoneyAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["money_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CustomerLedgerEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_ledger_entry_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SupplierLedgerEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplier_ledger_entry_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expense_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CashSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["cash_session_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleReturnEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_return_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PurchaseReturnEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_return_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MoneyMovementEntity::class,
            parentColumns = ["id"],
            childColumns = ["reverses_movement_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["money_account_id"]),
        Index(value = ["reverses_movement_id"], unique = true),
        Index(value = ["sale_payment_id"]),
        Index(value = ["cash_session_id"]),
    ],
)
internal data class MoneyMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "money_account_id") val moneyAccountId: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "balance_after") val balanceAfter: Long,
    @ColumnInfo(name = "available_date") val availableDate: String?,
    @ColumnInfo(name = "transfer_group_id") val transferGroupId: String?,
    @ColumnInfo(name = "fx_rate_micro") val fxRateMicro: Long?,
    @ColumnInfo(name = "sale_payment_id") val salePaymentId: Long?,
    @ColumnInfo(name = "customer_ledger_entry_id") val customerLedgerEntryId: Long?,
    @ColumnInfo(name = "supplier_ledger_entry_id") val supplierLedgerEntryId: Long?,
    @ColumnInfo(name = "expense_id") val expenseId: Long?,
    @ColumnInfo(name = "purchase_id") val purchaseId: Long?,
    @ColumnInfo(name = "sale_return_id") val saleReturnId: Long?,
    @ColumnInfo(name = "purchase_return_id") val purchaseReturnId: Long?,
    @ColumnInfo(name = "cash_session_id") val cashSessionId: Long?,
    @ColumnInfo(name = "reverses_movement_id") val reversesMovementId: Long?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
