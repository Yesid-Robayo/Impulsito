package io.github.yesidrobayo.shopledger.data.catalog

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.media.MediaCleanup
import io.github.yesidrobayo.shopledger.data.media.MediaSyncStore
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductPhotoChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La limpieza de las fotos que nada usa, sobre la base real: marca las que perdieron su uso, borra
 * a los 30 días la fila y el archivo y la anota para borrarla del servicio, devuelve la marca a la
 * que volvió a usarse, y sin nada que hacer no escribe nada.
 */
internal class MediaCleanupTest : CatalogDatabaseTest() {
    private val ports by lazy {
        DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
    }
    private val root by lazy { File(context.cacheDir, "media-cleanup").apply { mkdirs() } }
    private val media by lazy { File(root, "media").apply { mkdirs() } }
    private val create by lazy {
        CreateProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings), FIXED_CLOCK,
            ports.mediaAssets,
        )
    }

    @After
    fun removeFiles() {
        root.deleteRecursively()
    }

    @Test
    fun unusedPhotosAreMarkedThenDeletedAfterThirtyDays() = runBlocking {
        val now = Instant.fromEpochMilliseconds(CREATED_AT)
        saved(create(form("Pan de queso", 'a')))
        val reused = saved(create(form("Pan de bono", 'd')))
        insertRow('b', orphanedAt = null)
        insertRow('c', orphanedAt = (now - 31.days).toEpochMilliseconds())
        execute(
            "UPDATE media_assets SET orphaned_at = ${(now - 40.days).toEpochMilliseconds()} " +
                "WHERE sha256 = '${sha('d')}'",
        )
        insertRow('e', orphanedAt = (now - 29.days).toEpochMilliseconds())
        listOf('a', 'b', 'c', 'd', 'e').forEach { photoFile(it).writeBytes(ByteArray(10)) }
        val changeSeq = count(CHANGE_SEQ)

        cleanup().run(now)

        // En uso: sin marca, con su archivo.
        assertEquals(0L, orphanedAt('a'))
        assertTrue(photoFile('a').isFile)
        // Sin uso desde hoy: marcada, con su archivo.
        assertEquals(CREATED_AT, orphanedAt('b'))
        assertTrue(photoFile('b').isFile)
        // Marcada hace 31 días: fila y archivo borrados, y anotada para el servicio.
        assertEquals(0L, count("SELECT COUNT(*) FROM media_assets WHERE sha256 = '${sha('c')}'"))
        assertFalse(photoFile('c').exists())
        // Marcada pero vuelta a usar: pierde la marca y se queda.
        assertEquals(0L, orphanedAt('d'))
        assertTrue(photoFile('d').isFile)
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM products WHERE id = $reused AND photo_media_id IS NOT NULL",
            ),
        )
        // Marcada hace 29 días: todavía no.
        assertTrue(photoFile('e').isFile)
        assertEquals(setOf(sha('c')), store().deletions())
        // Marcar y borrar son escrituras: cuentan, una por cada transacción.
        assertEquals(changeSeq + 2, count(CHANGE_SEQ))
    }

    @Test
    fun aClockSetBackNeverDeletesEarly() = runBlocking {
        val now = Instant.fromEpochMilliseconds(CREATED_AT)
        insertRow('c', orphanedAt = (now + 5.days).toEpochMilliseconds())
        photoFile('c').writeBytes(ByteArray(10))
        val changeSeq = count(CHANGE_SEQ)

        cleanup().run(now)

        assertTrue(photoFile('c').isFile)
        assertEquals(1L, count("SELECT COUNT(*) FROM media_assets"))
        assertEquals(emptySet<String>(), store().deletions())
        // Sin nada que marcar ni borrar, no escribe ni cuenta nada.
        assertEquals(changeSeq, count(CHANGE_SEQ))
    }

    private fun cleanup() = MediaCleanup(
        rows = ports.mediaAssetRows,
        transactions = ports.transactions,
        store = ::store,
        mediaDirectory = { media },
        ioContext = Dispatchers.IO,
        log = SilentLog,
    )

    private fun store() = MediaSyncStore(root, Dispatchers.IO)

    private fun photoFile(digit: Char) = File(media, StoredImage.fileNameOf(sha(digit)))

    private suspend fun orphanedAt(digit: Char): Long =
        count("SELECT COALESCE(orphaned_at, 0) FROM media_assets WHERE sha256 = '${sha(digit)}'")

    private suspend fun insertRow(digit: Char, orphanedAt: Long?) = execute(
        "INSERT INTO media_assets (sha256, kind, mime_type, width_px, height_px, size_bytes, " +
            "created_at, orphaned_at) VALUES ('${sha(digit)}', 'PRODUCT_PHOTO', 'image/webp', " +
            "640, 480, 21000, $CREATED_AT, ${orphanedAt ?: "NULL"})",
    )

    private fun form(name: String, digit: Char) = ProductForm(
        name = name,
        salePrice = Money(2_500L),
        photo = ProductPhotoChange.Use(
            StoredImage(sha(digit), "image/webp", 640, 480, 21_000L),
        ),
    )

    private fun saved(result: SaveProductResult): Long =
        (result as? SaveProductResult.Saved)?.productId
            ?: throw AssertionError("No se guardó: $result")

    private fun sha(digit: Char) = digit.toString().repeat(64)

    private companion object {
        const val CHANGE_SEQ = "SELECT COALESCE(MAX(change_seq), -1) FROM backup_state"
    }

    private object SilentLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }
}
