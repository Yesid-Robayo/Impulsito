package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.UnitEntity
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListEntity

/**
 * Una línea de venta. Nunca se edita ni se borra, ni siquiera al anular la venta: la anulación
 * agrega filas compensatorias al libro de stock, y la línea sigue explicando qué salió y a qué
 * precio.
 *
 * Un producto puede repetirse en una venta, con otra unidad o como extra de otra línea: por eso el
 * índice `(sale_id, product_id)` no es único. Ese índice también sirve a la llave de `sale_id`.
 *
 * - `quantity_milli` está en milésimas de la unidad base del producto, y `entered_quantity_milli`
 *   en la unidad `unit_id` en que se vendió. `unit_factor_milli` se copia al vender.
 * - `unit_price` es por una unidad entera de `unit_id`, y `unit_cost` por una de la unidad base.
 *   Los dos quedan congelados. `unit_cost` es solo para mostrar: el costo real es `line_cost`, que
 *   sale del valor del stock, así que un `unit_cost` en cero no significa que no haya costo.
 * - `order_adjustment_share` lleva signo: es la parte de la línea en el ajuste de toda la venta.
 * - La tasa, el nombre del impuesto y el nombre del producto se copian, porque pueden cambiar.
 * - `stock_effect` se copia del modo de stock del producto al vender, para que la venta se anule
 *   igual aunque el producto cambie después.
 * - `parent_line_id` es la línea principal de un extra, de la misma venta. Venta, producto, línea
 *   principal y lista de precios usan `RESTRICT`. La línea principal y la lista no llevan índice:
 *   ninguna de las dos se borra con ventas.
 * - La unidad también usa `RESTRICT` sin índice: una unidad con ventas no se borra, se archiva.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "sale_lines",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_line_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PriceListEntity::class,
            parentColumns = ["id"],
            childColumns = ["price_list_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = UnitEntity::class,
            parentColumns = ["id"],
            childColumns = ["unit_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sale_id", "product_id"]),
        Index(value = ["product_id"]),
    ],
)
internal data class SaleLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sale_id") val saleId: Long,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "unit_id") val unitId: Long,
    @ColumnInfo(name = "unit_factor_milli") val unitFactorMilli: Long,
    @ColumnInfo(name = "entered_quantity_milli") val enteredQuantityMilli: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "unit_price") val unitPrice: Long,
    @ColumnInfo(name = "unit_cost") val unitCost: Long,
    @ColumnInfo(name = "line_discount") val lineDiscount: Long,
    @ColumnInfo(name = "order_adjustment_share") val orderAdjustmentShare: Long,
    @ColumnInfo(name = "net_amount") val netAmount: Long,
    @ColumnInfo(name = "tax_rate_bp") val taxRateBp: Long,
    @ColumnInfo(name = "tax_name_snapshot") val taxNameSnapshot: String?,
    @ColumnInfo(name = "tax_amount") val taxAmount: Long,
    @ColumnInfo(name = "line_total") val lineTotal: Long,
    @ColumnInfo(name = "line_cost") val lineCost: Long,
    @ColumnInfo(name = "unit_replacement_cost") val unitReplacementCost: Long,
    @ColumnInfo(name = "line_replacement_cost") val lineReplacementCost: Long,
    @ColumnInfo(name = "product_name_snapshot") val productNameSnapshot: String,
    @ColumnInfo(name = "stock_effect") val stockEffect: String,
    @ColumnInfo(name = "parent_line_id") val parentLineId: Long?,
    @ColumnInfo(name = "price_list_id") val priceListId: Long,
)
