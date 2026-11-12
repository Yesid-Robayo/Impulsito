package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo que se sella en `rk.sealed` y lo que se escribe en `state.json`, en Kotlin puro. El código es
 * el sintético de la especificación del formato: nunca uno real.
 */
class RecoveryFilesCodecTest {
    private val code = RecoveryCode("G7K2M9PQR4STV6WXY8Z3ABCDEF")

    @Test
    fun `the sealed content carries the code and its key id`() {
        val bytes = RecoveryEnvelopeCodec.encode(code)
        assertEquals(1 + 8 + 26, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals("8755f48d645c0bad", bytes.copyOfRange(1, 9).toLowerHex())
        assertEquals(code, RecoveryEnvelopeCodec.decode(bytes))
    }

    @Test
    fun `sealed content that does not match its key id is not a code`() {
        val good = RecoveryEnvelopeCodec.encode(code)
        assertNull(
            RecoveryEnvelopeCodec.decode(
                good.copyOf().also {
                    it[3] = (it[3] + 1).toByte()
                },
            ),
        )
        assertNull(RecoveryEnvelopeCodec.decode(good.copyOf().also { it[0] = 2 }))
        assertNull(RecoveryEnvelopeCodec.decode(good.copyOf(good.size - 1)))
        assertNull(RecoveryEnvelopeCodec.decode(good + 0))
        // Un carácter fuera del alfabeto, aunque el largo cuadre.
        assertNull(RecoveryEnvelopeCodec.decode(good.copyOf().also { it[9] = 'U'.code.toByte() }))
        assertNull(RecoveryEnvelopeCodec.decode(ByteArray(0)))
    }

    @Test
    fun `the state round trips and never carries the code`() {
        val states =
            listOf(
                RecoveryState.NEVER_ENABLED,
                RecoveryState(true, "8755f48d645c0bad", 1_793_491_200_000L, null, null),
                RecoveryState(
                    true,
                    "8755f48d645c0bad",
                    1_793_491_200_000L,
                    null,
                    null,
                    lastCodeCheckAtUtcMillis = 1_794_096_000_000L,
                ),
                RecoveryState(
                    false,
                    "8755f48d645c0bad",
                    1_793_491_200_000L,
                    1_793_577_600_000L,
                    "8755f48d645c0bad",
                ),
            )
        for (state in states) {
            val text = RecoveryStateCodec.encode(state)
            assertEquals(state, RecoveryStateCodec.decode(text))
            assertFalse(text.contains(code.canonical))
        }
        assertEquals(
            "{\"version\":1,\"encryption_enabled\":true," +
                "\"key_id\":\"8755f48d645c0bad\",\"enabled_at_utc_ms\":5}",
            RecoveryStateCodec.encode(RecoveryState(true, "8755f48d645c0bad", 5, null, null)),
        )
    }

    @Test
    fun `a state file with another shape is not used`() {
        val rejected =
            listOf(
                "",
                "{}",
                "{\"version\":2,\"encryption_enabled\":false}",
                "{\"version\":1}",
                "{\"version\":1,\"encryption_enabled\":\"true\",\"key_id\":\"8755f48d645c0bad\"}",
                "{\"version\":1,\"encryption_enabled\":true}",
                "{\"version\":1,\"encryption_enabled\":true,\"key_id\":\"8755F48D645C0BAD\"}",
                "{\"version\":1,\"encryption_enabled\":true,\"key_id\":\"8755f48d645c0b\"}",
                "{\"version\":1,\"encryption_enabled\":false,\"enabled_at_utc_ms\":-1}",
                "{\"version\":1,\"encryption_enabled\":false,\"enabled_at_utc_ms\":\"5\"}",
                "{\"version\":1,\"encryption_enabled\":false,\"disabled_key_id\":5}",
                "{\"version\":1,\"encryption_enabled\":false,\"last_code_check_at_utc_ms\":true}",
                "{\"version\":1,\"encryption_enabled\":false,\"version\":1}",
            )
        for (text in rejected) assertNull(text, RecoveryStateCodec.decode(text))
    }

    @Test
    fun `an unknown key from a newer version is ignored`() {
        val text = "{\"version\":1,\"encryption_enabled\":false,\"next_check_at_utc_ms\":9}"
        assertEquals(RecoveryState.NEVER_ENABLED, RecoveryStateCodec.decode(text))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an enabled state needs a key id`() {
        RecoveryState(true, null, null, null, null)
    }
}
