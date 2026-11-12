package io.github.yesidrobayo.shopledger.data.crypto

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El formato de las copias cifradas en el teléfono, con el Tink y el `Inflater` del sistema: ida y
 * vuelta de un archivo de varios segmentos y rechazo de un segmento alterado. Datos inventados.
 */
class EncryptedCopyDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "encrypted-copy-test").apply { mkdirs() }
    private val key = BackupKey.from(RecoveryCode("0123456789ABCDEFGHJKMNPQRS"))
    private val database =
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) +
            Random(SEED).nextBytes(PAYLOAD_BYTES)
    private val header =
        BackupHeader(key.keyId, 7, 1, 1_793_491_200_000L, UUID(1, 2), database.size.toLong())

    @After
    fun clean() {
        directory.deleteRecursively()
    }

    @Test
    fun aCopyWrittenToDiskReadsBackTheSameDatabase() {
        val file = File(directory, "copy.slbk")
        val written = file.outputStream().use {
            EncryptedCopy.write(database.inputStream(), it, header, key)
        }
        assertEquals(sha256(database), written.sha256)
        val result = file.inputStream().buffered().use { EncryptedCopy.read(it, key) }
        assertTrue(result is EncryptedReadResult.Read)
        assertEquals(
            RawContent(database.size.toLong(), sha256(database)),
            (result as EncryptedReadResult.Read).content,
        )
    }

    @Test
    fun aChangedSegmentIsDamagedAndAnotherCodeReadsNothing() {
        val file = File(directory, "copy.slbk")
        file.outputStream().use { EncryptedCopy.write(database.inputStream(), it, header, key) }
        val bytes = file.readBytes()
        val middle = BackupHeader.FIXED_LENGTH + 2 * BackupCipher.CIPHERTEXT_SEGMENT_BYTES + 99
        bytes[middle] = (bytes[middle].toInt() xor 1).toByte()
        assertEquals(EncryptedReadResult.Damaged, EncryptedCopy.read(bytes.inputStream(), key))
        val other = BackupKey.from(RecoveryCode("G7K2M9PQR4STV6WXY8Z3ABCDEF"))
        val result = EncryptedCopy.read(file.inputStream(), other)
        assertEquals(EncryptedReadResult.OtherKey(key.keyIdHex), result)
        assertFalse(result is EncryptedReadResult.Read)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

    private companion object {
        const val SEED = 26
        const val PAYLOAD_BYTES = 3 * (1 shl 20) + 12_345
    }
}
