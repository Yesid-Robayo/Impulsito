package io.github.yesidrobayo.shopledger.domain.model.imports

/**
 * Los caracteres de un archivo de texto, uno por uno y en flujo: así un archivo grande nunca se
 * carga entero en memoria antes de comprobar sus topes. Lo da `:data`, que ya decodificó los
 * bytes.
 */
fun interface ImportCharSource {
    /** El siguiente carácter, o [END] si el archivo terminó. */
    fun read(): Int

    companion object {
        /** Lo que devuelve [read] al final del archivo. */
        const val END: Int = -1
    }
}
