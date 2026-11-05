package io.github.yesidrobayo.shopledger.localization

import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.data.UserText
import io.github.yesidrobayo.shopledger.data.UserTextPort

/**
 * Da a los trabajos de la capa de datos sus textos, de los recursos de la app y en el idioma de la
 * interfaz, aunque no haya ninguna actividad: los lee del contexto de [contexts].
 */
class AndroidUserTextPort(private val contexts: LocalizedContextProvider) : UserTextPort {
    override fun text(text: UserText): String = contexts.context().getString(text.resource)
}

/** El recurso de cada texto. Una clave nueva sin su recurso no compila. */
@get:StringRes
private val UserText.resource: Int
    get() = when (this) {
        UserText.RESOURCES_LANGUAGE -> R.string.resources_language
    }
