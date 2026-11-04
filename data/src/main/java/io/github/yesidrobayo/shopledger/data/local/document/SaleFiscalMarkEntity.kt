package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity

/**
 * La marca que la persona pone a una venta según la haya registrado o no en el sistema de su
 * autoridad, por su cuenta. La app no emite nada ni habla con ninguna autoridad: solo recuerda lo
 * que la persona anotó.
 *
 * Vive en su propia tabla, y no en `sales`, porque una venta confirmada solo cambia al anularse: su
 * guarda de anulación no admite otro cambio, y marcarla no debe pasar por ella.
 *
 * - `status` es el nombre guardado de `FiscalMarkStatus`. `RECORDED` exige `recorded_at`.
 * - `external_reference` es el número que la persona copió, tal cual.
 * - Una venta tiene a lo sumo una marca: el índice de `sale_id` es único, y la guarda no deja
 *   moverla a otra venta. Sin marca, la venta está pendiente. La venta usa `RESTRICT`.
 */
@Entity(
    tableName = "sale_fiscal_marks",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["sale_id"], unique = true)],
)
internal data class SaleFiscalMarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sale_id") val saleId: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "external_reference") val externalReference: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
