package io.github.yesidrobayo.shopledger.domain.rule.phrase

/** La frase sin la forma de crédito que rodeaba a alguien, y si la había. */
internal data class Unwrapped(val credit: Boolean, val tokens: List<Token>)

/**
 * Formas de crédito que llevan a la persona dentro: "on Charles's tab". Se quita la forma, queda
 * el crédito, y la persona se deja después de la preposición con que se nombra a alguien ("to
 * Charles"), para que se busque como cualquier otra. Si dentro solo hay relleno ("on my tab"), no
 * es una persona: esa forma ya está entre las de crédito.
 */
internal object CreditWraps {
    /** Más palabras que esto ya no son el nombre de una persona. */
    private const val MAX_NAME_WORDS = 3

    fun unwrap(tokens: List<Token>, lexicon: PhraseLexicon): Unwrapped {
        // Todos los idiomas tienen una preposición para nombrar a alguien: "a", "to", "para".
        val lead = lexicon.unknownPartyPrepositions.first()
        for ((before, after) in lexicon.creditWraps) {
            for (start in tokens.indices) {
                val inner = start + before.size
                if (!matches(tokens, start, before)) continue
                val last = minOf(inner + MAX_NAME_WORDS, tokens.size - after.size)
                val end = (inner + 1..last).firstOrNull { matches(tokens, it, after) } ?: continue
                val name = tokens.subList(inner, end)
                if (name.all { it.norm in lexicon.nameStopwords || it.norm in lexicon.pronouns }) {
                    continue
                }
                val rewritten = tokens.subList(0, start) + Token(lead, lead) + name +
                    tokens.subList(end + after.size, tokens.size)
                return Unwrapped(true, rewritten)
            }
        }
        return Unwrapped(false, tokens)
    }

    private fun matches(tokens: List<Token>, start: Int, words: List<String>): Boolean =
        tokens.drop(start).take(words.size).map { it.norm } == words
}
