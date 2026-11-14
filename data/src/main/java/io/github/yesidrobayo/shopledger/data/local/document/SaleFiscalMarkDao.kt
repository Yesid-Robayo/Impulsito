package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

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
 * Una venta activa de la lista de pendientes de facturar, con su marca, el nombre de su cliente y
 * si tiene una devolución activa. [status] y [externalReference] son nulos si la venta no tiene
 * marca. No lee costo, margen ni ganancia.
 */
internal data class FiscalSaleRow(
    @ColumnInfo(name = "id") val saleId: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "customer_name") val customerName: String?,
    @ColumnInfo(name = "status") val status: String?,
    @ColumnInfo(name = "external_reference") val externalReference: String?,
    @ColumnInfo(name = "has_return") val hasReturn: Long,
)

/**
 * Lecturas y escrituras de `sale_fiscal_marks`. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso. Nunca tocan `sales`: marcar una venta no es cambiarla.
 */
@Dao
internal interface SaleFiscalMarkDao {
    @Query("SELECT * FROM sale_fiscal_marks WHERE sale_id = :saleId")
    suspend fun findBySale(saleId: Long): SaleFiscalMarkEntity?

    @Query("SELECT * FROM sale_fiscal_marks WHERE sale_id = :saleId")
    fun observeBySale(saleId: Long): Flow<SaleFiscalMarkEntity?>

    /** Si alguna venta tiene marca: sin ninguna, la pantalla muestra su primer uso. */
    @Query("SELECT EXISTS (SELECT 1 FROM sale_fiscal_marks)")
    fun observeAny(): Flow<Boolean>

    /** El estado guardado de la venta [saleId], o nulo si no existe. */
    @Query("SELECT status FROM sales WHERE id = :saleId")
    suspend fun findSaleStatus(saleId: Long): String?

    /**
     * Las ventas activas entre [fromDate] y [toDate], fechas de negocio con los dos extremos, cuya
     * marca es [status], de la más nueva a la más antigua. Una venta sin marca cuenta como
     * `PENDING`. Sin límite: la pantalla lee un mes a la vez.
     */
    @Query(
        "SELECT s.id, s.business_date, s.total, c.name AS customer_name, f.status, " +
            "f.external_reference, EXISTS (SELECT 1 FROM sale_returns r " +
            "WHERE r.sale_id = s.id AND r.status = 'ACTIVE') AS has_return FROM sales s " +
            "LEFT JOIN sale_fiscal_marks f ON f.sale_id = s.id " +
            "LEFT JOIN customers c ON c.id = s.customer_id " +
            "WHERE s.status = 'ACTIVE' AND s.business_date BETWEEN :fromDate AND :toDate " +
            "AND IFNULL(f.status, 'PENDING') = :status " +
            "ORDER BY s.business_date DESC, s.id DESC",
    )
    fun observeSales(fromDate: String, toDate: String, status: String): Flow<List<FiscalSaleRow>>

    /** La venta activa [saleId] con su marca, o nula si no existe o está anulada. */
    @Query(
        "SELECT s.id, s.business_date, s.total, c.name AS customer_name, f.status, " +
            "f.external_reference, EXISTS (SELECT 1 FROM sale_returns r " +
            "WHERE r.sale_id = s.id AND r.status = 'ACTIVE') AS has_return FROM sales s " +
            "LEFT JOIN sale_fiscal_marks f ON f.sale_id = s.id " +
            "LEFT JOIN customers c ON c.id = s.customer_id " +
            "WHERE s.id = :saleId AND s.status = 'ACTIVE'",
    )
    fun observeSale(saleId: Long): Flow<FiscalSaleRow?>

    /**
     * Las ventas activas entre [fromDate] y [toDate], fechas de negocio con los dos extremos, que
     * la persona aún no registró: primero la más nueva. Una venta sin marca también está
     * pendiente.
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
