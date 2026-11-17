package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage

/**
 * El vocabulario de un idioma ya normalizado, listo para comparar con las palabras de una frase:
 * cada frase pasa por el mismo [PhraseTokenizer] que el texto, así que una entrada escrita con
 * tildes, apóstrofos o mayúsculas coincide con lo que llega de un teclado o de un dictado.
 */
internal class PhraseLexicon(vocabulary: PhraseVocabulary) {
    val fillers = PhraseTable.of(vocabulary.fillers + CommonVocabulary.fillers)
    val credit = PhraseTable.of(vocabulary.credit)
    val verbs =
        PhraseTable(vocabulary.verbs.flatMap { (kind, phrases) -> phrases.map { it to kind } })
    val totalBefore = PhraseTable.of(vocabulary.totalBefore)
    val totalAfter = PhraseTable.of(vocabulary.totalAfter)
    val amountMarkers = PhraseTable(
        vocabulary.unitPriceBefore.map { it to AmountKind.UNIT } +
            vocabulary.lineTotalBefore.map { it to AmountKind.LINE },
    )
    val eachAfter = PhraseTable.of(vocabulary.eachAfter)
    val perUnitLeads = words(vocabulary.perUnitLeads)
    val amountFor = words(vocabulary.amountFor)
    val separators = PhraseTable.of(vocabulary.separators)
    val separatorWords = words(vocabulary.separators)
    val continuations = PhraseTable(vocabulary.continuations.toList())
    val fixedNumbers = PhraseTable(vocabulary.fixedNumbers.toList())
    val numberWords = keyed(vocabulary.numberWords)
    val multipliers = keyed(vocabulary.multipliers)
    val magnitudes = keyed(vocabulary.magnitudes)
    val conjunctions = words(vocabulary.conjunctions)
    val oneArticles = words(vocabulary.oneArticles)
    val vague = PhraseTable.of(vocabulary.vague)
    val currencies = words(CommonVocabulary.currencies)
    val moneyMagnitudes = words(CommonVocabulary.moneyMagnitudes)
    val articles = words(vocabulary.articles)

    /**
     * Lo que se salta entre la cantidad o la unidad y el producto: "de", y también los artículos.
     */
    val connectors = words(vocabulary.connectors) + articles
    val stopwords = words(vocabulary.stopwords)

    /** Lo que une la cabeza de un nombre con su complemento: "de" en "Jugo de naranja". */
    val nameConnectors = words(vocabulary.connectors)

    /** Lo que se recorta al principio y al final de un nombre dicho. */
    val trimmed = stopwords + articles + words(vocabulary.connectors)

    /**
     * Palabras después de las que un número es un precio, una cantidad o parte de un número, nunca
     * el comienzo de otra línea: "a 500", "por 2", "de 400", "dos mil".
     */
    val amountBound: Set<String> = connectors + perUnitLeads + numberWords.keys +
        words(vocabulary.unitPriceBefore + vocabulary.lineTotalBefore + vocabulary.amountFor)

    /**
     * Tamaño dicho: 1 para lo grande y -1 para lo pequeño, para desempatar por la medida del
     * nombre.
     */
    val sizes: Map<String, Long> =
        words(vocabulary.big).associateWith { 1L } + words(vocabulary.small).associateWith { -1L }
    val partyPrepositions = words(vocabulary.partyPrepositions)
    val unknownPartyPrepositions = words(vocabulary.unknownPartyPrepositions)
    val toPrepositions = words(vocabulary.toPrepositions)
    val honorifics = words(vocabulary.honorifics)
    val paymentPrepositions = words(vocabulary.paymentPrepositions)
    val paymentSynonyms: Map<String, List<String>> = vocabulary.paymentSynonyms
    val categorySynonyms: Map<String, List<String>> = vocabulary.categorySynonyms
    val unitSynonyms: Map<String, List<String>> = CommonVocabulary.unitSynonyms
    val productLead = PhraseTable.of(vocabulary.productLead)
    val productAmounts = PhraseTable(
        vocabulary.priceKeywords.map { it to false } + vocabulary.costKeywords.map { it to true },
    )
    val changeFillers = words(vocabulary.changeFillers)
    val pronouns = words(vocabulary.pronouns)
    val questions = words(vocabulary.questions)
    val yieldLeads = words(vocabulary.yieldLeads)
    val pronounHeads = words(vocabulary.pronounHeads)
    val containers = words(vocabulary.containers)

    /** Palabras que no cuentan al comparar un nombre de persona, de pago o de categoría. */
    val nameStopwords = stopwords + honorifics + articles

    private val singularizer = Singularizer(vocabulary.singularRules)
    private val displaySingularizer = Singularizer(vocabulary.displayRules)

    fun singular(word: String): String = singularizer.singular(word)

    /** El singular legible de una palabra tal como se dijo, para proponer un nombre nuevo. */
    fun displaySingular(word: String): String = displaySingularizer.singular(word)

    private fun words(list: List<String>): Set<String> = list.map(::normalized).toSet()

    private fun keyed(map: Map<String, Long>): Map<String, Long> = map.mapKeys { (word, _) ->
        normalized(word)
    }

    private fun normalized(text: String): String =
        PhraseTokenizer.tokenize(text).joinToString(" ") {
            it.norm
        }
}

/** El vocabulario compilado de cada idioma, una sola vez. */
internal object PhraseLexicons {
    private val spanish = PhraseLexicon(SpanishVocabulary.vocabulary)
    private val english = PhraseLexicon(EnglishVocabulary.vocabulary)
    private val portuguese = PhraseLexicon(PortugueseVocabulary.vocabulary)

    fun of(language: PhraseLanguage): PhraseLexicon = when (language) {
        PhraseLanguage.ES -> spanish
        PhraseLanguage.EN -> english
        PhraseLanguage.PT -> portuguese
    }
}
