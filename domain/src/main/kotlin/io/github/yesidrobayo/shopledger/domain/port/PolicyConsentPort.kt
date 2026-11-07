package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus

/**
 * La aceptación de la política de privacidad y los términos de uso en este teléfono. Es de la
 * instalación, no del negocio: no viaja en una copia, y restaurar una no la cambia.
 *
 * Sin la versión vigente aceptada, ninguna copia sale a la nube. Los datos, las ventas, exportar y
 * las copias locales siguen igual: no aceptar nunca bloquea nada más. Nada de esto usa la red.
 */
interface PolicyConsentPort {
    /** Cómo está la aceptación de las versiones que publica la app. */
    suspend fun status(): PolicyConsentStatus

    /**
     * La persona aceptó las versiones que publica la app. Cuando vuelve con éxito, la aceptación ya
     * está en el disco, y las copias que esperaban vuelven a programarse.
     */
    suspend fun acceptCurrent(): PolicyAcceptance
}

/** Lo que pasó al guardar la aceptación. */
enum class PolicyAcceptance {
    /** Quedó en el disco: las copias en la nube pueden salir. */
    SAVED,

    /**
     * El teléfono no la pudo guardar, por ejemplo sin espacio. Nada cambió: las copias siguen en
     * pausa, y la persona puede intentarlo otra vez.
     */
    NOT_SAVED,
}
