package io.github.yesidrobayo.shopledger.localization

/**
 * El idioma de la interfaz que eligió la persona: el del teléfono, o uno fijado dentro de la app.
 *
 * No es el formato regional. Montos, cantidades y fechas siguen el formato regional elegido aunque
 * la interfaz esté en otro idioma: con la app en inglés, un negocio que escribe `1.234,56` lo sigue
 * viendo así.
 *
 * No hay una lista cerrada de idiomas. Agregar uno es agregar su carpeta de recursos, sin tocar este
 * código: cualquier etiqueta bien formada se acepta, y Android elige la carpeta más cercana. Una
 * etiqueta sin carpeta propia cae en inglés.
 *
 * Es Kotlin puro, para probarlo en el PC.
 */
sealed interface UiLanguage {
    /** El texto que guarda el archivo de idioma, sin el salto de línea. */
    val fileValue: String

    /** Sigue al teléfono. Es el valor de una instalación nueva. */
    data object System : UiLanguage {
        override val fileValue: String = SYSTEM_VALUE
    }

    /**
     * Un idioma fijado dentro de la app, por su etiqueta, como `es`, `pt` o `pt-BR`. Se crea con
     * [of] o [parse], que comprueban la forma de la etiqueta.
     */
    @ConsistentCopyVisibility
    data class Chosen internal constructor(val tag: String) : UiLanguage {
        override val fileValue: String get() = tag
    }

    companion object {
        /** El texto del archivo cuando el idioma sigue al teléfono. */
        const val SYSTEM_VALUE = "system"

        // Holgura para etiquetas con región o escritura, como es-419 o zh-Hant-TW.
        private const val MAX_TAG_LENGTH = 35

        // Se escribe por su código: lint rechaza el carácter literal dentro de un archivo.
        private val byteOrderMark = Char(0xFEFF)

        // La forma de una etiqueta de idioma: un idioma de letras y, después, partes de letras o
        // cifras separadas por guiones. No admite rutas, espacios ni otros signos.
        private val tagShape = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")

        /**
         * Lee el contenido del archivo de idioma. Devuelve `null` si no es ni [SYSTEM_VALUE] ni una
         * etiqueta bien formada: quien llama lo trata como [System], porque un archivo dañado nunca
         * debe impedir que la app abra.
         *
         * Acepta espacios, saltos de línea y la marca de orden de bytes alrededor, que deja un
         * editor de texto.
         */
        fun parse(raw: String): UiLanguage? =
            of(raw.trim { it.isWhitespace() || it == byteOrderMark })

        /**
         * El idioma de la etiqueta [tag], como la entrega Android o el selector. Una etiqueta vacía
         * o [SYSTEM_VALUE] es [System]: vacía es como Android dice que la app sigue al teléfono, y
         * un archivo vacío no guarda ninguna elección. Devuelve `null` si la etiqueta no tiene la
         * forma de una etiqueta de idioma.
         */
        fun of(tag: String): UiLanguage? = when {
            tag.isEmpty() || tag.equals(SYSTEM_VALUE, ignoreCase = true) -> System
            tag.length > MAX_TAG_LENGTH || !tagShape.matches(tag) -> null
            else -> Chosen(tag)
        }
    }
}
