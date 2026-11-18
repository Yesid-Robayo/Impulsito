package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch

/**
 * Qué productos admite una operación, y qué problema se anota si el que más se parece no es de
 * esos: producir algo sin receta deja [PhraseIssue.NO_RECIPE]. Sin problema anotado, se propone
 * igual el producto que más se parece.
 */
internal class ProductRule(val admits: (PhraseProduct) -> Boolean, val fallbackIssue: PhraseIssue?)

/** El producto de una línea, y el problema que deja, si deja uno. */
internal data class ProductResolution(val match: ProductMatch, val issue: PhraseIssue?)

/**
 * Busca el producto que se nombró.
 *
 * Todo lo dicho debe estar en el nombre: "harina" cabe en "Harina de trigo" y en "Harina de maíz",
 * y deja elegir, pero "torta de fresa" no cabe en "Torta de chocolate" y es un producto nuevo. Un
 * nombre dicho entero gana a los que solo se dijeron en parte, y entre los que quedan igual de
 * cerca, una palabra de tamaño ("grande", "pequeña") elige por la medida del nombre. Es mejor
 * preguntar que registrar con confianza algo equivocado.
 */
internal class ProductFinder(
    private val products: List<PhraseProduct>,
    private val shaper: WordShaper,
    private val lexicon: PhraseLexicon,
) {
    private val entries: List<ProductEntry> = products.map { product ->
        val tokens = PhraseTokenizer.tokenize(product.name)
        val head = shaper.shape(tokens.takeWhile { it.norm !in lexicon.nameConnectors }).texts
        ProductEntry(product, shaper.shape(tokens), head)
    }
    private val byId: Map<Long, PhraseProduct> = products.associateBy { it.id }

    /**
     * Palabras unidas por un separador dentro de un nombre, como "sal y pimienta": ahí la "y" no
     * parte la línea.
     */
    private val joinedPairs: Set<Pair<String, String>> = products.flatMap { product ->
        val tokens = PhraseTokenizer.tokenize(product.name)
        (1 until tokens.size - 1)
            .filter { tokens[it].norm in lexicon.separatorWords }
            .map { lexicon.singular(tokens[it - 1].norm) to lexicon.singular(tokens[it + 1].norm) }
    }.toSet()

    fun byId(id: Long): PhraseProduct = byId.getValue(id)

    /** Si la palabra es, o se parece a, una palabra del nombre de algún producto. */
    fun knowsWord(norm: String): Boolean {
        val word = lexicon.singular(norm)
        return entries.any { entry ->
            entry.name.texts.any { PhraseWords.closeness(word, it) != null }
        }
    }

    /**
     * Si un número dicho después de [span] es parte de un nombre: algún producto lleva ese número y
     * alguna de las palabras ya dichas, como "gaseosa" y 400 en "Gaseosa 400 ml".
     */
    fun fitsNumber(span: List<Token>, numberWord: String): Boolean {
        val said = shaper.shape(span).texts
        return entries.any { entry ->
            numberWord in entry.name.texts &&
                said.any { it in entry.name.texts }
        }
    }

    /** Si algún producto lleva en su nombre esa medida entera: el 400 y los ml de "Soda 400 ml". */
    fun hasMeasure(numberWord: String, unitWord: String): Boolean = entries.any { entry ->
        numberWord in entry.name.texts && unitWord in entry.name.texts
    }

    /** Si el separador entre [before] y [after] es parte del nombre de un producto. */
    fun joins(tokens: List<Token>, before: Int, after: Int): Boolean {
        if (before < 0 || after >= tokens.size) return false
        val pair = lexicon.singular(tokens[before].norm) to lexicon.singular(tokens[after].norm)
        return pair in joinedPairs
    }

    /**
     * El producto nombrado en [span], o `null` si no se nombró ninguno. Prefiere los que [rule]
     * admite; si ninguno se parece, propone el que más se parece de todos, con el problema de la
     * regla, y si nada se parece, un producto nuevo con el nombre dicho.
     */
    fun find(span: List<Token>, rule: ProductRule): ProductResolution? {
        val spoken = shaper.shape(span).words
        if (spoken.isEmpty()) return null
        val plurals = pluralsOf(span)
        val preferred = best(spoken, entries.filter { rule.admits(it.product) }, plurals)
        val any = preferred ?: best(spoken, entries, plurals)
        val issue = if (preferred == null && any != null) rule.fallbackIssue else null
        return ProductResolution(any ?: ProductMatch.Unknown(spokenName(span)), issue)
    }

    /** Si [span] nombra un producto del catálogo que [admits] acepta. */
    fun names(span: List<Token>, admits: (PhraseProduct) -> Boolean): Boolean {
        val spoken = shaper.shape(span).words
        val pool = entries.filter { admits(it.product) }
        return spoken.isNotEmpty() && best(spoken, pool, pluralsOf(span)) != null
    }

    /**
     * Las palabras dichas en plural, ya en singular: "oranges" da "orange". Solo las que terminan
     * en "s": la regla de singular también cambia "berry" a "berrie" para comparar.
     */
    private fun pluralsOf(span: List<Token>): Set<String> = span
        .filter { it.norm.endsWith(PLURAL_END) && lexicon.singular(it.norm) != it.norm }
        .map { lexicon.singular(it.norm) }
        .toSet()

    /**
     * Entre los productos [ids], el que nombra [span]: "las grandes" entre las dos gaseosas por las
     * que se preguntó. Solo un tamaño elige por la medida del nombre; si no, se compara el nombre
     * como siempre, solo entre esos. `null` si no elige.
     */
    fun among(span: List<Token>, ids: List<Long>): ProductMatch? {
        val spoken = shaper.shape(span).words
        val pool = entries.filter { it.product.id in ids }
        if (spoken.all { it.soft }) {
            val sized = bySize(pool.map { Candidate(it, complete = true, distance = 0) }, spoken)
            return sized.singleOrNull()?.let { ProductMatch.Found(it.entry.product.id) }
        }
        // Entre los que se preguntó, el complemento basta: "de maíz" entre las harinas.
        return best(spoken, pool, pluralsOf(span), complements = true)
    }

    /** Si [span] nombra un producto del catálogo con todas sus palabras dichas sin error. */
    fun namesExactly(span: List<Token>): Boolean {
        val spoken = shaper.shape(span).words
        return entries.any { entry -> score(spoken, entry)?.distance == 0 }
    }

    /** El producto que ya se llama exactamente así, para no crear uno repetido. */
    fun existing(span: List<Token>): ProductMatch.Found? {
        val spoken = shaper.shape(span).words
        val same = entries
            .mapNotNull { score(spoken, it) }
            .filter { it.complete && it.distance == 0 }
        return same.singleOrNull()?.let { ProductMatch.Found(it.entry.product.id) }
    }

    private fun best(
        spoken: List<ShapedWord>,
        pool: List<ProductEntry>,
        plurals: Set<String>,
        complements: Boolean = false,
    ): ProductMatch? {
        val candidates = pool.mapNotNull { score(spoken, it, complements) }
        if (candidates.isEmpty()) return null
        // "oranges" con solo "Orange juice": un modificador no va en plural, así que es otra cosa.
        if (candidates.all { modifierOnly(spoken, plurals, it.entry) }) return null
        val complete = candidates.filter { it.complete }.ifEmpty { candidates }
        val closest = complete.map { it.distance }.min()
        val tied = complete.filter { it.distance == closest }
        val ids = bySize(tied, spoken).map { it.entry.product.id }
        return if (ids.size == 1) ProductMatch.Found(ids[0]) else ProductMatch.Choose(ids)
    }

    /**
     * Si todo lo dicho está en el nombre, qué tan cerca. Las palabras de tamaño no excluyen
     * ("gaseosa grande" cabe en "Gaseosa 1,5 L"), salvo que sean lo único dicho.
     */
    private fun score(
        spoken: List<ShapedWord>,
        entry: ProductEntry,
        complements: Boolean = false,
    ): Candidate? {
        val (soft, hard) = spoken.partition { it.soft }
        val required = hard.ifEmpty { soft }.map { it.text }
        val optional = soft.map { it.text }.filterNot { it in required }
        val first = PhraseWords.cover(required, entry.name.texts)
        val complementOnly = !complements && saysOnlyComplement(required, entry)
        if (first.matched < required.size || complementOnly) return null
        val second = PhraseWords.cover(optional, first.free)
        val distance = first.distance + second.distance
        return Candidate(entry, complete = second.free.isEmpty(), distance = distance)
    }

    /**
     * En un idioma que pone lo importante al final ([PhraseLexicon.headLast]), si lo dicho en
     * plural solo cubre un modificador del nombre: "oranges" en "Orange juice". "mozzarella" en
     * "Mozzarella cheese" sí vale, porque un modificador dicho en singular puede ser el producto.
     */
    private fun modifierOnly(
        spoken: List<ShapedWord>,
        plurals: Set<String>,
        entry: ProductEntry,
    ): Boolean {
        if (!lexicon.headLast) return false
        val head = entry.name.texts.lastOrNull {
            it[0] != PhraseWords.CANONICAL_PREFIX && it.length > 1
        } ?: return false
        val said = spoken.map { it.text }
        val coversHead = PhraseWords.cover(said, listOf(head)).matched > 0
        // Lo dicho ya está en el nombre: si no es su cabeza, es un modificador.
        val pluralModifier = said.any { word -> word in plurals && word != head }
        return !coversHead && pluralModifier
    }

    /**
     * Si lo dicho solo nombra lo que va después de "de" en el nombre: "naranja" no es "Jugo de
     * naranja", ni "chocolate" es "Torta de chocolate".
     */
    private fun saysOnlyComplement(required: List<String>, entry: ProductEntry): Boolean {
        val hasComplement = entry.head.size < entry.name.words.size
        return hasComplement && PhraseWords.cover(required, entry.head).matched == 0
    }

    /**
     * Entre los empatados, "grande" elige el de más medida y "pequeña" el de menos; sin medidas,
     * siguen empatados.
     */
    private fun bySize(tied: List<Candidate>, spoken: List<ShapedWord>): List<Candidate> {
        val direction = spoken.firstNotNullOfOrNull { lexicon.sizes[it.text] } ?: return tied
        val sized = tied.mapNotNull { candidate ->
            candidate.entry.name.measure?.let { candidate to it * direction }
        }
        val pick = sized.maxByOrNull { it.second } ?: return tied
        return listOf(pick.first)
    }

    /**
     * El nombre dicho, sin relleno a los lados, cada palabra en singular y con mayúscula inicial.
     */
    fun spokenName(span: List<Token>): String = span.dropWhile { it.norm in lexicon.trimmed }
        .dropLastWhile { it.norm in lexicon.trimmed }
        .joinToString(" ") { lexicon.displaySingular(it.raw.lowercase()) }
        .capitalized()

    /** Un producto con su nombre comparable y la cabeza de ese nombre, lo que va antes de "de". */
    private data class ProductEntry(
        val product: PhraseProduct,
        val name: ShapedName,
        val head: List<String>,
    )

    private data class Candidate(val entry: ProductEntry, val complete: Boolean, val distance: Int)

    private companion object {
        /** Cómo termina un plural en los idiomas de la app. */
        const val PLURAL_END = 's'
    }
}
