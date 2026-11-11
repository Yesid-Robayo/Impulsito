package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `sales`. Las escrituras solo se llaman dentro de la transacción del caso
 * de uso.
 *
 * Una venta confirmada no se edita ni se borra: la única escritura después de insertarla es la
 * anulación, que toca solo el estado y sus tres columnas. Sin `REPLACE`: un `INSERT OR REPLACE`
 * borraría la venta que choca sin pasar por la guarda de borrado.
 *
 * `ACTIVE` y `VOIDED` son los nombres guardados de `DocumentStatus`, que no cambian nunca.
 */
@Dao
internal interface SaleDao {
    /**
     * Las ventas con fecha de negocio entre [startDate] y [endDate], las dos incluidas, de la más
     * nueva a la más antigua. En la misma fecha, la registrada después va primero.
     *
     * Las fechas son texto `YYYY-MM-DD`, cuyo orden es el de las fechas. El filtro por los dos
     * estados no descarta nada: deja que SQLite busque por el índice `(status, business_date)` en
     * vez de recorrer años de ventas cada vez que la lista vuelve a emitir.
     */
    @Query(SALES_IN_PERIOD_SQL)
    fun observeIn(startDate: String, endDate: String): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE id = :id")
    fun observeById(id: Long): Flow<SaleEntity?>

    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun findById(id: Long): SaleEntity?

    /**
     * Devuelve el id nuevo, que también es el número de la venta. El repositorio la guarda activa y
     * sin datos de anulación.
     */
    @Insert
    suspend fun insert(sale: SaleEntity): Long

    /**
     * Anula una venta activa: el estado pasa a `VOIDED` con el instante, el motivo y la nota.
     * Devuelve 0 si no existe o ya estaba anulada, porque un documento se anula una sola vez, y no
     * toca ninguna otra columna.
     */
    @Query(
        "UPDATE sales SET status = 'VOIDED', voided_at = :voidedAt, " +
            "void_reason = :voidReason, void_note = :voidNote " +
            "WHERE id = :id AND status = 'ACTIVE'",
    )
    suspend fun markVoided(id: Long, voidedAt: Long, voidReason: String, voidNote: String?): Int

    /**
     * Las sumas de impuesto de los documentos activos con fecha de negocio entre [startDate] y
     * [endDate], las dos incluidas: ventas, devoluciones de clientes y compras. Vuelve a emitir
     * con cada cambio en cualquiera de las tres tablas.
     *
     * Suma la cabecera de cada documento, que ya es la suma de sus líneas. Cada subconsulta filtra
     * por `(status, business_date)`, que es su índice.
     */
    @Query(TAX_TOTALS_SQL)
    fun observeTaxTotals(startDate: String, endDate: String): Flow<TaxTotalsRow>

    /**
     * Un tramo del historial, de la más nueva a la más antigua por `(occurred_at, id)`, después de
     * `(afterAt, afterId)` o desde el principio con los dos nulos. Cada filtro nulo no filtra.
     * Recorre las ventas por el índice `(occurred_at, id)` y se detiene al juntar [limit].
     */
    @Query(SALE_HISTORY_PAGE_SQL)
    suspend fun historyPage(
        fromDate: String?,
        toDate: String?,
        methodId: Long?,
        onlyVoided: Boolean,
        afterAt: Long?,
        afterId: Long?,
        limit: Int,
    ): List<SaleHistoryRowEntity>

    /**
     * Un tramo del historial de las ventas con algún pago de [methodId], en el mismo orden y con los
     * mismos filtros que [historyPage]. Parte de los pagos de ese método, por su índice, y ordena
     * solo sus ventas: sirve para un método con pocas ventas, que [historyPage] encontraría
     * recorriendo años de ventas de otros métodos.
     */
    @Query(SALE_HISTORY_METHOD_PAGE_SQL)
    suspend fun historyPageOfMethod(
        methodId: Long,
        fromDate: String?,
        toDate: String?,
        onlyVoided: Boolean,
        afterAt: Long?,
        afterId: Long?,
        limit: Int,
    ): List<SaleHistoryRowEntity>

    /**
     * Cuántos pagos tiene [methodId], contados por su índice hasta [cap]: dice si el método tiene
     * pocas ventas sin recorrer las de un método muy usado.
     */
    @Query(
        "SELECT COUNT(*) FROM (SELECT 1 FROM sale_payments WHERE payment_method_id = :methodId " +
            "LIMIT :cap)",
    )
    suspend fun paymentCountUpTo(methodId: Long, cap: Int): Int

    /**
     * Los métodos de [methodIds] con al menos un pago, de menor a mayor id. Una búsqueda por
     * método en el índice de los pagos.
     */
    @Query(
        "SELECT m.id FROM payment_methods m WHERE m.id IN (:methodIds) " +
            "AND EXISTS (SELECT 1 FROM sale_payments p WHERE p.payment_method_id = m.id) " +
            "ORDER BY m.id",
    )
    suspend fun methodsWithPayments(methodIds: List<Long>): List<Long>

    /** Lo vendido en cada una de [dates], solo de ventas activas, con el mismo filtro de método. */
    @Query(
        "SELECT business_date, SUM(total) AS total FROM sales s WHERE status = 'ACTIVE' " +
            "AND business_date IN (:dates) " +
            "AND (:methodId IS NULL OR EXISTS (SELECT 1 FROM sale_payments p " +
            "WHERE p.sale_id = s.id AND p.payment_method_id = :methodId)) " +
            "GROUP BY business_date",
    )
    suspend fun dayTotals(dates: List<String>, methodId: Long?): List<SaleDayTotal>

    /** Cambia con cada venta nueva y con cada anulación: el historial se vuelve a leer. */
    @Query(
        "SELECT COALESCE(MAX(id), 0) || ':' || " +
            "(SELECT COUNT(*) FROM sales WHERE status = 'VOIDED') FROM sales",
    )
    fun observeHistoryMark(): Flow<String>
}

/** Las tres sumas de [SaleDao.observeTaxTotals], en unidad menor. */
internal data class TaxTotalsRow(
    @ColumnInfo(name = "sales_tax") val salesTax: Long,
    @ColumnInfo(name = "returns_tax_refund") val returnsTaxRefund: Long,
    @ColumnInfo(name = "purchases_tax") val purchasesTax: Long,
)

/**
 * La consulta de [SaleDao.observeTaxTotals]. Vive aparte para que la prueba del plan de consulta
 * mire la misma sentencia que corre la app.
 */
internal const val TAX_TOTALS_SQL =
    "SELECT " +
        "(SELECT COALESCE(SUM(tax_total), 0) FROM sales WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS sales_tax, " +
        "(SELECT COALESCE(SUM(tax_refund), 0) FROM sale_returns WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS returns_tax_refund, " +
        "(SELECT COALESCE(SUM(tax_total), 0) FROM purchases WHERE status = 'ACTIVE' " +
        "AND business_date BETWEEN :startDate AND :endDate) AS purchases_tax"

/** Una fila del historial, con lo justo de sus líneas y sus pagos. */
internal data class SaleHistoryRowEntity(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "total") val total: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "first_product") val firstProduct: String?,
    @ColumnInfo(name = "line_count") val lineCount: Int,
    @ColumnInfo(name = "method_name") val methodName: String?,
    @ColumnInfo(name = "payment_count") val paymentCount: Int,
)

/** Lo vendido en un día. */
internal data class SaleDayTotal(
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "total") val total: Long,
)

/** Las columnas de una fila del historial, iguales en las dos formas de leer un tramo. */
private const val SALE_HISTORY_COLUMNS =
    "SELECT s.id, s.occurred_at, s.business_date, s.total, s.status, " +
        "(SELECT l.product_name_snapshot FROM sale_lines l WHERE l.sale_id = s.id " +
        "AND l.parent_line_id IS NULL ORDER BY l.id LIMIT 1) AS first_product, " +
        "(SELECT COUNT(*) FROM sale_lines l WHERE l.sale_id = s.id " +
        "AND l.parent_line_id IS NULL) AS line_count, " +
        "(SELECT p.method_name_snapshot FROM sale_payments p WHERE p.sale_id = s.id " +
        "ORDER BY p.id LIMIT 1) AS method_name, " +
        "(SELECT COUNT(*) FROM sale_payments p WHERE p.sale_id = s.id) AS payment_count "

/** Después del cursor, de la más nueva a la más antigua, hasta llenar el tramo. */
private const val SALE_HISTORY_AFTER_CURSOR =
    "AND (:afterAt IS NULL OR s.occurred_at < :afterAt " +
        "OR (s.occurred_at = :afterAt AND s.id < :afterId)) " +
        "ORDER BY s.occurred_at DESC, s.id DESC LIMIT :limit"

/**
 * La consulta de [SaleDao.historyPage]. Vive aparte para que la prueba del plan de consulta mire la
 * misma sentencia que corre la app. Las líneas que cuenta son las principales, sin extras.
 */
internal const val SALE_HISTORY_PAGE_SQL =
    SALE_HISTORY_COLUMNS +
        "FROM sales s INDEXED BY index_sales_occurred_at_id " +
        "WHERE (:onlyVoided = 0 OR s.status = 'VOIDED') " +
        "AND (:fromDate IS NULL OR s.business_date >= :fromDate) " +
        "AND (:toDate IS NULL OR s.business_date <= :toDate) " +
        "AND (:methodId IS NULL OR EXISTS (SELECT 1 FROM sale_payments p " +
        "WHERE p.sale_id = s.id AND p.payment_method_id = :methodId)) " +
        SALE_HISTORY_AFTER_CURSOR

/**
 * La consulta de [SaleDao.historyPageOfMethod]. Las ventas del método salen de su índice en
 * `sale_payments`, sin repetir la venta que lo usó dos veces, y `CROSS JOIN` obliga a SQLite a
 * empezar por ellas en vez de recorrer todas las ventas.
 */
internal const val SALE_HISTORY_METHOD_PAGE_SQL =
    SALE_HISTORY_COLUMNS +
        "FROM (SELECT DISTINCT sale_id FROM sale_payments WHERE payment_method_id = :methodId) m " +
        "CROSS JOIN sales s " +
        "WHERE s.id = m.sale_id " +
        "AND (:onlyVoided = 0 OR s.status = 'VOIDED') " +
        "AND (:fromDate IS NULL OR s.business_date >= :fromDate) " +
        "AND (:toDate IS NULL OR s.business_date <= :toDate) " +
        SALE_HISTORY_AFTER_CURSOR

/**
 * La consulta de [SaleDao.observeIn]. Vive aparte para que la prueba del plan de consulta mire la
 * misma sentencia que corre la app, y no una copia que podría apartarse de ella.
 */
internal const val SALES_IN_PERIOD_SQL =
    "SELECT * FROM sales WHERE status IN ('ACTIVE', 'VOIDED') " +
        "AND business_date BETWEEN :startDate AND :endDate " +
        "ORDER BY business_date DESC, id DESC"
