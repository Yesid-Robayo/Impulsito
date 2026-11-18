package io.github.yesidrobayo.shopledger.domain.rule.phrase

/**
 * Los métodos de pago dichos en la frase, todos: "en efectivo", "con tarjeta", o un sinónimo
 * suelto. [split] dice que se nombraron dos o más distintos, como en "la mitad en efectivo y la
 * mitad por transferencia": la tarjeta deja elegir, porque la frase reparte el pago y el contrato
 * lleva uno solo. [rest] son las palabras sin los pagos ni lo que los reparte ("la mitad").
 */
internal data class PaymentScan(val ids: List<Long>, val split: Boolean, val rest: List<Token>) {
    companion object {
        fun all(
            tokens: List<Token>,
            lexicon: PhraseLexicon,
            payments: NameIndex,
            loose: NameIndex,
        ): PaymentScan {
            var rest = tokens
            val hits = mutableListOf<NameHit>()
            while (true) {
                val found = rest.indices.firstNotNullOfOrNull { index ->
                    at(rest, index, lexicon, payments, loose)
                } ?: break
                hits += found.first
                rest = found.second
            }
            val ids = hits.flatMap { it.ids }.distinct()
            return PaymentScan(ids, hits.size > 1 && ids.size > 1, rest)
        }

        /**
         * Un pago en [index]: un nombre del catálogo después de "en" o "con", o un sinónimo suelto,
         * con lo que lo reparte delante si lo hay.
         */
        private fun at(
            tokens: List<Token>,
            index: Int,
            lexicon: PhraseLexicon,
            payments: NameIndex,
            loose: NameIndex,
        ): Pair<NameHit, List<Token>>? {
            val start = lexicon.shares.matchAt(tokens, index)?.end ?: index
            if (start >= tokens.size) return null
            val prepositioned = tokens[start].norm in lexicon.paymentPrepositions
            val afterPreposition = if (prepositioned) payments.matchAt(tokens, start + 1) else null
            val hit = afterPreposition
                ?: loose.matchAt(tokens, start)?.takeUnless { counted(tokens, start, it, lexicon) }
                ?: return null
            return hit to tokens.cut(index, hit.end)
        }

        /**
         * "20 de crédito no celular", "20 de efectivo": lo que se cuenta después de una cifra no
         * es el pago. Se mira la primera palabra del nombre, sin el "de" que la abre.
         */
        private fun counted(
            tokens: List<Token>,
            start: Int,
            hit: NameHit,
            lexicon: PhraseLexicon,
        ): Boolean {
            // El nombre tiene al menos una palabra que no es relleno: por ella se encontró.
            val first = start + tokens.subList(start, hit.end)
                .indexOfFirst { it.norm !in lexicon.nameStopwords }
            return first >= 2 && tokens[first - 1].norm in lexicon.nameConnectors &&
                tokens[first - 2].looksNumeric()
        }
    }
}
