package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductPhotoChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import kotlin.time.Instant

/**
 * El id de la fila de [image], anotándola como [kind] si es nueva. Una imagen que ya estaba
 * anotada, quizá marcada como sin uso, vuelve a usarse: dos imágenes iguales son una sola fila y un
 * solo archivo. Solo se llama dentro de la transacción del caso de uso.
 */
internal suspend fun recordImage(
    media: MediaAssetRepository,
    image: StoredImage,
    kind: MediaKind,
    at: Instant,
): Long {
    val existing = media.findIdBySha256(image.sha256)
        ?: return media.insert(image, kind, at)
    media.setOrphanedAt(existing, orphanedAt = null)
    return existing
}

/** El id de la foto que queda tras [change], si [current] es la que tenía el producto. */
internal suspend fun photoIdAfter(
    media: MediaAssetRepository,
    change: ProductPhotoChange,
    current: Long?,
    at: Instant,
): Long? = when (change) {
    ProductPhotoChange.Keep -> current
    ProductPhotoChange.Remove -> null
    is ProductPhotoChange.Use -> recordImage(media, change.image, MediaKind.PRODUCT_PHOTO, at)
}
