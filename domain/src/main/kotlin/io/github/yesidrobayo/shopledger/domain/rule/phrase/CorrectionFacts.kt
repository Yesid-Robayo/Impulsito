package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent

/**
 * Lo que una corrección dice fuera de las líneas: si queda a crédito, el método de pago, la
 * persona, la categoría y un total, cada uno con lo que se encontró en el catálogo. [rest] son las
 * palabras que quedan para las líneas.
 */
internal data class CorrectionFacts(
    val credit: Boolean,
    val payment: NameHit?,
    val party: NameHit?,
    val category: NameHit?,
    val total: Long?,
    val rest: List<Token>,
) {
    /** Si dijo algo de esto. */
    val any: Boolean
        get() = credit || payment != null || party != null || category != null || total != null
}

/**
 * Saca de una corrección lo mismo que el intérprete saca de una frase antes de partirla en líneas,
 * con las mismas preposiciones y sinónimos: "en efectivo", "a María", "donde Don Ramiro", "todo por
 * 30000". La persona se busca solo del lado que usa la operación (clientes en una venta,
 * proveedores en una compra), y la categoría solo en un gasto, para no confundir "6 aguas" con el
 * recibo del agua.
 */
internal class CorrectionFactsReader(
    private val lexicon: PhraseLexicon,
    private val numbers: NumberReader,
    shaper: WordShaper,
    catalog: PhraseCatalog,
) {
    private val payments =
        NameIndex.of(catalog.paymentMethods, lexicon.paymentSynonyms, shaper, includeNames = true)
    private val loosePayments =
        NameIndex.of(catalog.paymentMethods, lexicon.paymentSynonyms, shaper, includeNames = false)
    private val sides: Map<PhraseIntent, NameIndex> = run {
        val customers = NameIndex.of(catalog.customers, emptyMap(), shaper, includeNames = true)
        val suppliers = NameIndex.of(catalog.suppliers, emptyMap(), shaper, includeNames = true)
        mapOf(
            PhraseIntent.SALE to customers,
            PhraseIntent.CUSTOMER_PAYMENT to customers,
            PhraseIntent.PURCHASE to suppliers,
            PhraseIntent.SUPPLIER_PAYMENT to suppliers,
        )
    }
    private val categories =
        NameIndex.of(
            catalog.expenseCategories,
            lexicon.categorySynonyms,
            shaper,
            includeNames = true,
        )

    fun read(intent: PhraseIntent, tokens: List<Token>): CorrectionFacts {
        val credit = lexicon.credit.findAll(tokens)
        val total = first(tokens.without(credit)) { all, index ->
            totalBefore(all, index) ?: totalAfter(all, index)
        }
        val payment = first(total.rest, ::paymentAt)
        val side = sides[intent]
        val party = if (side == null) {
            Extracted(null, payment.rest)
        } else {
            first(payment.rest) { all, index -> partyAt(side, all, index) }
        }
        val category = if (intent == PhraseIntent.EXPENSE) {
            first(party.rest, ::categoryAt)
        } else {
            Extracted(null, party.rest)
        }
        return CorrectionFacts(
            credit = credit.isNotEmpty(),
            payment = payment.value,
            party = party.value,
            category = category.value,
            total = total.value,
            rest = category.rest,
        )
    }

    private fun <T> first(
        tokens: List<Token>,
        at: (List<Token>, Int) -> Extracted<T>?,
    ): Extracted<T> =
        tokens.indices.firstNotNullOfOrNull { at(tokens, it) } ?: Extracted(null, tokens)

    /** "todo por 30000", "en total 12000". */
    private fun totalBefore(tokens: List<Token>, index: Int): Extracted<Long>? {
        val marker = lexicon.totalBefore.matchAt(tokens, index) ?: return null
        val amount = numbers.read(tokens, marker.end) ?: return null
        return Extracted(amount.milli, tokens.cut(index, amount.end))
    }

    /** "12000 en total". */
    private fun totalAfter(tokens: List<Token>, index: Int): Extracted<Long>? {
        val amount = numbers.read(tokens, index) ?: return null
        val marker = lexicon.totalAfter.matchAt(tokens, amount.end) ?: return null
        return Extracted(amount.milli, tokens.cut(index, marker.end))
    }

    /** "en efectivo", "por transferencia", o un sinónimo suelto: "cash". */
    private fun paymentAt(tokens: List<Token>, index: Int): Extracted<NameHit>? {
        val prepositioned = tokens[index].norm in lexicon.paymentPrepositions
        val afterPreposition = if (prepositioned) payments.matchAt(tokens, index + 1) else null
        val hit = afterPreposition ?: loosePayments.matchAt(tokens, index) ?: return null
        return Extracted(hit, tokens.cut(index, hit.end))
    }

    /**
     * "a María", "era de Don Ramiro", "doña Rosa": un nombre del catálogo después de una
     * preposición, con su tratamiento o al principio. Un nombre que no está no cuenta: la tarjeta
     * no tiene dónde ponerlo.
     */
    private fun partyAt(side: NameIndex, tokens: List<Token>, index: Int): Extracted<NameHit>? {
        val norm = tokens[index].norm
        val prepositioned = norm in lexicon.partyPrepositions
        if (!prepositioned && index > 0 && norm !in lexicon.honorifics) return null
        val hit = side.matchAt(tokens, if (prepositioned) index + 1 else index) ?: return null
        return Extracted(hit, tokens.cut(index, hit.end))
    }

    /** "era la luz", "fue de taxi": una categoría que no va después de una cantidad. */
    private fun categoryAt(tokens: List<Token>, index: Int): Extracted<NameHit>? {
        if (index > 0 && numbers.startsAt(tokens, index - 1)) return null
        val hit = categories.matchAt(tokens, index) ?: return null
        return Extracted(hit, tokens.cut(index, hit.end))
    }
}
