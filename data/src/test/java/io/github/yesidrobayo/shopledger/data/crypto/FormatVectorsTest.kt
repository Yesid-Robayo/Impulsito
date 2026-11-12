package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El lector y el escritor de la app contra los vectores públicos del formato, los mismos que usa la
 * herramienta de rescate en el PC: código, control, `key_id`, clave, copias y alteraciones.
 *
 * Los vectores viven en `tools/backup-format/vectors/`, fuera de la app. Si faltan, la prueba
 * falla: son la prueba de que otra implementación abre lo que escribe la app.
 */
class FormatVectorsTest {
    private val vectors: Map<String, Any?> by lazy {
        val path =
            requireNotNull(System.getProperty("backupFormat.vectors")) {
                "Falta la ruta de los vectores"
            }
        val file = File(path)
        assertTrue("No están los vectores del formato en $path", file.isFile)
        @Suppress("UNCHECKED_CAST")
        MiniJson.parse(file.readText(Charsets.UTF_8)) as Map<String, Any?>
    }

    @Test
    fun `the parameters are the ones the app uses`() {
        val parameters = vectors.obj("parameters")
        assertEquals("SLBK", vectors["format"])
        assertEquals(2L, vectors["format_version"])
        assertEquals(
            BackupCipher.CIPHERTEXT_SEGMENT_BYTES.toLong(),
            parameters["ciphertext_segment_size"],
        )
        assertEquals(1L shl 30, parameters["max_decompressed_bytes"])
        assertEquals("SLBK-v2", parameters["salt"])
        assertEquals("backup-aead", parameters["info_backup_key"])
        assertEquals("key-id", parameters["info_key_id"])
    }

    @Test
    fun `every code derives its check, key id and key`() {
        for (entry in vectors.list("codes")) {
            val parsed = RecoveryCodec.parse(entry.str("printed")) as CodeParseResult.Valid
            val code = parsed.code
            assertEquals(entry.str("canonical"), code.canonical)
            assertEquals(entry.str("check"), RecoveryCodec.checkCharacters(code.canonical))
            assertEquals(entry.str("key_id"), BackupKey.from(code).keyIdHex)
            assertEquals(entry.str("bk_sha256"), sha256(RecoveryCodec.deriveBackupKey(code)))
            assertEquals(entry.str("printed"), RecoveryCodec.printable(code))
        }
    }

    @Test
    fun `typed codes normalize and fail like the specification says`() {
        for (entry in vectors.list("typed_codes")) {
            val typed = entry.str("typed")
            val actual =
                when (val parsed = RecoveryCodec.parse(typed)) {
                    is CodeParseResult.Valid -> "VALID:" + parsed.code.canonical
                    CodeParseResult.CheckMismatch -> "CHECK_MISMATCH"
                    CodeParseResult.WrongLength -> "WRONG_LENGTH"
                    CodeParseResult.InvalidCharacter -> "INVALID_CHARACTER"
                }
            val expected = entry.str("result").let {
                if (it ==
                    "VALID"
                ) {
                    "VALID:" + entry.str("canonical")
                } else {
                    it
                }
            }
            assertEquals(typed, expected, actual)
        }
    }

    @Test
    fun `every copy reads to its expected result`() {
        val failures =
            vectors.list("copies").mapNotNull { copy ->
                val bytes = copyBytes(copy)
                assertEquals(copy.str("name"), copy.str("file_sha256"), sha256(bytes))
                val (actual, plain) = outcome(bytes, copy.str("code"), copy.str("kind"))
                val expected = copy.str("expected")
                when {
                    actual != expected -> "${copy.str("name")}: $actual y no $expected"

                    expected == "OK" && sha256(
                        plain,
                    ) != copy.str("plain_sha256") -> "${copy.str("name")}: otro contenido"

                    expected == "OK" && plain.size.toLong() != copy["plain_bytes"] -> "${copy.str(
                        "name",
                    )}: otro largo"

                    else -> null
                }
            }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `every mutation is rejected with its cause`() {
        val copies = vectors.list("copies").associateBy { it.str("name") }
        val failures =
            vectors.list("mutations").mapNotNull { mutation ->
                val base = copyBytes(copies.getValue(mutation.str("base")))
                val bytes = apply(base, mutation.obj("operation"), copies)
                val (actual, _) = outcome(bytes, mutation.str("code"), "database")
                val expected = mutation.str("expected")
                if (actual == expected) null else "${mutation.str("name")}: $actual y no $expected"
            }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `the app writes what the vectors read, with the same header`() {
        // Cada copia válida, descifrada y vuelta a cifrar por la app con su misma cabecera: los
        // primeros bytes son idénticos y el lector la abre con el mismo contenido. El resto cambia
        // siempre, por la sal aleatoria de cada copia.
        for (copy in vectors.list("copies").filter { it.str("expected") == "OK" }) {
            val bytes = copyBytes(copy)
            val key = BackupKey.from(
                (RecoveryCodec.parse(copy.str("code")) as CodeParseResult.Valid).code,
            )
            val plain = ByteArrayOutputStream()
            val read = EncryptedCopy.read(
                ByteArrayInputStream(bytes),
                key,
                plain,
            ) as EncryptedReadResult.Read
            val header = read.header
            assertArrayEquals(bytes.copyOf(header.length), header.encode())
            val rewritten = ByteArrayOutputStream()
            EncryptedCopy.write(ByteArrayInputStream(plain.toByteArray()), rewritten, header, key)
            val again = rewritten.toByteArray()
            assertArrayEquals(bytes.copyOf(header.length), again.copyOf(header.length))
            val reread = EncryptedCopy.read(
                ByteArrayInputStream(again),
                key,
            ) as EncryptedReadResult.Read
            assertEquals(copy.str("plain_sha256"), reread.content.sha256)
        }
    }

    /** El resultado con el nombre de los vectores, y el contenido descifrado si se leyó. */
    private fun outcome(
        bytes: ByteArray,
        printedCode: String,
        kind: String,
    ): Pair<String, ByteArray> {
        val key = BackupKey.from((RecoveryCodec.parse(printedCode) as CodeParseResult.Valid).code)
        val plain = ByteArrayOutputStream()
        val name =
            when (val result = EncryptedCopy.read(ByteArrayInputStream(bytes), key, plain)) {
                // El encabezado de SQLite lo revisa la restauración, después de descifrar.
                is EncryptedReadResult.Read ->
                    if (kind == "database" &&
                        !plain.toByteArray().startsWithSqlite()
                    ) {
                        "NOT_SQLITE"
                    } else {
                        "OK"
                    }

                EncryptedReadResult.PlainFormat -> "PLAIN_FORMAT"

                EncryptedReadResult.NotEncryptedCopy -> "NOT_A_BACKUP"

                is EncryptedReadResult.NewerFormat -> "NEWER_FORMAT"

                is EncryptedReadResult.HeaderRejected -> "HEADER_OUT_OF_LIST"

                EncryptedReadResult.TooLarge -> "TOO_LARGE"

                is EncryptedReadResult.OtherKey -> "OTHER_CODE"

                EncryptedReadResult.Damaged -> "DAMAGED"

                EncryptedReadResult.SizeMismatch -> "SIZE_MISMATCH"
            }
        return name to plain.toByteArray()
    }

    private fun apply(
        base: ByteArray,
        operation: Map<String, Any?>,
        copies: Map<String, Map<String, Any?>>,
    ): ByteArray = when (val op = operation.str("op")) {
        "none" -> base

        "xor" ->
            base.copyOf().also {
                val offset = operation.int("offset")
                it[offset] = (it[offset].toInt() xor operation.int("mask")).toByte()
            }

        "set" ->
            base.copyOf().also {
                val value = hex(operation.str("hex"))
                value.copyInto(it, operation.int("offset"))
            }

        "truncate" -> base.copyOf(operation.int("length"))

        "append" -> base + hex(operation.str("hex"))

        "header_from" -> {
            val donor = copyBytes(copies.getValue(operation.str("copy")))
            val length = operation.int("length")
            donor.copyOf(length) + base.copyOfRange(length, base.size)
        }

        "body_from" -> {
            val donor = copyBytes(copies.getValue(operation.str("copy")))
            val length = operation.int("length")
            base.copyOf(length) + donor.copyOfRange(length, donor.size)
        }

        else -> throw AssertionError("Operación desconocida: $op")
    }

    private fun copyBytes(copy: Map<String, Any?>): ByteArray =
        Base64.getDecoder().decode(copy.str("file_base64"))

    private fun ByteArray.startsWithSqlite(): Boolean {
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        return size >= magic.size && copyOf(magic.size).contentEquals(magic)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

    private fun hex(text: String) = ByteArray(text.length / 2) {
        text.substring(2 * it, 2 * it + 2).toInt(16).toByte()
    }

    private fun Map<String, Any?>.str(key: String): String = requireNotNull(this[key] as? String) {
        "Falta $key"
    }

    private fun Map<String, Any?>.int(key: String): Int = requireNotNull(this[key] as? Long) {
        "Falta $key"
    }.toInt()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(key: String): Map<String, Any?> =
        this[key] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> =
        this[key] as List<Map<String, Any?>>
}

/**
 * Un lector de JSON para las pruebas: objetos, listas, textos, enteros, booleanos y `null`. En la
 * JVM, `org.json` es solo un esbozo de Android.
 */
private object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipSpace()
        require(reader.atEnd()) { "Sobra texto tras el JSON" }
        return value
    }

    private class Reader(private val text: String) {
        private var position = 0

        fun atEnd() = position == text.length

        fun skipSpace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun value(): Any? {
            skipSpace()
            return when (val char = text[position]) {
                '{' -> obj()

                '[' -> list()

                '"' -> string()

                't' -> literal("true", true)

                'f' -> literal("false", false)

                'n' -> literal("null", null)

                else -> if (char == '-' ||
                    char.isDigit()
                ) {
                    number()
                } else {
                    error("Carácter inesperado")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            position++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (text[position] == '}') return out.also { position++ }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                out[key] = value()
                skipSpace()
                if (text[position] == ',') position++ else break
            }
            expect('}')
            return out
        }

        private fun list(): List<Any?> {
            position++
            val out = ArrayList<Any?>()
            skipSpace()
            if (text[position] == ']') return out.also { position++ }
            while (true) {
                out += value()
                skipSpace()
                if (text[position] == ',') position++ else break
            }
            expect(']')
            return out
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val char = text[position++]
                when (char) {
                    '"' -> return out.toString()

                    '\\' ->
                        when (val escaped = text[position++]) {
                            'n' -> out.append('\n')

                            't' -> out.append('\t')

                            'r' -> out.append('\r')

                            'b' -> out.append('\b')

                            'f' -> out.append('\u000C')

                            'u' -> out.append(
                                text.substring(position, position + 4).toInt(16).toChar(),
                            ).also {
                                position +=
                                    4
                            }

                            else -> out.append(escaped)
                        }

                    else -> out.append(char)
                }
            }
        }

        private fun number(): Long {
            val start = position
            if (text[position] == '-') position++
            while (position < text.length && text[position].isDigit()) position++
            require(position == text.length || text[position] !in ".eE") { "Solo enteros" }
            return text.substring(start, position).toLong()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, position)) { "Literal inesperado" }
            position += word.length
            return value
        }

        private fun expect(char: Char) {
            require(text[position] == char) { "Se esperaba $char" }
            position++
        }
    }
}
