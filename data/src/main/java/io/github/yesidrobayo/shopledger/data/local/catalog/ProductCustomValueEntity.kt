package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * El valor de un campo propio en un producto.
 *
 * `value_text` guarda el valor como texto neutro, sin formato regional: el dominio lo interpreta
 * según el `field_type` de su campo. Así un número o una fecha se leen igual con cualquier idioma o
 * formato del teléfono.
 *
 * Un producto tiene a lo sumo un valor por campo. El valor se va en cascada con su producto y con
 * su campo: no es historia de nada.
 */
@Entity(
    tableName = "product_custom_values",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CustomFieldDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["field_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["product_id", "field_id"], unique = true),
        Index(value = ["field_id"]),
    ],
)
internal data class ProductCustomValueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "field_id") val fieldId: Long,
    @ColumnInfo(name = "value_text") val valueText: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
