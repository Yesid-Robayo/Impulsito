package io.github.yesidrobayo.shopledger.data.local.pricing

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity

/**
 * El precio de un producto en una lista fija distinta de la inicial. La inicial usa el precio de
 * venta del producto, y una lista relativa se calcula desde su base: ninguna de las dos guarda
 * filas aquí, y la guarda lo impone.
 *
 * - Hay a lo sumo una fila por lista y producto: el índice `(price_list_id, product_id)` es único.
 *   Un producto sin fila en una lista fija toma el precio de la inicial.
 * - `price` va en unidad menor de la moneda del negocio, por una unidad entera de la unidad base.
 * - Con el producto se borra en cascada. La lista usa `RESTRICT`: con precios, no se borra.
 *
 * Solo `Long`, y sin valores por defecto en las columnas: los pone el código.
 */
@Entity(
    tableName = "product_prices",
    foreignKeys = [
        ForeignKey(
            entity = PriceListEntity::class,
            parentColumns = ["id"],
            childColumns = ["price_list_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["price_list_id", "product_id"], unique = true),
        Index(value = ["product_id"]),
    ],
)
internal data class ProductPriceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "price_list_id") val priceListId: Long,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "price") val price: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
