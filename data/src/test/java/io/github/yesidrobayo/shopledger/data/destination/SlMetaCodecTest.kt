package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El `.slmeta` se escribe siempre igual y se lee como entrada no confiable: cualquier cosa que no
 * sea exactamente un objeto JSON plano con las claves conocidas en su forma deja la copia sin
 * decidir ni podar, y se ofrece para restaurar sin suma esperada. Todos los datos son inventados.
 */
class SlMetaCodecTest {
    @Test
    fun `a copy's metadata are written in a fixed order and with fixed types`() {
        val expected =
            """{"sl_kind":"backup","sl_format":1,"sl_device_id":"$DEVICE_ID",""" +
                """"sl_device_label":"Fabricante Modelo 9","sl_generation":42,""" +
                """"sl_schema_version":1,"sl_app_version":"1.4.0","sl_sha256":"$SHA",""" +
                """"sl_raw_size":85278720,"sl_file_size":47512345,"sl_records":485000,""" +
                """"sl_catalog_count":640,"sl_valid":true}"""

        assertEquals(expected, SlMetaCodec.encode(META).decodeToString())
    }

    @Test
    fun `what is written is read back the same`() {
        val encrypted = ENCRYPTED.copy(deviceLabel = null, appVersion = null)
        for (meta in listOf(META, INVALID, FATAL_PINNED, encrypted)) {
            assertEquals(
                CopyMetadata.Valid(meta),
                SlMetaCodec.decode(SlMetaCodec.encode(meta), NAME),
            )
        }
    }

    @Test
    fun `a valid metadata decides and gives the expected sum`() {
        val read = SlMetaCodec.decode(SlMetaCodec.encode(META), NAME)

        assertTrue(read.decides)
        assertEquals(SHA, read.expectedSha256)
    }

    @Test
    fun `an encrypted copy carries neither the phone nor the app version`() {
        val text = SlMetaCodec.encode(ENCRYPTED).decodeToString()

        assertFalse(text.contains("sl_device_label"))
        assertFalse(text.contains("sl_app_version"))
        assertTrue(text.contains(""""sl_format":2,"sl_key_id":"$KEY_ID""""))
    }

    @Test
    fun `the flags only appear when set`() {
        val plain = SlMetaCodec.encode(META).decodeToString()
        val flagged = SlMetaCodec.encode(FATAL_PINNED).decodeToString()

        assertFalse(plain.contains("sl_fatal") || plain.contains("sl_pinned"))
        assertTrue(flagged.endsWith(""""sl_valid":false,"sl_fatal":1,"sl_pinned":1}"""))
    }

    @Test
    fun `a label that needs escaping round trips`() {
        val meta = META.copy(deviceLabel = "Marca \"X\" \\ Ñandú 漢字 😀")

        assertEquals(CopyMetadata.Valid(meta), SlMetaCodec.decode(SlMetaCodec.encode(meta), NAME))
    }

    @Test
    fun `metadata that its own reader would refuse are never written`() {
        assertThrows(IllegalStateException::class.java) {
            SlMetaCodec.encode(META.copy(sha256 = "NO-ES-HEX"))
        }
        assertThrows(IllegalStateException::class.java) {
            SlMetaCodec.encode(META.copy(format = 2, keyId = null))
        }
    }

    @Test
    fun `missing metadata do not decide and have no expected sum`() {
        val read = SlMetaCodec.decode(null, NAME)

        assertEquals(CopyMetadata.Unusable(UnusableReason.MISSING), read)
        assertFalse(read.decides)
        assertNull(read.expectedSha256)
    }

    @Test
    fun `metadata over sixteen KiB are refused, and exactly sixteen are read`() {
        val encoded = SlMetaCodec.encode(META)
        val padding = SlMetaCodec.MAX_BYTES - encoded.size
        val exact = " ".repeat(padding).encodeToByteArray() + encoded
        val over = " ".repeat(padding + 1).encodeToByteArray() + encoded

        assertEquals(CopyMetadata.Valid(META), SlMetaCodec.decode(exact, NAME))
        assertEquals(
            CopyMetadata.Unusable(UnusableReason.TOO_LARGE),
            SlMetaCodec.decode(over, NAME),
        )
    }

    @Test
    fun `the size is counted while reading`() {
        val exact = ByteArray(SlMetaCodec.MAX_BYTES) { ' '.code.toByte() }
        val stream = TrickleStream(ByteArray(SlMetaCodec.MAX_BYTES * 4) { ' '.code.toByte() })

        assertArrayEquals(exact, SlMetaCodec.readBounded(ByteArrayInputStream(exact)))
        assertNull(SlMetaCodec.readBounded(stream))
        // No siguió leyendo después del tope y un byte.
        assertEquals(SlMetaCodec.MAX_BYTES + 1, stream.consumed)
        assertArrayEquals(ByteArray(0), SlMetaCodec.readBounded(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `text that is not exactly a flat JSON object is malformed`() {
        val valid = SlMetaCodec.encode(META).decodeToString()
        val body = valid.removePrefix("{").removeSuffix("}")
        val cases =
            listOf(
                "",
                "[]",
                "null",
                "{$body",
                "$valid}",
                "$valid x",
                "{$body,}",
                "{,$body}",
                "\uFEFF$valid",
                "{$body,\"sl_x\":{\"a\":1}}",
                "{$body,\"sl_x\":[1]}",
                "{$body,\"sl_x\":null}",
                "{$body,'sl_x':1}",
                "{$body,\"sl_x\":NaN}",
                "{$body,\"sl_x\":01}",
                "{$body,\"sl_x\":1.}",
                "{$body,\"sl_x\":1e}",
                "{$body,\"sl_x\":-}",
                "{$body,\"sl_x\":tru}",
                "{$body,\"sl_x\":\"a\u0001b\"}",
                "{$body,\"sl_x\":\"\\x\"}",
                "{$body,\"sl_x\":\"\\u12g4\"}",
                "{$body,\"sl_x\":\"\\u12\"}",
                "{$body,\"sl_x\":\"sin cierre}",
                "{$body,\"sl_x\" 1}",
                "{$body /* comentario */}",
                // Dos veces la misma clave, conocida o no: dos lectores podrían leer distinto.
                "{$body,\"sl_generation\":42}",
                "{$body,\"sl_x\":1,\"sl_x\":1}",
            )
        for (text in cases) {
            assertEquals(text, MALFORMED, SlMetaCodec.decode(text.encodeToByteArray(), NAME))
        }
    }

    @Test
    fun `bytes that are not UTF-8 are malformed`() {
        val valid = SlMetaCodec.encode(META)
        val broken = valid.copyOf(valid.size - 1) + byteArrayOf(0xC3.toByte(), '}'.code.toByte())

        assertEquals(MALFORMED, SlMetaCodec.decode(broken, NAME))
    }

    @Test
    fun `an unknown key from a newer app is ignored`() {
        val valid = SlMetaCodec.encode(META).decodeToString().removeSuffix("}")
        val newer =
            "$valid,\"sl_future\":\"x\",\"sl_other\":-1.5E+3,\"sl_flag\":false," +
                "\"sl_text\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\"}"

        assertEquals(CopyMetadata.Valid(META), SlMetaCodec.decode(newer.encodeToByteArray(), NAME))
    }

    @Test
    fun `whitespace between elements is allowed`() {
        val spaced =
            SlMetaCodec.encode(META).decodeToString()
                .replace(",", " ,\n\t")
                .replace(":", "\r: ")

        assertEquals(CopyMetadata.Valid(META), SlMetaCodec.decode(spaced.encodeToByteArray(), NAME))
    }

    @Test
    fun `a known key without its exact type or form is malformed`() {
        val cases =
            listOf(
                "\"sl_generation\":42" to "\"sl_generation\":\"42\"",
                "\"sl_generation\":42" to "\"sl_generation\":042",
                "\"sl_generation\":42" to "\"sl_generation\":42.0",
                "\"sl_generation\":42" to "\"sl_generation\":4.2e1",
                "\"sl_records\":485000" to "\"sl_records\":-1",
                "\"sl_records\":485000" to "\"sl_records\":9223372036854775808",
                "\"sl_valid\":true" to "\"sl_valid\":\"true\"",
                "\"sl_valid\":true" to "\"sl_valid\":1",
                "\"sl_format\":1" to "\"sl_format\":\"1\"",
                "\"sl_format\":1" to "\"sl_format\":3",
                "\"sl_kind\":\"backup\"" to "\"sl_kind\":\"Backup\"",
                "\"sl_sha256\":\"$SHA\"" to "\"sl_sha256\":\"${SHA.uppercase()}\"",
                "\"sl_device_id\":\"$DEVICE_ID\"" to
                    "\"sl_device_id\":\"${DEVICE_ID.uppercase()}\"",
                "\"sl_device_label\":\"Fabricante Modelo 9\"" to "\"sl_device_label\":\"\"",
                "\"sl_device_label\":\"Fabricante Modelo 9\"" to "\"sl_device_label\":\"a\\nb\"",
                "\"sl_device_label\":\"Fabricante Modelo 9\"" to "\"sl_device_label\":\"\\ud800\"",
                "\"sl_app_version\":\"1.4.0\"" to "\"sl_app_version\":\"1.4\"",
                "\"sl_app_version\":\"1.4.0\"" to "\"sl_app_version\":1",
            )
        for ((from, to) in cases) assertMalformed(to, META, from, to)
    }

    @Test
    fun `each required key is required`() {
        val text = SlMetaCodec.encode(META).decodeToString()
        val required =
            listOf(
                "\"sl_kind\":\"backup\",",
                "\"sl_format\":1,",
                "\"sl_device_id\":\"$DEVICE_ID\",",
                "\"sl_generation\":42,",
                "\"sl_schema_version\":1,",
                "\"sl_sha256\":\"$SHA\",",
                "\"sl_raw_size\":85278720,",
                "\"sl_file_size\":47512345,",
                "\"sl_records\":485000,",
                "\"sl_catalog_count\":640,",
                ",\"sl_valid\":true",
            )
        for (member in required) {
            assertTrue(member, text.contains(member))
            assertEquals(
                member,
                MALFORMED,
                SlMetaCodec.decode(text.replace(member, "").encodeToByteArray(), NAME),
            )
        }
        // Las optativas pueden faltar.
        val bare = META.copy(deviceLabel = null, appVersion = null)
        assertEquals(CopyMetadata.Valid(bare), SlMetaCodec.decode(SlMetaCodec.encode(bare), NAME))
    }

    @Test
    fun `metadata that contradict themselves are malformed`() {
        // Una foto nunca parece una copia.
        assertMalformed("media", META, "\"sl_kind\":\"backup\"", "\"sl_kind\":\"media\"")
        assertMalformed(
            "suma de foto",
            META,
            "\"sl_valid\":true",
            "\"sl_valid\":true,\"sl_media_sha256\":\"$SHA\"",
        )
        // El identificador de la clave va con el cifrado, y solo con él.
        assertMalformed("cifrada sin clave", ENCRYPTED, ",\"sl_key_id\":\"$KEY_ID\"", "")
        assertMalformed(
            "en claro con clave",
            META,
            "\"sl_format\":1",
            "\"sl_format\":1,\"sl_key_id\":\"$KEY_ID\"",
        )
        // Un fallo fatal siempre deja la copia inválida.
        assertMalformed("fatal y válida", FATAL_PINNED, "\"sl_valid\":false", "\"sl_valid\":true")
        assertMalformed("fatal con otro valor", FATAL_PINNED, "\"sl_fatal\":1", "\"sl_fatal\":0")
    }

    @Test
    fun `numbers outside their limits are malformed`() {
        val zeroName = NAME.copy(generation = 0)
        val zero = SlMetaCodec.encode(
            META,
        ).decodeToString().replace("\"sl_generation\":42", "\"sl_generation\":0")
        assertEquals(MALFORMED, SlMetaCodec.decode(zero.encodeToByteArray(), zeroName))
        assertMalformed("esquema 0", META, "\"sl_schema_version\":1", "\"sl_schema_version\":0")
        val huge = NAME.copy(schemaVersion = Int.MAX_VALUE + 1L)
        val hugeText =
            SlMetaCodec.encode(META).decodeToString()
                .replace("\"sl_schema_version\":1", "\"sl_schema_version\":${Int.MAX_VALUE + 1L}")
        assertEquals(MALFORMED, SlMetaCodec.decode(hugeText.encodeToByteArray(), huge))
        val widest = META.copy(schemaVersion = Int.MAX_VALUE.toLong())
        val widestName = NAME.copy(schemaVersion = Int.MAX_VALUE.toLong())
        assertEquals(
            CopyMetadata.Valid(widest),
            SlMetaCodec.decode(SlMetaCodec.encode(widest), widestName),
        )
        assertMalformed("sin bytes", META, "\"sl_file_size\":47512345", "\"sl_file_size\":0")
        assertMalformed("base vacía", META, "\"sl_raw_size\":85278720", "\"sl_raw_size\":0")
        assertMalformed(
            "archivo que no se podría bajar",
            META,
            "\"sl_file_size\":47512345",
            "\"sl_file_size\":${BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES + 1}",
        )
        assertMalformed(
            "base que no se podría restaurar",
            META,
            "\"sl_raw_size\":85278720",
            "\"sl_raw_size\":${BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES + 1}",
        )
        val atLimits =
            META.copy(
                fileSize = BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES,
                rawSize = BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES,
                records = Long.MAX_VALUE,
                catalogCount = 0,
            )
        assertEquals(
            CopyMetadata.Valid(atLimits),
            SlMetaCodec.decode(SlMetaCodec.encode(atLimits), NAME),
        )
    }

    @Test
    fun `metadata of another copy do not match its name`() {
        val bytes = SlMetaCodec.encode(META)
        val others =
            listOf(
                NAME.copy(generation = 43),
                NAME.copy(schemaVersion = 2),
                NAME.copy(deviceIdPrefix = "00000000"),
                NAME.copy(deviceIdPrefix = DEVICE_ID.take(7)),
                NAME.copy(deviceIdPrefix = ""),
            )
        for (name in others) {
            assertEquals(
                "$name",
                CopyMetadata.Unusable(UnusableReason.NAME_MISMATCH),
                SlMetaCodec.decode(bytes, name),
            )
        }
    }

    @Test
    fun `the phone label is cleaned and cut at its byte limit`() {
        assertEquals("Fabricante Modelo 9", SlMetaCodec.deviceLabel("Fabricante", "Modelo 9"))
        assertEquals("Marca Modelo", SlMetaCodec.deviceLabel("  Marca\u0000 ", "\tModelo\n"))
        assertEquals("Solo", SlMetaCodec.deviceLabel(null, "Solo"))
        assertNull(SlMetaCodec.deviceLabel(null, null))
        assertNull(SlMetaCodec.deviceLabel(" ", "\u0007"))
        // 124 bytes: a la letra de dos bytes que no cabe se la deja afuera entera.
        val long = SlMetaCodec.deviceLabel("a".repeat(123), "ñ")
        assertEquals("a".repeat(123), long)
        assertEquals("a".repeat(122) + "ñ", SlMetaCodec.deviceLabel("a".repeat(122) + "ñ", "x"))
        // Un emoji son dos caracteres y cuatro bytes: entra entero o no entra.
        val emoji = "a".repeat(120) + "😀"
        assertEquals(emoji, SlMetaCodec.deviceLabel(emoji, null))
        assertEquals("a".repeat(121), SlMetaCodec.deviceLabel("a".repeat(121) + "😀", null))
        // Una mitad suelta de un par no es texto.
        assertNull(SlMetaCodec.deviceLabel("\ud800", null))
    }

    private fun assertMalformed(case: String, meta: SlMeta, from: String, to: String) {
        val text = SlMetaCodec.encode(meta).decodeToString()
        assertTrue("$case: no está $from", text.contains(from))
        assertEquals(
            case,
            MALFORMED,
            SlMetaCodec.decode(text.replace(from, to).encodeToByteArray(), NAME),
        )
    }

    /** Entrega de a pocos bytes, como una red lenta, y cuenta cuántos entregó. */
    private class TrickleStream(private val bytes: ByteArray) : InputStream() {
        var consumed = 0
            private set

        override fun read(): Int = if (consumed < bytes.size) bytes[consumed++].toInt() else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= bytes.size) return -1
            val count = minOf(len, CHUNK, bytes.size - consumed)
            bytes.copyInto(b, off, consumed, consumed + count)
            consumed += count
            return count
        }
    }

    private companion object {
        const val CHUNK = 1000
        const val DEVICE_ID = "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd"
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val KEY_ID = "00112233aabbccdd"
        val NAME = CopyIdentity(generation = 42, schemaVersion = 1, deviceIdPrefix = "7f3a9c1e")
        val MALFORMED = CopyMetadata.Unusable(UnusableReason.MALFORMED)

        val META =
            SlMeta(
                format = 1,
                keyId = null,
                deviceId = DEVICE_ID,
                deviceLabel = "Fabricante Modelo 9",
                generation = 42,
                schemaVersion = 1,
                appVersion = "1.4.0",
                sha256 = SHA,
                rawSize = 85_278_720,
                fileSize = 47_512_345,
                records = 485_000,
                catalogCount = 640,
                valid = true,
            )
        val INVALID = META.copy(valid = false)
        val FATAL_PINNED = META.copy(valid = false, fatal = true, pinned = true)
        val ENCRYPTED = META.copy(format = 2, keyId = KEY_ID)
    }
}
