package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity

/**
 * Una fila del libro de un cliente: lo que debe y lo que abonó. Solo se agrega, nunca se edita ni
 * se borra. Anular agrega un `ENTRY_VOID` con el monto opuesto, que apunta a la fila que compensa.
 *
 * El orden del libro es el de `id`, que nunca se reutiliza: el reloj del teléfono puede cambiar, el
 * orden en que se registraron las filas no. El índice de `customer_id` lleva el `rowid`, así que da
 * ese orden dentro de cada cliente.
 *
 * - `amount` va en unidad menor y con signo, nunca 0: positivo aumenta la deuda. Un cargo a
 *   crédito suma, un abono y un saldo a favor por devolución restan.
 * - `balance_after` es el saldo del cliente al terminar esta fila: el de la fila anterior del mismo
 *   cliente más `amount`, o `amount` si es la primera. Se escribe en la misma transacción que
 *   `customers.balance`, que queda con ese valor. Así el saldo es la suma del libro, y la cadena
 *   se comprueba fila por fila sin recalcular nada.
 * - `sale_id` lo lleva solo un `CREDIT_SALE`, `sale_return_id` solo un `RETURN_CREDIT` y
 *   `reverses_entry_id` solo un `ENTRY_VOID`. Su índice es único: una fila se anula una sola vez.
 *   SQLite admite varios `NULL` en un índice único, así que no toca las demás filas.
 * - `due_date` es el vencimiento opcional de un cargo. Los abonos cubren primero los más viejos,
 *   pero eso es una lectura: no se escribe aquí.
 * - Cliente, venta y fila anulada usan `RESTRICT`: nada con filas en el libro se borra.
 * - `sale_return_id` todavía no tiene llave foránea: la tabla de devoluciones no existe aún. La
 *   llave, con `RESTRICT`, llega con ella, dentro del mismo esquema 1.
 */
@Entity(
    tableName = "customer_ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CustomerLedgerEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["reverses_entry_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["customer_id"]),
        Index(value = ["reverses_entry_id"], unique = true),
        Index(value = ["sale_id"]),
        Index(value = ["due_date"]),
    ],
)
internal data class CustomerLedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "balance_after") val balanceAfter: Long,
    @ColumnInfo(name = "sale_id") val saleId: Long?,
    @ColumnInfo(name = "sale_return_id") val saleReturnId: Long?,
    @ColumnInfo(name = "reverses_entry_id") val reversesEntryId: Long?,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
