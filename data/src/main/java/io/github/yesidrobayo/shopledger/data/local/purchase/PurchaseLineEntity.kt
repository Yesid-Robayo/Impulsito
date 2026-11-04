package io.github.yesidrobayo.shopledger.data.local.purchase

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity

/**
 * Una línea de compra. Nunca se edita ni se borra, ni siquiera al anular la compra: la anulación
 * agrega filas compensatorias al libro de stock, y la línea sigue explicando qué entró.
 *
 * Un producto puede repetirse en una compra, así que no hay índice único por compra y producto.
 *
 * - `quantity_milli` está en milésimas de la unidad base del producto, la del libro de stock, y
 *   `entered_quantity_milli` en la unidad `unit_id` en que se compró. `unit_factor_milli` es
 *   cuántas milésimas base trae una unidad `unit_id`, copiado al momento.
 * - `unit_cost` es por una unidad entera de `unit_id`. `line_total` lleva el impuesto, y
 *   `cost_amount` es lo que la línea suma al valor del stock: sin el impuesto si es recuperable.
 * - La tasa y el nombre del impuesto se copian, porque pueden cambiar después.
 * - `reference_unit_cost` va en la moneda en que se compró, si no es la del negocio.
 * - Compra y producto usan `RESTRICT`: un producto con compras no se borra.
 * - `unit_id` y `lot_id` todavía no tienen llave foránea: sus tablas no existen aún. La llave
 *   llega con la tabla a la que apunta, dentro del mismo esquema 1.
 */
@Entity(
    tableName = "purchase_lines",
    foreignKeys = [
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["purchase_id"]),
        Index(value = ["product_id"]),
    ],
)
internal data class PurchaseLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "purchase_id") val purchaseId: Long,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "unit_id") val unitId: Long,
    @ColumnInfo(name = "unit_factor_milli") val unitFactorMilli: Long,
    @ColumnInfo(name = "entered_quantity_milli") val enteredQuantityMilli: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "unit_cost") val unitCost: Long,
    @ColumnInfo(name = "line_total") val lineTotal: Long,
    @ColumnInfo(name = "tax_rate_bp") val taxRateBp: Long,
    @ColumnInfo(name = "tax_name_snapshot") val taxNameSnapshot: String?,
    @ColumnInfo(name = "tax_amount") val taxAmount: Long,
    @ColumnInfo(name = "cost_amount") val costAmount: Long,
    @ColumnInfo(name = "lot_id") val lotId: Long?,
    @ColumnInfo(name = "reference_unit_cost") val referenceUnitCost: Long?,
)
