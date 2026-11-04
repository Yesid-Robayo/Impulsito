package io.github.yesidrobayo.shopledger.domain.rule

/**
 * Forma normalizada de un nombre: la que se guarda junto al nombre para exigir que no se repita y
 * para ordenar, y la que se aplica al texto que se busca. Así `Jabón Líquido` y `jabon liquido`
 * chocan, y buscar `jabon` encuentra `Jabón Líquido`.
 *
 * Es la misma en los tres idiomas de la app y nunca lee el idioma del teléfono. Los pasos, en orden:
 *
 * 1. Pasa a minúsculas con `lowercase()` de Kotlin, que no depende del idioma: con el teléfono en
 *    turco, `INDIO` sigue siendo `indio` y no pierde el punto de la i.
 * 2. Une la `n` seguida de la virgulilla combinante (U+0303) en `ñ`, y quita las demás marcas
 *    combinantes, de U+0300 a U+036F. Un nombre escrito con la marca aparte, como lo dejan algunos
 *    teclados o un archivo importado, da lo mismo que con la letra ya compuesta.
 * 3. Cambia cada vocal con tilde, acento grave, diéresis, circunflejo o virgulilla por la vocal sola,
 *    y la `ç` por `c`. La `ñ` se conserva: en español es otra letra, y `Piña` y `Pina` son productos
 *    distintos.
 * 4. Recorta los extremos y deja un solo espacio donde había varios. Cuenta como espacio todo blanco
 *    de Unicode, también el espacio duro y el tabulador que llegan al pegar texto de otra app.
 *
 * No usa el normalizador de Unicode de la plataforma, porque el dominio es Kotlin puro y debe dar el
 * mismo resultado en cualquier plataforma. Por eso las letras del paso 3 salen de una tabla propia y
 * acotada a los tres idiomas de la app: una letra fuera de ella, o una marca fuera de ese bloque,
 * queda igual.
 *
 * El resultado vive en la base. Cambiar cualquiera de estos pasos obliga a recalcular la columna en
 * una migración: si no, dos nombres que antes chocaban dejarían de chocar.
 *
 * Nunca es más largo que el texto que recibe, así que cabe en el mismo tope del nombre. Puede quedar
 * vacío, por ejemplo con un nombre hecho solo de espacios o de marcas combinantes, y quien guarda un
 * nombre lo trata como vacío. No escapa `%`, `_` ni `\`: el nombre normalizado los guarda tal cual,
 * y quien arma una búsqueda con `LIKE` los escapa.
 */
object TextNormalizer {
    /** Marcas combinantes que quita el paso 2: el bloque de las diacríticas de uso general. */
    private val COMBINING_MARKS = '\u0300'..'\u036F'

    /** Virgulilla combinante: pegada a una `n`, forma la `ñ`. */
    private const val COMBINING_TILDE = '\u0303'

    /** `ñ`, ya compuesta. */
    private const val ENIE = '\u00F1'

    fun normalize(text: String): String =
        collapseSpaces(foldLatinLetters(composeEnieAndDropMarks(text.lowercase())))

    /**
     * Paso 2. Solo una virgulilla pegada a la `n` forma la `ñ`: con otra marca en medio, la
     * secuencia no equivale a la letra compuesta, y las dos marcas se quitan.
     */
    private fun composeEnieAndDropMarks(text: String): String {
        val result = StringBuilder(text.length)
        for (index in text.indices) {
            val char = text[index]
            when {
                char !in COMBINING_MARKS -> result.append(char)

                // La n anterior ya está en el resultado, porque este paso la copia tal cual.
                char == COMBINING_TILDE && index > 0 && text[index - 1] == 'n' ->
                    result.setCharAt(result.length - 1, ENIE)

                // Las demás marcas combinantes se descartan.
                else -> Unit
            }
        }
        return result.toString()
    }

    /** Paso 3. Cada letra se cambia por una sola, así que el largo no cambia. */
    private fun foldLatinLetters(text: String): String {
        val result = StringBuilder(text.length)
        for (char in text) result.append(foldLetter(char))
        return result.toString()
    }

    /**
     * La tabla del paso 3. Solo recibe minúsculas, porque el paso 1 ya pasó todo a minúsculas. La
     * `ñ` no está, y por eso se conserva.
     */
    private fun foldLetter(char: Char): Char = when (char) {
        // à á â ã ä
        '\u00E0', '\u00E1', '\u00E2', '\u00E3', '\u00E4' -> 'a'

        // è é ê ẽ ë
        '\u00E8', '\u00E9', '\u00EA', '\u1EBD', '\u00EB' -> 'e'

        // ì í î ĩ ï
        '\u00EC', '\u00ED', '\u00EE', '\u0129', '\u00EF' -> 'i'

        // ò ó ô õ ö
        '\u00F2', '\u00F3', '\u00F4', '\u00F5', '\u00F6' -> 'o'

        // ù ú û ũ ü
        '\u00F9', '\u00FA', '\u00FB', '\u0169', '\u00FC' -> 'u'

        // ç
        '\u00E7' -> 'c'

        else -> char
    }

    /**
     * Paso 4. Un grupo de blancos se escribe como un espacio solo si antes y después hay algo que no
     * es blanco: así los extremos se van sin recortar aparte.
     */
    private fun collapseSpaces(text: String): String {
        val result = StringBuilder(text.length)
        var spacePending = false
        for (char in text) {
            if (char.isWhitespace()) {
                spacePending = true
            } else {
                if (spacePending && result.isNotEmpty()) result.append(' ')
                spacePending = false
                result.append(char)
            }
        }
        return result.toString()
    }
}
