package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Un campo propio que la persona agrega a sus productos, como una talla o una fecha de cosecha.
 *
 * - `field_type` es un `CustomFieldType` por su nombre: dice cómo interpreta el dominio el texto de
 *   cada valor.
 * - El nombre normalizado es único solo entre los vigentes, y lo impone la guarda de la tabla: un
 *   campo archivado no reserva su nombre, igual que un producto. Por eso su índice no es único.
 *   Archivar un campo solo lo esconde, y sus valores siguen en los productos.
 * - `sort_order` es el orden en que la persona los ve. Dos campos con el mismo orden salen por
 *   `id`.
 * - Borrarlo borra sus valores en cascada. Por eso solo se ofrece sin valores, o cuando la persona
 *   lo confirma.
 */
@Entity(
    tableName = "custom_field_definitions",
    indices = [Index(value = ["normalized_name"])],
)
internal data class CustomFieldDefinitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "field_type") val fieldType: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
