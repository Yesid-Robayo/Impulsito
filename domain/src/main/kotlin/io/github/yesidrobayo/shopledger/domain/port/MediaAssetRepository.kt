package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import kotlin.time.Instant

/**
 * Las filas de las imágenes: sus metadatos, nunca la imagen ni su ruta. Las funciones que escriben
 * solo se llaman dentro de [TransactionRunner.write].
 */
interface MediaAssetRepository {
    /** El id de la imagen con ese contenido, si ya se anotó. */
    suspend fun findIdBySha256(sha256: String): Long?

    /** La imagen anotada con [id], o `null` si ya no existe. */
    suspend fun findImage(id: Long): StoredImage?

    /** Anota la imagen, sin marca de huérfana, y devuelve su id. */
    suspend fun insert(image: StoredImage, kind: MediaKind, createdAt: Instant): Long

    /** Si algo la usa todavía: un producto, un método de pago, un comprobante o el logo. */
    suspend fun isReferenced(id: Long): Boolean

    /** La marca desde [orphanedAt] como sin uso, o con `null` como usada otra vez. */
    suspend fun setOrphanedAt(id: Long, orphanedAt: Instant?)
}
