package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListEntity

/**
 * Una fila del historial de precios: solo se agrega. Cambiar un precio nunca altera una venta
 * pasada, y cada cambio queda explicado con su origen y, si fue masivo, con su cálculo.
 *
 * Ningún DAO la edita ni la borra. Solo desaparece en cascada, con un producto que se elimina por
 * no tener historia: por eso `product_id` usa `CASCADE`. La lista de precios usa `RESTRICT`: una
 * lista con historial no se borra.
 *
 * - `old_price` solo es nulo en el precio con que nace el producto.
 * - `batch_id` es el UUID, en texto de 36 caracteres, que agrupa una actualización masiva o su
 *   deshacer, para deshacerla entera.
 * - `bulk_mode` y `rounding_step` solo existen en una actualización masiva, y de `percent_bp`,
 *   `amount_delta`, `target_margin_bp` y `fx_rate_micro` solo el que pide su modo.
 * - `rounding_step` es el paso en unidad menor; cero es sin redondeo.
 * - El orden del historial es el de `id`, no el de `occurred_at`: el reloj del teléfono puede
 *   cambiar, el orden en que se registraron las filas no.
 *
 * `price_list_id` no lleva índice: las listas de precios casi nunca se borran, y SQLite solo busca
 * hijos por esa columna al borrar una.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "product_price_history",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PriceListEntity::class,
            parentColumns = ["id"],
            childColumns = ["price_list_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["product_id"]),
        Index(value = ["batch_id"]),
    ],
)
internal data class PriceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "price_list_id") val priceListId: Long,
    @ColumnInfo(name = "old_price") val oldPrice: Long?,
    @ColumnInfo(name = "new_price") val newPrice: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "batch_id") val batchId: String?,
    @ColumnInfo(name = "bulk_mode") val bulkMode: String?,
    @ColumnInfo(name = "percent_bp") val percentBp: Long?,
    @ColumnInfo(name = "amount_delta") val amountDelta: Long?,
    @ColumnInfo(name = "target_margin_bp") val targetMarginBp: Long?,
    @ColumnInfo(name = "fx_rate_micro") val fxRateMicro: Long?,
    @ColumnInfo(name = "rounding_step") val roundingStep: Long?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
)
