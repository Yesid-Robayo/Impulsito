package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity

/**
 * Una fila del libro de puntos de un cliente. Solo se agrega, nunca se edita ni se borra. Anular
 * agrega un `ENTRY_VOID` con los puntos opuestos, que apunta a la fila que compensa.
 *
 * Los puntos **no son dinero**: no se cambian por efectivo, no entran en ninguna cuenta, no son
 * saldo a favor y no cuentan como deuda. Por eso esta tabla no exige la moneda confirmada.
 *
 * - `points` son enteros con signo, nunca 0: ganar suma y canjear resta. Su tope protege de
 *   desbordar, no es una cifra de ninguna moneda.
 * - `balance_after` es el saldo de puntos al terminar esta fila, con el mismo tope: el de la fila
 *   anterior del mismo cliente más `points`, o `points` si es la primera. Se escribe en la misma
 *   transacción que `customers.points_balance`, que queda con ese valor.
 * - `sale_id` lo llevan solo `EARN` y `REDEEM`, y `reverses_entry_id` solo un `ENTRY_VOID`, con
 *   índice único: una fila se anula una sola vez.
 * - El orden es el de `id`. Cliente, venta y fila anulada usan `RESTRICT`.
 */
@Entity(
    tableName = "loyalty_ledger_entries",
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
            entity = LoyaltyLedgerEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["reverses_entry_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["customer_id"]),
        Index(value = ["reverses_entry_id"], unique = true),
        Index(value = ["sale_id"]),
    ],
)
internal data class LoyaltyLedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "points") val points: Long,
    @ColumnInfo(name = "balance_after") val balanceAfter: Long,
    @ColumnInfo(name = "sale_id") val saleId: Long?,
    @ColumnInfo(name = "reverses_entry_id") val reversesEntryId: Long?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
