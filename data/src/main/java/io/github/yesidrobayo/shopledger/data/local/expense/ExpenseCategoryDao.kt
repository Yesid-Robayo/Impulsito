package io.github.yesidrobayo.shopledger.data.local.expense

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `expense_categories`. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso.
 *
 * Todo sale en el orden en que se crearon, por `id`: el orden no depende del idioma, aunque las
 * iniciales se muestren traducidas. Sin `REPLACE`: un `INSERT OR REPLACE` borraría la categoría
 * que choca, y con gastos fallaría su llave a mitad del caso de uso.
 *
 * La de otros gastos, con `is_system = 1`, nunca se archiva ni se borra: las dos escrituras que lo
 * harían la excluyen en su condición y devuelven 0.
 */
@Dao
internal interface ExpenseCategoryDao {
    /** Las vigentes: las que se ofrecen al anotar un gasto. */
    @Query("SELECT * FROM expense_categories WHERE archived_at IS NULL ORDER BY id")
    fun observeActive(): Flow<List<ExpenseCategoryEntity>>

    /** Las archivadas, que siguen en los reportes y se pueden reactivar. */
    @Query("SELECT * FROM expense_categories WHERE archived_at IS NOT NULL ORDER BY id")
    fun observeArchived(): Flow<List<ExpenseCategoryEntity>>

    @Query("SELECT * FROM expense_categories WHERE id = :id")
    suspend fun findById(id: Long): ExpenseCategoryEntity?

    /**
     * La vigente con ese nombre normalizado. El nombre es único solo entre las vigentes, así que hay
     * a lo sumo una: las archivadas con el mismo nombre no cuentan.
     */
    @Query(
        "SELECT * FROM expense_categories " +
            "WHERE normalized_name = :normalizedName AND archived_at IS NULL",
    )
    suspend fun findActiveByNormalizedName(normalizedName: String): ExpenseCategoryEntity?

    /** Si tiene gastos, aunque estén anulados. Con gastos ya no se borra: solo se archiva. */
    @Query("SELECT EXISTS (SELECT 1 FROM expenses WHERE expense_category_id = :id)")
    suspend fun hasExpenses(id: Long): Boolean

    /** Devuelve el id nuevo. Falla si la clave ya existe, o si el nombre ya es de una vigente. */
    @Insert
    suspend fun insert(category: ExpenseCategoryEntity): Long

    /**
     * Cambia lo que la persona escribe, sin tocar la clave, la marca de sistema, el archivo ni
     * `created_at`. Devuelve las filas cambiadas.
     */
    @Query(
        "UPDATE expense_categories SET name = :name, normalized_name = :normalizedName, " +
            "is_fixed = :isFixed, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String?,
        normalizedName: String?,
        isFixed: Long,
        updatedAt: Long,
    ): Int

    /**
     * La archiva con [archivedAt], o la reactiva con `null`. Devuelve 0 si no existe o es la de
     * otros gastos, que nunca se archiva.
     */
    @Query(
        "UPDATE expense_categories SET archived_at = :archivedAt, updated_at = :updatedAt " +
            "WHERE id = :id AND is_system = 0",
    )
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Elimina una categoría sin gastos. Con gastos, su llave `RESTRICT` hace fallar la sentencia y
     * no borra nada. Devuelve 0 si no existe o es la de otros gastos, que nunca se borra.
     */
    @Query("DELETE FROM expense_categories WHERE id = :id AND is_system = 0")
    suspend fun delete(id: Long): Int
}
