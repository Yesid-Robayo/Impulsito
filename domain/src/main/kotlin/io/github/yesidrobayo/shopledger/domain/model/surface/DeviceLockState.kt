package io.github.yesidrobayo.shopledger.domain.model.surface

/**
 * Cómo está el teléfono al dibujar una superficie del sistema, como el widget. Nada de esto sale de
 * la base: solo de la pantalla y del bloqueo.
 *
 * @property screenOn la pantalla está encendida.
 * @property keyguardShowing el sistema muestra la pantalla de bloqueo, con o sin método seguro.
 * @property deviceLocked el teléfono pide PIN, patrón, contraseña o huella para abrirse.
 */
data class DeviceLockState(
    val screenOn: Boolean,
    val keyguardShowing: Boolean,
    val deviceLocked: Boolean,
) {
    /** Solo quien abrió el teléfono puede ver la pantalla: encendida y sin bloqueo a la vista. */
    val isOpenToOwner: Boolean
        get() = screenOn && !keyguardShowing && !deviceLocked

    /**
     * Combina dos lecturas y se queda, campo por campo, con la más restrictiva: una señal del
     * sistema que llegó tarde no abre lo que la lectura directa ve cerrado, ni al revés.
     */
    fun strictest(other: DeviceLockState): DeviceLockState = DeviceLockState(
        screenOn = screenOn && other.screenOn,
        keyguardShowing = keyguardShowing || other.keyguardShowing,
        deviceLocked = deviceLocked || other.deviceLocked,
    )

    companion object {
        /** La lectura de partida, mientras no hay una real: todo cuenta como bloqueado. */
        val fullyLocked =
            DeviceLockState(screenOn = false, keyguardShowing = true, deviceLocked = true)
    }
}

/** Si una superficie del sistema muestra un monto y, si no, por qué. */
enum class AmountDecision {
    /** La persona no pidió montos. Es el estado de un widget recién puesto. */
    HIDDEN_BY_DEFAULT,

    /** Los pidió, pero la pantalla está apagada o el teléfono está bloqueado. */
    HIDDEN_LOCKED,

    /** Los pidió, pero el modo discreto está activo. */
    HIDDEN_DISCREET,

    /** Se muestra el monto. */
    VISIBLE,
    ;

    /** Si el monto se dibuja. */
    val isVisible: Boolean
        get() = this == VISIBLE
}
