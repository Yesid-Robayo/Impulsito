package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.document.DocumentAttachmentEntity
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.document.AttachmentTarget
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentAttachment
import io.github.yesidrobayo.shopledger.domain.port.DocumentAttachmentRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las fotos de comprobantes sobre `document_attachments`. Cada fila apunta a exactamente un gasto
 * o una compra, y lo exige la guarda; aquí se escribe siempre con uno de los dos.
 */
internal class RoomDocumentAttachmentRepository(database: AppDatabase) :
    DocumentAttachmentRepository {
    private val attachments = database.documentAttachmentDao()
    private val writes = RepositoryWrites(database)

    override fun observe(target: AttachmentTarget): Flow<List<DocumentAttachment>> {
        val rows = when (target) {
            is AttachmentTarget.Expense -> attachments.observeByExpense(target.expenseId)
            is AttachmentTarget.Purchase -> attachments.observeByPurchase(target.purchaseId)
        }
        return rows.map { list -> list.map(::toAttachment) }.distinctUntilChanged()
    }

    override suspend fun findAll(target: AttachmentTarget): List<DocumentAttachment> =
        when (target) {
            is AttachmentTarget.Expense -> attachments.findByExpense(target.expenseId)
            is AttachmentTarget.Purchase -> attachments.findByPurchase(target.purchaseId)
        }.map(::toAttachment)

    override suspend fun findById(id: Long): DocumentAttachment? =
        attachments.findById(id)?.let(::toAttachment)

    override suspend fun findDocumentStatus(target: AttachmentTarget): DocumentStatus? =
        when (target) {
            is AttachmentTarget.Expense -> attachments.findExpenseStatus(target.expenseId)
            is AttachmentTarget.Purchase -> attachments.findPurchaseStatus(target.purchaseId)
        }?.let { storedEnum<DocumentStatus>(it) }

    override suspend fun insert(
        target: AttachmentTarget,
        mediaAssetId: Long,
        createdAt: Instant,
    ): Long = writes.inTransaction {
        attachments.insert(
            DocumentAttachmentEntity(
                mediaAssetId = mediaAssetId,
                expenseId = (target as? AttachmentTarget.Expense)?.expenseId,
                purchaseId = (target as? AttachmentTarget.Purchase)?.purchaseId,
                caption = null,
                createdAt = createdAt.toStoredMillis(),
            ),
        )
    }

    override suspend fun delete(id: Long) {
        writes.inTransaction { requireOneRow(attachments.delete(id)) }
    }

    private fun toAttachment(row: DocumentAttachmentEntity): DocumentAttachment {
        val expenseId = row.expenseId
        val purchaseId = row.purchaseId
        val target = when {
            expenseId != null && purchaseId == null -> AttachmentTarget.Expense(expenseId)

            purchaseId != null && expenseId == null -> AttachmentTarget.Purchase(purchaseId)

            // La guarda lo impide: una fila así vino de fuera de la app.
            else -> brokenRow()
        }
        return DocumentAttachment(
            id = row.id,
            target = target,
            mediaAssetId = row.mediaAssetId,
            caption = row.caption,
            createdAt = row.createdAt.toInstant(),
        )
    }
}
