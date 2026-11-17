package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit

/**
 * Una línea leída, antes de buscar su producto: [span] son las palabras del producto. Los montos
 * van en milésimas de la unidad mayor de la moneda. [tail] son las palabras después de "a" o "para"
 * sin cifras, que nombran a alguien. [ellipsis] es el "de" que siguió a la cantidad sin unidad,
 * como en "y 2 de naranja": la línea puede tomar la cabeza del nombre de la anterior. [alternative]
 * es ese nombre completado con la línea anterior, que se prueba si el propio no da con un producto.
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
    fun read(tokens: List<Token>): LineDraft = LineScan(tokens).run()

    private inner class LineScan(private val tokens: List<Token>) {
        private var index = 0
        private var quantity: Long? = null
        private var unitId: Long? = null
        private var unitPrice: Long? = null
        private var lineTotal: Long? = null
        private var unreadable = false
        private var eachPending = false
        private var tail: List<Token> = emptyList()

        fun run(): LineDraft {
            skip(lexicon.articles)
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
            )
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
            if (number.money || paidFor) lineTotal = number.milli else quantity = number.milli
            if (!paidFor) return
            index++
            skip(lexicon.articles)
            val next = numbers.read(tokens, index) ?: return
            quantity = next.milli
            index = next.end
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
            val more = lexicon.continuations.matchAt(tokens, index) ?: return
            quantity = (quantity ?: Scale.MILLI) + more.value
            index = more.end
        }

        /**
         * Dónde termina el nombre del producto: en un precio, en un número que no es de ningún
         * nombre, o en "a" o "para" seguidos de palabras sin cifras, que nombran a alguien.
         */
        private fun spanEnd(): Int {
            var end = index
            while (end < tokens.size) {
                if (priceStarts(end)) return end
                if (end > index && tokens[end].norm in lexicon.pronounHeads) {
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
                    if (!products.fitsNumber(
                            tokens.subList(index, end),
                            WordShaper.numberWord(number.milli),
                        )
                    ) {
                        return end
                    }
                    val unit = units.at(tokens, number.end, afterNumber = true)
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

        private fun noNumberFrom(position: Int): Boolean = (position until tokens.size).none {
            numbers.startsAt(tokens, it)
        }

        private fun priceStarts(position: Int): Boolean {
            val marker = lexicon.amountMarkers.matchAt(tokens, position)
            val priced = marker != null && numbers.startsAt(tokens, marker.end)
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
            val number = numbers.read(tokens, marker?.end ?: index) ?: return null
            val perUnit = marker?.value == AmountKind.UNIT || eachPending
            if (perUnit) unitPrice = number.milli else lineTotal = number.milli
            return number.end
        }
    }
}
