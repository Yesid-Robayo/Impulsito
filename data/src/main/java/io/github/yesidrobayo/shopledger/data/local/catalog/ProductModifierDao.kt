package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `product_modifiers`, los extras de cada producto. Las escrituras solo se
 * llaman dentro de la transacción del caso de uso.
 *
 * Cada escritura toca solo sus columnas. Sin `REPLACE`: un `INSERT OR REPLACE` borraría el extra que
 * choca sin que nadie lo pidiera.
 */
@Dao
internal interface ProductModifierDao {
    /** Los extras del producto, en el orden que eligió la persona, y por `id` con el mismo orden. */
    @Query("SELECT * FROM product_modifiers WHERE product_id = :productId ORDER BY sort_order, id")
    fun observeByProduct(productId: Long): Flow<List<ProductModifierEntity>>

    @Query("SELECT * FROM product_modifiers WHERE product_id = :productId ORDER BY sort_order, id")
    suspend fun findByProduct(productId: Long): List<ProductModifierEntity>

    /** Devuelve el id nuevo. */
    @Insert
    suspend fun insert(modifier: ProductModifierEntity): Long

    /** Cambia el grupo y el orden, sin tocar `created_at`. Devuelve las filas cambiadas. */
    @Query(
        "UPDATE product_modifiers SET group_name = :groupName, sort_order = :sortOrder, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(id: Long, groupName: String?, sortOrder: Long, updatedAt: Long): Int

    /** Quita el extra del producto. Las ventas pasadas no cambian. */
    @Query("DELETE FROM product_modifiers WHERE id = :id")
    suspend fun delete(id: Long): Int
}
