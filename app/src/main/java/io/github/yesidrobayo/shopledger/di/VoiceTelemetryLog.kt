package io.github.yesidrobayo.shopledger.di

import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.ui.phrase.ListenMode
import io.github.yesidrobayo.shopledger.ui.phrase.VoiceOutcome
import io.github.yesidrobayo.shopledger.ui.phrase.VoiceTelemetry

/**
 * Los intentos de escuchar en el registro de la app, que solo los conserva fuera de la versión
 * publicada. Solo el número del servicio, el modo, el resultado y el código del sistema.
 */
class VoiceTelemetryLog(private val log: EventLog) : VoiceTelemetry {
    override fun attempt(outcome: VoiceOutcome, service: Int?, mode: ListenMode, error: Int?) {
        val fields =
            listOfNotNull(
                LogField.code("servicio", service ?: SYSTEM_SERVICE),
                LogField.state("modo", mode),
                LogField.state("resultado", outcome),
                error?.let { LogField.code("error", it) },
            )
        log.info(VoiceEvent.ATTEMPT, *fields.toTypedArray())
    }

    private companion object {
        /** El número del motor sin red o del predeterminado, que el sistema elige. */
        const val SYSTEM_SERVICE = -1
    }
}

/** Lo que registra la escucha. El texto es fijo, y ninguna línea lleva lo oído. */
internal enum class VoiceEvent(override val text: String) : LogEvent {
    ATTEMPT("Intento de escuchar"),
}
