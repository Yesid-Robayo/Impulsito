package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseLineEntity

/**
 * Un lote de un producto que lleva lotes: lo que entró junto, con su código y su vencimiento. Una
 * serie es un lote de una sola unidad. El lote no tiene costo propio: el costo del producto sigue
 * siendo uno solo, promedio.
 *
 * - `lot_code` es único dentro de su producto: el índice `(product_id, lot_code)` es único, y sirve
 *   también a la llave del producto y a la lista de lotes de un producto.
 * - `is_unassigned`, 0 o 1, marca el lote sin asignar: absorbe lo vendido sin lote, para que vender
 *   nunca se bloquee, y es el único que puede quedar negativo. Hay uno por producto como máximo, sin
 *   código ni vencimiento, y no deja de serlo: lo imponen las guardas, porque Room no declara la
 *   unicidad de una parte de las filas.
 * - `expiry_date` es una fecha `YYYY-MM-DD`, sin hora, y `expiry_notified_at` evita repetir el aviso.
 * - `purchase_line_id` es la línea de compra con que entró, si entró por una compra. El producto usa
 *   `RESTRICT`: un producto con lotes no se borra. La línea usa `SET NULL`, sin índice: una línea
 *   de compra nunca se borra, y la llave que importa es la de la línea hacia su lote, que sí es
 *   `RESTRICT`. Si las dos lo fueran, formarían un ciclo, y Room no podría ordenar las tablas al
 *   vaciar la base.
 * - Un lote con filas en el libro no se borra: se cierra con `closed_at`.
 *
 * Solo `Long` y `String`, sin valores por defecto en las columnas: los pone el código.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "stock_lots",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PurchaseLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_line_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["product_id", "lot_code"], unique = true),
        Index(value = ["expiry_date"]),
    ],
)
internal data class StockLotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "lot_code") val lotCode: String?,
    // 0 o 1: SQLite no tiene booleanos.
    @ColumnInfo(name = "is_unassigned") val isUnassigned: Long,
    @ColumnInfo(name = "expiry_date") val expiryDate: String?,
    @ColumnInfo(name = "purchase_line_id") val purchaseLineId: Long?,
    @ColumnInfo(name = "expiry_notified_at") val expiryNotifiedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long?,
)
