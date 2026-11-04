package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.UnitEntity

/**
 * Una línea de cotización, con el precio y el nombre del producto congelados al cotizar: si el
 * precio cambia después, la cotización lo avisa en vez de cambiar sola.
 *
 * - Cantidades en milésimas, en la unidad elegida y en la base, como en una línea de venta.
 * - Dinero en unidad menor. `line_total` es neto más impuesto.
 * - Solo cambia mientras su cotización está `OPEN`: lo exige la guarda.
 * - La cotización usa `CASCADE`: borrar una cotización sin convertir borra sus líneas. El producto
 *   usa `RESTRICT`: un producto cotizado tiene historia, y no se borra.
 * - La unidad también usa `RESTRICT`: una unidad usada en una línea no se borra.
 */
@Entity(
    tableName = "quote_lines",
    foreignKeys = [
        ForeignKey(
            entity = QuoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["quote_id"],
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
        Index(value = ["quote_id"]),
        Index(value = ["product_id"]),
        Index(value = ["unit_id"]),
    ],
)
internal data class QuoteLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "quote_id") val quoteId: Long,
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
