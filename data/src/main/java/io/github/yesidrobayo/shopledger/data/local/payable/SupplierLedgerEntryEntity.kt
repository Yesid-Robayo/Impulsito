package io.github.yesidrobayo.shopledger.data.local.payable

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseEntity

/**
 * Una fila del libro de un proveedor: lo que el negocio le debe y lo que le pagó. Solo se agrega,
 * nunca se edita ni se borra. Anular agrega un `ENTRY_VOID` con el monto opuesto, que apunta a la
 * fila que compensa.
 *
 * El orden del libro es el de `id`, que nunca se reutiliza. El índice de `supplier_id` lleva el
 * `rowid`, así que da ese orden dentro de cada proveedor.
 *
 * - `amount` va en unidad menor y con signo, nunca 0: positivo aumenta lo que se debe. La parte
 *   no pagada de una compra suma, un pago y un crédito por devolución restan.
 * - `balance_after` es lo que se le debe al terminar esta fila: el de la fila anterior del mismo
 *   proveedor más `amount`, o `amount` si es la primera. Se escribe en la misma transacción que
 *   `suppliers.balance`, que queda con ese valor. Un saldo negativo es crédito con el proveedor.
 * - `purchase_id` lo lleva solo un `CREDIT_PURCHASE`, `purchase_return_id` solo un
 *   `RETURN_CREDIT` y `reverses_entry_id` solo un `ENTRY_VOID`, con índice único: una fila se anula
 *   una sola vez.
 * - `due_date` es el vencimiento de un cargo, que nace de la fecha y del plazo del proveedor.
 * - Proveedor, compra y fila anulada usan `RESTRICT`: nada con filas en el libro se borra.
 * - `purchase_return_id` todavía no tiene llave foránea: la tabla de devoluciones a proveedor no
 *   existe aún. La llave, con `RESTRICT`, llega con ella, dentro del mismo esquema 1.
 */
@Entity(
    tableName = "supplier_ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplier_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SupplierLedgerEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["reverses_entry_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["supplier_id"]),
        Index(value = ["reverses_entry_id"], unique = true),
        Index(value = ["purchase_id"]),
        Index(value = ["due_date"]),
    ],
)
internal data class SupplierLedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "supplier_id") val supplierId: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "balance_after") val balanceAfter: Long,
    @ColumnInfo(name = "purchase_id") val purchaseId: Long?,
    @ColumnInfo(name = "purchase_return_id") val purchaseReturnId: Long?,
    @ColumnInfo(name = "reverses_entry_id") val reversesEntryId: Long?,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
