package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/**
 * Una palabra de la frase. [raw] es como llegó, con sus mayúsculas y tildes, para devolver un
 * nombre nuevo tal como se dijo; [norm] es la forma normalizada con la que se compara.
 */
internal data class Token(val raw: String, val norm: String) {
    val isSeparator: Boolean get() = norm == SEPARATOR

    companion object {
        /** Lo que deja una coma, un punto o dos puntos: corta una línea de la siguiente. */
        const val SEPARATOR: String = ","

        val separator: Token = Token(SEPARATOR, SEPARATOR)
    }
}

/**
 * Parte una frase en palabras.
 *
 * El texto llega de un teclado o de un dictado: sin puntuación fija, con números pegados a la
 * unidad (`2kg`) y con símbolos de moneda que no dicen nada del monto. Por eso la coma o el punto
 * entre dos cifras queda dentro del número, y en cualquier otro lugar corta la línea; un punto
 * después de una abreviatura corta (`Sr.`, `kg.`) no corta nada; los apóstrofos se quitan (`I'm` es
 * `im`), y los guiones, comillas, paréntesis y símbolos de moneda separan.
 */
internal object PhraseTokenizer {
    /** Signos que cortan una línea de la siguiente, salvo entre dos cifras. */
    private const val BREAKS = ",;:.!?…"

    /**
     * Signos que separan palabras sin cortar la línea. Los de moneda se descartan: el número queda
     * solo.
     */
    private const val SPLITTERS = "-–—_+*=&#%|~^\"«»“”()[]{}¿¡<>$€£¥₡₲₱₽₹¢"

    /** Apóstrofos: se quitan sin separar, para que `don't` y `dont` sean lo mismo. */
    private const val APOSTROPHES = "'’`´"

    /** Largo máximo de una abreviatura seguida de punto, como `Sr.` o `kg.`. */
    private const val ABBREVIATION_LENGTH = 3

    fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val word = StringBuilder()
        for (index in text.indices) {
            val char = text[index]
            when {
                char in APOSTROPHES -> Unit

                char in BREAKS && isInsideNumber(text, index) -> word.append(char)

                char == '.' && isAbbreviation(word) -> flush(word, tokens)

                char in BREAKS -> {
                    flush(word, tokens)
                    tokens.add(Token.separator)
                }

                char.isWhitespace() || char in SPLITTERS -> flush(word, tokens)

                else -> word.append(char)
            }
        }
        flush(word, tokens)
        return tokens
    }

    /** Una coma o un punto entre dos cifras es parte del número: `1,5` o `2.500`. */
    private fun isInsideNumber(text: String, index: Int): Boolean =
        index > 0 && index + 1 < text.length && text[index - 1].isDigit() &&
            text[index + 1].isDigit()

    private fun isAbbreviation(word: StringBuilder): Boolean =
        word.isNotEmpty() && word.length <= ABBREVIATION_LENGTH && word.all(Char::isLetter)

    private fun flush(word: StringBuilder, tokens: MutableList<Token>) {
        for (piece in splitDigits(word.toString())) {
            val norm = TextNormalizer.normalize(piece)
            if (norm.isNotEmpty()) tokens.add(Token(piece, norm))
        }
        word.clear()
    }

    /**
     * Separa cifras y letras pegadas: `2kg` es `2` y `kg`, y `400ml` es `400` y `ml`. `1/4` y `c/u`
     * quedan enteros.
     */
    private fun splitDigits(text: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        for (index in 1 until text.length) {
            val before = text[index - 1]
            val after = text[index]
            if (before.isDigit() != after.isDigit() && before.isLetterOrDigit() &&
                after.isLetterOrDigit()
            ) {
                pieces.add(text.substring(start, index))
                start = index
            }
        }
        pieces.add(text.substring(start))
        return pieces
    }
}

/**
 * Un trozo de la frase que coincidió con una entrada de vocabulario: de [start] hasta antes de
 * [end].
 */
internal data class PhraseHit<out T>(val start: Int, val end: Int, val value: T)

/**
 * Frases de vocabulario, de una o varias palabras, con un valor cada una. Busca siempre la más
 * larga primero, para que "me compraron" gane a "compraron" y "a crédito" a "a".
 */
internal class PhraseTable<T>(entries: List<Pair<String, T>>) {
    private val phrases: List<Pair<List<String>, T>> = entries
        .map { (text, value) -> PhraseTokenizer.tokenize(text).map(Token::norm) to value }
        .sortedByDescending { (words, _) -> words.size }

    fun matchAt(tokens: List<Token>, start: Int): PhraseHit<T>? {
        val (words, value) = phrases.firstOrNull { (words, _) -> startsWith(tokens, start, words) }
            ?: return null
        return PhraseHit(start, start + words.size, value)
    }

    /** Una entrada de una sola palabra parecida a la de [index], con errores de dictado. */
    fun closeAt(tokens: List<Token>, index: Int): PhraseHit<T>? {
        val norm = tokens[index].norm
        val (_, value) = phrases.firstOrNull { (words, _) ->
            words.size == 1 && PhraseWords.closeness(norm, words[0]) != null
        } ?: return null
        return PhraseHit(index, index + 1, value)
    }

    /** Todas las apariciones, de izquierda a derecha y sin solaparse. */
    fun findAll(tokens: List<Token>): List<PhraseHit<T>> {
        val hits = mutableListOf<PhraseHit<T>>()
        var index = 0
        while (index < tokens.size) {
            val hit = matchAt(tokens, index)
            if (hit == null) {
                index++
            } else {
                hits.add(hit)
                index = hit.end
            }
        }
        return hits
    }

    private fun startsWith(tokens: List<Token>, start: Int, words: List<String>): Boolean =
        start + words.size <= tokens.size &&
            words.indices.all { tokens[start + it].norm == words[it] }

    companion object {
        /** Una tabla de frases sin valor: solo importa si aparecen. */
        fun of(phrases: List<String>): PhraseTable<Unit> = PhraseTable(phrases.map { it to Unit })
    }
}

/**
 * Lo que quedó después de sacar algo de la frase: [value], si se encontró, y las palabras que
 * siguen en [rest].
 */
internal data class Extracted<out T>(val value: T?, val rest: List<Token>)

/** Las palabras sin las que cubren los [hits]. */
internal fun List<Token>.without(hits: List<PhraseHit<*>>): List<Token> =
    filterIndexed { index, _ -> hits.none { index >= it.start && index < it.end } }

/** Las palabras sin las que van de [start] hasta antes de [end]. */
internal fun List<Token>.cut(start: Int, end: Int): List<Token> =
    subList(0, start) + subList(end, size)

/** Si la palabra empieza con una cifra: un número escrito, que se puede leer o no. */
internal fun Token.looksNumeric(): Boolean = norm[0].isDigit()

/**
 * Las palabras tal como se dijeron, con la primera letra en mayúscula: así se propone un nombre
 * nuevo.
 */
internal fun List<Token>.spoken(): String = joinToString(" ") { it.raw }.capitalized()

internal fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }
