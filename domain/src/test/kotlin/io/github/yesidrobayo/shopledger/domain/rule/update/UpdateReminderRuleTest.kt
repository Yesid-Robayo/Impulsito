package io.github.yesidrobayo.shopledger.domain.rule.update

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El recordatorio de buscar una versión nueva sale cada 60 días desde lo último que pasó, nunca
 * con la búsqueda apagada y nunca con el reloj atrasado. Fechas inventadas.
 */
class UpdateReminderRuleTest {
    private val installed = Instant.parse("2026-01-01T00:00:00Z")

    private fun due(
        now: Instant,
        enabled: Boolean = true,
        lastCheckAt: Instant? = null,
        lastReminderAt: Instant? = null,
    ) = UpdateReminderRule.isDue(enabled, installed, lastCheckAt, lastReminderAt, now)

    @Test
    fun `it is due 60 days after installing and not a moment before`() {
        assertFalse(due(installed + 60.days - 1.milliseconds))
        assertTrue(due(installed + 60.days))
    }

    @Test
    fun `a manual check starts the count again`() {
        val checked = installed + 50.days

        assertFalse(due(installed + 70.days, lastCheckAt = checked))
        assertTrue(due(checked + 60.days, lastCheckAt = checked))
    }

    @Test
    fun `the previous reminder starts the count again`() {
        val reminded = installed + 60.days

        assertFalse(due(installed + 90.days, lastReminderAt = reminded))
        assertTrue(due(reminded + 60.days, lastReminderAt = reminded))
    }

    @Test
    fun `the most recent of the three dates counts`() {
        val checked = installed + 100.days
        val reminded = installed + 70.days

        assertFalse(due(installed + 140.days, lastCheckAt = checked, lastReminderAt = reminded))
        assertTrue(due(checked + 60.days, lastCheckAt = checked, lastReminderAt = reminded))
    }

    @Test
    fun `with the check turned off it never reminds`() {
        assertFalse(due(installed + 365.days, enabled = false))
    }

    @Test
    fun `with the clock behind the last date it does not remind`() {
        assertFalse(due(installed - 90.days))
    }
}
