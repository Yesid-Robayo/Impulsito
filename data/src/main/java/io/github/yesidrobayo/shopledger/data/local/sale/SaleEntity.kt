package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListEntity

/**
 * La cabecera de una venta. Su `id` es también el número visible de la venta. Una venta confirmada
 * no se edita ni se borra: solo se anula, y la anulación es lo único que cambia en la fila.
 *
 * Dinero en unidad menor de la moneda del negocio, siempre `Long`. Cada total es la suma de su
 * columna en las líneas, y se guarda para que un reporte por período no tenga que leerlas.
 *
 * - `order_adjustment` y `cash_rounding_amount` llevan signo: un descuento o un redondeo hacia
 *   abajo son negativos. `tip_amount` es la propina, que no entra en ninguna ganancia, y
 *   `amount_due` es lo que se cobra: total, propina y redondeo.
 * - `status` nace `ACTIVE` y solo pasa a `VOIDED`, con `voided_at` y `void_reason`.
 * - La lista de precios usa `RESTRICT`: una lista con ventas no se borra. No lleva índice: las
 *   listas casi nunca se borran, y SQLite solo busca ventas por esa columna al borrar una.
 * - `customer_id`, `location_id`, `cash_session_id`, `quote_id` y `order_id` todavía no tienen
 *   llave foránea: sus tablas no existen aún. La llave llega con la tabla a la que apunta, dentro
 *   del mismo esquema 1. El índice de `customer_id` ya está.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "sales",
    foreignKeys = [
        ForeignKey(
            entity = PriceListEntity::class,
            parentColumns = ["id"],
            childColumns = ["price_list_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["status", "business_date"]),
        Index(value = ["occurred_at", "id"]),
        Index(value = ["customer_id"]),
    ],
)
internal data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "net_total") val netTotal: Long,
    @ColumnInfo(name = "tax_total") val taxTotal: Long,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "total_cost") val totalCost: Long,
    @ColumnInfo(name = "total_replacement_cost") val totalReplacementCost: Long,
    @ColumnInfo(name = "order_adjustment") val orderAdjustment: Long,
    @ColumnInfo(name = "tip_amount") val tipAmount: Long,
    @ColumnInfo(name = "cash_rounding_amount") val cashRoundingAmount: Long,
    @ColumnInfo(name = "amount_due") val amountDue: Long,
    @ColumnInfo(name = "customer_id") val customerId: Long?,
    @ColumnInfo(name = "price_list_id") val priceListId: Long,
    @ColumnInfo(name = "location_id") val locationId: Long,
    @ColumnInfo(name = "cash_session_id") val cashSessionId: Long?,
    @ColumnInfo(name = "quote_id") val quoteId: Long?,
    @ColumnInfo(name = "order_id") val orderId: Long?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "voided_at") val voidedAt: Long?,
    @ColumnInfo(name = "void_reason") val voidReason: String?,
    @ColumnInfo(name = "void_note") val voidNote: String?,
)
