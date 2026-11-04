package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `product_components`, la receta de cada combo. Las escrituras solo se
 * llaman dentro de la transacción del caso de uso.
 *
 * Sin `REPLACE`: un `INSERT OR REPLACE` borraría la fila que choca sin pasar por su guarda. Un
 * componente repetido falla por el índice único y no cambia nada. Las listas salen por `id`, el
 * orden en que la persona armó la receta.
 */
@Dao
internal interface ProductComponentDao {
    @Query("SELECT * FROM product_components WHERE parent_product_id = :parentId ORDER BY id")
    fun observeByParent(parentId: Long): Flow<List<ProductComponentEntity>>

    @Query("SELECT * FROM product_components WHERE parent_product_id = :parentId ORDER BY id")
    suspend fun findByParent(parentId: Long): List<ProductComponentEntity>

    /** Las recetas en que entra [componentId]: lo que se muestra antes de archivarlo. */
    @Query(
        "SELECT * FROM product_components WHERE component_product_id = :componentId ORDER BY id",
    )
    suspend fun findByComponent(componentId: Long): List<ProductComponentEntity>

    /** Devuelve el id nuevo. Falla si el combo ya tiene ese componente. */
    @Insert
    suspend fun insert(component: ProductComponentEntity): Long

    /** Cambia solo la cantidad, sin tocar `created_at`. Devuelve las filas cambiadas. */
    @Query(
        "UPDATE product_components SET quantity_milli = :quantityMilli, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateQuantity(id: Long, quantityMilli: Long, updatedAt: Long): Int

    /** Quita el componente de la receta. Las ventas pasadas no cambian. */
    @Query("DELETE FROM product_components WHERE id = :id")
    suspend fun delete(id: Long): Int
}
