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

    /** Las filas de una actualización masiva, o de su deshacer, por su UUID en texto. */
    @Query("SELECT * FROM product_price_history WHERE batch_id = :batchId ORDER BY id")
    suspend fun findByBatch(batchId: String): List<PriceHistoryEntity>

    /** Agrega la fila y devuelve su id. Solo se llama dentro de la transacción del caso de uso. */
    @Insert
    suspend fun insert(change: PriceHistoryEntity): Long
}
