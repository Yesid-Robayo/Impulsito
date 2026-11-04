package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.texts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los metadatos de las imágenes: sin rutas ni referencias del destino, una fila por contenido,
 * huérfanas que la limpieza encuentra por fecha, y productos que quedan sin foto al borrarla.
 */
@RunWith(AndroidJUnit4::class)
internal class MediaAssetDaoTest : CatalogDatabaseTest() {
    private val media get() = database.mediaAssetDao()

    @Test
    fun theTableKeepsNoPathNorRemoteReference() = runBlocking {
        // Solo el contenido y sus medidas: el archivo se encuentra por su sha256, en cualquier
        // teléfono y con cualquier destino.
        val columns =
            database.useReaderConnection {
                it.texts("SELECT name FROM pragma_table_info('media_assets') ORDER BY cid")
            }

        assertEquals(
            listOf(
                "id",
                "sha256",
                "kind",
                "mime_type",
                "width_px",
                "height_px",
                "size_bytes",
                "created_at",
                "orphaned_at",
            ),
            columns,
        )
    }

    @Test
    fun anImageIsStoredOncePerContentWithEveryColumn() = runBlocking {
        val logo =
            mediaAsset('c', orphanedAt = LATER).copy(
                kind = "BUSINESS_LOGO",
                mimeType = "image/png",
                widthPx = 512,
                heightPx = 256,
                sizeBytes = 2_097_152,
            )
        val id = media.insert(logo)

        constraintFailure(UNIQUE) { media.insert(mediaAsset('c')) }

        assertEquals(logo.copy(id = id), media.findById(id))
        assertEquals(id, media.findBySha256("c".repeat(64))?.id)
        assertNull(media.findBySha256("d".repeat(64)))
        assertNull(media.findById(MISSING_ID))
    }

    @Test
    fun cleanupFindsOnlyTheOnesOrphanedBeforeItsCutoffInIdOrder() = runBlocking {
        val inUse = media.insert(mediaAsset('1'))
        val recent = media.insert(mediaAsset('2', orphanedAt = MUCH_LATER))
        val old = media.insert(mediaAsset('3', orphanedAt = CREATED_AT))
        val older = media.insert(mediaAsset('4', orphanedAt = CREATED_AT))

        assertEquals(listOf(old, older), media.findOrphanedBefore(LATER).map { it.id })

        // Volver a usarla la saca de la limpieza, y el corte es estricto.
        assertEquals(1, media.setOrphanedAt(old, orphanedAt = null))
        assertEquals(1, media.setOrphanedAt(inUse, orphanedAt = LATER))
        assertEquals(listOf(older), media.findOrphanedBefore(LATER).map { it.id })
        assertEquals(
            listOf(inUse, recent, older),
            media.findOrphanedBefore(MUCH_LATER + 1).map { it.id },
        )
        assertEquals(0, media.setOrphanedAt(MISSING_ID, orphanedAt = LATER))
    }

    @Test
    fun deletingAnImageLeavesItsProductsWithoutPhoto() = runBlocking {
        val photo = media.insert(mediaAsset())
        val products = database.productDao()
        val first = products.insert(product("Queso").copy(photoMediaId = photo))
        val second = products.insert(product("Pan").copy(photoMediaId = photo))

        constraintFailure(FOREIGN_KEY) {
            products.insert(product("Leche").copy(photoMediaId = MISSING_ID))
        }
        assertEquals(1, media.delete(photo))

        assertNull(products.findById(first)?.photoMediaId)
        assertNull(products.findById(second)?.photoMediaId)
        assertEquals(2L, count("SELECT COUNT(*) FROM products"))
        assertEquals(0, media.delete(photo))
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}
