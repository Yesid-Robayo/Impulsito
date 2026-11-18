package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit

/**
 * Una línea leída, antes de buscar su producto: [span] son las palabras del producto. Los montos
 * van en milésimas de la unidad mayor de la moneda. [tail] son las palabras después de "a" o "para"
 * sin cifras, que nombran a alguien. [ellipsis] es el "de" que siguió a la cantidad sin unidad,
 * como en "y 2 de naranja": la línea puede tomar la cabeza del nombre de la anterior. [alternative]
 * es ese nombre completado con la línea anterior, que se prueba si el propio no da con un producto.
 * [unused] dice que el nombre se cortó en una palabra que no es de ningún producto, como "esto" o
 * "es": lo que seguía no se usó. [copula] dice que la línea empezó con "fueron" o "eran": solo dice
 * una cantidad o un monto. [costSaid] dice que el monto se dijo como lo que costó: "que me costaron
 * 4 mil".
 */
internal data class LineDraft(
    val span: List<Token>,
    val quantity: Long?,
    val unitId: Long?,
    val unitPrice: Long?,
    val lineTotal: Long?,
    val unreadable: Boolean,
    val tail: List<Token>,
    val ellipsis: Token? = null,
    val alternative: List<Token> = emptyList(),
    val unused: Boolean = false,
    val copula: Boolean = false,
    val costSaid: Boolean = false,
) {
    /** El primer monto de una línea sin producto, como el 50000 de "le pagué 50000". */
    val amount: Long? get() = quantity ?: lineTotal ?: unitPrice
}

/**
 * Lee una línea en el orden en que se dice: cantidad ("3", "medio", "un par de"), unidad ("kilos",
 * "kilo y medio"), producto, y al final el precio ("a 500", "500 c/u", "a 20000 el kilo"), el total
 * ("por 1500") o la cantidad si se dijo después ("harina de trigo 10 kilos").
 *
 * Un número dentro del nombre solo se queda en él si un producto lo lleva junto a lo ya dicho, como
 * el 400 de "gaseosa de 400"; si no, es un monto: el 400 de "pan 400".
 */
internal class LineReader(
    private val lexicon: PhraseLexicon,
    private val numbers: NumberReader,
    private val units: UnitIndex,
    private val products: ProductFinder,
) {
    /**
     * La línea de [tokens]. Con [amounts], un número sin marca después del producto se lee como un
     * monto, con sus centavos dichos en dos partes: en un precio o un costo nuevo no hay otra cosa.
     */
    fun read(tokens: List<Token>, amounts: Boolean = false): LineDraft =
        LineScan(tokens, amounts).run()

    private inner class LineScan(private val tokens: List<Token>, private val amounts: Boolean) {
        private var index = 0
        private var quantity: Long? = null
        private var unitId: Long? = null
        private var unitPrice: Long? = null
        private var lineTotal: Long? = null
        private var unreadable = false
        private var eachPending = false
        private var tail: List<Token> = emptyList()
        private var unused = false
        private var costSaid = false

        fun run(): LineDraft {
            // "fueron 10 kilos": la cópula sola, como "es", no abre nada y queda como no usada.
            val copula = lexicon.lineCopulas.matchAt(tokens, 0)?.takeIf { it.end < tokens.size }
            index = copula?.end ?: 0
            leadingFor()
            skip(lexicon.articles)
            demonstrative()
            vague()
            quantity()
            val connector = tokens.getOrNull(index)?.takeIf {
                quantity != null &&
                    it.norm in lexicon.nameConnectors
            }
            skip(lexicon.connectors)
            unit()
            skip(lexicon.connectors)
            val ellipsis = connector.takeIf { unitId == null }
            val spanStart = index
            val spanEnd = spanEnd()
            index = spanEnd
            prices()
            return LineDraft(
                span = tokens.subList(spanStart, spanEnd),
                quantity = quantity,
                unitId = unitId,
                unitPrice = unitPrice,
                lineTotal = lineTotal,
                unreadable = unreadable,
                tail = tail,
                ellipsis = ellipsis,
                unused = unused,
                copula = copula != null,
                costSaid = costSaid,
            )
        }

        /** "for a haircut", "por un corte": el "por" no es un monto si no le sigue una cifra. */
        private fun leadingFor() {
            val leads = norm(index) in lexicon.amountFor && index + 1 < tokens.size
            if (leads && !numbers.startsAt(tokens, index + 1)) index++
        }

        /**
         * "este pan", "this bread": uno, si algo sigue y no es "es". Una cifra después ya partió la
         * línea: "este 2 panes" son dos líneas.
         */
        private fun demonstrative() {
            if (norm(index) !in lexicon.demonstratives || index + 1 >= tokens.size) return
            if (tokens[index + 1].norm in lexicon.descriptionWords) return
            quantity = Scale.MILLI
            index++
        }

        private fun norm(position: Int): String = if (position <
            tokens.size
        ) {
            tokens[position].norm
        } else {
            ""
        }

        private fun skip(words: Set<String>) {
            while (norm(index) in words) index++
        }

        /** "unos panes" no dice cuántos; "unos 3 panes" sí. */
        private fun vague() {
            val hit = lexicon.vague.matchAt(tokens, index) ?: return
            index = hit.end
            unreadable = !numbers.startsAt(tokens, index)
        }

        /**
         * La cantidad del principio. Si es dinero ("2500 pesos de pan") o va antes de "por" o "en"
         * ("20000 por 10 kilos de harina"), es lo que se pagó por la línea, y la cantidad puede
         * venir después.
         */
        private fun quantity() {
            val number = numbers.read(tokens, index)
            if (number == null) {
                oneArticle()
                return
            }
            index = number.end
            val paidFor = norm(index) in lexicon.amountFor && index + 1 < tokens.size
            if (!paidFor) {
                if (number.money) lineTotal = number.milli else quantity = number.milli
                batches()
                return
            }
            index++
            skip(lexicon.articles)
            val next = numbers.read(tokens, index, amount = true)
            // "20000 por 10 kilos de harina": lo pagado y después cuánto, con algo detrás. "3 por
            // 1500", con nada detrás o con la moneda: cuántos y lo pagado.
            val counts = next == null || number.money || (!next.money && next.end < tokens.size)
            if (counts) {
                lineTotal = number.milli
                quantity = next?.milli
            } else {
                quantity = number.milli
                lineTotal = next.milli
            }
            index = next?.end ?: index
        }

        /**
         * "2 tandas de 20 panes" son 40. Sin cuántas trae cada tanda, no se sabe cuántos son: la
         * cantidad se pregunta.
         */
        private fun batches() {
            val count = quantity ?: return
            if (norm(index) !in lexicon.batches) return
            index++
            skip(lexicon.connectors)
            val each = numbers.read(tokens, index)
            if (each == null) {
                quantity = null
                unreadable = true
                return
            }
            quantity = scaled(count, each.milli)
            index = each.end
        }

        /**
         * "a bread" es una unidad; una cifra que no se puede leer, como "1.2.3", es una cantidad
         * ilegible.
         */
        private fun oneArticle() {
            if (norm(index) in lexicon.oneArticles) {
                quantity = Scale.MILLI
                index++
            } else if (index < tokens.size && tokens[index].looksNumeric()) {
                unreadable = true
                index++
            }
        }

        private fun unit() {
            val unit = units.at(tokens, index, afterNumber = quantity != null) ?: return
            unitId = unit.id
            index++
            val more = lexicon.continuations.matchAt(tokens, index)
            if (more != null) {
                quantity = (quantity ?: Scale.MILLI) + more.value
                index = more.end
                return
            }
            val sub = subunitAt(tokens, index - 1, units, numbers, lexicon) ?: return
            quantity = (quantity ?: Scale.MILLI) + sub.milli
            index = sub.end
        }

        /**
         * Dónde termina el nombre del producto: en un precio, en un número que no es de ningún
         * nombre, en "a" o "para" seguidos de palabras sin cifras, que nombran a alguien, o en una
         * palabra que no es de ningún producto, como "esto es": lo que sigue no es un nombre.
         */
        private fun spanEnd(): Int {
            var end = index
            while (end < tokens.size) {
                if (priceStarts(end)) return end
                if (tokens[end].norm in lexicon.descriptionWords) {
                    unused = true
                    return end
                }
                // "a vanilla one": "one" reemplaza el nombre, salvo que empiece un monto ("one
                // twenty").
                val head = end > index && tokens[end].norm in lexicon.pronounHeads &&
                    !numbers.startsAt(tokens, end + 1)
                if (head) {
                    end++
                    continue
                }
                val number = numbers.read(tokens, end)
                val bare = if (number == null) units.fractionAt(tokens, end) else null
                if (bare != null) {
                    val word = WordShaper.numberWord(bare.milli)
                    if (!products.fitsNumber(tokens.subList(index, end), word)) return end
                    end = bare.end
                } else if (number != null) {
                    val unit = units.at(tokens, number.end, afterNumber = true)
                    val word = WordShaper.numberWord(number.milli)
                    // "a 400 ml soda": una medida al principio es del nombre si un producto la
                    // lleva entera.
                    val measured = end == index && unit != null &&
                        products.hasMeasure(word, WordShaper.unitWord(unit.id))
                    if (!measured && !products.fitsNumber(tokens.subList(index, end), word)) {
                        return end
                    }
                    end = if (unit == null) number.end else number.end + 1
                } else if (tokens[end].norm in lexicon.toPrepositions && noNumberFrom(end + 1)) {
                    tail = namedTail(end + 1)
                    return end
                } else {
                    end++
                }
            }
            return end
        }

        /** Lo que sigue a "a" o "para", si nombra a alguien y no es solo relleno. */
        private fun namedTail(start: Int): List<Token> {
            val rest = tokens.subList(start, tokens.size)
            return if (rest.any { it.norm !in lexicon.nameStopwords }) rest else emptyList()
        }

        /** Si no viene ningún monto ni cantidad: "a una amiga" no trae cifras, trae a alguien. */
        private fun noNumberFrom(position: Int): Boolean = (position until tokens.size).none {
            numbers.startsAt(tokens, it) && !articleBeforeWord(it)
        }

        /**
         * "a un cliente": un artículo después de "a" seguido de una palabra no es un precio de uno,
         * es a quién.
         */
        private fun articleBeforeWord(position: Int): Boolean {
            val next = tokens.getOrNull(position + 1) ?: return false
            val article = lexicon.numberWords[norm(position)] == Scale.MILLI &&
                !tokens[position].looksNumeric()
            // Una cifra, "mil" o la moneda después del artículo siguen el monto: "a un mil".
            return article && !numbers.startsAt(tokens, position + 1) &&
                next.norm !in lexicon.currencies
        }

        private fun priceStarts(position: Int): Boolean {
            val marker = lexicon.amountMarkers.matchAt(tokens, position)
            val priced = marker != null && numbers.startsAt(tokens, marker.end) &&
                !articleBeforeWord(marker.end)
            return priced || lexicon.eachAfter.matchAt(tokens, position) != null ||
                perUnitAt(position) != null
        }

        private fun perUnitAt(position: Int): PhraseUnit? {
            if (norm(position) !in lexicon.perUnitLeads) return null
            return units.at(tokens, position + 1, afterNumber = true)
        }

        private fun prices() {
            while (index < tokens.size) {
                index = perUnit() ?: each() ?: laterQuantity() ?: amount() ?: (index + 1)
            }
        }

        /** "el kilo", "por kilo": el monto dicho es por esa unidad. */
        private fun perUnit(): Int? {
            val unit = perUnitAt(index) ?: return null
            unitId = unitId ?: unit.id
            markPerUnit()
            return index + 2
        }

        /** "cada uno", "c/u": el monto dicho, o el que viene, es por unidad. */
        private fun each(): Int? {
            val hit = lexicon.eachAfter.matchAt(tokens, index) ?: return null
            markPerUnit()
            return hit.end
        }

        private fun markPerUnit() {
            unitPrice = lineTotal ?: unitPrice
            eachPending = lineTotal == null
            lineTotal = null
        }

        /**
         * "harina de trigo 10 kilos", "queso kilo y medio": la cantidad, con su unidad, dicha
         * después del producto.
         */
        private fun laterQuantity(): Int? {
            if (quantity != null) return null
            val bare = units.fractionAt(tokens, index)
            if (bare != null) {
                quantity = bare.milli
                unitId = bare.unit.id
                return bare.end
            }
            val number = numbers.read(tokens, index) ?: return null
            val unit = units.at(tokens, number.end, afterNumber = true) ?: return null
            quantity = number.milli
            unitId = unit.id
            return number.end + 1
        }

        private fun amount(): Int? {
            val marker = lexicon.amountMarkers.matchAt(tokens, index)
            val start = marker?.end ?: index
            // "a una amiga": lo que sigue dice a quién, no un monto.
            if (marker != null && articleBeforeWord(start)) return tokens.size
            val number = numbers.read(tokens, start, amount = marker != null || amounts)
                ?: return null
            val perUnit = marker?.value == AmountKind.UNIT || eachPending
            if (marker?.value == AmountKind.COST) costSaid = true
            if (perUnit) unitPrice = number.milli else lineTotal = number.milli
            return number.end
        }
    }
}

/** Una medida más dicha después de la unidad: [milli] milésimas más, hasta antes de [end]. */
internal data class Subunit(val milli: Long, val end: Int)

/**
 * "un kilo doscientos", "um quilo e duzentos": después de un kilo o un litro, una cifra entera
 * menor que mil son gramos o mililitros. Solo si después viene "de", una coma o el final, para que
 * "1 kilo y 3 panes" siga siendo dos líneas, y si la cifra no es dinero.
 */
internal fun subunitAt(
    tokens: List<Token>,
    unitIndex: Int,
    units: UnitIndex,
    numbers: NumberReader,
    lexicon: PhraseLexicon,
): Subunit? {
    val unit = units.at(tokens, unitIndex, afterNumber = true) ?: return null
    if (unit.baseAmountMilli != SUBUNIT_BASE) return null
    var start = unitIndex + 1
    if (start < tokens.size && tokens[start].norm in lexicon.conjunctions) start++
    val number = numbers.read(tokens, start) ?: return null
    val whole = number.milli / Scale.MILLI
    if (number.money || whole !in 1 until SUBUNITS) return null
    val next = tokens.getOrNull(number.end)
    val closes = next == null || next.isSeparator || next.norm in lexicon.nameConnectors
    return if (closes) Subunit(whole, number.end) else null
}

/** Lo que mide un kilo o un litro en su unidad menor, en milésimas: mil gramos o mililitros. */
private const val SUBUNIT_BASE = 1_000_000L

/** Las unidades menores que caben en una mayor. */
private const val SUBUNITS = 1_000L

/** `count × each`, los dos en milésimas, o el máximo si no cabe. */
private fun scaled(count: Long, each: Long): Long =
    if (each != 0L && count > Long.MAX_VALUE / each) Long.MAX_VALUE else count * each / Scale.MILLI
