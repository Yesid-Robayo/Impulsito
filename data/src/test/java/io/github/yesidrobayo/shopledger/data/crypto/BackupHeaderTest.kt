package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupHeaderField
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La cabecera del formato 2, byte a byte, y su lectura contra la lista cerrada. */
class BackupHeaderTest {
    private val keyId = hex("8755f48d645c0bad")
    private val device = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")

    private fun header(extensions: ByteArray = ByteArray(0)) = BackupHeader(
        keyId = keyId,
        generation = 0x0102030405060708L,
        schemaVersion = 0xFFFF_FFFEL,
        createdAtUtcMillis = 1_793_000_000_000L,
        deviceId = device,
        rawSize = 47_500_000L,
        extensions = extensions,
    )

    @Test
    fun `the fixed header is laid out as the specification says`() {
        // Escrito a mano desde la tabla de la especificación, campo por campo.
        val expected =
            "534c424b0d0a1a0a" + // magic
                "0002" + // format_version
                "0044" + // header_len = 68
                "01" + "01" + "01" + "00" + // aead_id, key_source, payload_codec, reserved
                "8755f48d645c0bad" + // key_id
                "0102030405060708" + // generation
                "fffffffe" + // schema_version
                "000001a17720ca00" + // created_at_utc_ms
                "00112233445566778899aabbccddeeff" + // device_id
                "0000000002d4cae0" // raw_size
        assertEquals(expected, header().encode().toLowerHex())
        assertEquals(68, header().length)
    }

    @Test
    fun `the offsets agree with the closed list of the domain`() {
        val bytes = header().encode()
        val buffer = ByteBuffer.wrap(bytes)
        assertEquals(2, buffer.getShort(BackupHeaderField.FORMAT_VERSION.offset).toInt())
        assertEquals(68, buffer.getShort(BackupHeaderField.HEADER_LEN.offset).toInt())
        assertEquals(0x0102030405060708L, buffer.getLong(BackupHeaderField.GENERATION.offset))
        assertEquals(47_500_000L, buffer.getLong(BackupHeaderField.RAW_SIZE.offset))
        assertEquals(BackupHeaderField.FIXED_LENGTH, bytes.size)
    }

    @Test
    fun `what is written is read back the same`() {
        val extension = tlv(0x7F01, byteArrayOf(9, 9, 9))
        val written = header(extension)
        val read = BackupHeader.read(ByteArrayInputStream(written.encode() + ByteArray(5)))
        assertTrue(read is HeaderReadResult.Valid)
        read as HeaderReadResult.Valid
        assertArrayEquals(written.encode(), read.bytes)
        assertArrayEquals(keyId, read.header.keyId)
        assertEquals(0x0102030405060708L, read.header.generation)
        assertEquals(0xFFFF_FFFEL, read.header.schemaVersion)
        assertEquals(1_793_000_000_000L, read.header.createdAtUtcMillis)
        assertEquals(device, read.header.deviceId)
        assertEquals(47_500_000L, read.header.rawSize)
        assertArrayEquals(extension, read.header.extensions)
        assertEquals(75, read.header.length)
    }

    @Test
    fun `every closed field outside its list is rejected before any key`() {
        val cases =
            mapOf(
                BackupHeaderField.AEAD_ID to header().encode().with(12, 2),
                BackupHeaderField.KEY_SOURCE to header().encode().with(13, 0),
                BackupHeaderField.PAYLOAD_CODEC to header().encode().with(14, 2),
                BackupHeaderField.RESERVED to header().encode().with(15, 1),
                BackupHeaderField.HEADER_LEN to header().encode().withShort(10, 67),
                BackupHeaderField.FORMAT_VERSION to header().encode().withShort(8, 1),
                BackupHeaderField.GENERATION to header().encode().with(24, 0x80),
                BackupHeaderField.RAW_SIZE to header().encode().with(60, 0x80),
            )
        for ((field, bytes) in cases) {
            assertEquals(field.name, HeaderReadResult.Rejected(field), read(bytes))
        }
        val tooLong = header().encode().withShort(10, 4097) + ByteArray(4097 - 68)
        assertEquals(HeaderReadResult.Rejected(BackupHeaderField.HEADER_LEN), read(tooLong))
    }

    @Test
    fun `a broken extension is rejected`() {
        val fixed = header().encode()
        val brokenTlv = byteArrayOf(0x7F, 0x01)
        val broken = (fixed + brokenTlv).withShort(10, fixed.size + brokenTlv.size)
        assertEquals(HeaderReadResult.Rejected(BackupHeaderField.EXTENSIONS), read(broken))
        val overflowing = tlv(0x7F01, byteArrayOf(1, 2, 3)).with(3, 9)
        val long = (fixed + overflowing).withShort(10, fixed.size + overflowing.size)
        assertEquals(HeaderReadResult.Rejected(BackupHeaderField.EXTENSIONS), read(long))
    }

    @Test
    fun `a newer format asks to update and never says damaged`() {
        assertEquals(HeaderReadResult.NewerFormat(3), read(header().encode().withShort(8, 3)))
        assertEquals(
            HeaderReadResult.NewerFormat(65535),
            read(header().encode().withShort(8, 0xFFFF)),
        )
    }

    @Test
    fun `the format comes from the first bytes`() {
        assertEquals(HeaderReadResult.PlainFormat, read(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)))
        assertEquals(HeaderReadResult.NotEncryptedCopy, read("SQLite format 3".toByteArray()))
        assertEquals(HeaderReadResult.NotEncryptedCopy, read(header().encode().with(3, 0x4C)))
        assertEquals(HeaderReadResult.NotEncryptedCopy, read(ByteArray(0)))
        assertEquals(HeaderReadResult.NotEncryptedCopy, read(byteArrayOf(0x53, 0x4C)))
    }

    @Test
    fun `a file that ends inside the header is truncated`() {
        assertEquals(HeaderReadResult.Truncated, read(header().encode().copyOf(10)))
        assertEquals(HeaderReadResult.Truncated, read(header().encode().copyOf(67)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a key id of the wrong size cannot be written`() {
        BackupHeader(ByteArray(7), 1, 1, 0, device, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed extensions cannot be written`() {
        header(byteArrayOf(1, 2, 3))
    }

    private fun read(bytes: ByteArray) = BackupHeader.read(ByteArrayInputStream(bytes))

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

    private fun hex(text: String) = ByteArray(text.length / 2) {
        text.substring(2 * it, 2 * it + 2).toInt(16).toByte()
    }
}
