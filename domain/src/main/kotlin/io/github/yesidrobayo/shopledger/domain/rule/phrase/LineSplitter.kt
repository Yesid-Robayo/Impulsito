package io.github.yesidrobayo.shopledger.domain.rule.phrase

/**
 * Parte la frase en líneas por comas y por "y", "más", "también"... sin romper lo que va junto: un
 * número dicho con "y" ("treinta y cinco", "dos mil y quinientos", "dos y medio"), una unidad con
 * su fracción ("kilo y medio") ni un nombre del catálogo que lleva la palabra ("sal y pimienta").
 */
internal class LineSplitter(
    private val lexicon: PhraseLexicon,
    private val numbers: NumberReader,
    private val units: UnitIndex,
    private val products: ProductFinder,
) {
    fun split(tokens: List<Token>): List<List<Token>> {
        val lines = mutableListOf(mutableListOf<Token>())
        var index = 0
        while (index < tokens.size) {
            val kept = keptEnd(tokens, index)
            val separator = if (kept > index) null else separatorEnd(tokens, index)
            when {
                kept > index && startsLine(tokens, kept, lines.last()) -> {
                    lines.add(tokens.subList(index, kept).toMutableList())
                    index = kept
                }

                kept > index -> {
                    lines.last().addAll(tokens.subList(index, kept))
                    index = kept
                }

                separator != null -> {
                    lines.add(mutableListOf())
                    index = separator
                }

                else -> {
                    lines.last().add(tokens[index])
                    index++
                }
            }
        }
        return lines.filter { it.isNotEmpty() }
    }

    /** Hasta dónde va algo que no se parte: un número, o una unidad con su fracción. */
    private fun keptEnd(tokens: List<Token>, index: Int): Int {
        val number = numbers.read(tokens, index)
        if (number != null) return number.end
        val bare = units.fractionAt(tokens, index) ?: return index
        return bare.end
    }

    /**
     * Si el número que termina en [end] empieza un producto nuevo sin coma ni "y": va después de
     * una palabra de producto y antes de otra que no es su unidad, como en "tres panes dos
     * gaseosas".
     */
    private fun startsLine(tokens: List<Token>, end: Int, line: List<Token>): Boolean {
        val previous = line.lastOrNull() ?: return false
        val bound = previous.looksNumeric() || previous.norm in lexicon.amountBound
        if (bound || end >= tokens.size) return false
        return !tokens[end].isSeparator &&
            units.at(tokens, end, afterNumber = true) == null &&
            lexicon.eachAfter.matchAt(tokens, end) == null &&
            lexicon.separators.matchAt(tokens, end) == null
    }

    private fun separatorEnd(tokens: List<Token>, index: Int): Int? {
        if (tokens[index].isSeparator) return index + 1
        val hit = lexicon.separators.matchAt(tokens, index) ?: return null
        return if (products.joins(tokens, index - 1, hit.end)) null else hit.end
    }
}
