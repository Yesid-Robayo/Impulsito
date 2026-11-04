package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity

/**
 * Un insumo consumido por un lote de producción. Nunca se edita ni se borra, ni siquiera al anular
 * el lote: la anulación agrega filas compensatorias al libro, y la línea sigue explicando qué se
 * consumió y cuánto costó.
 *
 * - `quantity_milli` va en milésimas de la unidad base del insumo.
 * - `unit_cost` es el costo promedio del insumo al consumirlo, congelado para mostrar, y
 *   `line_cost` lo que salió del valor de su stock. La suma de `line_cost` es el costo del lote.
 * - Lote de producción, insumo y lote del insumo usan `RESTRICT`. El lote del insumo no lleva
 *   índice: un lote con filas en el libro tampoco se borra.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "production_batch_lines",
    foreignKeys = [
        ForeignKey(
            entity = ProductionBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["production_batch_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["component_product_id"],
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
        Index(value = ["production_batch_id"]),
        Index(value = ["component_product_id"]),
    ],
)
internal data class ProductionBatchLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "production_batch_id") val productionBatchId: Long,
    @ColumnInfo(name = "component_product_id") val componentProductId: Long,
    @ColumnInfo(name = "lot_id") val lotId: Long?,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "unit_cost") val unitCost: Long,
    @ColumnInfo(name = "line_cost") val lineCost: Long,
)
