package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.HumanCheckRecorder

/**
 * `Ya la vi en {provider}`: anota la revisión con la hora de ahora y la generación que vio la
 * persona. Es lo que ella dijo, no una comprobación: la copia sigue sin confirmar, y con la nube del
 * teléfono nunca se dice que todo está guardado.
 */
class RecordCloudCheckUseCase(private val recorder: HumanCheckRecorder, private val clock: Clock) {
    /** Devuelve si quedó anotada. */
    suspend operator fun invoke(generation: Long): Boolean =
        recorder.recordSeen(generation, clock.now())
}
