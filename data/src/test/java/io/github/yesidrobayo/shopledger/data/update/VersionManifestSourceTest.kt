package io.github.yesidrobayo.shopledger.data.update

import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El origen del aviso de versión: solo una dirección `https` a un archivo, sin usuario, parámetros
 * ni fragmento, con una clave pública de curva. Sin dirección o sin clave no hay consulta. Las
 * claves se crean en memoria para la prueba; ninguna es la real.
 */
class VersionManifestSourceTest {
    private val key: String =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(256) }
            .generateKeyPair()
            .public
            .encoded
            .let { Base64.getEncoder().encodeToString(it) }

    @Test
    fun `an https address to a file gives the notice and its signature next to it`() {
        val source = VersionManifestSource.parse(" https://example.com/app/version.json ", key)

        assertNotNull(source)
        assertEquals("https://example.com/app/version.json", source?.manifestUrl.toString())
        assertEquals("https://example.com/app/version.json.sig", source?.signatureUrl.toString())
    }

    @Test
    fun `the key may come wrapped in lines`() {
        val wrapped = key.chunked(40).joinToString("\n")

        assertNotNull(VersionManifestSource.parse("https://example.com/version.json", wrapped))
    }

    @Test
    fun `only https is accepted`() {
        assertNull(VersionManifestSource.parse("http://example.com/version.json", key))
        assertNull(VersionManifestSource.parse("ftp://example.com/version.json", key))
        assertNull(VersionManifestSource.parse("example.com/version.json", key))
    }

    @Test
    fun `the address carries nothing of the person`() {
        assertNull(VersionManifestSource.parse("https://user@example.com/version.json", key))
        assertNull(VersionManifestSource.parse("https://u:p@example.com/version.json", key))
        assertNull(VersionManifestSource.parse("https://example.com/version.json?id=1", key))
        assertNull(VersionManifestSource.parse("https://example.com/version.json#a", key))
    }

    @Test
    fun `the address names a file`() {
        assertNull(VersionManifestSource.parse("https://example.com/", key))
    }

    @Test
    fun `without an address or a key there is no source`() {
        assertNull(VersionManifestSource.parse("", key))
        assertNull(VersionManifestSource.parse("https://example.com/version.json", ""))
        assertNull(VersionManifestSource.parse("https://example.com/version.json", "  \n "))
    }

    @Test
    fun `a key that is not base64 or not an elliptic curve key is refused`() {
        val rsa =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .public
                .encoded
                .let { Base64.getEncoder().encodeToString(it) }

        assertNull(VersionManifestSource.publicKeyOf("no es base64 !"))
        val short = Base64.getEncoder().encodeToString(ByteArray(8))
        assertNull(VersionManifestSource.publicKeyOf(short))
        assertNull(VersionManifestSource.publicKeyOf(rsa))
    }
}
