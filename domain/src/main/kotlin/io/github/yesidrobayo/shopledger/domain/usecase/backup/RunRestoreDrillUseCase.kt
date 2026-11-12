package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.DrillOutcome
import io.github.yesidrobayo.shopledger.domain.port.RestoreDrillPort

/**
 * `Probar restauración`: comprueba que la copia más reciente se puede restaurar, sobre un temporal.
 * Nunca cambia los datos del negocio ni las copias del destino.
 */
class RunRestoreDrillUseCase(private val port: RestoreDrillPort) {
    suspend operator fun invoke(): DrillOutcome = port.runDrill()
}
