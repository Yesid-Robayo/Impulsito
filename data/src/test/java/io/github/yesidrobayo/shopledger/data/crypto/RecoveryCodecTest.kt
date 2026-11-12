package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import java.security.MessageDigest
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El código de recuperación y sus derivaciones, contra el vector sintético publicado con el
 * formato. Los valores esperados se copian de la especificación: nunca se calculan aquí.
 */
class RecoveryCodecTest {
    @Test
    fun `the published vector derives the documented values`() {
        val code = validCode(PRINTED)

        assertEquals("51", RecoveryCodec.checkCharacters(code.canonical))
        assertEquals("8755f48d645c0bad", RecoveryCodec.deriveKeyId(code).toLowerHex())
        val backupKey = RecoveryCodec.deriveBackupKey(code)
        assertEquals(32, backupKey.size)
        assertEquals(
            "0164321177d81349f81b0f3b5acce5ce20200bbd319222427cc90f96279881f8",
            MessageDigest.getInstance("SHA-256").digest(backupKey).toLowerHex(),
        )
        assertEquals(PRINTED, RecoveryCodec.printable(code))
        assertEquals("8755f48d645c0bad", BackupKey.from(code).keyIdHex)
    }

    @Test
    fun `typing is normalized before checking`() {
        // Minúsculas, espacios, O por 0, e I y L por 1, como escribiría una persona.
        assertEquals(validCode(PRINTED), validCode("g7k2 m9pq r4st v6wx y8z3 abcd ef5I"))
        assertEquals(validCode(PRINTED), validCode("G7K2M9PQR4STV6WXY8Z3ABCDEF5l"))
        assertEquals("01", RecoveryCodec.normalize("oI"))
        assertEquals("1", RecoveryCodec.normalize("L"))
    }

    @Test
    fun `a wrong check character is detected without using the code`() {
        assertEquals(
            CodeParseResult.CheckMismatch,
            RecoveryCodec.parse("G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF52"),
        )
    }

    @Test
    fun `wrong length and foreign characters are rejected`() {
        assertEquals(CodeParseResult.WrongLength, RecoveryCodec.parse("G7K2-M9PQ"))
        assertEquals(CodeParseResult.WrongLength, RecoveryCodec.parse(PRINTED + "0"))
        assertEquals(CodeParseResult.WrongLength, RecoveryCodec.parse(""))
        assertEquals(
            CodeParseResult.InvalidCharacter,
            RecoveryCodec.parse("U7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF51"),
        )
        assertEquals(
            CodeParseResult.InvalidCharacter,
            RecoveryCodec.parse("G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF5*"),
        )
    }

    @Test
    fun `every single typo in the code is caught before deriving a key`() {
        // El control o, si coincide por azar, el key_id: ningún error de una letra abre la copia.
        val compact = PRINTED.replace("-", "")
        val keyId = BackupKey.from(validCode(PRINTED)).keyIdHex
        for (position in 0 until RecoveryCodec.CODE_LENGTH) {
            val alphabet = RecoveryCodec.ALPHABET
            val replacement = alphabet[(alphabet.indexOf(compact[position]) + 1) % alphabet.length]
            val typed = compact.replaceRange(position, position + 1, replacement.toString())
            when (val parsed = RecoveryCodec.parse(typed)) {
                is CodeParseResult.Valid ->
                    assertNotEquals(keyId, BackupKey.from(parsed.code).keyIdHex)

                CodeParseResult.CheckMismatch -> Unit

                CodeParseResult.WrongLength,
                CodeParseResult.InvalidCharacter,
                -> throw AssertionError("Un cambio de letra no altera el largo ni el alfabeto")
            }
        }
    }

    @Test
    fun `generated codes have 26 symbols and a valid check`() {
        val random = SecureRandom()
        val seen = HashSet<String>()
        repeat(GENERATED) {
            val code = RecoveryCodec.generate(random)
            assertEquals(26, code.canonical.length)
            assertTrue(code.canonical.all { it in RecoveryCodec.ALPHABET })
            assertEquals(code, validCode(RecoveryCodec.printable(code)))
            seen += code.canonical
        }
        assertEquals(GENERATED, seen.size)
    }

    @Test
    fun `neither the code nor the key appears in their text form`() {
        val code = validCode(PRINTED)
        val key = BackupKey.from(code)
        assertFalse(code.toString().contains(code.canonical))
        assertFalse(key.toString().contains(key.keyIdHex))
        assertFalse(key.toString().contains(RecoveryCodec.deriveBackupKey(code).toLowerHex()))
        assertFalse(CodeParseResult.Valid(code).toString().contains(code.canonical))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a code with the wrong shape cannot be built`() {
        RecoveryCode("G7K2M9PQR4STV6WXY8Z3ABCDE")
    }

    private fun validCode(typed: String): RecoveryCode {
        val parsed = RecoveryCodec.parse(typed)
        assertTrue("Debía ser válido", parsed is CodeParseResult.Valid)
        return (parsed as CodeParseResult.Valid).code
    }

    private companion object {
        /** El código sintético de la especificación del formato. Nunca uno real. */
        const val PRINTED = "G7K2-M9PQ-R4ST-V6WX-Y8Z3-ABCD-EF51"
        const val GENERATED = 200
    }
}
