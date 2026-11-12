package io.github.yesidrobayo.shopledger.data.local.purchase

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `purchases`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Una compra confirmada no se edita ni se borra: la única escritura después de insertarla es la
 * anulación, que toca solo el estado y sus tres columnas. Sin `REPLACE`: un `INSERT OR REPLACE`
 * borraría la compra que choca sin pasar por la guarda de borrado.
 *
 * `ACTIVE` y `VOIDED` son los nombres guardados de `DocumentStatus`, que no cambian nunca.
 */
@Dao
internal interface PurchaseDao {
    /**
     * Las compras con fecha de negocio entre [startDate] y [endDate], las dos incluidas, de la más
     * nueva a la más antigua. En la misma fecha, la registrada después va primero.
     *
     * Las fechas son texto `YYYY-MM-DD`, cuyo orden es el de las fechas. El filtro por los dos
     * estados no descarta nada: deja que SQLite busque por el índice `(status, business_date)` en
     * vez de recorrer la tabla cada vez que la lista vuelve a emitir.
     */
    @Query(
        "SELECT * FROM purchases WHERE status IN ('ACTIVE', 'VOIDED') " +
            "AND business_date BETWEEN :startDate AND :endDate " +
            "ORDER BY business_date DESC, id DESC",
    )
    fun observeIn(startDate: String, endDate: String): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchases WHERE id = :id")
    fun observeById(id: Long): Flow<PurchaseEntity?>

    @Query("SELECT * FROM purchases WHERE id = :id")
    suspend fun findById(id: Long): PurchaseEntity?

    /** Devuelve el id nuevo. El repositorio la guarda activa y sin datos de anulación. */
    @Insert
    suspend fun insert(purchase: PurchaseEntity): Long

    /**
     * Anula una compra activa: el estado pasa a `VOIDED` con el instante, el motivo y la nota.
     * Devuelve 0 si no existe o ya estaba anulada, porque un documento se anula una sola vez, y no
     * toca ninguna otra columna.
     */
    @Query(
        "UPDATE purchases SET status = 'VOIDED', voided_at = :voidedAt, " +
            "void_reason = :voidReason, void_note = :voidNote " +
            "WHERE id = :id AND status = 'ACTIVE'",
    )
    suspend fun markVoided(id: Long, voidedAt: Long, voidReason: String, voidNote: String?): Int

    /**
     * Un tramo del historial, de la más nueva a la más antigua por `(business_date, id)`, después de
     * `(afterDate, afterId)` o desde el principio con los dos nulos. Cada filtro nulo no filtra:
     * [supplierMode] 0 es cualquier proveedor, 1 sin proveedor y 2 el de [supplierId]. Las compras
     * son pocas frente a las ventas, así que el orden por fecha basta sin un índice propio.
     */
    @Query(PURCHASE_HISTORY_PAGE_SQL)
    suspend fun historyPage(
        fromDate: String?,
        toDate: String?,
        supplierMode: Int,
        supplierId: Long?,
        onlyVoided: Boolean,
        afterDate: String?,
        afterId: Long?,
        limit: Int,
    ): List<PurchaseHistoryRowEntity>

    /** Lo comprado en cada una de [dates], solo de compras activas, con el mismo filtro. */
    @Query(
        "SELECT business_date, SUM(total) AS total FROM purchases p WHERE status = 'ACTIVE' " +
            "AND business_date IN (:dates) " + PURCHASE_SUPPLIER_FILTER +
            "GROUP BY business_date",
    )
    suspend fun dayTotals(
        dates: List<String>,
        supplierMode: Int,
        supplierId: Long?,
    ): List<PurchaseDayTotal>

    /** Cambia con cada compra nueva y con cada anulación: el historial se vuelve a leer. */
    @Query(
        "SELECT COALESCE(MAX(id), 0) || ':' || " +
            "(SELECT COUNT(*) FROM purchases WHERE status = 'VOIDED') FROM purchases",
    )
    fun observeHistoryMark(): Flow<String>
}

/** Una fila del historial, con su proveedor de hoy y cuántas líneas tiene. */
internal data class PurchaseHistoryRowEntity(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "invoice_reference") val invoiceReference: String?,
    @ColumnInfo(name = "supplier_name") val supplierName: String?,
    @ColumnInfo(name = "line_count") val lineCount: Int,
)

/** Lo comprado en un día. */
internal data class PurchaseDayTotal(
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "total") val total: Long,
)

/** El filtro de proveedor de [PurchaseDao.historyPage] y [PurchaseDao.dayTotals]. */
private const val PURCHASE_SUPPLIER_FILTER =
    "AND (:supplierMode = 0 OR (:supplierMode = 1 AND p.supplier_id IS NULL) " +
        "OR (:supplierMode = 2 AND p.supplier_id = :supplierId)) "

/**
 * La consulta de [PurchaseDao.historyPage]. El filtro por los dos estados no descarta nada: deja
 * que SQLite busque por el índice `(status, business_date)`.
 */
internal const val PURCHASE_HISTORY_PAGE_SQL =
    "SELECT p.id, p.occurred_at, p.business_date, p.total, p.status, p.invoice_reference, " +
        "(SELECT s.name FROM suppliers s WHERE s.id = p.supplier_id) AS supplier_name, " +
        "(SELECT COUNT(*) FROM purchase_lines l WHERE l.purchase_id = p.id) AS line_count " +
        "FROM purchases p WHERE p.status IN ('ACTIVE', 'VOIDED') " +
        "AND (:onlyVoided = 0 OR p.status = 'VOIDED') " +
        "AND (:fromDate IS NULL OR p.business_date >= :fromDate) " +
        "AND (:toDate IS NULL OR p.business_date <= :toDate) " +
        PURCHASE_SUPPLIER_FILTER +
        "AND (:afterDate IS NULL OR p.business_date < :afterDate " +
        "OR (p.business_date = :afterDate AND p.id < :afterId)) " +
        "ORDER BY p.business_date DESC, p.id DESC LIMIT :limit"
