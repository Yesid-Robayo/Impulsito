package io.github.yesidrobayo.shopledger.data.local.pricing

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una lista de precios. La inicial, de clave estable `DEFAULT`, usa el precio de venta del
 * producto, nace con la base y nunca se archiva ni se borra. Una lista relativa se calcula desde
 * su lista base con un ajuste en puntos básicos, y esa cadena nunca vuelve a la misma lista.
 *
 * `normalized_name` es único solo entre las vigentes: una archivada no reserva su nombre. Por eso
 * su índice no es único, y la unicidad la impone la guarda de la tabla. La inicial no tiene nombre
 * hasta que la persona lo edite.
 *
 * Solo `Long` y `String`: el repositorio convierte `kind` al enum del dominio por su nombre, y
 * la entidad nunca sale de esta capa. Sin valores por defecto en las columnas: los pone el código.
 * La lista base usa `RESTRICT`, así que una lista con listas que dependen de ella no se borra.
 */
@Entity(
    tableName = "price_lists",
    foreignKeys = [
        ForeignKey(
            entity = PriceListEntity::class,
            parentColumns = ["id"],
            childColumns = ["base_price_list_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["system_key"], unique = true),
        Index(value = ["normalized_name"]),
        Index(value = ["base_price_list_id"]),
    ],
)
internal data class PriceListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "normalized_name") val normalizedName: String?,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "base_price_list_id") val basePriceListId: Long?,
    @ColumnInfo(name = "adjustment_bp") val adjustmentBp: Long?,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
