package io.github.yesidrobayo.shopledger.data.local.returns

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseLineEntity
import io.github.yesidrobayo.shopledger.data.local.stock.StockLotEntity

/**
 * Una línea de una devolución a un proveedor. Nunca se edita ni se borra, ni siquiera al anular la
 * devolución: la anulación agrega filas compensatorias al libro de stock.
 *
 * - `quantity_milli` va en milésimas de la unidad base, de 1 en adelante, y `unit_cost` es el
 *   costo por unidad base con que sale, congelado.
 * - La línea de compra es opcional, como la compra de la cabecera.
 * - Devolución, línea de compra, producto y lote usan `RESTRICT`: nada con historia se borra. La
 *   línea de compra y el lote no llevan índice: las líneas de compra nunca se borran, y un lote
 *   casi nunca.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "purchase_return_lines",
    foreignKeys = [
        ForeignKey(
            entity = PurchaseReturnEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_return_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PurchaseLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_line_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StockLotEntity::class,
            parentColumns = ["id"],
            childColumns = ["lot_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["purchase_return_id"]),
        Index(value = ["product_id"]),
    ],
)
internal data class PurchaseReturnLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "purchase_return_id") val purchaseReturnId: Long,
    @ColumnInfo(name = "purchase_line_id") val purchaseLineId: Long?,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "lot_id") val lotId: Long?,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "unit_cost") val unitCost: Long,
    @ColumnInfo(name = "line_total") val lineTotal: Long,
)
