package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una categoría de producto.
 *
 * No tiene historia que proteger: se renombra y se elimina aunque esté en uso, y al eliminarla sus
 * productos quedan sin categoría por su llave foránea. Por eso, a diferencia de productos y
 * proveedores, su nombre normalizado es único en toda la tabla, sin excepción para archivados.
 *
 * `normalized_name` lo calcula `TextNormalizer` en el dominio, nunca la base: comparar dos nombres
 * no debe depender del idioma del teléfono. `target_margin_bp` es el margen objetivo de sus
 * productos en puntos básicos, o nulo si manda el del negocio.
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["normalized_name"], unique = true)],
)
internal data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "target_margin_bp") val targetMarginBp: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
