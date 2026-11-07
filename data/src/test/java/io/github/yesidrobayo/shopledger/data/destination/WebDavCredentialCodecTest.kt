package io.github.yesidrobayo.shopledger.data.destination

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** El contenido sellado, sin dispositivo. Todos los datos son inventados. */
class WebDavCredentialCodecTest {
    // Letras de otros alfabetos: la contraseña viaja en UTF-8 normal, no en el modificado de Java.
    private val credential =
        StoredWebDavCredential(
            url = "https://webdav.example.com/dav/",
            username = "persona-de-prueba",
            password = "invented-Пароль-密碼-🔑",
            destinationId = "00000000-0000-4000-8000-000000000001",
        )

    @Test
    fun encodeThenDecodeKeepsEveryField() {
        val decoded = requireNotNull(WebDavCredentialCodec.decode(encode()))
        assertEquals(credential.url, decoded.url)
        assertEquals(credential.username, decoded.username)
        assertEquals(credential.password, decoded.password)
        assertEquals(credential.destinationId, decoded.destinationId)
    }

    @Test
    fun theFourBytesOfAnEmojiStayStandardUtf8() {
        // El modificado de Java escribiría la llave como dos sustitutos de tres bytes cada uno.
        val bytes = encode()
        val key = "🔑".toByteArray(Charsets.UTF_8)
        assertEquals(4, key.size)
        assertNotNull(indexOf(bytes, key))
    }

    @Test
    fun rejectsOtherVersionTruncatedAndTrailingBytes() {
        val good = encode()
        assertNull(WebDavCredentialCodec.decode(ByteArray(0)))
        assertNull(WebDavCredentialCodec.decode(good.copyOf().also { it[0] = 2 }))
        assertNull(WebDavCredentialCodec.decode(good.copyOf(good.size - 1)))
        assertNull(WebDavCredentialCodec.decode(good + byteArrayOf(0)))
    }

    @Test
    fun rejectsALengthBeyondTheLimitWithoutReadingIt() {
        val bytes = encode()
        // La primera longitud, en los bytes 1 a 4, pasa a 2^31 - 1.
        bytes[1] = 0x7F
        for (i in 2..4) bytes[i] = 0xFF.toByte()
        assertNull(WebDavCredentialCodec.decode(bytes))
    }

    @Test
    fun onlyHttpsAndNoBlankOrOversizedFields() {
        assertEquals(
            CredentialProblem.NOT_HTTPS,
            WebDavCredentialCodec.problem(copy(url = "http://webdav.example.com/dav/")),
        )
        assertEquals(
            CredentialProblem.BLANK_FIELD,
            WebDavCredentialCodec.problem(copy(password = "")),
        )
        assertEquals(
            CredentialProblem.TOO_LONG,
            WebDavCredentialCodec.problem(
                copy(password = "x".repeat(WebDavCredentialCodec.MAX_FIELD_BYTES + 1)),
            ),
        )
        assertNull(WebDavCredentialCodec.problem(copy(url = "HTTPS://webdav.example.com/")))
    }

    @Test
    fun printedTextNeverCarriesTheCredential() {
        val printed = listOf(credential.toString(), CredentialRead.Usable(credential).toString())
        for (text in printed) {
            assertFalse(text, text.contains("Пароль"))
            assertFalse(text, text.contains("persona-de-prueba"))
            assertFalse(text, text.contains("example.com"))
        }
    }

    private fun encode() = WebDavCredentialCodec.encode(credential)

    private fun copy(url: String = credential.url, password: String = credential.password) =
        StoredWebDavCredential(url, credential.username, password, credential.destinationId)

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? =
        (0..haystack.size - needle.size).firstOrNull { start ->
            needle.indices.all { haystack[start + it] == needle[it] }
        }
}
