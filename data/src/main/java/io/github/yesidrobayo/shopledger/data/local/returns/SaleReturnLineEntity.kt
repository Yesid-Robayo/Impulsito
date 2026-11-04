package io.github.yesidrobayo.shopledger.data.local.returns

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.sale.SaleLineEntity

/**
 * Una línea de una devolución de venta: cuánto vuelve de una línea vendida. Nunca se edita ni se
 * borra, ni siquiera al anular la devolución: la anulación agrega filas compensatorias al libro de
 * stock, y la línea sigue explicando qué volvió y a qué costo.
 *
 * - `quantity_milli` va en milésimas de la unidad base, de 1 en adelante. Entre todas las
 *   devoluciones activas, una línea vendida no devuelve más de lo que se vendió: lo impone la guarda.
 * - `line_total` es `net_amount + tax_amount`, lo que se reembolsa por la línea.
 * - `line_cost` es el costo congelado de lo devuelto, sacado de la línea vendida.
 * - `restock` dice si la mercancía vuelve al stock. Con 0 no deja fila en el libro: se perdió.
 * - Devolución y línea vendida usan `RESTRICT`: nada con historia se borra.
 */
@Entity(
    tableName = "sale_return_lines",
    foreignKeys = [
        ForeignKey(
            entity = SaleReturnEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_return_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_line_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["sale_return_id"]),
        Index(value = ["sale_line_id"]),
    ],
)
internal data class SaleReturnLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sale_return_id") val saleReturnId: Long,
    @ColumnInfo(name = "sale_line_id") val saleLineId: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "net_amount") val netAmount: Long,
    @ColumnInfo(name = "tax_amount") val taxAmount: Long,
    @ColumnInfo(name = "line_total") val lineTotal: Long,
    @ColumnInfo(name = "line_cost") val lineCost: Long,
    // 0 o 1: SQLite no tiene booleanos.
    @ColumnInfo(name = "restock") val restock: Long,
)
