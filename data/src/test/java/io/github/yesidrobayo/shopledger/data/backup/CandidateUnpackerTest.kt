package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.crypto.BackupHeader
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Preparar el archivo de una copia: la suma antes de descomprimir, el formato por sus bytes, la
 * descompresión con tope y nada a medias. Todos los datos son inventados.
 */
class CandidateUnpackerTest {
    @get:Rule val folder = TemporaryFolder()

    private val content = Random(SEED).nextBytes(200_000)
    private val candidateFolder get() = File(folder.root, "candidate")
    private val target get() = File(candidateFolder, "candidate.db")

    @Test
    fun `a sound copy is decompressed whole with the sum of its file`() = runBlocking {
        val source = file("incoming.db.gz", gzip(content))

        val result = unpacker().unpack(source, target, expectedSha256 = sha256(source.readBytes()))

        assertEquals(UnpackResult.Unpacked(target, sha256(source.readBytes()), 200_000L), result)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `without an expected sum the copy is still decompressed and its sum given`() = runBlocking {
        val source = file("incoming.db.gz", gzip(content))

        val result = unpacker().unpack(source, target, expectedSha256 = null)

        assertEquals(sha256(source.readBytes()), (result as UnpackResult.Unpacked).sourceSha256)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `one changed byte is refused by the sum before decompressing`() = runBlocking {
        val packed = gzip(content)
        val expected = sha256(packed)
        packed[packed.size / 2] = (packed[packed.size / 2] + 1).toByte()
        val source = file("incoming.db.gz", packed)

        val result = unpacker().unpack(source, target, expected)

        assertEquals(UnpackResult.Rejected(CandidateRejection.CHECKSUM_MISMATCH), result)
        assertFalse(target.exists())
        assertFalse("No se creó ni la carpeta", candidateFolder.exists())
    }

    @Test
    fun `the same changed byte without a sum fails when decompressing`() = runBlocking {
        val packed = gzip(content)
        packed[packed.size / 2] = (packed[packed.size / 2] + 1).toByte()

        val result = unpacker().unpack(file("picked", packed), target, expectedSha256 = null)

        assertEquals(UnpackResult.Rejected(CandidateRejection.DAMAGED_ARCHIVE), result)
        assertFalse(target.exists())
    }

    @Test
    fun `a truncated archive is refused`() = runBlocking {
        val packed = gzip(content)
        val source = file("incoming.db.gz", packed.copyOf(packed.size - 9))

        val result = unpacker().unpack(source, target, expectedSha256 = null)

        assertEquals(UnpackResult.Rejected(CandidateRejection.DAMAGED_ARCHIVE), result)
        assertFalse(target.exists())
    }

    @Test
    fun `a decompression bomb stops at the limit and leaves nothing`() = runBlocking {
        // Unos pocos kilobytes que se expanden a 64 MiB de ceros.
        val bomb = gzipZeros(64L * 1024 * 1024)
        assertTrue("La bomba ocupa ${bomb.size} bytes", bomb.size < 100_000)
        val limit = 4L * 1024 * 1024
        val source = file("incoming.db.gz", bomb)

        val result = unpacker(limit).unpack(source, target, expectedSha256 = null)

        assertEquals(UnpackResult.Rejected(CandidateRejection.DECOMPRESSED_TOO_LARGE), result)
        assertFalse(target.exists())
    }

    @Test
    fun `every member of a multi member archive counts for the limit`() = runBlocking {
        val half = content.copyOf(100_000)
        val members = gzip(half) + gzip(half) + gzip(half)
        val source = file("incoming.db.gz", members)

        val whole = unpacker(limit = 300_000).unpack(source, target, expectedSha256 = null)
        assertArrayEquals(half + half + half, target.readBytes())
        assertEquals(300_000L, (whole as UnpackResult.Unpacked).rawSizeBytes)

        val over = unpacker(limit = 299_999).unpack(source, target, expectedSha256 = null)
        assertEquals(UnpackResult.Rejected(CandidateRejection.DECOMPRESSED_TOO_LARGE), over)
        assertFalse(target.exists())
    }

    @Test
    fun `the name stored inside the archive is ignored`() = runBlocking {
        // Un gzip con un nombre interno que intenta salir de la carpeta.
        val named = gzipNamed(content, "../../databases/shopledger.db")
        val source = file("incoming.db.gz", named)

        val result = unpacker().unpack(source, target, expectedSha256 = null)

        assertTrue(result is UnpackResult.Unpacked)
        assertArrayEquals(content, target.readBytes())
        assertEquals(listOf("candidate.db"), candidateFolder.list().orEmpty().toList())
        assertFalse(File(folder.root, "databases").exists())
    }

    @Test
    fun `the format comes from the first bytes`() = runBlocking {
        // Empieza como una copia cifrada pero no lo es: se rechaza sin pedir ninguna clave.
        val encrypted = byteArrayOf(0x53, 0x4C, 0x42, 0x4B) + content
        assertTrue(
            unpacker().unpack(file("copy.db.gz", encrypted), target, expectedSha256 = null) is
                UnpackResult.Rejected,
        )
        // Una base sin comprimir con nombre de copia no se toma por copia.
        val plain = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + content
        assertEquals(
            UnpackResult.Rejected(CandidateRejection.UNKNOWN_FORMAT),
            unpacker().unpack(file("copy.db.gz", plain), target, expectedSha256 = null),
        )
        for (short in listOf(byteArrayOf(), byteArrayOf(0x1F), byteArrayOf(0x53, 0x4C, 0x42))) {
            assertEquals(
                UnpackResult.Rejected(CandidateRejection.UNKNOWN_FORMAT),
                unpacker().unpack(file("short", short), target, expectedSha256 = null),
            )
        }
        assertFalse(target.exists())
    }

    @Test
    fun `an encrypted copy opens with the key of its header and says which key it was`() =
        runBlocking {
            val packed = encrypt(content)
            val source = file("incoming.slbk", packed)

            val result = unpacker().unpack(source, target, sha256(packed), listOf(other, key))

            assertEquals(
                UnpackResult.Unpacked(target, sha256(packed), content.size.toLong(), key.keyIdHex),
                result,
            )
            assertArrayEquals(content, target.readBytes())
        }

    @Test
    fun `without the key of its header an encrypted copy decrypts nothing`() = runBlocking {
        val source = file("incoming.slbk", encrypt(content))

        assertEquals(
            UnpackResult.NeedsKey(key.keyIdHex),
            unpacker().unpack(source, target, expectedSha256 = null),
        )
        assertEquals(
            UnpackResult.NeedsKey(key.keyIdHex),
            unpacker().unpack(source, target, expectedSha256 = null, keys = listOf(other)),
        )
        assertFalse(target.exists())
    }

    @Test
    fun `a damaged encrypted copy is refused and leaves nothing`() = runBlocking {
        val packed = encrypt(content)
        packed[packed.size - 20] = (packed[packed.size - 20] + 1).toByte()

        val result =
            unpacker().unpack(file("picked", packed), target, expectedSha256 = null, listOf(key))

        assertEquals(UnpackResult.Rejected(CandidateRejection.DAMAGED_ARCHIVE), result)
        assertFalse(target.exists())
    }

    @Test
    fun `the header is checked before any key`() = runBlocking {
        val packed = encrypt(content)
        // Un formato más nuevo pide actualizar, nunca dice copia dañada, y no necesita clave.
        val newer = packed.copyOf().also { it[9] = 3 }
        assertEquals(
            UnpackResult.Rejected(CandidateRejection.FORMAT_TOO_NEW),
            unpacker().unpack(file("newer", newer), target, expectedSha256 = null),
        )
        // Una base más grande que el tope se rechaza por su cabecera, sin descifrar.
        assertEquals(
            UnpackResult.Rejected(CandidateRejection.DECOMPRESSED_TOO_LARGE),
            unpacker(limit = content.size - 1L)
                .unpack(file("large", packed), target, expectedSha256 = null),
        )
        // Cortada dentro de la cabecera: dañada.
        assertEquals(
            UnpackResult.Rejected(CandidateRejection.DAMAGED_ARCHIVE),
            unpacker().unpack(file("cut", packed.copyOf(40)), target, expectedSha256 = null),
        )
        assertFalse(target.exists())
    }

    @Test
    fun `a leftover candidate from a cut run is replaced`() = runBlocking {
        candidateFolder.mkdirs()
        target.writeBytes(ByteArray(10) { 7 })
        val source = file("incoming.db.gz", gzip(content))

        unpacker().unpack(source, target, expectedSha256 = null)

        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `a missing source is a disk failure that leaves nothing`() = runBlocking {
        val result =
            unpacker().unpack(File(folder.root, "missing.gz"), target, expectedSha256 = null)

        assertTrue(result is UnpackResult.Failed)
        assertFalse(target.exists())
    }

    @Test
    fun `the default limit is the restore limit`() {
        assertEquals(1024L * 1024 * 1024, BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES)
    }

    private val key = BackupKey.from(RecoveryCode("0123456789ABCDEFGHJKMNPQRS"))
    private val other = BackupKey.from(RecoveryCode("SRQPNMKJHGFEDCBA9876543210"))

    /** [bytes] como copia cifrada con [key], de la generación 1. */
    private fun encrypt(bytes: ByteArray): ByteArray {
        val header = BackupHeader(key.keyId, 1, 1, 1_000L, UUID(1, 2), bytes.size.toLong())
        val out = ByteArrayOutputStream()
        EncryptedCopy.write(bytes.inputStream(), out, header, key)
        return out.toByteArray()
    }

    private fun unpacker(limit: Long = BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES) =
        CandidateUnpacker(Dispatchers.IO, limit)

    private fun file(name: String, bytes: ByteArray): File =
        File(folder.root, name).apply { writeBytes(bytes) }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun gzipZeros(size: Long): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { gzip ->
            val zeros = ByteArray(1024 * 1024)
            repeat((size / zeros.size).toInt()) { gzip.write(zeros) }
        }
    }.toByteArray()

    /** Un gzip con el campo de nombre del encabezado, que `GZIPOutputStream` no escribe. */
    private fun gzipNamed(bytes: ByteArray, name: String): ByteArray {
        val plain = gzip(bytes)
        val flags = 0x08
        val header = plain.copyOf(10).also { it[3] = flags.toByte() }
        return header + name.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            plain.copyOfRange(10, plain.size)
    }

    private fun sha256(bytes: ByteArray): String = newSha256().digest(bytes).toLowerHex()

    private companion object {
        const val SEED = 20_261_107L
    }
}
