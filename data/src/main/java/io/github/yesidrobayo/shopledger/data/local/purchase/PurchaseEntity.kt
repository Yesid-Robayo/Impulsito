package io.github.yesidrobayo.shopledger.data.local.purchase

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierEntity

/**
 * La cabecera de una compra. Una compra confirmada no se edita ni se borra: solo se anula, y la
 * anulación es lo único que cambia en la fila.
 *
 * Dinero en unidad menor de la moneda del negocio, siempre `Long`. Los totales se guardan para que
 * un reporte por período no tenga que leer las líneas.
 *
 * - `amount_paid` es lo pagado al registrarla y no cambia: lo que se pague después va al libro del
 *   proveedor.
 * - `status` nace `ACTIVE` y solo pasa a `VOIDED`, con `voided_at` y `void_reason`.
 * - `fx_currency_code` y `fx_rate_micro` solo dejan constancia de la moneda en que se compró: los
 *   montos siempre van en la del negocio.
 * - El proveedor usa `RESTRICT`: un proveedor con compras no se borra, se archiva.
 * - `money_account_id` y `location_id` todavía no tienen llave foránea: sus tablas no existen aún.
 *   La llave llega con la tabla a la que apunta, dentro del mismo esquema 1.
 */
@Entity(
    tableName = "purchases",
    foreignKeys = [
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplier_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["supplier_id"]),
        Index(value = ["status", "business_date"]),
        Index(value = ["due_date"]),
    ],
)
internal data class PurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "supplier_id") val supplierId: Long?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "invoice_reference") val invoiceReference: String?,
    @ColumnInfo(name = "net_total") val netTotal: Long,
    @ColumnInfo(name = "tax_total") val taxTotal: Long,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "cost_total") val costTotal: Long,
    @ColumnInfo(name = "amount_paid") val amountPaid: Long,
    @ColumnInfo(name = "money_account_id") val moneyAccountId: Long?,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "location_id") val locationId: Long,
    @ColumnInfo(name = "fx_currency_code") val fxCurrencyCode: String?,
    @ColumnInfo(name = "fx_rate_micro") val fxRateMicro: Long?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "voided_at") val voidedAt: Long?,
    @ColumnInfo(name = "void_reason") val voidReason: String?,
    @ColumnInfo(name = "void_note") val voidNote: String?,
)
