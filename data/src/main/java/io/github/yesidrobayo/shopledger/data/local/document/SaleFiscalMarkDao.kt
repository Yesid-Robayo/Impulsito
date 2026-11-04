package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

/**
 * Una venta activa todavía sin registrar por la persona: sin marca, o con la marca en `PENDING`.
 * [status] y [externalReference] son nulos si la venta no tiene marca.
 */
internal data class PendingFiscalSale(
    @ColumnInfo(name = "id") val saleId: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "status") val status: String?,
    @ColumnInfo(name = "external_reference") val externalReference: String?,
)

/**
 * Lecturas y escrituras de `sale_fiscal_marks`. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso. Nunca tocan `sales`: marcar una venta no es cambiarla.
 */
@Dao
internal interface SaleFiscalMarkDao {
    @Query("SELECT * FROM sale_fiscal_marks WHERE sale_id = :saleId")
    suspend fun findBySale(saleId: Long): SaleFiscalMarkEntity?

    /**
     * Las ventas activas entre [fromDate] y [toDate], fechas de negocio con los dos extremos, que
     * la persona aún no registró: primero la más nueva. Una venta sin marca también está pendiente.
     */
    @Query(
        "SELECT s.id, s.business_date, s.total, f.status, f.external_reference FROM sales s " +
            "LEFT JOIN sale_fiscal_marks f ON f.sale_id = s.id " +
            "WHERE s.status = 'ACTIVE' AND s.business_date BETWEEN :fromDate AND :toDate " +
            "AND (f.id IS NULL OR f.status = 'PENDING') " +
            "ORDER BY s.business_date DESC, s.id DESC LIMIT :limit",
    )
    suspend fun findPending(fromDate: String, toDate: String, limit: Int): List<PendingFiscalSale>

    /** Devuelve el id nuevo. Falla si la venta ya tiene marca. */
    @Insert
    suspend fun insert(mark: SaleFiscalMarkEntity): Long

    /** Cambia la marca de la venta [saleId]. Devuelve 0 si la venta no tiene marca. */
    @Query(
        "UPDATE sale_fiscal_marks SET status = :status, external_reference = :externalReference, " +
            "recorded_at = :recordedAt, updated_at = :updatedAt WHERE sale_id = :saleId",
    )
    suspend fun update(
        saleId: Long,
        status: String,
        externalReference: String?,
        recordedAt: Long?,
        updatedAt: Long,
    ): Int
}
