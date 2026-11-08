package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupTimings
import kotlin.time.Instant

/**
 * Decide si toca contrastar el estado con el destino al abrir la app. El trabajo semanal no
 * alcanza solo: con la app poco usada, el sistema le quita la red a los trabajos en segundo plano,
 * y sin sondear, volver sin cambios pendientes diría que todo está guardado sin mirar el destino.
 */
object HeadProbeRule {
    /**
     * Si la última sonda, en [lastProbeAt], ya venció en [now]. Sin ninguna, toca. Con la hora
     * anterior a la última sonda, el reloj retrocedió: un reloj raro nunca deja de sondear.
     */
    fun isDue(lastProbeAt: Instant?, now: Instant): Boolean {
        if (lastProbeAt == null) return true
        return now < lastProbeAt || now - lastProbeAt >= BackupTimings.HEAD_PROBE
    }
}
