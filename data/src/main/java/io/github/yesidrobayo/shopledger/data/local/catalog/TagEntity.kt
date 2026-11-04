package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una etiqueta para agrupar y filtrar productos.
 *
 * No tiene historia: se renombra y se borra aunque esté en uso, y al borrarla sus vínculos con los
 * productos se van en cascada. Por eso su nombre normalizado es único en toda la tabla.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
internal data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
