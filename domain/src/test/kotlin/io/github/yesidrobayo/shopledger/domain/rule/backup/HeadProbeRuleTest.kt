package io.github.yesidrobayo.shopledger.domain.rule.backup

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuándo abrir la app vuelve a sondear el destino: sin sonda, a los 7 días y con el reloj atrasado. */
class HeadProbeRuleTest {
    private val now = Instant.parse("2026-11-08T10:00:00Z")

    @Test
    fun withoutAnyProbeItIsDue() {
        assertTrue(HeadProbeRule.isDue(lastProbeAt = null, now))
    }

    @Test
    fun aProbeLastsSevenDays() {
        assertFalse(HeadProbeRule.isDue(now - 7.days + 1.milliseconds, now))
        assertTrue(HeadProbeRule.isDue(now - 7.days, now))
    }

    @Test
    fun aClockSetBackProbesAgain() {
        assertTrue(HeadProbeRule.isDue(now + 1.hours, now))
    }
}
