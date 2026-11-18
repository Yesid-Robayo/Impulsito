package io.github.yesidrobayo.shopledger.ui.phrase

/**
 * El idioma que se le pide al servicio de voz, como etiqueta BCP 47 con país, sin nada de Android.
 *
 * **Por qué siempre con país.** Algunos servicios de voz no aceptan un idioma solo, como `es`: no
 * encuentran su paquete de idioma y fallan al instante, aunque lo tengan para `es-CO` o `es-US`. El
 * país no cambia ningún formato ni ninguna regla de la app: solo elige el paquete de voz.
 *
 * - **El idioma** es siempre el de la interfaz, que es en el que se dice la frase.
 * - **El país** es el de la región del negocio, la de los formatos, si esa región es del mismo
 *   idioma: quien usa la app en español con formato `es-CO` habla español de ahí.
 * - **Si no hay país, o la región es de otro idioma,** va uno por idioma que los servicios de voz
 *   suelen tener para cualquier app. Es un respaldo para el servicio, no un país asumido para el
 *   negocio.
 */
object SpeechLanguage {
    /**
     * La etiqueta para [uiLanguage], el código del idioma de la interfaz, con la región de
     * [formatTag], la etiqueta de los formatos del negocio, como `es-CO`.
     */
    fun tagFor(uiLanguage: String, formatTag: String): String {
        val language = uiLanguage.lowercase()
        val parts = formatTag.split('-', '_')
        val formatLanguage = parts.firstOrNull().orEmpty().lowercase()
        val region = parts.drop(1).firstOrNull(::isCountry)?.uppercase()
        val country =
            if (region != null && formatLanguage == language) region else FALLBACK_COUNTRY[language]
        return if (country == null) language else "$language-$country"
    }

    /**
     * El idioma con el que se escucha, entre los que el servicio dice tener. Un servicio puede tener
     * el español descargado como `es-US` y no reconocer `es-CO`: pedirle `es-CO` falla al instante,
     * aunque entendería la frase igual.
     *
     * Primero lo descargado ([installed]), que funciona sin internet: el mismo idioma con el mismo
     * país y, si no, con otro país. Después lo que reconoce con red ([online]), en el mismo orden. Si
     * el servicio no tiene el idioma, o no lo dijo, se pide [wanted] tal cual.
     */
    fun pick(wanted: String, installed: List<String>, online: List<String>): String {
        val language = languageOf(wanted)
        val sameTag = { tag: String ->
            normalized(tag).equals(normalized(wanted), ignoreCase = true)
        }
        val sameLanguage = { tag: String -> languageOf(tag) == language }
        return installed.firstOrNull(sameTag)
            ?: installed.firstOrNull(sameLanguage)
            ?: online.firstOrNull(sameTag)
            ?: online.firstOrNull(sameLanguage)
            ?: wanted
    }

    private fun normalized(tag: String): String = tag.replace('_', '-')

    private fun languageOf(tag: String): String = normalized(tag).substringBefore('-').lowercase()

    /** Un país de dos letras: ni un script, como `Latn`, ni una región numérica, como `419`. */
    private fun isCountry(part: String): Boolean =
        part.length == COUNTRY_LENGTH && part.all { it in 'a'..'z' || it in 'A'..'Z' }

    private const val COUNTRY_LENGTH = 2

    /** El país de respaldo de cada idioma de la app, con paquete de voz en casi todo servicio. */
    private val FALLBACK_COUNTRY =
        mapOf(
            "es" to "US",
            "en" to "US",
            "pt" to "BR",
        )
}
