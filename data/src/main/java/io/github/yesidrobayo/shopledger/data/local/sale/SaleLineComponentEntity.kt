package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity

/**
 * Un componente hoja que consumió una línea de combo, congelado al vender. Nunca se edita ni se
 * borra, ni siquiera al anular la venta: explica qué salió del stock aunque la receta cambie
 * después.
 *
 * - La línea es siempre de un combo, con `stock_effect = COMPONENTS`, y el componente nunca es otro
 *   combo: los niveles ya vienen expandidos hasta las hojas.
 * - `quantity_milli` es lo que se consumió de la hoja en toda la línea, en milésimas de su unidad
 *   base, con un solo redondeo por hoja. Va de 1 en adelante: una hoja que redondea a cero se
 *   rechaza antes de guardar la venta.
 * - `tracked` copia si la hoja lleva libro de stock al vender: con 1, la línea dejó en el libro una
 *   fila que apunta a este componente.
 * - `unit_cost` y `line_cost` son el costo congelado de la hoja, por unidad y en toda la línea.
 * - Línea y producto usan `RESTRICT`: un producto que se vendió como componente no se borra.
 */
@Entity(
    tableName = "sale_line_components",
    foreignKeys = [
        ForeignKey(
            entity = SaleLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_line_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["component_product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sale_line_id"]),
        Index(value = ["component_product_id"]),
    ],
)
internal data class SaleLineComponentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sale_line_id") val saleLineId: Long,
    @ColumnInfo(name = "component_product_id") val componentProductId: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    // 0 o 1: SQLite no tiene booleanos.
    @ColumnInfo(name = "tracked") val tracked: Long,
    @ColumnInfo(name = "unit_cost") val unitCost: Long,
    @ColumnInfo(name = "line_cost") val lineCost: Long,
)
