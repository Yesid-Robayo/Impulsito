package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cuándo escribe el segundo destino y qué copias propias poda, con o sin seguir a la principal. */
class SecondDestinationRuleTest {
    private val now = Instant.parse("2026-11-12T15:00:00Z")

    private fun at(text: String) = requireNotNull(CopyInstant.parse(text))

    @Test
    fun `the daily copy needs changes and a day since the last one`() {
        // Nunca hubo copia: toca con cambios.
        assertTrue(SecondDestinationRule.dailyDue(5, null, null, now))
        // Sin cambios desde la última, nunca.
        assertFalse(SecondDestinationRule.dailyDue(5, 5, null, now))
        // Con cambios, pero hace menos de un día.
        assertFalse(SecondDestinationRule.dailyDue(6, 5, now - 23.hours, now))
        // Con cambios y un día cumplido.
        assertTrue(SecondDestinationRule.dailyDue(6, 5, now - 24.hours, now))
        // Un reloj que retrocedió cuenta como vencido.
        assertTrue(SecondDestinationRule.dailyDue(6, 5, now + 1.hours, now))
    }

    @Test
    fun `following the main copy a streak writes after ten minutes`() {
        assertTrue(SecondDestinationRule.followMaxWaitReached(5, null, null, now))
        assertFalse(SecondDestinationRule.followMaxWaitReached(5, 5, now - 1.hours, now))
        assertFalse(SecondDestinationRule.followMaxWaitReached(6, 5, now - 9.minutes, now))
        assertTrue(SecondDestinationRule.followMaxWaitReached(6, 5, now - 10.minutes, now))
        assertTrue(SecondDestinationRule.followMaxWaitReached(6, 5, now + 1.minutes, now))
    }

    @Test
    fun `without following it keeps the three newest`() {
        val copies =
            mapOf(
                "a" to at("20261112T100000Z"),
                "b" to at("20261111T100000Z"),
                "c" to at("20261110T100000Z"),
                "d" to at("20261109T100000Z"),
                "e" to at("20261108T100000Z"),
            )

        assertEquals(setOf("d", "e"), SecondDestinationRule.toPrune(copies, following = false))
    }

    @Test
    fun `following it also keeps the last of each of the two previous days, five at most`() {
        val copies =
            mapOf(
                // Hoy, cuatro copias: quedan las tres más nuevas.
                "hoy-4" to at("20261112T140000Z"),
                "hoy-3" to at("20261112T130000Z"),
                "hoy-2" to at("20261112T120000Z"),
                "hoy-1" to at("20261112T110000Z"),
                // Ayer y anteayer, la última de cada día.
                "ayer-2" to at("20261111T200000Z"),
                "ayer-1" to at("20261111T080000Z"),
                "anteayer" to at("20261110T200000Z"),
                // Un día más atrás ya no entra.
                "antes" to at("20261109T200000Z"),
            )

        val pruned = SecondDestinationRule.toPrune(copies, following = true)

        assertEquals(setOf("hoy-1", "ayer-1", "antes"), pruned)
        assertEquals(SecondDestinationRule.MAX_FOLLOWING, copies.size - pruned.size)
    }

    @Test
    fun `a previous day already among the newest adds nothing`() {
        val copies =
            mapOf(
                "hoy" to at("20261112T140000Z"),
                "ayer-2" to at("20261111T200000Z"),
                "ayer-1" to at("20261111T100000Z"),
                "ayer-0" to at("20261111T080000Z"),
                "anteayer" to at("20261110T200000Z"),
            )

        // Ayer ya tiene su última entre las tres más nuevas; anteayer suma la suya.
        assertEquals(setOf("ayer-0"), SecondDestinationRule.toPrune(copies, following = true))
    }

    @Test
    fun `nothing to prune when there is nothing`() {
        assertEquals(
            emptySet<String>(),
            SecondDestinationRule.toPrune(emptyMap(), following = true),
        )
    }
}
