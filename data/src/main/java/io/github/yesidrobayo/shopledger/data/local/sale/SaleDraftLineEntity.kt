package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.UnitEntity

/**
 * Una línea de una venta en espera. Como la espera, no es historia: cambia y se borra libremente.
 *
 * - `entered_quantity_milli` va en milésimas de la unidad `unit_id` en que se vende.
 * - `unit_price` nulo es el precio vigente de la lista al retomar la venta. Con valor, es el precio
 *   que la persona escribió a mano.
 * - `line_discount` es el descuento de la línea, en unidad menor.
 * - `parent_line_id` es la línea principal de un extra, de la misma espera.
 * - La espera, el producto y la línea principal usan `CASCADE`: borrar cualquiera de los tres
 *   saca la línea, y un extra se va con su línea principal.
 * - La unidad usa `RESTRICT`: una unidad con ventas en espera no se borra, se archiva.
 */
@Entity(
    tableName = "sale_draft_lines",
    foreignKeys = [
        ForeignKey(
            entity = SaleDraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draft_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UnitEntity::class,
            parentColumns = ["id"],
            childColumns = ["unit_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleDraftLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_line_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["draft_id"]),
        Index(value = ["product_id"]),
        Index(value = ["unit_id"]),
        Index(value = ["parent_line_id"]),
    ],
)
internal data class SaleDraftLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "draft_id") val draftId: Long,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "unit_id") val unitId: Long,
    @ColumnInfo(name = "entered_quantity_milli") val enteredQuantityMilli: Long,
    @ColumnInfo(name = "unit_price") val unitPrice: Long?,
    @ColumnInfo(name = "line_discount") val lineDiscount: Long,
    @ColumnInfo(name = "parent_line_id") val parentLineId: Long?,
)
