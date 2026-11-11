package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Las líneas de venta: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una línea, y no debe haberla: la línea guarda el precio y el
 * costo con que se vendió, y explica las filas que su venta dejó en el libro de stock, también
 * después de anularla. La inserción no usa `REPLACE`, que borraría la línea que choca. Las líneas
 * salen en el orden en que se guardaron, por `id`: un extra siempre después de su línea principal.
 */
@Dao
internal interface SaleLineDao {
    @Query("SELECT * FROM sale_lines WHERE sale_id = :saleId ORDER BY id")
    fun observeBySale(saleId: Long): Flow<List<SaleLineEntity>>

    @Query("SELECT * FROM sale_lines WHERE sale_id = :saleId ORDER BY id")
    suspend fun findBySale(saleId: Long): List<SaleLineEntity>

    /** Agrega la línea y devuelve su id. Solo se llama dentro de la transacción del caso de uso. */
    @Insert
    suspend fun insert(line: SaleLineEntity): Long

    /**
     * Cuántas líneas tiene cada producto en ventas activas con fecha de negocio desde [sinceDate],
     * incluida, como texto `YYYY-MM-DD`. Busca las ventas por el índice `(status, business_date)` y
     * sus líneas por `(sale_id, product_id)`, sin recorrer todo el historial.
     */
    @Query(PRODUCT_LINE_COUNTS_SQL)
    fun observeLineCountsSince(sinceDate: String): Flow<List<ProductLineCount>>
}

/** Las líneas de un producto en un período: lo que ordena los más vendidos. */
internal data class ProductLineCount(
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "line_count") val lineCount: Int,
)

/**
 * La consulta de [SaleLineDao.observeLineCountsSince]. Vive aparte para que la prueba del plan de
 * consulta mire la misma sentencia que corre la app.
 */
internal const val PRODUCT_LINE_COUNTS_SQL =
    "SELECT l.product_id AS product_id, COUNT(*) AS line_count FROM sales s " +
        "JOIN sale_lines l ON l.sale_id = s.id " +
        "WHERE s.status = 'ACTIVE' AND s.business_date >= :sinceDate " +
        "GROUP BY l.product_id"
