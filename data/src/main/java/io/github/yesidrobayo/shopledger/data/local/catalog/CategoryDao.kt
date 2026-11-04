package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `categories`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Sin `REPLACE`: un `INSERT OR REPLACE` borra la fila que choca, y con ella pondría sin categoría a
 * sus productos sin que nadie lo pidiera. Un choque de nombre falla y no cambia nada.
 */
@Dao
internal interface CategoryDao {
    /** Todas, por nombre normalizado, que es único: el orden no depende del idioma del teléfono. */
    @Query("SELECT * FROM categories ORDER BY normalized_name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun findById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE normalized_name = :normalizedName")
    suspend fun findByNormalizedName(normalizedName: String): CategoryEntity?

    /** Productos de la categoría, archivados o no: es lo que se muestra antes de eliminarla. */
    @Query("SELECT COUNT(*) FROM products WHERE category_id = :id")
    suspend fun countProducts(id: Long): Int

    /** Devuelve el id nuevo. Falla si el nombre normalizado ya existe. */
    @Insert
    suspend fun insert(category: CategoryEntity): Long

    /** Cambia lo que la persona escribe, sin tocar `created_at`. Devuelve las filas cambiadas. */
    @Query(
        "UPDATE categories SET name = :name, normalized_name = :normalizedName, " +
            "target_margin_bp = :targetMarginBp, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String,
        normalizedName: String,
        targetMarginBp: Long?,
        updatedAt: Long,
    ): Int

    /**
     * Elimina la categoría. Su llave foránea deja sin categoría a sus productos en la misma
     * sentencia. Devuelve las filas borradas.
     */
    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long): Int
}
