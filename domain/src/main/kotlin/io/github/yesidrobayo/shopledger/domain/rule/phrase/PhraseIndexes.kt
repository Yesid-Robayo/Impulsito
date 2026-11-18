package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Las unidades del catálogo por cada forma en que se dicen: nombre, símbolo, plural y los sinónimos
 * de las unidades iniciales. Un símbolo de una letra ("m", "l", "g") solo vale después de un
 * número, porque suelto es una talla, una inicial o una palabra.
 */
internal class UnitIndex(units: List<PhraseUnit>, private val lexicon: PhraseLexicon) {
    private val byId: Map<Long, PhraseUnit> = units.associateBy { it.id }
    private val byWord: Map<String, PhraseUnit> = units
        .flatMap { unit -> spokenForms(unit).map { it to unit } }
        .toMap()

    private fun spokenForms(unit: PhraseUnit): List<String> {
        val synonyms = unit.systemKey?.let { lexicon.unitSynonyms[it] }.orEmpty()
        return (listOfNotNull(unit.name, unit.symbol) + synonyms)
            .map { PhraseTokenizer.tokenize(it).joinToString(" ") { token -> token.norm } }
            .flatMap { listOf(it, lexicon.singular(it)) }
    }

    /**
     * La unidad dicha en [index], si la hay. Sin un número antes, solo vale una forma de dos letras
     * o más.
     */
    fun at(tokens: List<Token>, index: Int, afterNumber: Boolean): PhraseUnit? {
        if (index >= tokens.size) return null
        val norm = tokens[index].norm
        val unit = byWord[norm] ?: byWord[lexicon.singular(norm)] ?: return null
        return if (afterNumber || norm.length >= MIN_BARE_LENGTH) unit else null
    }

    fun byId(id: Long): PhraseUnit = byId.getValue(id)

    /** "litro y medio", "kilo y medio": una unidad entera más su fracción, sin número antes. */
    fun fractionAt(tokens: List<Token>, index: Int): BareMeasure? {
        val unit = at(tokens, index, afterNumber = false) ?: return null
        val fraction = lexicon.continuations.matchAt(tokens, index + 1) ?: return null
        return BareMeasure(unit, Scale.MILLI + fraction.value, fraction.end)
    }

    /**
     * Si el producto admite la unidad: su base, una de sus conversiones, o una unidad de masa,
     * volumen o longitud que se convierte a una de esas, como 500 gramos de algo que se lleva por
     * kilo.
     */
    fun admits(product: PhraseProduct, unit: PhraseUnit): Boolean {
        val own = listOf(product.baseUnitId) + product.conversions.map { it.unitId }
        if (unit.id in own) return true
        if (unit.baseAmountMilli == null) return false
        return own.mapNotNull {
            byId[it]
        }.any { it.kind == unit.kind && it.baseAmountMilli != null }
    }

    private companion object {
        const val MIN_BARE_LENGTH = 2
    }
}

/** Una medida dicha sin número, como "litro y medio": [milli] de [unit], hasta antes de [end]. */
internal data class BareMeasure(val unit: PhraseUnit, val milli: Long, val end: Int)

/**
 * Una palabra de un nombre ya comparable. [soft] es una palabra de tamaño, que ayuda a elegir pero
 * no excluye.
 */
internal data class ShapedWord(val text: String, val soft: Boolean)

/** Un nombre ya comparable, con la medida que dice ("400 ml", en mililitros), si dice una. */
internal data class ShapedName(val words: List<ShapedWord>, val measure: Long?) {
    val texts: List<String> get() = words.map { it.text }
}

/**
 * Lleva un nombre, dicho o del catálogo, a palabras comparables: sin relleno, en singular, y con
 * cada número y su unidad en forma canónica. Así "gaseosa de 1,5 litros" y "Gaseosa 1,5 L" dan lo
 * mismo, y "tornillo de un cuarto" coincide con "Tornillo 1/4".
 */
internal class WordShaper(
    private val lexicon: PhraseLexicon,
    private val numbers: NumberReader,
    private val units: UnitIndex,
) {
    fun shape(tokens: List<Token>): ShapedName {
        val clean = tokens.filterNot(Token::isSeparator)
        val words = mutableListOf<ShapedWord>()
        val measures = mutableListOf<Long>()
        var index = 0
        while (index < clean.size) {
            val number = numbers.read(clean, index)
            val bare = if (number == null) units.fractionAt(clean, index) else null
            if (bare != null) {
                words.add(ShapedWord(numberWord(bare.milli), soft = false))
                words.add(ShapedWord(unitWord(bare.unit.id), soft = false))
                measureOf(bare.milli, bare.unit)?.let(measures::add)
                index = bare.end
            } else if (number != null) {
                words.add(ShapedWord(numberWord(number.milli), soft = false))
                index = number.end
                val unit = units.at(clean, index, afterNumber = true)
                if (unit != null) {
                    words.add(ShapedWord(unitWord(unit.id), soft = false))
                    measureOf(number.milli, unit)?.let(measures::add)
                    index++
                }
            } else {
                val norm = clean[index].norm
                if (norm !in lexicon.stopwords) {
                    words.add(ShapedWord(lexicon.singular(norm), soft = norm in lexicon.sizes))
                }
                index++
            }
        }
        return ShapedName(words, measures.firstOrNull())
    }

    /**
     * Las palabras de un nombre de persona, de pago o de categoría: sin relleno ni tratamiento, en
     * singular.
     */
    fun nameWords(tokens: List<Token>): List<String> =
        tokens.map { it.norm }.filterNot { it in lexicon.nameStopwords }.map(lexicon::singular)

    private fun measureOf(milli: Long, unit: PhraseUnit): Long? {
        val base = unit.baseAmountMilli ?: return null
        return mulDivRound(milli, base, Scale.MILLI).valueOrNull()
    }

    companion object {
        /** Cómo empieza un número en forma canónica: `#n400000` son 400. */
        val NUMBER_PREFIX: String = "${PhraseWords.CANONICAL_PREFIX}n"

        /** Cómo empieza una unidad en forma canónica: `#u7` es la unidad 7. */
        val UNIT_PREFIX: String = "${PhraseWords.CANONICAL_PREFIX}u"

        fun numberWord(milli: Long): String = NUMBER_PREFIX + milli

        fun unitWord(id: Long): String = UNIT_PREFIX + id
    }
}

/**
 * Un nombre del catálogo listo para comparar. Si [partial], basta con decir parte de él: "María"
 * por "María Gómez".
 */
internal data class NameEntry(val id: Long, val words: List<String>, val partial: Boolean)

/** Uno o varios nombres que coinciden con lo dicho hasta antes de la palabra [end]. */
internal data class NameHit(val ids: List<Long>, val end: Int)

/**
 * Busca nombres de personas, métodos de pago o categorías en la frase, con errores de dictado.
 * Prueba primero el trozo más largo, de hasta [MAX_WINDOW] palabras, y si un nombre se dijo entero
 * gana a los que solo se dijeron en parte: "María Gómez" elige a una, "María" deja elegir entre las
 * dos.
 */
internal class NameIndex(
    private val entries: List<NameEntry>,
    private val shaper: WordShaper,
    private val tolerance: Int = 2,
) {
    fun matchAt(tokens: List<Token>, start: Int): NameHit? =
        (MAX_WINDOW downTo 1).firstNotNullOfOrNull { size -> matchWindow(tokens, start, size) }

    private fun matchWindow(tokens: List<Token>, start: Int, size: Int): NameHit? {
        val end = start + size
        if (end > tokens.size) return null
        val window = tokens.subList(start, end)
        if (window.any { it.isSeparator || it.looksNumeric() }) return null
        // Un nombre no termina en relleno: en "Charles for a haircut", la persona es "Charles" y
        // lo demás queda para la línea.
        if (size > 1 && shaper.nameWords(window.subList(size - 1, size)).isEmpty()) return null
        val spoken = shaper.nameWords(window)
        if (spoken.isEmpty()) return null
        val scored = entries.mapNotNull { entry -> score(spoken, entry) }
        val chosen = scored.filter { it.second }.ifEmpty { scored }
        val ids = chosen.map { it.first.id }.distinct()
        return if (ids.isEmpty()) null else NameHit(ids, end)
    }

    /** La entrada y si se dijo entera, o `null` si lo dicho no está en ella. */
    private fun score(spoken: List<String>, entry: NameEntry): Pair<NameEntry, Boolean>? {
        val cover = PhraseWords.cover(spoken, entry.words, abbreviated = false, tolerance)
        val complete = cover.free.isEmpty()
        val fits = cover.matched == spoken.size && (entry.partial || complete)
        return if (fits) entry to complete else null
    }

    companion object {
        private const val MAX_WINDOW = 4

        /**
         * Los nombres del catálogo, que se pueden decir en parte, y los sinónimos por clave, que se
         * dicen enteros. [tolerance] es cuántos errores de dictado se admiten como máximo en una
         * palabra: en las categorías de gasto, uno, para que "encargué" no sea "recargué".
         */
        fun of(
            names: List<PhraseName>,
            synonyms: Map<String, List<String>>,
            shaper: WordShaper,
            includeNames: Boolean,
            tolerance: Int = 2,
        ): NameIndex {
            val catalogEntries = names.map {
                NameEntry(it.id, shaper.nameWords(PhraseTokenizer.tokenize(it.name)), true)
            }
            val synonymEntries = names.flatMap { name ->
                synonymsOf(name, synonyms).map {
                    NameEntry(name.id, shaper.nameWords(PhraseTokenizer.tokenize(it)), false)
                }
            }
            return NameIndex(
                catalogEntries.takeIf {
                    includeNames
                }.orEmpty() + synonymEntries,
                shaper,
                tolerance,
            )
        }

        private fun synonymsOf(
            name: PhraseName,
            synonyms: Map<String, List<String>>,
        ): List<String> = name.systemKey?.let { synonyms[it] }.orEmpty()
    }
}
