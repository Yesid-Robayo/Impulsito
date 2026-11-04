package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.UnitEntity

/**
 * Una línea de pedido: las mismas columnas de una línea de cotización, con el precio y el nombre
 * congelados. Sus cantidades, en milésimas de la unidad base, son lo que el pedido reserva.
 *
 * - Solo cambia mientras su pedido está `PENDING` o `READY`: lo exige la guarda.
 * - El pedido usa `CASCADE`: borrar un pedido sin entregar borra sus líneas. El producto usa
 *   `RESTRICT`: un producto pedido tiene historia, y no se borra. El índice de `product_id` también
 *   suma las reservas de un producto.
 * - La unidad también usa `RESTRICT`: una unidad usada en una línea no se borra.
 */
@Entity(
    tableName = "order_lines",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["order_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
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
        Index(value = ["order_id"]),
        Index(value = ["product_id"]),
        Index(value = ["unit_id"]),
    ],
)
internal data class OrderLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "order_id") val orderId: Long,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "unit_id") val unitId: Long,
    @ColumnInfo(name = "unit_factor_milli") val unitFactorMilli: Long,
    @ColumnInfo(name = "entered_quantity_milli") val enteredQuantityMilli: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "unit_price") val unitPrice: Long,
    @ColumnInfo(name = "line_discount") val lineDiscount: Long,
    @ColumnInfo(name = "net_amount") val netAmount: Long,
    @ColumnInfo(name = "tax_rate_bp") val taxRateBp: Long,
    @ColumnInfo(name = "tax_name_snapshot") val taxNameSnapshot: String?,
    @ColumnInfo(name = "tax_amount") val taxAmount: Long,
    @ColumnInfo(name = "line_total") val lineTotal: Long,
    @ColumnInfo(name = "product_name_snapshot") val productNameSnapshot: String,
)
