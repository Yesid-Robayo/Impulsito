package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una ubicación del stock: la inicial, de clave `MAIN`, o una que creó la persona, como un depósito
 * o un puesto de feria. Toda fila del libro, toda compra y toda venta llevan la suya desde el
 * principio, aunque la persona tenga una sola y nunca la vea.
 *
 * - `system_key` es la clave estable, en inglés, de la ubicación inicial. Mientras `name` es nulo se
 *   muestra el texto traducido de esa clave, en el idioma de la interfaz. Las que crea la persona no
 *   tienen clave y siempre tienen nombre. Por eso `name` y `normalized_name` admiten nulo, y siempre
 *   van juntos.
 * - `normalized_name` es único solo entre las vigentes: una archivada no reserva su nombre. Por
 *   eso su índice no es único, y la unicidad la impone la guarda de la tabla. La inicial sin nombre
 *   no choca con nadie.
 * - `kind` es el nombre de un `LocationKind`.
 * - La de clave `MAIN` nunca se archiva ni se borra: siempre hay dónde anotar el stock. Las demás
 *   se borran solo sin filas en el libro, saldos, compras ni ventas, porque esas llaves son
 *   `RESTRICT`.
 *
 * Solo `Long` y `String`, sin valores por defecto en las columnas: los pone el código.
 */
@Entity(
    tableName = "locations",
    indices = [
        Index(value = ["system_key"], unique = true),
        Index(value = ["normalized_name"]),
    ],
)
internal data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "normalized_name") val normalizedName: String?,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
