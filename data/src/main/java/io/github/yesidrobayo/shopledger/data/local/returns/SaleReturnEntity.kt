package io.github.yesidrobayo.shopledger.data.local.returns

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity

/**
 * La cabecera de una devolución de venta, parcial o de un cambio. Como una venta, no se edita ni se
 * borra: solo se anula, y la anulación es lo único que cambia en la fila.
 *
 * Dinero en unidad menor de la moneda del negocio, siempre `Long`.
 *
 * - `resolution` es un `ReturnResolution` por su nombre: reembolso, saldo a favor o cambio.
 * - `total_refund` es `net_refund + tax_refund`, lo que vuelve a la persona que compró.
 * - `cost_returned` es solo el costo de lo que vuelve al stock. Lo que se devolvió y se perdió, con
 *   `restock = 0` en su línea, sigue en el costo de lo vendido.
 * - La venta original solo se devuelve mientras está activa, y no se anula mientras tenga una
 *   devolución activa: se devuelve el resto. Lo imponen las guardas de las dos tablas.
 * - `replacement_sale_id` es la venta nueva de un cambio.
 * - Venta y venta nueva usan `RESTRICT`: una venta con devoluciones no se borra, y las ventas
 *   nunca se borran.
 * - La cuenta de dinero del reembolso usa `RESTRICT`, sin índice: una cuenta con movimientos no se
 *   borra, y la devolución se encuentra por su venta o su proveedor.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "sale_returns",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["replacement_sale_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MoneyAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["money_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sale_id"]),
        Index(value = ["status", "business_date"]),
        Index(value = ["replacement_sale_id"]),
    ],
)
internal data class SaleReturnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sale_id") val saleId: Long,
    @ColumnInfo(name = "resolution") val resolution: String,
    @ColumnInfo(name = "net_refund") val netRefund: Long,
    @ColumnInfo(name = "tax_refund") val taxRefund: Long,
    @ColumnInfo(name = "total_refund") val totalRefund: Long,
    @ColumnInfo(name = "cost_returned") val costReturned: Long,
    @ColumnInfo(name = "money_account_id") val moneyAccountId: Long?,
    @ColumnInfo(name = "replacement_sale_id") val replacementSaleId: Long?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "voided_at") val voidedAt: Long?,
    @ColumnInfo(name = "void_reason") val voidReason: String?,
    @ColumnInfo(name = "void_note") val voidNote: String?,
)
