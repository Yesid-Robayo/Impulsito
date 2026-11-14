package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.document.AttachmentTarget
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentAttachment
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las fotos de comprobantes de los gastos y las compras: sus filas, nunca la imagen ni su ruta.
 * Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface DocumentAttachmentRepository {
    /** Las fotos de [target], en el orden en que se agregaron, y otra vez cada vez que cambian. */
    fun observe(target: AttachmentTarget): Flow<List<DocumentAttachment>>

    /** Las fotos de [target], en el orden en que se agregaron. */
    suspend fun findAll(target: AttachmentTarget): List<DocumentAttachment>

    /** La foto [id], o `null` si ya no existe. */
    suspend fun findById(id: Long): DocumentAttachment?

    /** El estado del gasto o la compra de [target], o `null` si no existe. */
    suspend fun findDocumentStatus(target: AttachmentTarget): DocumentStatus?

    /** Une la imagen [mediaAssetId] a [target] y devuelve el id nuevo. */
    suspend fun insert(target: AttachmentTarget, mediaAssetId: Long, createdAt: Instant): Long

    /** Quita la foto [id] de su documento, porque la persona lo decide. */
    suspend fun delete(id: Long)
}
