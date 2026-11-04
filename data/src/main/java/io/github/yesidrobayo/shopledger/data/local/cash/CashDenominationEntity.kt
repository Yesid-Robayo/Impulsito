package io.github.yesidrobayo.shopledger.data.local.cash

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Un billete o una moneda que la persona escribe para contar la caja. **No hay** denominaciones
 * iniciales: cuáles existen depende de la moneda de cada negocio, y la app no supone ninguna.
 *
 * - `value` es su valor en la unidad menor de la moneda, mayor que cero.
 * - `is_active` es 1 si se ofrece al contar. Una que ya se usó en un conteo no se borra, por su
 *   llave: se desactiva.
 * - `sort_order` es el orden en que se muestran, que elige la persona.
 */
@Entity(tableName = "cash_denominations")
internal data class CashDenominationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "value") val value: Long,
    @ColumnInfo(name = "is_active") val isActive: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
