package io.github.yesidrobayo.shopledger.domain.model.safety

/** Si la persona aceptó la política de privacidad y los términos de uso que publica la app. */
enum class PolicyConsentStatus {
    /** Aceptó las versiones vigentes, o unas más nuevas: las copias en la nube pueden salir. */
    ACCEPTED,

    /**
     * Nunca aceptó ninguna versión en esta instalación. Se pregunta antes de conectar un destino, y
     * hasta entonces ninguna copia sale a la nube.
     */
    NEVER_ACCEPTED,

    /**
     * Aceptó una versión anterior, y la política o los términos cambiaron. Se pregunta al abrir la
     * app, y mientras tanto las copias en la nube quedan en pausa. Nunca bloquea los datos ni la
     * exportación.
     */
    OUTDATED,
}
