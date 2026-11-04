package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity

/**
 * El saldo de una parte del stock de un producto: en una ubicación y, si el producto lleva lotes,
 * en un lote. Es una copia derivada del libro: cada fila del libro suma su cantidad a su parte, en
 * la misma transacción, y la suma de las partes de un producto es su saldo.
 *
 * - `lot_key` es el `lot_id` de la parte, o 0 sin lote. No es llave foránea a propósito: SQLite
 *   trata cada `NULL` como distinto, y con `lot_id` nulo el índice único
 *   `(product_id, location_id, lot_key)` dejaría repetir la parte sin lote. Los ids empiezan en 1,
 *   así que 0 nunca es un lote. El índice da también los saldos de un producto, en orden.
 * - `on_hand_milli` va en milésimas de la unidad base y puede ser negativo: vender nunca se bloquea
 *   por falta de stock.
 * - Nunca se borra: producto y ubicación usan `RESTRICT`, y sin filas en el libro no hay saldo.
 *   Tampoco se reemplaza: solo suma, con [StockBalanceDao.add].
 *
 * Solo `Long`, sin valores por defecto en las columnas: los pone el código.
 */
@Entity(
    tableName = "stock_balances",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["location_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["product_id", "location_id", "lot_key"], unique = true),
        Index(value = ["location_id"]),
    ],
)
internal data class StockBalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "location_id") val locationId: Long,
    @ColumnInfo(name = "lot_key") val lotKey: Long,
    @ColumnInfo(name = "on_hand_milli") val onHandMilli: Long,
) {
    companion object {
        /** La clave de lote de una parte: el id del lote, o [NO_LOT] si el producto no lleva lotes. */
        fun lotKeyOf(lotId: Long?): Long = lotId ?: NO_LOT

        /** La clave de lote de la parte sin lote. Ningún lote tiene este id. */
        const val NO_LOT: Long = 0L
    }
}
