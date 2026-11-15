package io.github.yesidrobayo.shopledger.domain.rule.update

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Decide si toca el recordatorio de buscar una versión nueva. Sale sin red, cada [INTERVAL] desde
 * lo último que pasó: instalar o actualizar la app, buscar a mano, o el recordatorio anterior. Con
 * la búsqueda apagada no sale nunca.
 *
 * Con la hora anterior a esa fecha, el reloj retrocedió, y no sale: un recordatorio de más cada
 * día molestaría, y ninguno esconde un dato de la persona.
 */
object UpdateReminderRule {
    /** Cada cuánto se recuerda revisar. */
    val INTERVAL: Duration = 60.days

    /**
     * Si en [now] sale el recordatorio, con la búsqueda [enabled], la app instalada o actualizada
     * en [installedAt], la última búsqueda en [lastCheckAt] y el último recordatorio en
     * [lastReminderAt], cada uno nulo si nunca pasó.
     */
    fun isDue(
        enabled: Boolean,
        installedAt: Instant,
        lastCheckAt: Instant?,
        lastReminderAt: Instant?,
        now: Instant,
    ): Boolean {
        if (!enabled) return false
        val since = listOfNotNull(installedAt, lastCheckAt, lastReminderAt).max()
        return now - since >= INTERVAL
    }
}
