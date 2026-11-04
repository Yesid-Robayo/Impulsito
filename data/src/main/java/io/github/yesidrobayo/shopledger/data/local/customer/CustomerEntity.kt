package io.github.yesidrobayo.shopledger.data.local.customer

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListEntity

/**
 * Un cliente, con lo mínimo: solo el nombre es obligatorio. No hay documento de identidad ni otra
 * columna que lo pida, y teléfono, correo y notas son opcionales. El nombre no es único, porque dos
 * personas pueden llamarse igual: su índice solo busca y ordena.
 *
 * Con historia ya no se borra: se archiva con `archived_at`. Anonimizar fija `anonymized_at` y
 * deja en nulo el nombre, el nombre normalizado, el teléfono, el correo y las notas, que son sus
 * datos personales. Su libro, sus montos y sus notas no cambian, y la anonimización no se deshace.
 * Por eso `name` solo puede ser nulo en un cliente anonimizado, como exige su guarda.
 *
 * - `balance` es lo que debe, en unidad menor: positivo debe y negativo es saldo a favor.
 * - `points_balance` son sus puntos. No son dinero: no se cambian por efectivo ni cuentan como
 *   saldo a favor. Puede quedar negativo si se anula una venta cuyos puntos ya se canjearon.
 * - Los dos son saldos derivados de su libro: los escribe solo la transacción que agrega la fila,
 *   con el `balance_after` de esa fila.
 * - La lista de precios usa `SET NULL`: borrarla deja al cliente con la lista de siempre.
 */
@Entity(
    tableName = "customers",
    foreignKeys = [
        ForeignKey(
            entity = PriceListEntity::class,
            parentColumns = ["id"],
            childColumns = ["price_list_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["normalized_name"]),
        Index(value = ["price_list_id"]),
    ],
)
internal data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "normalized_name") val normalizedName: String?,
    @ColumnInfo(name = "phone") val phone: String?,
    @ColumnInfo(name = "email") val email: String?,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "price_list_id") val priceListId: Long?,
    @ColumnInfo(name = "balance") val balance: Long,
    @ColumnInfo(name = "points_balance") val pointsBalance: Long,
    @ColumnInfo(name = "anonymized_at") val anonymizedAt: Long?,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
