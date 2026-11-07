package io.github.yesidrobayo.shopledger.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** El sobre, sin dispositivo: el alias viaja en el encabezado y todo lo que no cuadra se rechaza. */
class SealedEnvelopeTest {
    private val iv = ByteArray(12) { it.toByte() }
    private val ciphertext = ByteArray(40) { (it * 7).toByte() }

    @Test
    fun theAliasTravelsInTheHeaderAndReadsBack() {
        val bytes = SealedEnvelope("webdav-credential-b", iv, ciphertext).encode()
        val decoded = requireNotNull(SealedEnvelope.decode(bytes))
        assertEquals("webdav-credential-b", decoded.alias)
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
        assertEquals("webdav-credential-b", SealedEnvelope.aliasOf(bytes))
    }

    @Test
    fun anythingThatIsNotAnEnvelopeOfThisVersionIsRejected() {
        val good = SealedEnvelope("webdav-credential-a", iv, ciphertext).encode()
        assertNull(SealedEnvelope.decode(ByteArray(0)))
        assertNull(SealedEnvelope.decode(good.copyOf().also { it[0] = 0 }))
        assertNull(SealedEnvelope.decode(good.copyOf().also { it[4] = 2 }))
        // Un texto cifrado más corto que la etiqueta de GCM no puede ser válido.
        assertNull(SealedEnvelope.decode(good.copyOf(good.size - ciphertext.size + 15)))
        // Un alias con mayúsculas o espacios no es de la app.
        val badAlias = good.copyOf().also { it[6] = 'W'.code.toByte() }
        assertNull(SealedEnvelope.decode(badAlias))
    }

    @Test
    fun aliasesAreLowercaseAsciiOnly() {
        assertFalse(SealedEnvelope.isValidAlias(""))
        assertFalse(SealedEnvelope.isValidAlias("Webdav"))
        assertFalse(SealedEnvelope.isValidAlias("webdav credential"))
        assertFalse(SealedEnvelope.isValidAlias("x".repeat(65)))
    }

    @Test
    fun printedTextCarriesNoBytes() {
        val text = SealedEnvelope("webdav-credential-a", iv, ciphertext).toString()
        assertEquals("SealedEnvelope(iv=12, ciphertext=40)", text)
    }
}
