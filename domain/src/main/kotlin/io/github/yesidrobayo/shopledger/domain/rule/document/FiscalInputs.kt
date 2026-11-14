package io.github.yesidrobayo.shopledger.domain.rule.document

/** Cómo quedó la referencia externa que escribió la persona. */
sealed interface ReferenceInput {
    /** Se puede guardar: [value] sin espacios en los extremos, o `null` si quedó vacía. */
    data class Valid(val value: String?) : ReferenceInput

    /** Pasa de [FiscalInputs.MAX_REFERENCE_LENGTH] caracteres. */
    data object TooLong : ReferenceInput
}

/** Cómo quedó el enlace al sistema externo que escribió la persona. */
sealed interface PortalUrlInput {
    /** Se puede guardar: [value] sin espacios en los extremos, o `null` para quitar el enlace. */
    data class Valid(val value: String?) : PortalUrlInput

    /** No es una dirección `https` con un servidor, o lleva espacios o caracteres de control. */
    data object NotHttps : PortalUrlInput

    /** Pasa de [FiscalInputs.MAX_PORTAL_URL_LENGTH] caracteres. */
    data object TooLong : PortalUrlInput
}

/**
 * Las reglas de lo que la persona escribe en el puente fiscal: la referencia de una venta y el
 * enlace al sistema que usa fuera de la app.
 *
 * Los largos se cuentan como los cuenta la base, por caracteres: un emoji es uno. El enlace solo
 * se acepta con `https`, porque la app lo entrega a otra app para abrirlo y nunca debe llevar a
 * una dirección en claro. La app no lo visita ni lee la respuesta.
 */
object FiscalInputs {
    /** Largo máximo de la referencia externa, el mismo que guarda la base. */
    const val MAX_REFERENCE_LENGTH: Int = 60

    /** Largo máximo del enlace al sistema externo, el mismo que guarda la base. */
    const val MAX_PORTAL_URL_LENGTH: Int = 300

    private const val SCHEME = "https://"

    fun reference(text: String): ReferenceInput {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> ReferenceInput.Valid(null)
            trimmed.characterCount() > MAX_REFERENCE_LENGTH -> ReferenceInput.TooLong
            else -> ReferenceInput.Valid(trimmed)
        }
    }

    fun portalUrl(text: String): PortalUrlInput {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> PortalUrlInput.Valid(null)
            trimmed.characterCount() > MAX_PORTAL_URL_LENGTH -> PortalUrlInput.TooLong
            !isHttpsWithHost(trimmed) -> PortalUrlInput.NotHttps
            else -> PortalUrlInput.Valid(trimmed)
        }
    }

    /**
     * Si [url] empieza por `https://`, sin importar mayúsculas, y le sigue un servidor. Un espacio
     * o un carácter de control en cualquier parte lo invalida: otra app podría leerlo distinto.
     */
    private fun isHttpsWithHost(url: String): Boolean {
        if (!url.startsWith(SCHEME, ignoreCase = true)) return false
        if (url.any { it.isWhitespace() || it.isISOControl() }) return false
        val host = url.substring(SCHEME.length).takeWhile { it != '/' && it != '?' && it != '#' }
        return host.isNotEmpty()
    }

    /** Cuántos caracteres tiene el texto como los cuenta la base: un par sustituto es uno. */
    private fun String.characterCount(): Int = length - count { it.isLowSurrogate() }
}
