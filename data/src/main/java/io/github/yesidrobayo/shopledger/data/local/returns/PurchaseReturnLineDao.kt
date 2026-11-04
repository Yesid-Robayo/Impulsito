package io.github.yesidrobayo.shopledger.data.local.returns

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

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

    /** Agrega la línea y devuelve su id. Solo se llama dentro de la transacción de la devolución. */
    @Insert
    suspend fun insert(line: PurchaseReturnLineEntity): Long
}
