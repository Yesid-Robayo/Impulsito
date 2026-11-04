package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una conversión de compra o de venta de un producto: cuánto trae una unidad distinta de su unidad
 * base, como la caja de 24 de un producto que se lleva por unidad.
 *
 * - `factor_milli` son milésimas de la unidad base por una unidad entera de `unit_id`, al menos 1:
 *   una caja de 24 es 24000, y un gramo de un producto en kilogramos es 1. Una línea lo copia al
 *   registrarse, así que cambiarlo después no toca lo ya vendido ni lo ya comprado.
 * - Un producto tiene una sola conversión por unidad: el índice `(product_id, unit_id)` es único, y
 *   sirve también a la llave del producto.
 * - `sale_price` nulo es precio calculado con el factor; con valor, es el precio de esa unidad.
 * - `barcode` es el código de esa presentación. No se repite entre conversiones ni con el de otro
 *   producto vigente: lo impone la guarda, porque Room no declara la unicidad entre activos.
 * - `for_sale` y `for_purchase`, 0 o 1, dicen dónde se ofrece.
 * - Se borra con su producto, en cascada. La unidad usa `RESTRICT`: una unidad con conversiones
 *   no se borra.
 *
 * Solo `Long` y `String`, sin valores por defecto en las columnas: los pone el código.
 */
@Entity(
    tableName = "product_units",
    foreignKeys = [
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
    ],
    indices = [
        Index(value = ["product_id", "unit_id"], unique = true),
        Index(value = ["unit_id"]),
        Index(value = ["barcode"]),
    ],
)
internal data class ProductUnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "unit_id") val unitId: Long,
    @ColumnInfo(name = "factor_milli") val factorMilli: Long,
    @ColumnInfo(name = "sale_price") val salePrice: Long?,
    @ColumnInfo(name = "barcode") val barcode: String?,
    @ColumnInfo(name = "for_sale") val forSale: Long,
    @ColumnInfo(name = "for_purchase") val forPurchase: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
