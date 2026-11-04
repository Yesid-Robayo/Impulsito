package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `tags` y de sus vínculos con los productos, `product_tags`. Las
 * escrituras solo se llaman dentro de la transacción del caso de uso.
 *
 * Sin `REPLACE`: un `INSERT OR REPLACE` sobre una etiqueta la borraría con sus vínculos en cascada.
 * Un nombre o un vínculo repetido falla por su índice único y no cambia nada.
 */
@Dao
internal interface TagDao {
    /** Todas, por nombre normalizado, que es único: el orden no depende del idioma del teléfono. */
    @Query("SELECT * FROM tags ORDER BY normalized_name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun findById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE normalized_name = :normalizedName")
    suspend fun findByNormalizedName(normalizedName: String): TagEntity?

    /** Devuelve el id nuevo. Falla si el nombre normalizado ya existe. */
    @Insert
    suspend fun insert(tag: TagEntity): Long

    /** Cambia el nombre, sin tocar `created_at`. Devuelve las filas cambiadas. */
    @Query(
        "UPDATE tags SET name = :name, normalized_name = :normalizedName, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(id: Long, name: String, normalizedName: String, updatedAt: Long): Int

    /** Borra la etiqueta y, en cascada, sus vínculos. Los productos no cambian. */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long): Int

    /** Las etiquetas del producto, por nombre normalizado. */
    @Query(
        "SELECT t.* FROM tags t JOIN product_tags pt ON pt.tag_id = t.id " +
            "WHERE pt.product_id = :productId ORDER BY t.normalized_name",
    )
    fun observeTagsOf(productId: Long): Flow<List<TagEntity>>

    /** Los productos con la etiqueta, por `id`: el filtro de la lista de productos. */
    @Query("SELECT product_id FROM product_tags WHERE tag_id = :tagId ORDER BY product_id")
    suspend fun findProductIdsWith(tagId: Long): List<Long>

    /** Devuelve el id nuevo. Falla si el producto ya tiene la etiqueta. */
    @Insert
    suspend fun link(link: ProductTagEntity): Long

    /** Quita la etiqueta del producto. Devuelve las filas borradas. */
    @Query("DELETE FROM product_tags WHERE product_id = :productId AND tag_id = :tagId")
    suspend fun unlink(productId: Long, tagId: Long): Int
}
