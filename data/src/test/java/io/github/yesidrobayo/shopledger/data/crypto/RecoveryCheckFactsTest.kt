package io.github.yesidrobayo.shopledger.data.crypto

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La prueba periódica del código vista desde `recovery/state.json`: solo con el cifrado activo, a los
 * 7 y 30 días de activar, luego 90 desde la última prueba correcta, y pedida si falta la fecha de
 * activación.
 */
class RecoveryCheckFactsTest {
    private val enabledAt = Instant.parse("2026-11-01T10:00:00Z")
    private val keyId = "8755f48d645c0bad"

    private fun state(
        lastCheck: Instant? = null,
        enabled: Boolean = true,
        at: Instant? = enabledAt,
    ) = RecoveryState(
        enabled = enabled,
        keyIdHex = keyId,
        enabledAtUtcMillis = at?.toEpochMilliseconds(),
        offConfirmedAtUtcMillis = null,
        disabledKeyIdHex = null,
        lastCodeCheckAtUtcMillis = lastCheck?.toEpochMilliseconds(),
    )

    private fun facts(state: RecoveryState, now: Instant) =
        state.checkFacts(now.toEpochMilliseconds())

    @Test
    fun withEncryptionOffThereIsNoCheck() {
        assertEquals(RecoveryCheckFacts.NONE, facts(state(enabled = false), enabledAt + 400.days))
        assertEquals(RecoveryCheckFacts.NONE, facts(RecoveryState.NEVER_ENABLED, enabledAt))
    }

    @Test
    fun theFirstCheckIsDueOnTheSeventhDay() {
        assertEquals(RecoveryCheckFacts(true, false, null), facts(state(), enabledAt + 6.days))
        assertEquals(RecoveryCheckFacts(true, true, null), facts(state(), enabledAt + 7.days))
    }

    @Test
    fun afterTheThirtiethDayItCountsNinetyDaysFromTheLastCorrectCheck() {
        val lastCheck = enabledAt + 31.days
        assertEquals(
            RecoveryCheckFacts(true, false, lastCheck),
            facts(state(lastCheck), lastCheck + 89.days),
        )
        assertEquals(
            RecoveryCheckFacts(true, true, lastCheck),
            facts(state(lastCheck), lastCheck + 90.days),
        )
    }

    @Test
    fun withoutAnActivationDateTheCheckIsAsked() {
        assertEquals(RecoveryCheckFacts(true, true, null), facts(state(at = null), enabledAt))
    }
}
