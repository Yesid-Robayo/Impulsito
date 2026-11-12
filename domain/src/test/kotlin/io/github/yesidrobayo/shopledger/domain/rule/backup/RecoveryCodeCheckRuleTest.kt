package io.github.yesidrobayo.shopledger.domain.rule.backup

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La prueba periódica del código: a los 7 y 30 días de activar o cambiar el código, y después 90
 * días desde la última prueba correcta. Nunca con el cifrado apagado, y un reloj atrasado no la
 * esconde.
 */
class RecoveryCodeCheckRuleTest {
    private val enabledAt = Instant.parse("2026-11-01T10:00:00Z")

    private fun due(now: Instant, lastCheck: Instant? = null, snoozedUntil: Instant? = null) =
        RecoveryCodeCheckRule.isDue(
            encryptionEnabled = true,
            enabledAt = enabledAt,
            lastCorrectCheckAt = lastCheck,
            now = now,
            snoozedUntil = snoozedUntil,
        )

    @Test
    fun theFirstCheckComesSevenDaysAfterEnabling() {
        assertEquals(
            enabledAt + 7.days,
            RecoveryCodeCheckRule.nextDueAt(enabledAt, lastCorrectCheckAt = null),
        )
        assertFalse(due(enabledAt + 7.days - 1.milliseconds))
        assertTrue(due(enabledAt + 7.days))
    }

    @Test
    fun aCheckBeforeTheSeventhDayDoesNotSkipIt() {
        val early = enabledAt + 3.days

        assertEquals(enabledAt + 7.days, RecoveryCodeCheckRule.nextDueAt(enabledAt, early))
        assertTrue(due(enabledAt + 7.days, early))
    }

    @Test
    fun afterTheFirstCheckTheNextIsDayThirty() {
        val first = enabledAt + 8.days

        assertEquals(enabledAt + 30.days, RecoveryCodeCheckRule.nextDueAt(enabledAt, first))
        assertFalse(due(enabledAt + 30.days - 1.milliseconds, first))
        assertTrue(due(enabledAt + 30.days, first))
    }

    @Test
    fun aCheckExactlyOnDaySevenCountsForIt() {
        assertEquals(
            enabledAt + 30.days,
            RecoveryCodeCheckRule.nextDueAt(
                enabledAt,
                enabledAt + 7.days,
            ),
        )
    }

    @Test
    fun fromDayThirtyEveryNinetyDaysAfterTheLastCorrectCheck() {
        val second = enabledAt + 31.days
        assertEquals(second + 90.days, RecoveryCodeCheckRule.nextDueAt(enabledAt, second))

        val manual = enabledAt + 115.days
        assertEquals(manual + 90.days, RecoveryCodeCheckRule.nextDueAt(enabledAt, manual))
        assertFalse(due(manual + 90.days - 1.milliseconds, manual))
        assertTrue(due(manual + 90.days, manual))
    }

    @Test
    fun aCheckWithThePreviousCodeDoesNotCount() {
        val beforeChange = enabledAt - 1.days

        assertEquals(enabledAt + 7.days, RecoveryCodeCheckRule.nextDueAt(enabledAt, beforeChange))
    }

    @Test
    fun neverWithEncryptionOff() {
        assertFalse(
            RecoveryCodeCheckRule.isDue(
                encryptionEnabled = false,
                enabledAt = enabledAt,
                lastCorrectCheckAt = null,
                now = enabledAt + 400.days,
            ),
        )
    }

    @Test
    fun aClockSetBackNeverHidesIt() {
        assertTrue(due(enabledAt - 1.hours))
        val lastCheck = enabledAt + 40.days
        assertTrue(due(lastCheck - 1.hours, lastCheck))
    }

    @Test
    fun laterWaitsUntilTheNextUtcDay() {
        val now = Instant.parse("2026-11-08T23:30:00Z")
        val until = RecoveryCodeCheckRule.snoozedUntil(now)

        assertEquals(Instant.parse("2026-11-09T00:00:00Z"), until)
        assertFalse(due(until - 1.milliseconds, snoozedUntil = until))
        assertTrue(due(until, snoozedUntil = until))
    }

    @Test
    fun laterAtMidnightWaitsAWholeDay() {
        val midnight = Instant.parse("2026-11-08T00:00:00Z")

        assertEquals(
            Instant.parse("2026-11-09T00:00:00Z"),
            RecoveryCodeCheckRule.snoozedUntil(midnight),
        )
    }

    @Test
    fun laterBefore1970StillGoesToTheNextDay() {
        val beforeEpoch = Instant.parse("1969-12-31T12:00:00Z")

        assertEquals(
            Instant.parse("1970-01-01T00:00:00Z"),
            RecoveryCodeCheckRule.snoozedUntil(beforeEpoch),
        )
        assertEquals(
            Instant.parse("1970-01-01T00:00:00Z"),
            RecoveryCodeCheckRule.snoozedUntil(Instant.parse("1969-12-31T00:00:00Z")),
        )
    }

    @Test
    fun aSnoozeNeverStopsTheCheckForGood() {
        val now = enabledAt + 7.days
        val until = RecoveryCodeCheckRule.snoozedUntil(now)

        assertFalse(due(now, snoozedUntil = until))
        assertTrue(due(until + 1.days, snoozedUntil = until))
    }
}
