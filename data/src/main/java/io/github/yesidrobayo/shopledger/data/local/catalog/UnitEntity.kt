package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una unidad de medida: una de las iniciales, con su clave, o una que creó la persona. Las
 * unidades son datos editables, no una lista fija: ninguna se asume de un país.
 *
 * - `system_key` es la clave estable, en inglés, de una unidad inicial. Mientras `name` es nulo se
 *   muestra el texto traducido de esa clave, en el idioma de la interfaz, y lo mismo el símbolo
 *   mientras `symbol` es nulo. Las que crea la persona no tienen clave y siempre tienen nombre.
 *   Por eso `name` y `normalized_name` admiten nulo, y siempre van juntos.
 * - `normalized_name` es único solo entre las vigentes, como en productos y proveedores: una
 *   archivada no reserva su nombre. Por eso su índice no es único, y la unicidad la impone la
 *   guarda de la tabla. Las iniciales sin nombre no chocan con nadie.
 * - `kind` es el nombre de un `UnitKind`, y `allows_fraction`, 0 o 1, dice si una línea en esta
 *   unidad admite decimales.
 * - La de clave `UNIT` es la unidad base por defecto de todo producto: nunca se archiva ni se
 *   borra. Las demás se borran solo sin productos, líneas ni conversiones que la usen, porque
 *   esas llaves son `RESTRICT`.
 *
 * Solo `Long` y `String`, sin valores por defecto en las columnas: los pone el código.
 */
@Entity(
    tableName = "units",
    indices = [
        Index(value = ["system_key"], unique = true),
        Index(value = ["normalized_name"]),
    ],
)
internal data class UnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "normalized_name") val normalizedName: String?,
    @ColumnInfo(name = "symbol") val symbol: String?,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "allows_fraction") val allowsFraction: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
