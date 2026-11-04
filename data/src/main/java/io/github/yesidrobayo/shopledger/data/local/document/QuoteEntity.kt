package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity

/**
 * Una cotización: precios ofrecidos a un cliente, sin efecto sobre el stock ni sobre ningún libro.
 *
 * - `status` es el nombre guardado de `QuoteStatus`. Nace `OPEN` y pasa una sola vez a `CONVERTED`,
 *   con la venta que salió de ella, o a `CANCELLED`. Lo exige la guarda.
 * - Dinero en unidad menor de la moneda del negocio. `total` es neto más impuesto.
 * - Una cotización convertida no se borra: la venta la apunta con `quote_id`, con `RESTRICT`. Su
 *   propia llave hacia la venta usa `SET NULL`, porque dos llaves `RESTRICT` en ciclo rompen la
 *   compilación de Room. Una venta nunca se borra, así que nunca llega a ponerla en nulo.
 * - El cliente usa `RESTRICT`: un cliente con cotizaciones no se borra, se archiva.
 */
@Entity(
    tableName = "quotes",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customer_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["converted_sale_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["customer_id"]),
        Index(value = ["status", "business_date"]),
        Index(value = ["converted_sale_id"]),
    ],
)
internal data class QuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "customer_id") val customerId: Long?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "valid_until") val validUntil: String?,
    @ColumnInfo(name = "net_total") val netTotal: Long,
    @ColumnInfo(name = "tax_total") val taxTotal: Long,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "converted_sale_id") val convertedSaleId: Long?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
