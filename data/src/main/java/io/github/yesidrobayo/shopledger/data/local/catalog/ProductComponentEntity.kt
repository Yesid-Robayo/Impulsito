package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Un componente de un combo o de una receta: cuánto de `component_product_id` lleva una unidad de
 * `parent_product_id`, en milésimas de la unidad base del componente.
 *
 * - El padre siempre está en modo `COMPONENTS`, y un combo tiene a lo sumo dos niveles, sin ciclos.
 *   Lo impone la guarda de la tabla, con comprobaciones explícitas, porque SQLite no admite
 *   consultas recursivas dentro de un trigger.
 * - El par de padre y componente es único: un componente que se repite va en una sola fila.
 * - Borrar el combo borra su receta en cascada. Un producto que está en una receta no se borra: el
 *   combo quedaría vendiendo algo que no existe.
 * - La receta solo dice qué se descuenta al vender. Lo que se consumió en cada venta queda
 *   congelado en `sale_line_components`, así que editarla no cambia el pasado.
 */
@Entity(
    tableName = "product_components",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["component_product_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["parent_product_id", "component_product_id"], unique = true),
        Index(value = ["component_product_id"]),
    ],
)
internal data class ProductComponentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "parent_product_id") val parentProductId: Long,
    @ColumnInfo(name = "component_product_id") val componentProductId: Long,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
