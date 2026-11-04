package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity

/**
 * Una línea de una transferencia: cuánto de un producto, y de qué lote si lo lleva, pasa del origen
 * al destino. Nunca se edita ni se borra, ni siquiera al anular la transferencia: la anulación
 * agrega filas compensatorias al libro, y la línea sigue explicando qué se movió.
 *
 * - `quantity_milli` va en milésimas de la unidad base, de una milésima al tope de una línea.
 * - Transferencia, producto y lote usan `RESTRICT`. El lote no lleva índice: un lote con filas en
 *   el libro tampoco se borra.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "stock_transfer_lines",
    foreignKeys = [
        ForeignKey(
            entity = StockTransferEntity::class,
            parentColumns = ["id"],
            childColumns = ["transfer_id"],
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
        Index(value = ["transfer_id"]),
        Index(value = ["product_id"]),
    ],
)
internal data class StockTransferLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "transfer_id") val transferId: Long,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "lot_id") val lotId: Long?,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
)
