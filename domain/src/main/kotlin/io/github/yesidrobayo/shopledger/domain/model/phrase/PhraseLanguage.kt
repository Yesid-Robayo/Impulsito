package io.github.yesidrobayo.shopledger.domain.model.phrase

/**
 * Idioma en que llega la frase escrita o dictada. Decide el vocabulario que se busca en ella: los
 * verbos, los números dichos en palabras y las palabras de relleno. No dice nada del país: la
 * moneda y el formato de los números entran aparte.
 */
enum class PhraseLanguage {
    ES,
    EN,
    PT,
}

/**
 * Cómo escribe los números el teclado al dictar: [decimal] separa los decimales y [grouping] agrupa
 * los miles. Sale de la configuración regional que eligió la persona, nunca del idioma de la frase.
 */
data class NumberStyle(val decimal: Char, val grouping: Char)
