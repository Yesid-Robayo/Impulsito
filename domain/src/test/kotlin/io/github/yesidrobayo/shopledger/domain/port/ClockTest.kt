package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    @Test
    fun `a fixed clock always gives its instant`() {
        val clock = FixedClock.atEpochMillis(1_793_593_800_000)

        assertEquals(Instant.fromEpochMilliseconds(1_793_593_800_000), clock.now())
        assertEquals(1_793_593_800_000, clock.nowEpochMillis())
        assertEquals(clock.now(), clock.now())
    }

    @Test
    fun `epoch milliseconds come from the same instant`() {
        val clock = Clock { Instant.parse("2026-11-02T04:30:00.123Z") }

        assertEquals(1_793_593_800_123, clock.nowEpochMillis())
    }

    @Test
    fun `the system clock gives the current instant`() {
        // Con margen amplio: el reloj del equipo puede corregirse entre dos lecturas.
        val reference = kotlin.time.Clock.System.now()

        assertTrue((SystemClock.now() - reference).absoluteValue < 1.minutes)
        val referenceMillis = reference.toEpochMilliseconds()
        assertTrue(abs(SystemClock.nowEpochMillis() - referenceMillis) < 60_000)
    }
}
