package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings

/**
 * Una transferencia de stock entre dos ubicaciones. No se edita ni se borra: solo se anula, y la
 * anulación es lo único que cambia en la fila.
 *
 * Mover stock no cambia el saldo total del producto, ni su costo, ni el valor de su stock: cada
 * línea escribe una salida en la ubicación de origen y una entrada en la de destino, que suman
 * cero, y las dos con costo cero.
 *
 * - `status` nace `ACTIVE` y solo pasa a `VOIDED`, con `voided_at` y `void_reason`.
 * - Origen y destino son ubicaciones distintas, por guarda, y usan `RESTRICT` sin índice: una
 *   ubicación con transferencias no se borra, y las ubicaciones casi nunca se borran.
 * - El índice de `business_date` da la lista por período.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "stock_transfers",
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["from_location_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["to_location_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["business_date"]),
    ],
)
internal data class StockTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "from_location_id") val fromLocationId: Long,
    @ColumnInfo(name = "to_location_id") val toLocationId: Long,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "voided_at") val voidedAt: Long?,
    @ColumnInfo(name = "void_reason") val voidReason: String?,
    @ColumnInfo(name = "void_note") val voidNote: String?,
)
