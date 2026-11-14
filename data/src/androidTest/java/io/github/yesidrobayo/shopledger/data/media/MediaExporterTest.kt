package io.github.yesidrobayo.shopledger.data.media

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.backup.ExportSink
import io.github.yesidrobayo.shopledger.data.backup.PackEncryption
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedReadResult
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoExport
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Exportar fotos`: un ZIP con cada foto en uso, en claro sin cifrado y como `.slmd` con el cifrado
 * activo, nunca en claro sin la clave, y nada a medias si algo falla. Las fotos son bytes inventados.
 */
class MediaExporterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "media-export-" + Random.nextLong().toULong())
    private val media = File(root, "media").apply { mkdirs() }
    private val document = File(root, "documento.zip")
    private var discarded = false

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun inClearEachPhotoGoesAsItIsAndAMissingFileIsLeftOut() = runBlocking {
        val photos = listOf(photo(1), photo(2))
        val missing = StoredImage("f".repeat(64), "image/webp", 640, 480, 10)

        val result = exporter(photos + missing, PackEncryption.Plain).export("documento")

        assertEquals(PhotoExport.Saved(2), result)
        val entries = entries()
        assertEquals(photos.map { "${it.sha256}.webp" }.toSet(), entries.keys)
        for (photo in photos) {
            assertArrayEquals(fileOf(photo).readBytes(), entries.getValue("${photo.sha256}.webp"))
        }
        assertFalse(root.walk().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun withEncryptionEachPhotoIsSealedAndNothingGoesInClear() = runBlocking {
        val photo = photo(1)

        exporter(listOf(photo), PackEncryption.Encrypt(CODE)).export("documento")

        val sealed = entries().getValue("${photo.sha256}.slmd")
        val plain = ByteArrayOutputStream()
        val read = EncryptedCopy.read(sealed.inputStream(), BackupKey.from(CODE), sink = plain)
        assertTrue(read.toString(), read is EncryptedReadResult.Read)
        assertArrayEquals(fileOf(photo).readBytes(), plain.toByteArray())
    }

    @Test
    fun withoutTheKeyOrWithoutPhotosNothingIsWritten() = runBlocking {
        val photo = photo(1)

        assertEquals(
            PhotoExport.KeyUnavailable,
            exporter(listOf(photo), PackEncryption.KeyUnavailable).export("documento"),
        )
        assertEquals(PhotoExport.NoPhotos, exporter(emptyList(), PackEncryption.Plain).export("d"))
        assertFalse(document.exists())
    }

    @Test
    fun aDocumentThatReadsBackDifferentIsDiscarded() = runBlocking {
        val result =
            exporter(listOf(photo(1)), PackEncryption.Plain, corrupt = true).export("documento")

        assertEquals(PhotoExport.Failed, result)
        assertTrue(discarded)
    }

    private fun exporter(
        photos: List<StoredImage>,
        encryption: PackEncryption,
        corrupt: Boolean = false,
    ) = MediaExporter(
        photos = { photos },
        mediaDirectory = { media },
        workDirectory = { File(root, "exports") },
        sinkFor = {
            object : ExportSink {
                override fun write(input: InputStream) {
                    val bytes = input.readBytes()
                    if (corrupt) bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
                    document.writeBytes(bytes)
                }

                override fun read(): InputStream = document.inputStream()

                override fun discard() {
                    discarded = true
                    document.delete()
                }
            }
        },
        encryption = { encryption },
        deviceId = { "00000000-0000-4000-8000-000000000001" },
        nowUtcMillis = { 1_767_225_600_000L },
        ioContext = Dispatchers.IO,
    )

    private fun entries(): Map<String, ByteArray> = ZipInputStream(document.inputStream()).use {
        generateSequence { it.nextEntry }.associate { entry -> entry.name to it.readBytes() }
    }

    private fun fileOf(photo: StoredImage) = File(media, StoredImage.fileNameOf(photo.sha256))

    private fun photo(seed: Int): StoredImage {
        val bytes = Random(seed).nextBytes(3_000 + seed)
        val sha =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        File(media, StoredImage.fileNameOf(sha)).writeBytes(bytes)
        return StoredImage(sha, "image/webp", 640, 480, bytes.size.toLong())
    }

    private companion object {
        val CODE = RecoveryCode("0123456789ABCDEFGHJKMNPQRS")
    }
}
