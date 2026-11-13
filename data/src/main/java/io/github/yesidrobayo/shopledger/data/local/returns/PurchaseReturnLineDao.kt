package io.github.yesidrobayo.shopledger.data.local.returns

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Las líneas de las devoluciones a proveedor: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una línea, y no debe haberla: explica las filas que su
 * devolución dejó en el libro de stock, también después de anularla. La inserción no usa `REPLACE`,
 * que borraría la línea que choca. Salen por `id`, en el orden en que se guardaron.
 */
@Dao
internal interface PurchaseReturnLineDao {
    @Query(
        "SELECT * FROM purchase_return_lines WHERE purchase_return_id = :purchaseReturnId " +
            "ORDER BY id",
    )
    suspend fun findByReturn(purchaseReturnId: Long): List<PurchaseReturnLineEntity>

    @Query(
        "SELECT * FROM purchase_return_lines WHERE purchase_return_id = :purchaseReturnId " +
            "ORDER BY id",
    )
    fun observeByReturn(purchaseReturnId: Long): Flow<List<PurchaseReturnLineEntity>>

    /**
     * Lo que ya se llevaron de cada línea de la compra [purchaseId] sus devoluciones activas: la
     * cantidad, lo que devolvió el proveedor y el costo con que salió del stock, que guarda su fila
     * del libro. Una devolución anulada no cuenta, y una línea sin línea de compra tampoco.
     */
    @Query(TAKEN_BY_PURCHASE)
    suspend fun takenByPurchase(purchaseId: Long): List<TakenFromPurchaseLine>

    /** Agrega la línea y devuelve su id. Solo se llama dentro de la transacción de la devolución. */
    @Insert
    suspend fun insert(line: PurchaseReturnLineEntity): Long
}

/**
 * Lo que se llevaron de la línea de compra [purchaseLineId] sus devoluciones activas: [quantityMilli]
 * milésimas, [lineTotal] que devolvió el proveedor y [cost] que salió del stock, en positivo.
 */
internal data class TakenFromPurchaseLine(
    val purchaseLineId: Long,
    val quantityMilli: Long,
    val lineTotal: Long,
    val cost: Long,
)

// Cada línea de devolución tiene una sola fila `PURCHASE_RETURN` en el libro, con su costo en
// negativo.
internal const val TAKEN_BY_PURCHASE =
    "SELECT l.purchase_line_id AS purchaseLineId, SUM(l.quantity_milli) AS quantityMilli, " +
        "SUM(l.line_total) AS lineTotal, SUM(-m.total_cost) AS cost " +
        "FROM purchase_return_lines l " +
        "JOIN purchase_returns r ON r.id = l.purchase_return_id " +
        "JOIN stock_movements m ON m.purchase_return_line_id = l.id " +
        "AND m.type = 'PURCHASE_RETURN' " +
        "WHERE r.purchase_id = :purchaseId AND r.status = 'ACTIVE' " +
        "AND l.purchase_line_id IS NOT NULL " +
        "GROUP BY l.purchase_line_id ORDER BY l.purchase_line_id"
