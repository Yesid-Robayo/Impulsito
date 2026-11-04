package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.CategoryEntity

/**
 * Una sesión de conteo del stock de una ubicación, a ciegas si se eligió: quien cuenta no ve lo
 * esperado. Aplicarla agrega al libro un ajuste por cada diferencia, con esta sesión como origen.
 *
 * - `status` es el nombre de un `CountSessionStatus`. Nace `OPEN`, y solo una abierta pasa a
 *   `APPLIED` o `CANCELLED`, con `finished_at`. Una cerrada ya no cambia: lo imponen las guardas.
 * - `is_blind`, 0 o 1, y la ubicación no cambian después de empezar.
 * - `category_id` limita el conteo a una categoría. Si la categoría se elimina, la sesión queda
 *   sin ella. La ubicación usa `RESTRICT` sin índice: casi nunca se borra.
 * - El índice de `status` encuentra la sesión abierta.
 * - Una sesión con ajustes aplicados no se borra, porque esa llave del libro es `RESTRICT`. Sin
 *   ellos, sus líneas se van con ella.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "count_sessions",
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["location_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["status"]),
        Index(value = ["category_id"]),
    ],
)
internal data class CountSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "status") val status: String,
    // 0 o 1: SQLite no tiene booleanos.
    @ColumnInfo(name = "is_blind") val isBlind: Long,
    @ColumnInfo(name = "location_id") val locationId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "business_date") val businessDate: String,
)
