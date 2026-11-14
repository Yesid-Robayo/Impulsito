package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.MediaAssetEntity
import io.github.yesidrobayo.shopledger.domain.model.catalog.MediaKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.MediaAssetRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Las filas de las imágenes sobre `media_assets`: sus metadatos, nunca su ruta. El archivo vive
 * aparte, nombrado por su suma, y lo escribe el importador antes de la transacción.
 */
internal class RoomMediaAssetRepository(database: AppDatabase) : MediaAssetRepository {
    private val assets = database.mediaAssetDao()
    private val writes = RepositoryWrites(database)

    override suspend fun findIdBySha256(sha256: String): Long? = assets.findBySha256(sha256)?.id

    override suspend fun findImage(id: Long): StoredImage? = assets.findById(id)?.toImage()

    override fun observeImages(): Flow<Map<Long, StoredImage>> =
        assets.observeAll().map { rows -> rows.associate { it.id to it.toImage() } }

    private fun MediaAssetEntity.toImage(): StoredImage = StoredImage(
        sha256 = sha256,
        mimeType = mimeType,
        widthPx = widthPx.toStoredInt(),
        heightPx = heightPx.toStoredInt(),
        sizeBytes = sizeBytes,
    )

    override suspend fun insert(image: StoredImage, kind: MediaKind, createdAt: Instant): Long =
        writes.inTransaction {
            assets.insert(
                MediaAssetEntity(
                    sha256 = image.sha256,
                    kind = kind.name,
                    mimeType = image.mimeType,
                    widthPx = image.widthPx.toLong(),
                    heightPx = image.heightPx.toLong(),
                    sizeBytes = image.sizeBytes,
                    createdAt = createdAt.toStoredMillis(),
                    orphanedAt = null,
                ),
            )
        }

    override suspend fun isReferenced(id: Long): Boolean = assets.isReferenced(id)

    override suspend fun setOrphanedAt(id: Long, orphanedAt: Instant?) {
        writes.inTransaction {
            requireOneRow(assets.setOrphanedAt(id, orphanedAt?.toStoredMillis()))
        }
    }
}
