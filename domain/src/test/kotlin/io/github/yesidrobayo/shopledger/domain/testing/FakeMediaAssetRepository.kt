package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import kotlin.time.Instant

/** La fila de una imagen en [InMemoryDatabase]. */
internal data class MediaAssetRow(
    val image: StoredImage,
    val kind: MediaKind,
    val createdAt: Instant,
    val orphanedAt: Instant?,
)

/**
 * Un [MediaAssetRepository] sobre [InMemoryDatabase]. Una imagen se usa si algún método de pago o
 * producto la nombra, o si la prueba la puso en [otherReferences], como un comprobante.
 */
internal class FakeMediaAssetRepository(private val database: InMemoryDatabase) :
    MediaAssetRepository {
    val otherReferences: MutableSet<Long> = mutableSetOf()

    override suspend fun findIdBySha256(sha256: String): Long? =
        database.read().mediaAssets.entries.firstOrNull { it.value.image.sha256 == sha256 }?.key

    override suspend fun findImage(id: Long): StoredImage? = database.read().mediaAssets[id]?.image

    override suspend fun insert(image: StoredImage, kind: MediaKind, createdAt: Instant): Long {
        check(findIdBySha256(image.sha256) == null) { "La misma imagen dos veces" }
        val id = database.nextId(TABLE)
        val row = MediaAssetRow(image, kind, createdAt, orphanedAt = null)
        database.write { it.copy(mediaAssets = it.mediaAssets + (id to row)) }
        return id
    }

    override suspend fun isReferenced(id: Long): Boolean = id in otherReferences ||
        database.read().paymentMethods.values.any { it.details.instructionsMediaId == id } ||
        database.read().products.values.any { it.details.photoMediaId == id }

    override suspend fun setOrphanedAt(id: Long, orphanedAt: Instant?) {
        val row = checkNotNull(database.read().mediaAssets[id]) { "La imagen no existe" }
        database.write {
            it.copy(mediaAssets = it.mediaAssets + (id to row.copy(orphanedAt = orphanedAt)))
        }
    }

    private companion object {
        const val TABLE = "media_assets"
    }
}
