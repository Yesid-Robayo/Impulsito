package io.github.yesidrobayo.shopledger.ui.phrase

/**
 * Lo que la escucha cuenta de cada intento, para saber en un teléfono real qué servicio de voz
 * falla y por qué. Solo números y estados: nunca lo oído ni el idioma.
 */
interface VoiceTelemetry {
    /**
     * Terminó un intento con [outcome]. [service] es el número del servicio en el orden de prueba,
     * o `null` para el motor sin red o el predeterminado; [error] es el código del sistema si falló.
     */
    fun attempt(outcome: VoiceOutcome, service: Int?, mode: ListenMode, error: Int?)
}

/** Cómo terminó un intento de escuchar. */
enum class VoiceOutcome {
    /** El servicio respondió con texto. */
    ANSWERED,

    /** El servicio falló con un código del sistema. */
    FAILED,

    /** No se pudo crear el motor del intento. */
    NOT_CREATED,
}
