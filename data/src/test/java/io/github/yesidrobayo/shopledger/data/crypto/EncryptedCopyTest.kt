package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupHeaderField
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ida y vuelta del formato 2, su sobrecarga, la memoria acotada y la prueba de suplantación: cada
 * alteración se rechaza con su causa, y nada se descifra con otro código. Todos los datos son
 * inventados y la semilla es fija.
 */
class EncryptedCopyTest {
    private val random = Random(SEED)

    /** Un código inventado, fijo: las pruebas no dependen del azar. */
    private val key = BackupKey.from(RecoveryCode("0123456789ABCDEFGHJKMNPQRS"))
    private val otherKey = BackupKey.from(
        RecoveryCodec.parse(OTHER_CODE).let {
            (it as CodeParseResult.Valid).code
        },
    )
    private val device = UUID(random.nextLong(), random.nextLong())

    /** Una base inventada de varios segmentos: el comienzo de SQLite y bytes que no comprimen. */
    private val database: ByteArray =
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + random.nextBytes(PAYLOAD_BYTES)
    private val databaseSha = sha256(database)

    private fun header(
        generation: Long = GENERATION,
        createdAt: Long = 1_000_000L,
        rawSize: Long = database.size.toLong(),
        extensions: ByteArray = ByteArray(0),
    ) = BackupHeader(key.keyId, generation, 1, createdAt, device, rawSize, extensions)

    private fun encrypt(header: BackupHeader = header(), content: ByteArray = database): ByteArray {
        val out = ByteArrayOutputStream()
        val written = EncryptedCopy.write(ByteArrayInputStream(content), out, header, key)
        assertEquals(sha256(content), written.sha256)
        return out.toByteArray()
    }

    private val copyA by lazy { encrypt() }
    private val copyB by lazy {
        encrypt(header(generation = GENERATION + 1, createdAt = 2_000_000L))
    }

    private fun read(bytes: ByteArray, readKey: BackupKey? = key): EncryptedReadResult =
        EncryptedCopy.read(ByteArrayInputStream(bytes), readKey)

    @Test
    fun `a copy round trips to the same database`() {
        val sink = ByteArrayOutputStream()
        val result = EncryptedCopy.read(ByteArrayInputStream(copyA), key, sink)
        assertTrue(result is EncryptedReadResult.Read)
        result as EncryptedReadResult.Read
        assertEquals(RawContent(database.size.toLong(), databaseSha), result.content)
        assertEquals(databaseSha, sha256(sink.toByteArray()))
        assertEquals(GENERATION, result.header.generation)
        assertEquals(key.keyIdHex, result.header.keyId.toLowerHex())
        // Lo que no está cifrado es solo la cabecera.
        assertTrue(copyA.copyOf(BackupHeader.FIXED_LENGTH).contentEquals(header().encode()))
    }

    @Test
    fun `the overhead over the plain copy is the header, Tink's header and one tag per segment`() {
        val gzip = ByteArrayOutputStream()
        GZIPOutputStream(gzip, EncryptedCopy.BUFFER_BYTES).use { it.write(database) }
        val plain = gzip.size().toLong()
        val perSegment = BackupCipher.CIPHERTEXT_SEGMENT_BYTES - BackupCipher.TAG_BYTES
        val stream = plain + BackupCipher.STREAM_HEADER_BYTES
        val segments = (stream + perSegment - 1) / perSegment
        assertEquals(68 + 40 + 16 * segments, copyA.size - plain)
    }

    @Test
    fun `an empty file and a photo header also round trip`() {
        val empty = encrypt(header(rawSize = 0), ByteArray(0))
        assertEquals(
            RawContent(0, sha256(ByteArray(0))),
            (read(empty) as EncryptedReadResult.Read).content,
        )
        val photo = random.nextBytes(30_000)
        val media = BackupHeader(key.keyId, 0, 0, 5L, device, photo.size.toLong())
        val bytes = encrypt(media, photo)
        val result = read(bytes) as EncryptedReadResult.Read
        assertEquals(sha256(photo), result.content.sha256)
        assertEquals(0L, result.header.generation)
        assertEquals(0L, result.header.schemaVersion)
    }

    @Test
    fun `writing streams out without holding the file`() {
        // Una fuente de 12 MiB generada al vuelo: si el escritor la guardara entera, el destino no
        // recibiría nada hasta el final.
        val total = 12L shl 20
        val destination = CountingOutput()
        var maxAhead = 0L
        val source =
            GeneratedInput(total) { consumed ->
                maxAhead = maxOf(maxAhead, consumed - destination.count)
            }
        EncryptedCopy.write(source, destination, header(rawSize = total), key)
        // Lo leído nunca va más de dos segmentos y el búfer del gzip por delante de lo escrito.
        assertTrue(
            "Va $maxAhead bytes por delante",
            maxAhead < 2L * BackupCipher.CIPHERTEXT_SEGMENT_BYTES + 2 * EncryptedCopy.BUFFER_BYTES,
        )
        assertTrue(destination.count > total)
    }

    @Test
    fun `reading streams out without holding the file`() {
        val total = 12L shl 20
        val encrypted = ByteArrayOutputStream()
        EncryptedCopy.write(GeneratedInput(total) {}, encrypted, header(rawSize = total), key)
        val bytes = encrypted.toByteArray()
        val sink = CountingOutput()
        var maxAhead = 0L
        val source =
            object : InputStream() {
                private val inner = ByteArrayInputStream(bytes)
                private var consumed = 0L

                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = inner.read(b, off, len)
                    if (read > 0) consumed += read
                    maxAhead = maxOf(maxAhead, consumed - sink.count)
                    return read
                }
            }
        val result = EncryptedCopy.read(source, key, sink)
        assertTrue(result is EncryptedReadResult.Read)
        assertEquals(total, sink.count)
        assertTrue(
            "Va $maxAhead bytes por delante",
            maxAhead < 2L * BackupCipher.CIPHERTEXT_SEGMENT_BYTES + 2 * EncryptedCopy.BUFFER_BYTES,
        )
    }

    @Test(expected = RawSizeMismatch::class)
    fun `a source shorter than its header fails the write`() {
        EncryptedCopy.write(
            ByteArrayInputStream(database),
            ByteArrayOutputStream(),
            header(
                rawSize =
                    database.size + 1L,
            ),
            key,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a header of another key is not written`() {
        val foreign = BackupHeader(otherKey.keyId, 1, 1, 1, device, 1)
        EncryptedCopy.encryptingStream(ByteArrayOutputStream(), foreign, key)
    }

    @Test
    fun `every tampering is rejected with its cause and downgrade only reads as plain`() {
        val a = copyA
        val b = copyB
        val headerLength = BackupHeader.FIXED_LENGTH
        val segment = BackupCipher.CIPHERTEXT_SEGMENT_BYTES
        val tinkHeader = BackupCipher.STREAM_HEADER_BYTES
        val segmentCount = (a.size - headerLength + segment - 1) / segment
        assertTrue("Hacen falta 4 segmentos", segmentCount >= 4)
        val lastSegment = segmentCount - 1

        fun start(index: Int) = if (index ==
            0
        ) {
            headerLength + tinkHeader
        } else {
            headerLength + index * segment
        }

        fun end(index: Int) = minOf(headerLength + (index + 1) * segment, a.size)

        val damaged = EncryptedReadResult.Damaged
        val otherKeyResult = EncryptedReadResult.OtherKey(key.keyIdHex)
        fun outOfList(field: BackupHeaderField) = EncryptedReadResult.HeaderRejected(field)

        val cases =
            listOf(
                // La cabecera dentro de la lista cerrada: la autenticación la rechaza.
                Case("generation", a.flip(31), damaged),
                Case("schema_version", a.flip(35), damaged),
                Case("created_at", a.flip(43), damaged),
                Case("device_id", a.flip(44), damaged),
                Case("raw_size", a.flip(67), damaged),
                // Fuera de la lista cerrada: se rechaza antes de usar la clave.
                Case("aead_id", a.with(12, 2), outOfList(BackupHeaderField.AEAD_ID)),
                Case("key_source", a.with(13, 2), outOfList(BackupHeaderField.KEY_SOURCE)),
                Case("payload_codec", a.with(14, 2), outOfList(BackupHeaderField.PAYLOAD_CODEC)),
                Case("reserved", a.with(15, 1), outOfList(BackupHeaderField.RESERVED)),
                Case("header_len_67", a.withShort(10, 67), outOfList(BackupHeaderField.HEADER_LEN)),
                Case(
                    "header_len_4097",
                    a.withShort(10, 4097),
                    outOfList(BackupHeaderField.HEADER_LEN),
                ),
                Case(
                    "broken_extension",
                    insertExtension(a, byteArrayOf(0x7F, 0x01)),
                    outOfList(BackupHeaderField.EXTENSIONS),
                ),
                Case(
                    "added_extension",
                    insertExtension(a, tlv(0x7F01, byteArrayOf(1, 2, 3, 4))),
                    damaged,
                ),
                Case("format_3", a.withShort(8, 3), EncryptedReadResult.NewerFormat(3)),
                Case(
                    "format_1_with_slbk_magic",
                    a.withShort(8, 1),
                    outOfList(BackupHeaderField.FORMAT_VERSION),
                ),
                Case("magic", a.flip(3), EncryptedReadResult.NotEncryptedCopy),
                Case("raw_size_over_limit", a.with(60, 0x7F), EncryptedReadResult.TooLarge),
                Case(
                    "key_id",
                    a.flip(16),
                    EncryptedReadResult.OtherKey(a.flip(16).copyOfRange(16, 24).toLowerHex()),
                ),
                Case("other_code", a, otherKeyResult, otherKey),
                Case("no_code", a, otherKeyResult, null),
                // El flujo de Tink.
                Case("tink_header_length", a.with(headerLength, 0x29), damaged),
                Case("tink_salt", a.flip(headerLength + 5), damaged),
                Case("tink_nonce_prefix", a.flip(headerLength + 35), damaged),
                Case("segment_0", a.flip(start(0) + 100), damaged),
                Case("middle_segment", a.flip(start(2) + 777), damaged),
                Case("final_tag", a.flip(a.size - 1), damaged),
                Case("truncated_in_header", a.copyOf(40), damaged),
                Case("truncated_in_tink_header", a.copyOf(headerLength + 20), damaged),
                Case("truncated_at_segment_boundary", a.copyOf(start(lastSegment)), damaged),
                Case("truncated_mid_segment", a.copyOf(start(2) + 1000), damaged),
                Case("bytes_appended", a + ByteArray(16), damaged),
                Case(
                    "segment_of_other_copy_appended",
                    a + b.copyOfRange(start(1), end(1)),
                    damaged,
                ),
                Case("segments_reordered", swap(a, start(1), start(2), segment), damaged),
                Case("segment_from_other_copy", replace(a, b, start(2), end(2)), damaged),
                Case("header_from_other_copy", replace(a, b, 0, headerLength), damaged),
                Case(
                    "tink_header_from_other_copy",
                    replace(
                        a,
                        b,
                        headerLength,
                        headerLength + tinkHeader,
                    ),
                    damaged,
                ),
                Case(
                    "last_segment_from_other_copy",
                    replace(a, b, start(lastSegment), a.size),
                    damaged,
                ),
                Case("plain_copy", plainCopy(), EncryptedReadResult.PlainFormat),
            )
        val failed =
            cases.mapNotNull { case ->
                val actual = read(case.bytes, case.readKey)
                if (actual == case.expected) null else "${case.name}: $actual y no ${case.expected}"
            }
        assertEquals(emptyList<String>(), failed)
    }

    @Test
    fun `an authenticated but lying raw size is a size mismatch`() {
        // Escrita con la clave correcta y un raw_size menor: la autenticación pasa y el tamaño no.
        val lying = header(rawSize = database.size - 1L)
        val out = ByteArrayOutputStream()
        val headerBytes = lying.encode()
        out.write(headerBytes)
        GZIPOutputStream(BackupCipher(key).encryptingStream(out, headerBytes)).use {
            it.write(database)
        }
        assertEquals(EncryptedReadResult.SizeMismatch, read(out.toByteArray()))
        val longer = header(rawSize = database.size + 1L)
        val out2 = ByteArrayOutputStream()
        out2.write(longer.encode())
        GZIPOutputStream(BackupCipher(key).encryptingStream(out2, longer.encode())).use {
            it.write(database)
        }
        assertEquals(EncryptedReadResult.SizeMismatch, read(out2.toByteArray()))
    }

    @Test
    fun `an unknown authenticated extension is ignored`() {
        val extended = encrypt(header(extensions = tlv(0x7F01, byteArrayOf(9, 9))))
        val result = read(extended)
        assertTrue(result is EncryptedReadResult.Read)
        assertEquals(databaseSha, (result as EncryptedReadResult.Read).content.sha256)
    }

    @Test
    fun `the same content gives a different file each time`() {
        // La sal aleatoria de Tink da una clave distinta por copia: dos copias iguales no se parecen.
        assertTrue(!copyA.contentEquals(encrypt()))
    }

    private inner class Case(
        val name: String,
        val bytes: ByteArray,
        val expected: EncryptedReadResult,
        val readKey: BackupKey? = key,
    ) {
        override fun toString(): String = name
    }

    private fun plainCopy(): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(database) }
        return out.toByteArray()
    }

    private fun ByteArray.flip(offset: Int) = copyOf().also {
        it[offset] =
            (it[offset].toInt() xor 1).toByte()
    }

    private fun ByteArray.with(offset: Int, value: Int) = copyOf().also {
        it[offset] =
            value.toByte()
    }

    private fun ByteArray.withShort(offset: Int, value: Int) =
        copyOf().also { ByteBuffer.wrap(it).putShort(offset, value.toShort()) }

    private fun tlv(tag: Int, value: ByteArray): ByteArray = ByteBuffer
        .allocate(4 + value.size)
        .putShort(tag.toShort())
        .putShort(value.size.toShort())
        .put(value)
        .array()

    /** Inserta bytes al final de la cabecera fija y ajusta `header_len`, sin volver a cifrar. */
    private fun insertExtension(copy: ByteArray, extension: ByteArray): ByteArray {
        val fixed = BackupHeader.FIXED_LENGTH
        val out = copy.copyOfRange(0, fixed) + extension + copy.copyOfRange(fixed, copy.size)
        ByteBuffer.wrap(out).putShort(10, (fixed + extension.size).toShort())
        return out
    }

    private fun swap(copy: ByteArray, first: Int, second: Int, length: Int): ByteArray {
        val out = copy.copyOf()
        System.arraycopy(copy, second, out, first, length)
        System.arraycopy(copy, first, out, second, length)
        return out
    }

    private fun replace(copy: ByteArray, donor: ByteArray, from: Int, to: Int): ByteArray {
        val out = copy.copyOf()
        System.arraycopy(donor, from, out, from, to - from)
        return out
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

    /** Cuenta lo que recibe y lo descarta. */
    private class CountingOutput : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
        }
    }

    /** [size] bytes que no comprimen, generados al vuelo; avisa cuánto lleva entregado. */
    private class GeneratedInput(private val size: Long, private val onRead: (Long) -> Unit) :
        InputStream() {
        private val random = Random(SEED + 1)
        private var consumed = 0L

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= size) return -1
            val count = minOf(len.toLong(), size - consumed).toInt()
            random.nextBytes(b, off, off + count)
            consumed += count
            onRead(consumed)
            return count
        }
    }

    private companion object {
        const val SEED = 20261112
        const val GENERATION = 10L
        const val PAYLOAD_BYTES = 5 * (1 shl 20) + 300_000

        /** Otro código sintético, de la misma forma que el de la especificación. */
        const val OTHER_CODE = "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF51"
    }
}
