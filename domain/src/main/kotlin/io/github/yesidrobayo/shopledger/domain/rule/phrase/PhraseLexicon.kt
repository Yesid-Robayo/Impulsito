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
            vocabulary.lineTotalBefore.map { it to AmountKind.LINE } +
            vocabulary.costClauses.map { it to AmountKind.COST },
    )
    val headLast: Boolean = vocabulary.headLast
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
        words(vocabulary.unitPriceBefore + vocabulary.lineTotalBefore + vocabulary.amountFor) +
        // De "que me costaron", solo el verbo: "que" solo no dice que sigue un monto.
        vocabulary.costClauses.mapNotNull { tokens(it).lastOrNull() }

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
    val questions = PhraseTable.of(vocabulary.questions)
    val refusals = PhraseTable.of(vocabulary.refusals.phrases)
    val negations = PhraseTable.of(vocabulary.refusals.negations)
    val notYet = PhraseTable.of(vocabulary.refusals.notYet)
    val percents = PhraseTable.of(vocabulary.refusals.percents)
    val fromPrepositions = words(vocabulary.speech.fromPrepositions)
    val selfFixes = PhraseTable.of(vocabulary.speech.selfFixes)
    val createLeads = PhraseTable.of(vocabulary.speech.createLeads)
    val priceCopulas = words(vocabulary.speech.priceCopulas)
    val softFillers = words(vocabulary.speech.softFillers)
    val demonstratives = words(vocabulary.speech.demonstratives)
    val lineCopulas = PhraseTable.of(vocabulary.speech.lineCopulas)
    val batches = words(vocabulary.speech.batches)
    val decimalWords = words(vocabulary.speech.decimalWords)
    val tenderedBefore = PhraseTable.of(vocabulary.speech.tenderedBefore)
    val tenderedAfter = PhraseTable.of(vocabulary.speech.tenderedAfter)
    val shares = PhraseTable.of(vocabulary.speech.shares)
    val genericParties = PhraseTable.of(vocabulary.speech.genericParties)
    val recipeEach = PhraseTable.of(vocabulary.speech.recipeEach)
    val priceSetters = PhraseTable.of(vocabulary.speech.priceSetters)
    val setterMarkers = words(vocabulary.speech.setterMarkers)

    /** Lo que va antes y después de la persona en una forma de crédito, ya normalizado. */
    val creditWraps: List<Pair<List<String>, List<String>>> =
        vocabulary.speech.creditWraps.map { (before, after) -> tokens(before) to tokens(after) }
    val cents = words(CommonVocabulary.cents)

    /** Las marcas de monto de una sola palabra: "a", "por", "at". */
    val amountWords: Set<String> =
        words(vocabulary.unitPriceBefore + vocabulary.lineTotalBefore).filterNot {
            ' ' in it
        }.toSet()

    /**
     * Palabras que pueden seguir a un precio dicho en dos partes sin que la segunda sea otra
     * línea: "the kilo", "each", "in cash", "and".
     */
    val afterPrice: Set<String> = separatorWords + perUnitLeads + currencies + cents +
        words(vocabulary.paymentPrepositions + vocabulary.amountFor + vocabulary.toPrepositions) +
        words(vocabulary.eachAfter.map { it.split(' ').first() })
    val countFactors: Map<String, Long> = CommonVocabulary.countFactors
    val yieldLeads = words(vocabulary.yieldLeads)
    val pronounHeads = words(vocabulary.pronounHeads)
    val containers = words(vocabulary.containers)
    val traitLeads = PhraseTable.of(vocabulary.traits.leads)
    val traitArticles = words(vocabulary.traits.articles)
    val kinds = PhraseTable(vocabulary.traits.kinds.toList())
    val plainKinds = PhraseTable(vocabulary.traits.plainKinds.toList())
    val categoryBefore = PhraseTable.of(vocabulary.traits.categoryBefore)
    val categoryAfter = PhraseTable.of(vocabulary.traits.categoryAfter)
    val categoryAfterLeads = PhraseTable.of(vocabulary.traits.categoryAfterLeads)

    /** Cualquier marca de categoría, antes o después del nombre: cortan el nombre de otra. */
    val categoryMarks =
        PhraseTable.of(vocabulary.traits.categoryBefore + vocabulary.traits.categoryAfter)
    val descriptionWords = words(vocabulary.traits.descriptionWords)

    /**
     * Palabras que cortan el nombre de una categoría dicha: un pago, un monto, a quién va, o la
     * apertura de otra descripción.
     */
    val stopsCategory: Set<String> = words(
        vocabulary.paymentPrepositions + vocabulary.toPrepositions + vocabulary.unitPriceBefore +
            vocabulary.lineTotalBefore + vocabulary.traits.leads,
    )

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

    private fun tokens(text: String): List<String> = PhraseTokenizer.tokenize(text).map { it.norm }

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
