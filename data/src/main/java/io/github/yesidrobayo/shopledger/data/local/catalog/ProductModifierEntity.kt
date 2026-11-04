package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Un extra u opción de un producto, como una porción adicional. Al venderlo es una línea normal
 * enlazada a su línea principal: paga impuesto, tiene costo y mueve stock como cualquier otra.
 *
 * - `group_name` agrupa los extras en la pantalla, como "Salsas". Nulo: sin grupo.
 * - `sort_order` es el orden en que la persona los ve. Dos extras con el mismo orden salen por `id`.
 * - Borrar el producto borra sus extras en cascada. Un producto que es extra de otro no se borra:
 *   el otro ofrecería algo que no existe.
 */
@Entity(
    tableName = "product_modifiers",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["modifier_product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["product_id"]),
        Index(value = ["modifier_product_id"]),
    ],
)
internal data class ProductModifierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "modifier_product_id") val modifierProductId: Long,
    @ColumnInfo(name = "group_name") val groupName: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
