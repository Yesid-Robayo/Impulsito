package io.github.yesidrobayo.shopledger.data.local.sale

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de las ventas en espera, `sale_drafts`, y de sus líneas,
 * `sale_draft_lines`.
 *
 * Una espera no es historia: cada escritura toca solo sus columnas, y borrar es libre. Sin
 * `REPLACE`: un `INSERT OR REPLACE` sobre una espera la borraría con sus líneas en cascada. Las
 * listas salen por `id`, el orden en que se crearon, y una línea extra siempre después de su
 * línea principal.
 */
@Dao
internal interface SaleDraftDao {
    @Query("SELECT * FROM sale_drafts ORDER BY id")
    fun observeAll(): Flow<List<SaleDraftEntity>>

    @Query("SELECT * FROM sale_drafts WHERE id = :id")
    suspend fun findById(id: Long): SaleDraftEntity?

    /** Devuelve el id nuevo. */
    @Insert
    suspend fun insert(draft: SaleDraftEntity): Long

    /** Cambia el nombre, el cliente y la lista, sin tocar `created_at`. */
    @Query(
        "UPDATE sale_drafts SET label = :label, customer_id = :customerId, " +
            "price_list_id = :priceListId, updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        label: String?,
        customerId: Long?,
        priceListId: Long?,
        updatedAt: Long,
    ): Int

    /** Borra la espera y, en cascada, sus líneas: al confirmarla o al descartarla. */
    @Query("DELETE FROM sale_drafts WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT * FROM sale_draft_lines WHERE draft_id = :draftId ORDER BY id")
    fun observeLines(draftId: Long): Flow<List<SaleDraftLineEntity>>

    @Query("SELECT * FROM sale_draft_lines WHERE draft_id = :draftId ORDER BY id")
    suspend fun findLines(draftId: Long): List<SaleDraftLineEntity>

    /** Las líneas de todas las esperas, por `id`: la lista de ventas en espera las agrupa. */
    @Query("SELECT * FROM sale_draft_lines ORDER BY id")
    fun observeAllLines(): Flow<List<SaleDraftLineEntity>>

    @Query("SELECT * FROM sale_drafts ORDER BY id")
    suspend fun findAll(): List<SaleDraftEntity>

    @Query("SELECT * FROM sale_draft_lines ORDER BY id")
    suspend fun findAllLines(): List<SaleDraftLineEntity>

    /** Borra todas las líneas de una espera, para guardarla de nuevo con las de ahora. */
    @Query("DELETE FROM sale_draft_lines WHERE draft_id = :draftId")
    suspend fun deleteLines(draftId: Long): Int

    /** Devuelve el id nuevo. */
    @Insert
    suspend fun insertLine(line: SaleDraftLineEntity): Long

    /** Cambia lo que la persona edita en la línea. El producto, la unidad y su principal quedan. */
    @Query(
        "UPDATE sale_draft_lines SET entered_quantity_milli = :enteredQuantityMilli, " +
            "unit_price = :unitPrice, line_discount = :lineDiscount WHERE id = :id",
    )
    suspend fun updateLine(
        id: Long,
        enteredQuantityMilli: Long,
        unitPrice: Long?,
        lineDiscount: Long,
    ): Int

    /** Borra la línea y, en cascada, sus extras. */
    @Query("DELETE FROM sale_draft_lines WHERE id = :id")
    suspend fun deleteLine(id: Long): Int
}
