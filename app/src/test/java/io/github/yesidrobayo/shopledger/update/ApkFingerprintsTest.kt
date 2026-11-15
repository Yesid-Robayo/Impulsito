package io.github.yesidrobayo.shopledger.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las huellas del verificador de APK y la comparación de firmantes: un archivo vale si trae todos
 * los certificados de la app instalada, y una firma ajena nunca. Huellas inventadas.
 */
class ApkFingerprintsTest {
    @Test
    fun `the hex form uses two lowercase digits per byte`() {
        assertEquals("00ff7f80", ApkFingerprints.hex(byteArrayOf(0, -1, 127, -128)))
        assertEquals("", ApkFingerprints.hex(ByteArray(0)))
    }

    @Test
    fun `the sha256 of a known text matches its published value`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkFingerprints.sha256Hex("abc".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `the fingerprint is read in uppercase pairs separated by colons`() {
        assertEquals("3F:9A:21", ApkFingerprints.grouped("3f9a21"))
    }

    @Test
    fun `the same signer matches`() {
        assertTrue(ApkFingerprints.signersMatch(setOf("a"), setOf("a")))
    }

    @Test
    fun `a rotated signer that keeps the installed certificate in its history matches`() {
        assertTrue(ApkFingerprints.signersMatch(setOf("a"), setOf("a", "b")))
    }

    @Test
    fun `another signer never matches`() {
        assertFalse(ApkFingerprints.signersMatch(setOf("a"), setOf("b")))
    }

    @Test
    fun `a file that lacks one of the installed signers does not match`() {
        assertFalse(ApkFingerprints.signersMatch(setOf("a", "b"), setOf("a")))
    }

    @Test
    fun `without installed signers nothing matches`() {
        assertFalse(ApkFingerprints.signersMatch(emptySet(), emptySet()))
        assertFalse(ApkFingerprints.signersMatch(emptySet(), setOf("a")))
    }
}
