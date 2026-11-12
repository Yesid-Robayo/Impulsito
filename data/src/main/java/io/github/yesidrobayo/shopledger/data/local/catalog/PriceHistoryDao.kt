package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * El historial de precios: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una fila, y no debe haberla: un precio pasado explica una venta
 * pasada. La inserción no usa `REPLACE`, porque un `INSERT OR REPLACE` con un id existente borraría
 * esa fila y la reemplazaría: falla y deja la original. Todo sale en el orden en que se registró,
 * por `id`.
 */
@Dao
internal interface PriceHistoryDao {
    @Query("SELECT * FROM product_price_history WHERE product_id = :productId ORDER BY id")
    fun observeByProduct(productId: Long): Flow<List<PriceHistoryEntity>>

    /** Lo mismo, leído una vez: dentro de una transacción de lectura no se observa. */
    @Query("SELECT * FROM product_price_history WHERE product_id = :productId ORDER BY id")
    suspend fun findByProduct(productId: Long): List<PriceHistoryEntity>

    /** Las filas de una actualización masiva, o de su deshacer, por su UUID en texto. */
    @Query("SELECT * FROM product_price_history WHERE batch_id = :batchId ORDER BY id")
    suspend fun findByBatch(batchId: String): List<PriceHistoryEntity>

    /**
     * El lote que se puede deshacer: el de la última fila con lote, si es `BULK`. Si es un
     * `BULK_UNDO`, o no hay filas con lote, `null`.
     */
    @Query(UNDOABLE_BATCH)
    fun observeUndoableBatch(): Flow<String?>

    /** Lo mismo, leído una vez, dentro de la transacción que deshace. */
    @Query(UNDOABLE_BATCH)
    suspend fun findUndoableBatch(): String?

    /** Agrega la fila y devuelve su id. Solo se llama dentro de la transacción del caso de uso. */
    @Insert
    suspend fun insert(change: PriceHistoryEntity): Long
}

/** La última fila con lote decide: solo un `BULK` se puede deshacer, y un deshacer no se deshace. */
private const val UNDOABLE_BATCH =
    "SELECT CASE WHEN source = 'BULK' THEN batch_id END FROM product_price_history " +
        "WHERE batch_id IS NOT NULL ORDER BY id DESC LIMIT 1"
