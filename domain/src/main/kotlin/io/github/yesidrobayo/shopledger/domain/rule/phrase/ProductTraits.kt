package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName

/** El tipo que se dice de un producto nuevo: para qué es, y si es un servicio. */
internal enum class SpokenKind(val usage: ProductUsage, val stockMode: ProductStockMode?) {
    /** Un insumo o una materia prima: no se ofrece al vender. */
    INPUT(ProductUsage.INPUT, null),

    /** Algo para vender: el uso de siempre, dicho. */
    SALE(ProductUsage.SALE, null),

    /** Se vende y también se usa para fabricar. */
    BOTH(ProductUsage.BOTH, null),

    /** Un servicio: se vende y no lleva stock. */
    SERVICE(ProductUsage.SALE, ProductStockMode.UNTRACKED),
}

/**
 * Una descripción que se sacó de la frase: [traits] es lo que dice, y [position] la palabra de lo
 * que quedó delante de la que estaba, para saber de qué producto habla.
 */
internal data class PlacedTrait(val position: Int, val traits: NewProductTraits)

/** La frase sin sus descripciones, en [rest], y las descripciones, en orden. */
internal data class TraitScan(val rest: List<Token>, val traits: List<PlacedTrait>)

/**
 * Saca de la frase lo que describe un producto nuevo, antes de buscar verbos y líneas: "esto es un
 * insumo", "es para vender", "en la categoría materias primas". Así una descripción nunca se lee
 * como un producto más, y lo que dice llega al producto nuevo.
 *
 * - **El tipo** va después de una apertura ("esto es", "es", "como") si es una palabra que podría
 *   ser un nombre ("insumo", "servicio"), o solo si no lo es ("para vender", "materia prima").
 * - **La categoría** va después de "categoría" o de "en la categoría", y en inglés también entre
 *   "in the" y "category". Se busca entre las categorías de producto, sin tildes, mayúsculas ni
 *   plurales, con el trozo más largo que dé con una entera; si ninguna da, queda lo dicho, con
 *   mayúscula inicial, como una categoría nueva que se crea con el producto.
 */
internal class TraitReader(
    private val lexicon: PhraseLexicon,
    private val numbers: NumberReader,
    shaper: WordShaper,
    categories: List<PhraseName>,
) {
    private val categoryIndex = NameIndex.of(categories, emptyMap(), shaper, includeNames = true)

    /**
     * Una descripción nunca parte un verbo: "to sell" no se saca de "I'm going to sell", que crea un
     * producto. Sí puede llevar uno entero dentro, como "venta" en "para la venta".
     */
    fun scan(tokens: List<Token>): TraitScan {
        val verbs = lexicon.verbs.findAll(tokens)
        val rest = mutableListOf<Token>()
        val traits = mutableListOf<PlacedTrait>()
        var index = 0
        while (index < tokens.size) {
            val hit = traitAt(tokens, index)?.takeUnless { splitsVerb(it, verbs) }
            if (hit == null) {
                rest.add(tokens[index])
                index++
            } else {
                traits.add(PlacedTrait(rest.size, hit.value))
                index = hit.end
            }
        }
        return TraitScan(rest, traits)
    }

    /** Si [hit] toma parte de un verbo y deja el resto fuera. */
    private fun splitsVerb(hit: PhraseHit<*>, verbs: List<PhraseHit<*>>): Boolean =
        verbs.any { verb ->
            val overlaps = verb.start < hit.end && hit.start < verb.end
            overlaps && (verb.start < hit.start || verb.end > hit.end)
        }

    /** La descripción que empieza en [index], si empieza una. */
    private fun traitAt(tokens: List<Token>, index: Int): PhraseHit<NewProductTraits>? =
        kindAt(tokens, index) ?: categoryAt(tokens, index) ?: categoryAroundAt(tokens, index)

    /** "es un insumo", "para vender", "es materia prima". */
    private fun kindAt(tokens: List<Token>, index: Int): PhraseHit<NewProductTraits>? {
        val lead = lexicon.traitLeads.matchAt(tokens, index)
        val start = lead?.end ?: index
        val article = start < tokens.size && tokens[start].norm in lexicon.traitArticles
        val kindStart = if (article) start + 1 else start
        val plain = lexicon.plainKinds.matchAt(tokens, kindStart)
        val kind = plain ?: lead?.let { lexicon.kinds.matchAt(tokens, kindStart) } ?: return null
        val traits = NewProductTraits(usage = kind.value.usage, stockMode = kind.value.stockMode)
        return PhraseHit(index, kind.end, traits)
    }

    /** "en la categoría bebidas", "es de la categoría materias primas". */
    private fun categoryAt(tokens: List<Token>, index: Int): PhraseHit<NewProductTraits>? {
        val afterLead = lexicon.traitLeads.matchAt(tokens, index)
            ?.let { lexicon.categoryBefore.matchAt(tokens, it.end) }
        val marker = afterLead ?: lexicon.categoryBefore.matchAt(tokens, index) ?: return null
        val window = windowFrom(tokens, marker.end)
        val named = categoryOf(window)
        return PhraseHit(index, marker.end + named.size, named.traits)
    }

    /**
     * "in the drinks category": el nombre entre la apertura y "category". Al principio de la frase
     * o después de una coma, la apertura sobra: "cocoa powder, raw materials category".
     */
    private fun categoryAroundAt(tokens: List<Token>, index: Int): PhraseHit<NewProductTraits>? {
        val opens = index == 0 || tokens[index - 1].isSeparator
        val leadEnd = lexicon.categoryAfterLeads.matchAt(tokens, index)?.end
            ?: index.takeIf { opens && lexicon.categoryAfter.isNotEmpty } ?: return null
        val window = windowFrom(tokens, leadEnd)
        // La ventana se corta justo antes de "category": si no la cierra ahí, no es una categoría.
        val marker = lexicon.categoryAfter.matchAt(tokens, leadEnd + window.size) ?: return null
        if (window.isEmpty()) return null
        val named = categoryOf(window)
        // Lo que no dio con una categoría entera queda dicho tal cual: está entre las dos marcas.
        val traits = if (named.size == window.size) named.traits else unknown(window)
        return PhraseHit(index, marker.end, traits)
    }

    /**
     * Las palabras que pueden ser el nombre de una categoría: hasta una coma, un número, un monto,
     * la apertura de otra descripción, otra marca de categoría o el tope de palabras de un nombre.
     * Un tipo no la corta: en "categoría materias primas", "materias primas" es el nombre.
     */
    private fun windowFrom(tokens: List<Token>, start: Int): List<Token> {
        var end = start
        while (end < tokens.size && end - start < MAX_CATEGORY_WORDS && !stopsName(tokens, end)) {
            end++
        }
        return tokens.subList(start, end)
    }

    private fun stopsName(tokens: List<Token>, index: Int): Boolean {
        val token = tokens[index]
        return token.isSeparator ||
            numbers.startsAt(tokens, index) ||
            token.norm in lexicon.stopsCategory ||
            lexicon.traitLeads.matchAt(tokens, index) != null ||
            lexicon.categoryMarks.matchAt(tokens, index) != null
    }

    /**
     * La categoría que nombra el principio de [window], con el trozo más largo que dé con una
     * entera. Si ninguna da, toda la ventana es el nombre de una nueva: "frutas y verduras".
     */
    private fun categoryOf(window: List<Token>): NamedCategory {
        val found = (window.size downTo 1).firstNotNullOfOrNull { size ->
            val part = window.subList(0, size)
            val hit = categoryIndex.matchAt(part, 0)?.takeIf { it.end == size }
            hit?.let { size to it.ids }
        }
        if (found != null) {
            val (size, ids) = found
            val traits = if (ids.size == 1) {
                NewProductTraits(categoryId = ids[0])
            } else {
                unknown(window.subList(0, size))
            }
            return NamedCategory(size, traits)
        }
        return NamedCategory(window.size, unknown(window))
    }

    /** Una categoría que no se encontró, o que podría ser varias: queda tal como se dijo. */
    private fun unknown(said: List<Token>): NewProductTraits {
        val words = said.dropWhile { it.norm in lexicon.trimmed }
            .dropLastWhile { it.norm in lexicon.trimmed }
        return if (words.isEmpty()) {
            NewProductTraits.NONE
        } else {
            NewProductTraits(newCategory = words.spoken())
        }
    }

    /** Lo que nombró una categoría: cuántas palabras tomó y qué dice. */
    private data class NamedCategory(val size: Int, val traits: NewProductTraits)

    private companion object {
        /** Más palabras que esto ya no son el nombre de una categoría. */
        const val MAX_CATEGORY_WORDS = 4
    }
}

/**
 * A qué producto nuevo va cada descripción: al que se dijo justo antes, o si no hay ninguno antes,
 * al primero que se dice después ("es un insumo: 2 kilos de harina"). [starts] es dónde empieza el
 * nombre de cada producto nuevo en la frase sin descripciones, en el mismo orden que la lista que
 * devuelve. Una descripción sin producto nuevo se descarta: el producto ya existe y no se cambia
 * desde aquí.
 */
internal fun placeTraits(starts: List<Int>, traits: List<PlacedTrait>): List<NewProductTraits> {
    val placed = MutableList(starts.size) { NewProductTraits.NONE }
    for (trait in traits) {
        val before =
            starts.indices.filter { starts[it] < trait.position }.maxByOrNull { starts[it] }
        val after =
            starts.indices.filter { starts[it] >= trait.position }.minByOrNull { starts[it] }
        val target = before ?: after ?: continue
        placed[target] = placed[target].with(trait.traits)
    }
    return placed
}
