package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * El libro de stock: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una fila, y no debe haberla en ningún DAO: el saldo, el costo
 * y el valor de cada producto son la suma de sus filas, y una fila cambiada los dejaría mal sin que
 * nada lo delatara. La inserción no usa `REPLACE`, que borraría la fila que choca y escribiría otra
 * en su lugar.
 *
 * Todo sale en el orden del libro, por `id`. [insert] solo se llama dentro de la transacción del
 * caso de uso, la misma en que el producto queda con el saldo, el costo y el valor que dice la
 * fila: así esas copias nunca se apartan del libro.
 */
@Dao
internal interface StockMovementDao {
    @Query("SELECT * FROM stock_movements WHERE product_id = :productId ORDER BY id")
    fun observeByProduct(productId: Long): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movements WHERE id = :id")
    suspend fun findById(id: Long): StockMovementEntity?

    /** Las filas de un producto en el orden del libro: lo que reproduce su costo. */
    @Query("SELECT * FROM stock_movements WHERE product_id = :productId ORDER BY id")
    suspend fun findByProduct(productId: Long): List<StockMovementEntity>

    /**
     * Las filas que salieron de las líneas de una venta, con sus compensatorios. También las de los
     * componentes de un combo, que llevan la línea del combo.
     */
    @Query(
        "SELECT * FROM stock_movements WHERE sale_line_id IN " +
            "(SELECT id FROM sale_lines WHERE sale_id = :saleId) ORDER BY id",
    )
    suspend fun findBySale(saleId: Long): List<StockMovementEntity>

    /** Las filas que salieron de las líneas de una compra, con sus compensatorios. */
    @Query(
        "SELECT * FROM stock_movements WHERE purchase_line_id IN " +
            "(SELECT id FROM purchase_lines WHERE purchase_id = :purchaseId) ORDER BY id",
    )
    suspend fun findByPurchase(purchaseId: Long): List<StockMovementEntity>

    /** Las filas de una producción, con sus compensatorios: la llevan todas en su columna. */
    @Query(
        "SELECT * FROM stock_movements WHERE production_batch_id = :productionBatchId ORDER BY id",
    )
    suspend fun findByProductionBatch(productionBatchId: Long): List<StockMovementEntity>

    /** Las filas que salieron de las líneas de una devolución a proveedor, con sus compensatorios. */
    @Query(
        "SELECT * FROM stock_movements WHERE purchase_return_line_id IN " +
            "(SELECT id FROM purchase_return_lines " +
            "WHERE purchase_return_id = :purchaseReturnId) ORDER BY id",
    )
    suspend fun findByPurchaseReturn(purchaseReturnId: Long): List<StockMovementEntity>

    /** Las filas que entraron por las líneas de una devolución de venta, y sus compensatorios. */
    @Query(
        "SELECT * FROM stock_movements WHERE sale_return_line_id IN " +
            "(SELECT id FROM sale_return_lines WHERE sale_return_id = :saleReturnId) ORDER BY id",
    )
    suspend fun findBySaleReturn(saleReturnId: Long): List<StockMovementEntity>

    /** Si alguna fila ya anula a [movementId]: una fila se anula una sola vez. */
    @Query("SELECT EXISTS (SELECT 1 FROM stock_movements WHERE reverses_movement_id = :movementId)")
    suspend fun isCompensated(movementId: Long): Boolean

    /** Agrega la fila y devuelve su id, que fija su lugar en el orden del libro. */
    @Insert
    suspend fun insert(movement: StockMovementEntity): Long

    /**
     * La venta o la compra de la que salió cada fila de un producto, por la línea que guarda la
     * fila. Las filas que no salieron de una no aparecen.
     */
    @Query(
        "SELECT sm.id AS movement_id, sl.sale_id AS sale_id, pl.purchase_id AS purchase_id " +
            "FROM stock_movements sm " +
            "LEFT JOIN sale_lines sl ON sl.id = sm.sale_line_id " +
            "LEFT JOIN purchase_lines pl ON pl.id = sm.purchase_line_id " +
            "WHERE sm.product_id = :productId " +
            "AND (sm.sale_line_id IS NOT NULL OR sm.purchase_line_id IS NOT NULL) ORDER BY sm.id",
    )
    fun observeDocuments(productId: Long): Flow<List<MovementDocumentRow>>

    /**
     * Las milésimas que salieron de cada producto en filas `SALE` sin compensar, desde la fecha
     * de negocio [sinceDate], incluida, como texto `YYYY-MM-DD`, y solo de [locationId] si no es
     * nulo.
     * Busca por el índice `(type, business_date)` y mira la compensación por el índice único de
     * `reverses_movement_id`, sin recorrer todo el libro.
     */
    @Query(SALE_OUTFLOW_SQL)
    fun observeSaleOutflow(sinceDate: String, locationId: Long?): Flow<List<ProductQuantityOut>>
}

/** Lo que salió de un producto por ventas en un período: lo que mide su velocidad. */
internal data class ProductQuantityOut(
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "quantity_out_milli") val quantityOutMilli: Long,
)

/**
 * La consulta de [StockMovementDao.observeSaleOutflow]. Vive aparte para que la prueba del plan de
 * consulta mire la misma sentencia que corre la app.
 */
internal const val SALE_OUTFLOW_SQL =
    "SELECT sm.product_id AS product_id, -SUM(sm.quantity_milli) AS quantity_out_milli " +
        "FROM stock_movements sm " +
        "WHERE sm.type = 'SALE' AND sm.business_date >= :sinceDate " +
        "AND (:locationId IS NULL OR sm.location_id = :locationId) " +
        "AND NOT EXISTS (SELECT 1 FROM stock_movements r WHERE r.reverses_movement_id = sm.id) " +
        "GROUP BY sm.product_id ORDER BY sm.product_id"

/** El documento de una fila del libro, tal como lo lee [StockMovementDao.observeDocuments]. */
internal data class MovementDocumentRow(
    @ColumnInfo(name = "movement_id") val movementId: Long,
    @ColumnInfo(name = "sale_id") val saleId: Long?,
    @ColumnInfo(name = "purchase_id") val purchaseId: Long?,
)
