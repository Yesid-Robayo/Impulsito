package io.github.yesidrobayo.shopledger.data.local.purchase

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Las líneas de compra: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una línea, y no debe haberla: la línea explica las filas que su
 * compra dejó en el libro de stock, también después de anularla. La inserción no usa `REPLACE`, que
 * borraría la línea que choca. Las líneas salen en el orden en que se guardaron, por `id`.
 */
@Dao
internal interface PurchaseLineDao {
    @Query("SELECT * FROM purchase_lines WHERE purchase_id = :purchaseId ORDER BY id")
    fun observeByPurchase(purchaseId: Long): Flow<List<PurchaseLineEntity>>

    @Query("SELECT * FROM purchase_lines WHERE purchase_id = :purchaseId ORDER BY id")
    suspend fun findByPurchase(purchaseId: Long): List<PurchaseLineEntity>

    /** Agrega la línea y devuelve su id. Solo se llama dentro de la transacción del caso de uso. */
    @Insert
    suspend fun insert(line: PurchaseLineEntity): Long
}
