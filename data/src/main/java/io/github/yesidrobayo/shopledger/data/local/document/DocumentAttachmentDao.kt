package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `document_attachments`. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso. Cada lista sale en el orden en que se agregaron, por `id`.
 */
@Dao
internal interface DocumentAttachmentDao {
    @Query("SELECT * FROM document_attachments WHERE expense_id = :expenseId ORDER BY id")
    fun observeByExpense(expenseId: Long): Flow<List<DocumentAttachmentEntity>>

    @Query("SELECT * FROM document_attachments WHERE purchase_id = :purchaseId ORDER BY id")
    fun observeByPurchase(purchaseId: Long): Flow<List<DocumentAttachmentEntity>>

    @Query("SELECT * FROM document_attachments WHERE expense_id = :expenseId ORDER BY id")
    suspend fun findByExpense(expenseId: Long): List<DocumentAttachmentEntity>

    @Query("SELECT * FROM document_attachments WHERE purchase_id = :purchaseId ORDER BY id")
    suspend fun findByPurchase(purchaseId: Long): List<DocumentAttachmentEntity>

    @Query("SELECT * FROM document_attachments WHERE id = :id")
    suspend fun findById(id: Long): DocumentAttachmentEntity?

    /** El estado guardado del gasto [expenseId], o nulo si no existe. */
    @Query("SELECT status FROM expenses WHERE id = :expenseId")
    suspend fun findExpenseStatus(expenseId: Long): String?

    /** El estado guardado de la compra [purchaseId], o nulo si no existe. */
    @Query("SELECT status FROM purchases WHERE id = :purchaseId")
    suspend fun findPurchaseStatus(purchaseId: Long): String?

    /** Devuelve el id nuevo. Falla si no apunta a exactamente un documento. */
    @Insert
    suspend fun insert(attachment: DocumentAttachmentEntity): Long

    /** Cambia el pie de la foto. Devuelve las filas cambiadas. */
    @Query("UPDATE document_attachments SET caption = :caption WHERE id = :id")
    suspend fun updateCaption(id: Long, caption: String?): Int

    /** Quita la foto del documento, porque la persona lo decide. Devuelve las filas borradas. */
    @Query("DELETE FROM document_attachments WHERE id = :id")
    suspend fun delete(id: Long): Int
}
