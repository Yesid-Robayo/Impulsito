package io.github.yesidrobayo.shopledger.domain.model.safety

/**
 * Las versiones de la política de privacidad y de los términos de uso: las que la app publica, o
 * las que la persona aceptó.
 *
 * Cada una empieza en 1 y solo sube cuando cambia el uso de los datos. Una errata o un cambio de
 * redacción no la sube, porque haría preguntar otra vez por algo que no cambió y pausaría las
 * copias en la nube sin motivo.
 *
 * @property privacyPolicy la versión de la política de privacidad.
 * @property termsOfUse la versión de los términos de uso.
 */
data class PolicyVersions(val privacyPolicy: Int, val termsOfUse: Int) {
    init {
        require(privacyPolicy >= FIRST && termsOfUse >= FIRST) { "Las versiones empiezan en 1" }
    }

    companion object {
        /** La primera versión de cada texto. */
        const val FIRST = 1

        /**
         * Las versiones que publica esta compilación. Subir una aquí vuelve a preguntar al abrir la
         * app y deja en pausa las copias en la nube hasta que la persona acepte: los textos de la
         * política y los términos se actualizan antes de publicar esa compilación.
         */
        val CURRENT = PolicyVersions(privacyPolicy = 1, termsOfUse = 1)
    }
}
