package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerEntity
import io.github.yesidrobayo.shopledger.data.local.pricing.PriceListEntity

/**
 * Una venta en espera: lo que la persona dejó armado para confirmar después, como el pedido de una
 * mesa. No es un hecho del negocio: no toca stock, libro, dinero ni reportes, no reserva nada y se
 * borra libremente. Por eso tampoco cuenta como cambio sin respaldar, aunque viaja en la copia.
 *
 * - `label` es el nombre opcional que la persona le pone, como una mesa.
 * - Al borrar la lista de precios, la espera queda con la lista de siempre, y al borrar el cliente,
 *   sin cliente: las dos se ponen en nulo.
 * - Borrar la espera borra sus líneas en cascada.
 */
@Entity(
    tableName = "sale_drafts",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = PriceListEntity::class,
            parentColumns = ["id"],
            childColumns = ["price_list_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["customer_id"]),
        Index(value = ["price_list_id"]),
    ],
)
internal data class SaleDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "customer_id") val customerId: Long?,
    @ColumnInfo(name = "price_list_id") val priceListId: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
