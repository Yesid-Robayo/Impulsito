package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.DrillOutcome

/**
 * `Probar restauración`: el simulacro completo sobre un temporal. Baja o lee la cabeza, la valida y
 * la migra como si fuera a restaurarla, sin tocar los datos del negocio ni las copias. Anota
 * `last_drill_at` y `last_drill_result`.
 */
fun interface RestoreDrillPort {
    suspend fun runDrill(): DrillOutcome
}
