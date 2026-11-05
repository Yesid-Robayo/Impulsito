package io.github.yesidrobayo.shopledger.data

/**
 * Los textos que un trabajo en segundo plano de esta capa necesita, en el idioma de la interfaz.
 *
 * Esta capa no tiene recursos de texto ni sabe qué idioma eligió la persona: los textos viven en la
 * capa de la interfaz, que implementa este puerto. Así un trabajo que corre con la app cerrada, en
 * un proceso sin ninguna pantalla, recibe el idioma elegido y no el del teléfono, y esta capa nunca
 * depende de la interfaz.
 *
 * Responde en memoria, sin tocar el disco ni la red, y se puede llamar desde cualquier hilo.
 */
fun interface UserTextPort {
    /** El texto de [text] en el idioma de la interfaz. */
    fun text(text: UserText): String
}

/**
 * Un texto que esta capa le puede pedir a [UserTextPort]. Es una lista cerrada: quien implementa el
 * puerto le da a cada valor su recurso, y uno sin recurso no compila.
 *
 * Cada trabajo que necesita un texto suma aquí su clave, con su recurso en los tres idiomas.
 */
enum class UserText {
    /**
     * La etiqueta del idioma de los recursos que respondieron, como `es`: no es un texto para la
     * persona. Comprueba, sin depender de ninguna frase, que un trabajo recibe el idioma elegido.
     * Un teléfono en un idioma sin carpeta propia responde con la del respaldo.
     */
    RESOURCES_LANGUAGE,
}
