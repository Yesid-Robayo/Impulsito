package io.github.yesidrobayo.shopledger.domain.rule.backup

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Cuándo pedir que la persona escriba su código de recuperación, con el cifrado de copias activo.
 *
 * La prueba descubre que el código se perdió mientras el teléfono todavía funciona, que es cuando
 * aún se puede ver o cambiar. Por eso se pide a los [FIRST_CHECK] y [SECOND_CHECK] desde que se
 * activó el cifrado o se cambió el código, y después cada [LATER_CHECKS] desde la última prueba
 * correcta, sea pedida o hecha a mano. Una prueba correcta antes del primer plazo no lo salta: los
 * dos primeros sirven para fijar el código en la memoria.
 *
 * Nunca bloquea nada. Un intento fallido no cuenta ni se guarda, así que no hay límite de intentos
 * ni nada que borrar. `Más tarde` no se guarda en disco: quien muestra la prueba lo recuerda hasta
 * [snoozedUntil], y si el proceso muere, se vuelve a pedir, que es lo seguro.
 *
 * Todo se cuenta con instantes UTC, sin la zona del negocio. Un reloj atrasado respecto de la
 * activación o de la última prueba nunca esconde el pedido: cuenta como vencido.
 */
object RecoveryCodeCheckRule {
    /** Primer pedido, desde la activación o el último cambio de código. */
    val FIRST_CHECK: Duration = 7.days

    /** Segundo pedido, desde la activación o el último cambio de código. */
    val SECOND_CHECK: Duration = 30.days

    /** Los siguientes, desde la última prueba correcta. */
    val LATER_CHECKS: Duration = 90.days

    private const val SECONDS_PER_DAY = 86_400L

    /**
     * El instante desde el que se pide la prueba.
     *
     * @param enabledAt cuándo se activó el cifrado o se cambió el código por última vez.
     * @param lastCorrectCheckAt la última prueba correcta, o `null` si no hubo. Una anterior a
     *   [enabledAt] fue con otro código y no cuenta.
     */
    fun nextDueAt(enabledAt: Instant, lastCorrectCheckAt: Instant?): Instant {
        val lastCheck = lastCorrectCheckAt?.takeIf { it >= enabledAt }
        val firstDue = enabledAt + FIRST_CHECK
        val secondDue = enabledAt + SECOND_CHECK
        return when {
            lastCheck == null || lastCheck < firstDue -> firstDue
            lastCheck < secondDue -> secondDue
            else -> lastCheck + LATER_CHECKS
        }
    }

    /**
     * Si en [now] toca pedir la prueba.
     *
     * @param encryptionEnabled con el cifrado apagado la prueba se detiene: el código ya no hace
     *   falta para las copias nuevas.
     * @param snoozedUntil hasta cuándo la persona eligió `Más tarde`, o `null`.
     */
    fun isDue(
        encryptionEnabled: Boolean,
        enabledAt: Instant,
        lastCorrectCheckAt: Instant?,
        now: Instant,
        snoozedUntil: Instant? = null,
    ): Boolean {
        if (!encryptionEnabled) return false
        if (snoozedUntil != null && now < snoozedUntil) return false
        val clockSetBack =
            now < enabledAt || (lastCorrectCheckAt != null && now < lastCorrectCheckAt)
        return clockSetBack || now >= nextDueAt(enabledAt, lastCorrectCheckAt)
    }

    /** Hasta cuándo dura `Más tarde`: el comienzo del día UTC siguiente a [now]. */
    fun snoozedUntil(now: Instant): Instant {
        val today = now.epochSeconds.floorDiv(SECONDS_PER_DAY)
        return Instant.fromEpochSeconds((today + 1) * SECONDS_PER_DAY)
    }
}
