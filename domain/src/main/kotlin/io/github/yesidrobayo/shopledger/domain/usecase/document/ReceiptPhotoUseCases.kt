package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.catalog.ImageImport
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.AttachmentTarget
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptPhoto
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.DocumentAttachmentRepository
import io.github.yesidrobayo.shopledger.domain.port.ImageImporter
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.recordImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Trae la foto del comprobante que eligió la persona, con el mismo importador de las fotos de
 * producto pero al tamaño de un comprobante, que debe poder leerse: lado mayor de 1600 px y 300
 * KiB como máximo. La imagen es entrada no confiable: se lee con tope, se recodifica a WebP sin
 * EXIF y el original nunca se guarda. Todavía no la une a nada: eso lo hace
 * [AttachReceiptPhotoUseCase].
 */
class ImportReceiptPhotoUseCase(private val importer: ImageImporter) {
    suspend operator fun invoke(source: String): ImageImport =
        importer.import(source, MediaKind.DOCUMENT_ATTACHMENT)
}

/** Cómo terminó unir una foto a su documento. */
sealed interface AttachReceiptResult {
    /** Quedó unida como la foto [attachmentId]. */
    data class Attached(val attachmentId: Long) : AttachReceiptResult

    /** El gasto o la compra no existe o ya está anulado: una foto nueva no se le agrega. */
    data object DocumentUnavailable : AttachReceiptResult

    /** La base no pudo escribir. Nada quedó a medias. */
    data class Failed(val failure: StorageFailure) : AttachReceiptResult
}

/**
 * Une al gasto o la compra activos la foto ya recodificada, en una sola transacción: anota la
 * imagen, o vuelve a usar la que ya estaba anotada con el mismo contenido, y crea su fila en
 * `document_attachments`. La misma foto dos veces en el mismo documento es una sola.
 */
class AttachReceiptPhotoUseCase(
    private val transactions: TransactionRunner,
    private val attachments: DocumentAttachmentRepository,
    private val media: MediaAssetRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        target: AttachmentTarget,
        image: StoredImage,
    ): AttachReceiptResult = try {
        transactions.write {
            if (attachments.findDocumentStatus(target) != DocumentStatus.ACTIVE) {
                return@write TxResult.Rollback(AttachReceiptResult.DocumentUnavailable)
            }
            val now = clock.now()
            val mediaId = recordImage(media, image, MediaKind.DOCUMENT_ATTACHMENT, now)
            val existing =
                attachments.findAll(target).firstOrNull { it.mediaAssetId == mediaId }
            val id = existing?.id ?: attachments.insert(target, mediaId, now)
            TxResult.Commit(AttachReceiptResult.Attached(id))
        }
    } catch (failure: StorageFailureException) {
        AttachReceiptResult.Failed(failure.failure)
    }
}

/** Cómo terminó quitar una foto de su documento. */
sealed interface RemoveReceiptResult {
    data object Removed : RemoveReceiptResult

    /** La foto ya no estaba. Nada cambió. */
    data object NotFound : RemoveReceiptResult

    /** La base no pudo escribir. La foto sigue en su documento. */
    data class Failed(val failure: StorageFailure) : RemoveReceiptResult
}

/**
 * Quita una foto de su documento, solo porque la persona lo decide, en una sola transacción. Vale
 * también en un documento anulado. Si ninguna otra fila usa la imagen, la marca como sin uso: la
 * limpieza de fotos borra después su archivo, y nunca uno que algo todavía usa.
 */
class RemoveReceiptPhotoUseCase(
    private val transactions: TransactionRunner,
    private val attachments: DocumentAttachmentRepository,
    private val media: MediaAssetRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(attachmentId: Long): RemoveReceiptResult = try {
        transactions.write {
            val attachment = attachments.findById(attachmentId)
                ?: return@write TxResult.Rollback(RemoveReceiptResult.NotFound)
            attachments.delete(attachmentId)
            if (!media.isReferenced(attachment.mediaAssetId)) {
                media.setOrphanedAt(attachment.mediaAssetId, clock.now())
            }
            TxResult.Commit(RemoveReceiptResult.Removed)
        }
    } catch (failure: StorageFailureException) {
        RemoveReceiptResult.Failed(failure.failure)
    }
}

/**
 * Las fotos de comprobante de un gasto o una compra, con su imagen, en el orden en que se
 * agregaron. Solo lee, y nada usa la red.
 */
class ObserveReceiptPhotosUseCase(
    private val attachments: DocumentAttachmentRepository,
    private val media: MediaAssetRepository,
) {
    operator fun invoke(target: AttachmentTarget): Flow<List<ReceiptPhoto>> = combine(
        attachments.observe(target),
        media.observeImages(),
    ) { rows, images ->
        rows.map { ReceiptPhoto(it.id, images[it.mediaAssetId]) }
    }.distinctUntilChanged()
}
