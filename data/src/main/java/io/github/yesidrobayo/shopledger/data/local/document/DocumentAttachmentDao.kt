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
