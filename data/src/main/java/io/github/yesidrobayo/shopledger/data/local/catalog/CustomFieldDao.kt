package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de los campos propios, `custom_field_definitions`, y de sus valores en cada
 * producto, `product_custom_values`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Cada escritura toca solo sus columnas, y ninguna usa `REPLACE`: un `INSERT OR REPLACE` sobre un
 * campo lo borraría con todos sus valores en cascada. Un nombre repetido entre los vigentes falla por
 * la guarda, y un valor repetido por su índice único: ninguno cambia nada.
 */
@Dao
internal interface CustomFieldDao {
    /** Los vigentes, en el orden que eligió la persona, y por `id` con el mismo orden. */
    @Query(
        "SELECT * FROM custom_field_definitions WHERE archived_at IS NULL ORDER BY sort_order, id",
    )
    fun observeActiveFields(): Flow<List<CustomFieldDefinitionEntity>>

    @Query("SELECT * FROM custom_field_definitions WHERE id = :id")
    suspend fun findField(id: Long): CustomFieldDefinitionEntity?

    /** El campo vigente con ese nombre: uno archivado no reserva el suyo. */
    @Query(
        "SELECT * FROM custom_field_definitions WHERE normalized_name = :normalizedName " +
            "AND archived_at IS NULL ORDER BY id LIMIT 1",
    )
    suspend fun findActiveField(normalizedName: String): CustomFieldDefinitionEntity?

    /** Productos con un valor en el campo: es lo que se muestra antes de borrarlo. */
    @Query("SELECT COUNT(*) FROM product_custom_values WHERE field_id = :fieldId")
    suspend fun countValues(fieldId: Long): Int

    /** Devuelve el id nuevo. Falla si un campo vigente ya tiene ese nombre normalizado. */
    @Insert
    suspend fun insertField(field: CustomFieldDefinitionEntity): Long

    /**
     * Cambia el nombre y el orden. El tipo no cambia: los valores guardados se escribieron para
     * él. Devuelve las filas cambiadas.
     */
    @Query(
        "UPDATE custom_field_definitions SET name = :name, normalized_name = :normalizedName, " +
            "sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateField(
        id: Long,
        name: String,
        normalizedName: String,
        sortOrder: Long,
        updatedAt: Long,
    ): Int

    /**
     * Archiva o reactiva el campo: con `null`, vuelve a mostrarse. Sus valores no cambian.
     * Reactivarlo falla si otro campo vigente ya tiene su nombre.
     */
    @Query(
        "UPDATE custom_field_definitions SET archived_at = :archivedAt, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun setFieldArchived(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /** Borra el campo y, en cascada, sus valores en todos los productos. */
    @Query("DELETE FROM custom_field_definitions WHERE id = :id")
    suspend fun deleteField(id: Long): Int

    /** Los valores del producto, por `id`. */
    @Query("SELECT * FROM product_custom_values WHERE product_id = :productId ORDER BY id")
    fun observeValues(productId: Long): Flow<List<ProductCustomValueEntity>>

    @Query("SELECT * FROM product_custom_values WHERE product_id = :productId ORDER BY id")
    suspend fun findValues(productId: Long): List<ProductCustomValueEntity>

    /** Devuelve el id nuevo. Falla si el producto ya tiene un valor en ese campo. */
    @Insert
    suspend fun insertValue(value: ProductCustomValueEntity): Long

    @Query(
        "UPDATE product_custom_values SET value_text = :valueText, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun updateValue(id: Long, valueText: String, updatedAt: Long): Int

    @Query("DELETE FROM product_custom_values WHERE id = :id")
    suspend fun deleteValue(id: Long): Int
}
