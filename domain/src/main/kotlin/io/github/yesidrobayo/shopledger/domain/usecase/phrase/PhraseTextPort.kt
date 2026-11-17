package io.github.yesidrobayo.shopledger.domain.usecase.phrase

import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage

/**
 * Lo que la lectura de una frase necesita de la interfaz, que tiene los textos y los formatos:
 * `:domain` no conoce el idioma elegido ni la región. Responde en memoria, sin tocar el disco ni la
 * red.
 */
interface PhraseTextPort {
    /**
     * El idioma en que se escribe o se dicta la frase: el de la interfaz, que es el del teclado. Un
     * idioma que la lectura no conoce responde el más cercano de los que conoce.
     */
    fun language(): PhraseLanguage

    /**
     * Cómo escribe los números el formato regional del negocio, que es como los escribe el teclado
     * al dictar. Sale de los mismos formateadores con que la app muestra las cifras.
     */
    fun numberStyle(): NumberStyle

    /**
     * El nombre visible de la fila inicial [systemKey] de [table], que nace sin nombre propio: el
     * texto traducido de su clave, en el idioma de la interfaz. Una clave que esta versión no
     * conoce devuelve la clave tal cual.
     */
    fun seedName(table: SeedTable, systemKey: String): String

    /** El símbolo visible de la unidad inicial [systemKey], o `null` si no tiene. */
    fun seedUnitSymbol(systemKey: String): String?
}
