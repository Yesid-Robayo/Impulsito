package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseLineEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineComponentEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineEntity

/**
 * Una fila del libro de stock, la verdad del stock. Solo se agrega: nunca se edita ni se borra.
 * Anular agrega otra fila, el compensatorio, con la cantidad opuesta y que apunta a esta.
 *
 * El orden del libro es el de `id`, no el de `occurred_at`: el reloj del teléfono puede cambiar, el
 * orden en que se registraron las filas no. El índice de `product_id` lleva el `rowid`, así que
 * también da ese orden dentro de cada producto.
 *
 * - `quantity_milli` va en milésimas de la unidad base y con signo: lo que entra suma y lo que sale
 *   resta. Es cero solo en una corrección de costo.
 * - `unit_cost` es el costo que la fila congeló, y `total_cost`, con el signo de la cantidad, lo
 *   que movió en el valor del stock. Con ellos, el tipo y `reverses_movement_id` se reproduce el
 *   costo del producto sin recalcular nada.
 * - `balance_after_milli`, `average_cost_after` y `stock_value_after` son el saldo, el costo
 *   promedio y el valor del stock del producto al terminar esta fila. Se escriben en la misma
 *   transacción que el producto, que queda con esos tres valores. El saldo puede ser negativo:
 *   vender nunca se bloquea por falta de stock.
 * - `reverses_movement_id` solo lo lleva un compensatorio. Su índice es único: una fila se anula
 *   una sola vez. SQLite admite varios `NULL` en un índice único, así que no toca las demás filas.
 * - De las columnas de origen, cada tipo lleva la suya: la línea de venta, la de compra, la de una
 *   devolución, una transferencia, una producción o un conteo.
 * - `business_date` de un compensatorio es la fecha de la anulación, no la del original.
 * - Producto, fila anulada, línea de venta y línea de compra usan `RESTRICT`: un producto o una
 *   línea con filas en el libro no se borran. Ubicación y lote también, sin índice: casi nunca se
 *   borran, y las filas de un producto se encuentran por `product_id`.
 * - El componente de un combo también usa `RESTRICT`, sin índice: nunca se borra, y sus filas se
 *   alcanzan por la línea de venta.
 * - `sale_return_line_id`, `purchase_return_line_id`, `transfer_line_id`, `production_batch_id` y
 *   `count_session_id` todavía no tienen llave foránea: sus tablas no existen aún. La llave, con
 *   `RESTRICT`, llega con la tabla a la que apunta, dentro del mismo esquema 1.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StockMovementEntity::class,
            parentColumns = ["id"],
            childColumns = ["reverses_movement_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_line_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PurchaseLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_line_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleLineComponentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_line_component_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["location_id"],
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
        Index(value = ["product_id"]),
        Index(value = ["type", "business_date"]),
        Index(value = ["sale_line_id"]),
        Index(value = ["purchase_line_id"]),
        Index(value = ["reverses_movement_id"], unique = true),
    ],
)
internal data class StockMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "location_id") val locationId: Long,
    @ColumnInfo(name = "lot_id") val lotId: Long?,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "reason") val reason: String?,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "unit_cost") val unitCost: Long,
    @ColumnInfo(name = "total_cost") val totalCost: Long,
    @ColumnInfo(name = "average_cost_after") val averageCostAfter: Long,
    @ColumnInfo(name = "stock_value_after") val stockValueAfter: Long,
    @ColumnInfo(name = "balance_after_milli") val balanceAfterMilli: Long,
    @ColumnInfo(name = "reverses_movement_id") val reversesMovementId: Long?,
    @ColumnInfo(name = "sale_line_id") val saleLineId: Long?,
    @ColumnInfo(name = "sale_line_component_id") val saleLineComponentId: Long?,
    @ColumnInfo(name = "purchase_line_id") val purchaseLineId: Long?,
    @ColumnInfo(name = "sale_return_line_id") val saleReturnLineId: Long?,
    @ColumnInfo(name = "purchase_return_line_id") val purchaseReturnLineId: Long?,
    @ColumnInfo(name = "transfer_line_id") val transferLineId: Long?,
    @ColumnInfo(name = "production_batch_id") val productionBatchId: Long?,
    @ColumnInfo(name = "count_session_id") val countSessionId: Long?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
)
