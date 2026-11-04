package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity

/**
 * Lo contado de un producto, y de un lote si lo lleva, en una sesión de conteo. Solo se agrega o
 * cambia mientras la sesión está abierta: lo imponen las guardas.
 *
 * - `expected_milli` es el saldo al empezar a contar, en milésimas de la unidad base. En un conteo
 *   a ciegas no se muestra.
 * - `counted_milli` es lo que se contó, de 0 al tope del stock, o nulo si todavía no se cuenta.
 * - Se borra con su sesión, en cascada. Producto y lote usan `RESTRICT`. El lote no lleva índice.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "count_session_lines",
    foreignKeys = [
        ForeignKey(
            entity = CountSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["count_session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
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
        Index(value = ["count_session_id"]),
        Index(value = ["product_id"]),
    ],
)
internal data class CountSessionLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "count_session_id") val countSessionId: Long,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "lot_id") val lotId: Long?,
    @ColumnInfo(name = "expected_milli") val expectedMilli: Long,
    @ColumnInfo(name = "counted_milli") val countedMilli: Long?,
)
