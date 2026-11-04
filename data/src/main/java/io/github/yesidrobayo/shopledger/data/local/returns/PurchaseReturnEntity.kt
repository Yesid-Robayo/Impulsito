package io.github.yesidrobayo.shopledger.data.local.returns

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierEntity
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseEntity

/**
 * La cabecera de una devolución a un proveedor. No se edita ni se borra: solo se anula, y la
 * anulación es lo único que cambia en la fila.
 *
 * Dinero en unidad menor de la moneda del negocio, siempre `Long`.
 *
 * - `resolution` es un `PurchaseReturnResolution` por su nombre: el proveedor devuelve el dinero,
 *   o deja un saldo a favor, que pide saber cuál proveedor es. Lo impone la guarda.
 * - La compra es opcional: se puede devolver mercancía sin decir de qué compra salió.
 * - Compra y proveedor usan `RESTRICT`: un proveedor con devoluciones no se borra, se archiva.
 * - La cuenta de dinero del reembolso usa `RESTRICT`, sin índice: una cuenta con movimientos no se
 *   borra, y la devolución se encuentra por su venta o su proveedor.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "purchase_returns",
    foreignKeys = [
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplier_id"],
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
        Index(value = ["purchase_id"]),
        Index(value = ["supplier_id"]),
        Index(value = ["status", "business_date"]),
    ],
)
internal data class PurchaseReturnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "purchase_id") val purchaseId: Long?,
    @ColumnInfo(name = "supplier_id") val supplierId: Long?,
    @ColumnInfo(name = "resolution") val resolution: String,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "money_account_id") val moneyAccountId: Long?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "voided_at") val voidedAt: Long?,
    @ColumnInfo(name = "void_reason") val voidReason: String?,
    @ColumnInfo(name = "void_note") val voidNote: String?,
)
