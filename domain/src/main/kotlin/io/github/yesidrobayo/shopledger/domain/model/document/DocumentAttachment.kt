package io.github.yesidrobayo.shopledger.domain.model.document

import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import kotlin.time.Instant

/** El documento al que va la foto de un comprobante: un gasto o una compra, nunca los dos. */
sealed interface AttachmentTarget {
    data class Expense(val expenseId: Long) : AttachmentTarget

    data class Purchase(val purchaseId: Long) : AttachmentTarget
}

/**
 * La foto de un comprobante unida a su documento. La foto no vive aquí: [mediaAssetId] apunta a su
 * registro, y el archivo está fuera de la base. Solo se quita porque la persona lo decide: anular
 * el documento la conserva.
 */
data class DocumentAttachment(
    val id: Long,
    val target: AttachmentTarget,
    val mediaAssetId: Long,
    val caption: String?,
    val createdAt: Instant,
)

/**
 * Una foto de comprobante lista para mostrar: [image] es su archivo, o `null` si su registro ya no
 * está, y la pantalla dice `Foto no disponible`.
 */
data class ReceiptPhoto(val attachmentId: Long, val image: StoredImage?)
