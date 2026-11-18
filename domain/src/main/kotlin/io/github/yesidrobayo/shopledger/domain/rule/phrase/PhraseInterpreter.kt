package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLanguage
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv

/**
 * Lee una frase escrita o dictada, como "vendí 3 panes y medio kilo de queso en efectivo", y
 * propone la operación que describe. Nunca registra nada: la pantalla muestra la lectura para
 * confirmar.
 *
 * No asume país: la moneda no tiene nombre ni símbolo aquí, el formato de los números llega en
 * [NumberStyle] y los decimales de la moneda en `moneyDecimals`. Las palabras de cada idioma viven
 * en su vocabulario, aparte.
 */
object PhraseInterpreter {
    /** Los decimales de moneda que admite: de cero a tres, como todas las monedas en uso. */
    const val MAX_MONEY_DECIMALS: Int = 3

    fun read(
        text: String,
        language: PhraseLanguage,
        numberStyle: NumberStyle,
        moneyDecimals: Int,
        catalog: PhraseCatalog,
    ): PhraseReading {
        require(moneyDecimals in 0..MAX_MONEY_DECIMALS) {
            "Los decimales de la moneda van de 0 a 3"
        }
        return PhraseSession(
            PhraseLexicons.of(language),
            numberStyle,
            moneyDecimals,
            catalog,
        ).read(text)
    }

    /**
     * Si la frase dice con un verbo qué operación es: "vendí 2 panes", "compré harina", "el pan
     * vale 600". Con una tarjeta a la vista, solo una frase así puede ser otra operación: "la
     * harina es insumo" o "2 panes" no dicen qué pasó, y se suman a la tarjeta o la dejan como
     * estaba.
     */
    fun statesOperation(text: String, language: PhraseLanguage): Boolean {
        val lexicon = PhraseLexicons.of(language)
        val tokens = PhraseTokenizer.tokenize(text)
        return lexicon.verbs.findAll(tokens).any { it.value.intent != null }
    }
}

/**
 * Lo que se encontró de una persona: como cliente, como proveedor, y lo dicho, por si no es
 * ninguno.
 */
internal data class PartyHit(val customer: NameHit?, val supplier: NameHit?, val spoken: String)

/**
 * Lo que se sacó de la frase antes de partirla en líneas, para decidir la operación y armar la
 * lectura.
 */
internal data class PhraseFacts(
    val total: Long?,
    val payment: NameMatch?,
    val party: PartyHit?,
    val category: NameMatch?,
    val rest: List<Token>,
    val drafts: List<LineDraft>,
    /** Si se nombró a alguien sin decir quién: "un cliente se llevó 3 jugos". */
    val someone: Boolean = false,
)

/** Lo que se sacó de la frase antes de leerla: si va a crédito y si nombra a alguien sin decir quién. */
internal data class Context(
    val credit: Boolean,
    val someone: Boolean,
    val someoneFirst: Boolean = false,
)

/** Una lectura con todos los índices del catálogo armados para el idioma de la frase. */
internal class PhraseSession(
    private val lexicon: PhraseLexicon,
    style: NumberStyle,
    private val moneyDecimals: Int,
    private val catalog: PhraseCatalog,
) {
    private val numbers = NumberReader(lexicon, style, cents = moneyDecimals > 0)
    private val units = UnitIndex(catalog.units, lexicon)
    private val shaper = WordShaper(lexicon, numbers, units)
    private val products = ProductFinder(catalog.products, shaper, lexicon)
    private val lineReader = LineReader(lexicon, numbers, units, products)
    private val splitter = LineSplitter(lexicon, numbers, units, products)
    private val payments = NameIndex.of(
        catalog.paymentMethods,
        lexicon.paymentSynonyms,
        shaper,
        includeNames = true,
    )
    private val loosePayments = NameIndex.of(
        catalog.paymentMethods,
        lexicon.paymentSynonyms,
        shaper,
        includeNames = false,
    )
    private val customers = NameIndex.of(catalog.customers, emptyMap(), shaper, includeNames = true)
    private val suppliers = NameIndex.of(catalog.suppliers, emptyMap(), shaper, includeNames = true)
    private val categories = NameIndex.of(
        catalog.expenseCategories,
        lexicon.categorySynonyms,
        shaper,
        includeNames = true,
        tolerance = 1,
    )
    private val onAccount = catalog.paymentMethods.firstOrNull { it.systemKey == ON_ACCOUNT }
    private val traitReader = TraitReader(lexicon, numbers, shaper, catalog.productCategories)

    /**
     * Lee la frase sin lo que describe a un producto nuevo ("esto es un insumo"), que va a ese
     * producto. Una descripción solo sirve donde se nombran productos nuevos: si sin ella la frase
     * es otra operación, como un gasto, se lee entera como siempre.
     */
    fun read(text: String): PhraseReading {
        // Una pregunta se responde en otra pantalla, aunque nombre productos o precios.
        if (QUESTION_MARK in text || PERCENT in text) return NOTHING
        val tokens = withoutFillers(PhraseTokenizer.tokenize(text))
        val wrapped = CreditWraps.unwrap(tokens, lexicon)
        val creditHits = lexicon.credit.findAll(wrapped.tokens)
        val unpaid = wrapped.tokens.without(creditHits)
        // "un cliente" dice que alguien se llevó algo, pero no es un producto ni una persona.
        val someoneHits = lexicon.genericParties.findAll(unpaid)
        val clean = unpaid.without(someoneHits)
        val said = Context(
            credit = creditHits.isNotEmpty() || wrapped.credit,
            someone = someoneHits.isNotEmpty(),
            someoneFirst = someoneHits.any { it.start == 0 },
        )
        val scan = traitReader.scan(clean)
        if (scan.traits.isEmpty()) return read(clean, emptyList(), said)
        val described = read(scan.rest, scan.traits, said)
        // Sin operación, la frase solo describía: no se lee de nuevo como, por ejemplo, un gasto.
        val operation = described.intent ?: return described
        return if (operation in DESCRIBED_INTENTS) described else read(clean, emptyList(), said)
    }

    /**
     * Las palabras sin relleno. Un relleno suave, como "este", solo se salta si no va delante del
     * nombre de un producto: en "vendí este pan" dice uno.
     */
    private fun withoutFillers(tokens: List<Token>): List<Token> {
        val kept = tokens.without(lexicon.fillers.findAll(tokens))
        val soft = kept.filterIndexed { index, token ->
            token.norm !in lexicon.softFillers || namesProductNext(kept, index)
        }
        // "y vendí", "é, cria": lo que une con algo anterior no dice nada al principio.
        return soft.dropWhile { it.isSeparator || it.norm in lexicon.separatorWords }
    }

    private fun namesProductNext(tokens: List<Token>, index: Int): Boolean {
        val next = tokens.getOrNull(index + 1) ?: return false
        return !next.isSeparator && products.knowsWord(next.norm)
    }

    private fun read(clean: List<Token>, traits: List<PlacedTrait>, said: Context): PhraseReading {
        val verbs = verbsOf(clean, said.someoneFirst)
        if (refused(clean, verbs)) return NOTHING
        val kinds = verbs.map { it.value }
        val facts = facts(
            clean.without(verbs).filterNot { it.norm in lexicon.pronouns },
            clean,
            verbs,
            said.someone,
        )
        val intents = intents(kinds, facts).map { spent(it, facts) }.distinct()
        val intent = intents.singleOrNull()
        val credit = said.credit
        return when {
            intents.size > 1 -> PhraseReading(
                intent = null,
                issues = setOf(PhraseIssue.MIXED_OPERATIONS),
            )

            intent == null -> PhraseReading(
                intent = null,
                issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD),
            )

            else -> reading(intent, kinds, verbs, Described(clean, traits), facts, credit)
        }
    }

    private fun reading(
        intent: PhraseIntent,
        kinds: List<VerbKind>,
        verbs: List<PhraseHit<VerbKind>>,
        said: Described,
        facts: PhraseFacts,
        credit: Boolean,
    ): PhraseReading = when (intent) {
        PhraseIntent.RECIPE -> recipe(said, verbs)

        PhraseIntent.NEW_PRODUCT -> newProduct(said, verbs)

        PhraseIntent.EXPENSE,
        PhraseIntent.CUSTOMER_PAYMENT,
        PhraseIntent.SUPPLIER_PAYMENT,
        -> payment(intent, facts)

        PhraseIntent.COST_CORRECTION, PhraseIntent.PRICE_CHANGE -> change(intent, facts)

        else -> goods(intent, kinds, said, facts, credit)
    }

    /**
     * Los verbos de la frase: los exactos, o uno mal dictado al principio; con lo que solo dice
     * una operación junto a otra palabra: "cada empanada lleva" abre una receta, y "pon el pan a
     * 600" fija un precio.
     */
    private fun verbsOf(clean: List<Token>, someoneFirst: Boolean): List<PhraseHit<VerbKind>> {
        val found = lexicon.verbs.findAll(clean).ifEmpty { closeVerb(clean) }
            .map { yieldOrCost(it, clean) }
        val subject = bySubject(found, clean, someoneFirst)
        return withCreateLead(withSetter(withEachRecipe(created(subject), clean), clean), clean)
    }

    /** "nuevo: galletas a 2 mil": sin otro verbo, la palabra de producto nuevo al principio. */
    private fun withCreateLead(
        verbs: List<PhraseHit<VerbKind>>,
        clean: List<Token>,
    ): List<PhraseHit<VerbKind>> {
        if (verbs.isNotEmpty()) return verbs
        val lead = lexicon.createLeads.matchAt(clean, 0) ?: return verbs
        // Lo que sigue es un nombre, no una cantidad: "nuevo, 3 empanadas" no crea nada.
        val next = (lead.end until clean.size).firstOrNull { !clean[it].isSeparator }
        if (next == null || numbers.startsAt(clean, next)) return verbs
        return listOf(PhraseHit(lead.start, lead.end, VerbKind.NEW_PRODUCT))
    }

    /**
     * Quien hace la operación cambia lo que es: "Miss Rose bought 3 rolls" es una venta, porque
     * compró un cliente, "Charles settled his account" es un abono, y "Don Ramiro vendió 2 bultos"
     * es una compra, porque vendió un proveedor. Solo si la frase empieza con esa persona, o con
     * alguien sin nombre ("a lady bought"), y el verbo va justo después.
     */
    private fun bySubject(
        verbs: List<PhraseHit<VerbKind>>,
        clean: List<Token>,
        someoneFirst: Boolean,
    ): List<PhraseHit<VerbKind>> {
        val customer = customers.matchAt(clean, 0)?.end ?: 0.takeIf { someoneFirst }
        val supplier = suppliers.matchAt(clean, 0)?.end
        return verbs.map { verb ->
            val kind = when (verb.start) {
                customer -> BY_CUSTOMER[verb.value]
                supplier -> BY_SUPPLIER[verb.value]
                else -> null
            }
            if (kind == null) verb else verb.copy(value = kind)
        }
    }

    /**
     * Después de "crea el producto", lo que suena a otro verbo es parte del nombre o de lo que se
     * dice de él: "bolsa de regalo", "gift bag", "sold by the pound".
     */
    private fun created(verbs: List<PhraseHit<VerbKind>>): List<PhraseHit<VerbKind>> {
        val create = verbs.firstOrNull { it.value == VerbKind.NEW_PRODUCT } ?: return verbs
        return verbs.filter {
            it.start <= create.start || it.value == VerbKind.NEW_PRODUCT || it.value.subordinate
        }
    }

    /**
     * Un verbo mal dictado al principio de la frase, como "bendí" por "vendí". Solo se busca si no
     * hay ningún verbo exacto, en la primera palabra, si es larga y si no se parece a ninguna
     * palabra de un producto: así "queso" no se lee como "quedó". Tampoco si la primera palabra ya
     * nombra un gasto o un pago ("conta de luz"), y solo da un verbo que diga la operación solo:
     * uno que acompaña a otro, como un precio, no se adivina.
     */
    private fun closeVerb(tokens: List<Token>): List<PhraseHit<VerbKind>> {
        val first = tokens.firstOrNull() ?: return emptyList()
        val short = first.norm.length < CLOSE_VERB_LENGTH
        if (short || products.knowsWord(first.norm)) return emptyList()
        if (categories.matchAt(tokens, 0) != null || loosePayments.matchAt(tokens, 0) != null) {
            return emptyList()
        }
        val hit = lexicon.verbs.closeAt(tokens, 0) ?: return emptyList()
        val decides = hit.value.intent != null && !hit.value.subordinate
        return if (decides) listOf(hit) else emptyList()
    }

    /**
     * "me salen" dice lo que rinde una receta si la frase empieza con lo que se usa: "con 1 kilo de
     * cera me salen 8 velas", o "2 kilos de azúcar me rinden 10 mermeladas", con una cantidad
     * delante y otra después sin precio. Sin eso es un costo: "las velas me salen a 5000".
     */
    private fun yieldOrCost(hit: PhraseHit<VerbKind>, clean: List<Token>): PhraseHit<VerbKind> {
        if (hit.value != VerbKind.RECIPE_YIELDS) return hit
        val led = clean[0].norm in lexicon.yieldLeads
        val counted = numbers.startsAt(clean, 0) && hit.end < clean.size &&
            lexicon.amountMarkers.matchAt(clean, hit.end) == null &&
            numbers.startsAt(clean, hit.end)
        return if (led || counted) hit else hit.copy(value = VerbKind.COST)
    }

    /**
     * "cada empanada lleva", "por cada 20 panes uso": la receta de una unidad, o de las que se
     * dicen. Solo abre una receta si la frase empieza así y dice lo que se usa: "cada empanada me
     * cuesta 1200" es un costo.
     */
    private fun withEachRecipe(
        verbs: List<PhraseHit<VerbKind>>,
        clean: List<Token>,
    ): List<PhraseHit<VerbKind>> {
        val uses = verbs.any { it.value == VerbKind.RECIPE_USES }
        if (!uses || verbs.any { it.value == VerbKind.RECIPE_FOR }) return verbs
        val each = lexicon.recipeEach.matchAt(clean, 0) ?: return verbs
        return listOf(PhraseHit(each.start, each.end, VerbKind.RECIPE_FOR)) + verbs
    }

    /**
     * "pon el kilo de papa a 2.900", "set potatoes to 1.30": un verbo que solo no dice nada fija un
     * precio si abre la frase, no hay otro verbo y después viene un monto con su marca.
     */
    private fun withSetter(
        verbs: List<PhraseHit<VerbKind>>,
        clean: List<Token>,
    ): List<PhraseHit<VerbKind>> {
        if (verbs.isNotEmpty()) return verbs
        val setter = lexicon.priceSetters.matchAt(clean, 0)
        if (setter != null) {
            val priced = (setter.end until clean.size - 1).any { index ->
                clean[index].norm in lexicon.setterMarkers && numbers.startsAt(clean, index + 1)
            }
            return if (priced) {
                listOf(
                    PhraseHit(setter.start, setter.end, VerbKind.PRICE),
                )
            } else {
                verbs
            }
        }
        return priceByCopula(clean)
    }

    /**
     * "rolls are 0.70 now", "el pan es 600": sin otro verbo, un producto, "es" y un monto dicen su
     * precio. Con una categoría no: "the rent is 800" es un gasto.
     */
    private fun priceByCopula(clean: List<Token>): List<PhraseHit<VerbKind>> {
        if (clean.indices.any { categories.matchAt(clean, it) != null }) return emptyList()
        val copula = (1 until clean.size - 1).firstOrNull { index ->
            clean[index].norm in lexicon.priceCopulas && numbers.startsAt(clean, index + 1)
        } ?: return emptyList()
        return listOf(PhraseHit(copula, copula + 1, VerbKind.PRICE))
    }

    /**
     * Si la frase no es algo que se registra así: una pregunta, una devolución, un retiro, una
     * cotización, un verbo negado ("no vendí nada") o algo que todavía no pasó ("mañana compro",
     * "tengo que pagar"). Una frase de estas que trae un verbo que la contiene entera no cuenta:
     * "voy a vender empanadas a 2000" crea un producto, y "hice una venta de 3 panes" es una venta.
     */
    private fun refused(tokens: List<Token>, verbs: List<PhraseHit<VerbKind>>): Boolean {
        if (tokens.isNotEmpty() && lexicon.questions.matchAt(tokens, 0) != null) return true
        if (lexicon.refusals.findAll(tokens).any { !insideVerb(it, verbs) }) return true
        if (negated(tokens, verbs) || percentage(tokens)) return true
        val creates = verbs.any { it.value == VerbKind.NEW_PRODUCT }
        return !creates && lexicon.notYet.findAll(tokens).any { !insideVerb(it, verbs) }
    }

    private fun insideVerb(hit: PhraseHit<*>, verbs: List<PhraseHit<VerbKind>>): Boolean =
        verbs.any { it.start <= hit.start && it.end >= hit.end }

    /** "un 10 por ciento": un porcentaje después de una cifra. */
    private fun percentage(tokens: List<Token>): Boolean =
        lexicon.percents.findAll(tokens).any { hit ->
            hit.start > 0 && numbers.startsAt(tokens, hit.start - 1)
        }

    /** Una negación pegada a un verbo, con solo pronombres en medio: "no le vendí". */
    private fun negated(tokens: List<Token>, verbs: List<PhraseHit<VerbKind>>): Boolean =
        lexicon.negations.findAll(tokens).any { negation ->
            var next = negation.end
            while (next < tokens.size && tokens[next].norm in lexicon.pronouns) next++
            verbs.any { it.start in negation.end..next }
        }

    // Lo que se saca de la frase antes de partirla en líneas.

    /**
     * La categoría se busca en toda la frase, con los verbos, porque a veces el verbo la dice:
     * "recargué 20000".
     */
    private fun facts(
        body: List<Token>,
        clean: List<Token>,
        verbs: List<PhraseHit<VerbKind>>,
        someone: Boolean,
    ): PhraseFacts {
        val total = findTotal(body)
        val tendered = findTendered(total.rest)
        val payment = findPayment(tendered.rest)
        val party = findParty(payment.rest)
        val rest = party.rest
        return PhraseFacts(
            total = total.value,
            payment = payment.value ?: verbPayment(clean, verbs) ?: tendered.value,
            party = party.value,
            category = findCategory(clean),
            rest = rest,
            drafts = selfFixed(rest),
            someone = someone,
        )
    }

    /**
     * Las líneas, con lo que la persona corrigió dentro de la misma frase: "vendí 3 panes, perdón,
     * 4 panes" son 4 panes, y "3 panes, perdón, empanadas" son 3 empanadas. Lo que va después de
     * "perdón" reemplaza la línea anterior, con lo que esa ya decía y esta no.
     */
    private fun selfFixed(rest: List<Token>): List<LineDraft> {
        val said = lexicon.selfFixes.findAll(rest).filter { it.start > 0 }
        val fixes = said.filter { fixesLine(rest, it.end) }
        // Un "perdón" que no corrige nada no es una línea: sale.
        if (fixes.isEmpty()) return linesOf(rest.without(said))
        val bounds = listOf(0) + fixes.flatMap { listOf(it.start, it.end) } + rest.size
        val segments = bounds.chunked(2).map { (from, to) -> linesOf(rest.subList(from, to)) }
        val result = segments.first().toMutableList()
        for (segment in segments.drop(1)) {
            // Lo que sigue a una corrección que vale dice una línea: ver [fixesLine].
            val fix = segment.first()
            val previous = result.removeLastOrNull()
            result += if (previous == null) fix else fixedLine(previous, fix)
            result += segment.drop(1)
        }
        return result
    }

    /**
     * Si lo que sigue a "perdón" dice una línea: una cantidad o un producto del catálogo. Así "y
     * esto no sé" no se lee como una corrección.
     */
    private fun fixesLine(tokens: List<Token>, start: Int): Boolean {
        val from = (start until tokens.size).firstOrNull { !tokens[it].isSeparator } ?: return false
        if (numbers.startsAt(tokens, from)) return true
        val line = splitter.split(tokens.subList(from, tokens.size)).first()
        return products.names(lineReader.read(line).span) { true }
    }

    private fun linesOf(tokens: List<Token>): List<LineDraft> =
        prepared(splitter.split(tokens).map(lineReader::read))

    private fun fixedLine(previous: LineDraft, fix: LineDraft): LineDraft = fix.copy(
        span = fix.span.ifEmpty { previous.span },
        quantity = fix.quantity ?: previous.quantity,
        unitId = fix.unitId ?: previous.unitId,
        unitPrice = fix.unitPrice ?: previous.unitPrice,
        lineTotal = fix.lineTotal ?: previous.lineTotal,
    )

    /**
     * "con un billete de 20 mil", "with a 20 bill": con cuánto pagó, que no es el precio. Sale de
     * la frase y dice que pagó en efectivo.
     */
    private fun findTendered(tokens: List<Token>): Extracted<NameMatch> =
        tokens.indices.firstNotNullOfOrNull { tenderedAt(tokens, it) } ?: Extracted(null, tokens)

    private fun tenderedAt(tokens: List<Token>, index: Int): Extracted<NameMatch>? {
        val lead = lexicon.tenderedBefore.matchAt(tokens, index) ?: return null
        val number = numbers.read(tokens, lead.end, amount = true) ?: return null
        val after = lexicon.tenderedAfter.matchAt(tokens, number.end)
        if (after == null && lexicon.tenderedAfter.isNotEmpty) return null
        val cash = catalog.paymentMethods.firstOrNull { it.systemKey == CASH }
        val end = after?.end ?: number.end
        return Extracted(cash?.let { NameMatch.Found(it.id) }, tokens.cut(index, end))
    }

    /** El pago que dice el verbo: "me transfirieron", "transferred". */
    private fun verbPayment(clean: List<Token>, verbs: List<PhraseHit<VerbKind>>): NameMatch? =
        verbs.firstNotNullOfOrNull { verb ->
            (verb.start until verb.end).firstNotNullOfOrNull { index ->
                loosePayments.matchAt(clean, index)
            }
        }?.let { nameMatch(it.ids) }

    /**
     * Junta una medida suelta con el producto anterior ("la mozarela, como 2 libras") y completa
     * una línea que solo dice el complemento con la cabeza de la anterior ("3 jugos de mora y 2 de
     * naranja", "a chocolate cake and a vanilla one").
     */
    private fun prepared(drafts: List<LineDraft>): List<LineDraft> {
        val result = mutableListOf<LineDraft>()
        for (draft in drafts) {
            val previous = result.lastOrNull()
            if (previous != null && measuresPrevious(draft, previous)) {
                // "Compré harina. Fueron 10 kilos a 4 mil": la medida, y el monto si lo dice.
                result[result.lastIndex] = previous.copy(
                    quantity = draft.quantity,
                    unitId = draft.unitId ?: previous.unitId,
                    unitPrice = draft.unitPrice ?: previous.unitPrice,
                    lineTotal = draft.lineTotal ?: previous.lineTotal,
                )
            } else {
                result.add(draft.copy(alternative = completed(draft, previous)))
            }
        }
        return result
    }

    /**
     * Si [draft] solo mide la línea anterior, que no dijo cuánto: "la mozarela, como 2 libras", o
     * "Hice empanadas. Fueron 30.". Una cifra sin unidad cuenta solo después de "fueron" o "eran",
     * y no si es grande: "fueron 3 mil" es lo que costó.
     */
    private fun measuresPrevious(draft: LineDraft, previous: LineDraft): Boolean {
        if (previous.span.isEmpty() || previous.quantity != null) return false
        if (countsContainers(draft)) return true
        if (draft.span.isNotEmpty()) return false
        if (draft.unitId != null) return true
        val quantity = draft.quantity ?: return false
        return draft.copula && quantity < LARGE_COUNT
    }

    /**
     * "2 frascos", "2 jars": solo una cantidad de envases, sin producto, que cuenta lo que se
     * nombró antes aunque un envase también sea un producto del catálogo.
     */
    private fun countsContainers(draft: LineDraft): Boolean {
        val only = draft.span.singleOrNull() ?: return false
        return draft.quantity != null && lexicon.singular(only.norm) in lexicon.containers
    }

    private fun completed(draft: LineDraft, previous: LineDraft?): List<Token> {
        val before = previous?.let { it.alternative.ifEmpty { it.span } }.orEmpty()
        val last = draft.span.lastOrNull()
        val connector = draft.ellipsis
        return when {
            before.isEmpty() || last == null -> emptyList()

            connector != null -> before.takeWhile {
                it.norm !in lexicon.nameConnectors
            } + connector +
                draft.span

            last.norm in lexicon.pronounHeads -> draft.span.dropLast(1) + before.last()

            // "2 gaseosas grandes y una pequeña": solo el tamaño, con la cabeza de la anterior.
            draft.span.all { it.norm in lexicon.sizes } ->
                before.takeWhile {
                    it.norm !in lexicon.nameConnectors && it.norm !in lexicon.sizes
                } +
                    draft.span

            else -> emptyList()
        }
    }

    /**
     * Una palabra que no es de ningún producto y va sola antes de la lista, sin cantidad ni monto,
     * como un verbo que no se conoce: no es un producto nuevo.
     */
    private fun leadingUnknown(drafts: List<LineDraft>): Boolean {
        if (drafts.size < 2) return false
        val first = drafts[0]
        val said = listOfNotNull(first.quantity, first.unitId, first.unitPrice, first.lineTotal)
        if (said.isNotEmpty()) return false
        // Una palabra que solo se parece a un producto, como "boté" a "botella" o "nuevo" a
        // "huevo", tampoco lo nombra: suena a un verbo que no se conoce.
        return products.find(first.span, ANY_PRODUCT)?.match is ProductMatch.Unknown ||
            !products.namesExactly(first.span)
    }

    private fun findTotal(tokens: List<Token>): Extracted<Long> =
        tokens.indices.firstNotNullOfOrNull { totalBefore(tokens, it) ?: totalAfter(tokens, it) }
            ?: Extracted(null, tokens)

    /** "todo por 30000", "en total 12000". */
    private fun totalBefore(tokens: List<Token>, index: Int): Extracted<Long>? {
        val marker = lexicon.totalBefore.matchAt(tokens, index) ?: return null
        val amount = numbers.read(tokens, marker.end, amount = true) ?: return null
        return Extracted(amount.milli, tokens.cut(index, amount.end))
    }

    /** "12000 en total". */
    private fun totalAfter(tokens: List<Token>, index: Int): Extracted<Long>? {
        val amount = numbers.read(tokens, index) ?: return null
        val marker = lexicon.totalAfter.matchAt(tokens, amount.end) ?: return null
        return Extracted(amount.milli, tokens.cut(index, marker.end))
    }

    /**
     * "en efectivo", "con tarjeta": un nombre del catálogo después de "en" o "con", o un sinónimo
     * suelto.
     */
    private fun findPayment(tokens: List<Token>): Extracted<NameMatch> {
        val found = PaymentScan.all(tokens, lexicon, payments, loosePayments)
        val match = when {
            found.ids.isEmpty() -> null
            found.split -> NameMatch.Choose(found.ids)
            else -> nameMatch(found.ids)
        }
        return Extracted(match, found.rest)
    }

    /**
     * La persona nombrada: después de "a", "donde", "de"..., al principio de la frase ("María me
     * pagó") o con su tratamiento ("doña Rosa"). Si no está en el catálogo, solo cuenta un nombre
     * con mayúscula o con tratamiento después de "a" o "para": "a Pedro", "para don Luis".
     */
    private fun findParty(tokens: List<Token>): Extracted<PartyHit> =
        tokens.indices.firstNotNullOfOrNull { partyAt(tokens, it) } ?: Extracted(null, tokens)

    private fun partyAt(tokens: List<Token>, index: Int): Extracted<PartyHit>? {
        val norm = tokens[index].norm
        val prepositioned = norm in lexicon.partyPrepositions
        if (!prepositioned && index > 0 && norm !in lexicon.honorifics) return null
        val start = if (prepositioned) index + 1 else index
        val customer = customers.matchAt(tokens, start)
        val supplier = suppliers.matchAt(tokens, start)
        val known = listOfNotNull(customer?.end, supplier?.end).maxOrNull()
        val end = known ?: unknownNameEnd(tokens, start, norm) ?: return null
        val hit = PartyHit(customer, supplier, tokens.subList(start, end).spoken())
        // "de donde Don Ramiro": la preposición de antes también sale, para no dejar un "de"
        // suelto.
        val doubled =
            prepositioned && index > 0 && tokens[index - 1].norm in lexicon.partyPrepositions
        return Extracted(hit, tokens.cut(if (doubled) index - 1 else index, end))
    }

    /**
     * Dónde termina un nombre que no está en el catálogo. Después de "a" o "para" basta con que
     * vaya en mayúscula; después de "de" o "do", también debe cerrar la frase o ir antes de una
     * coma, y no parecerse a un producto: "de Pedro" es quien vendió, y "de Harina" no.
     */
    private fun unknownNameEnd(tokens: List<Token>, start: Int, preposition: String): Int? {
        val from = preposition in lexicon.fromPrepositions
        if (preposition !in lexicon.unknownPartyPrepositions && !from) return null
        var end = start
        while (end < tokens.size && end - start < MAX_NAME_WORDS && isNameWord(tokens[end])) end++
        val named = (start until end).any { tokens[it].norm !in lexicon.honorifics }
        if (!named) return null
        if (!from) return end
        val closes = end == tokens.size || tokens[end].isSeparator
        val productLike = (start until end).any { products.knowsWord(tokens[it].norm) }
        return if (closes && !productLike) end else null
    }

    private fun isNameWord(token: Token): Boolean =
        token.norm in lexicon.honorifics || token.raw[0].isUpperCase()

    /**
     * "el arriendo", "la luz": una categoría que no va después de una cantidad, como en "6 aguas".
     */
    private fun findCategory(tokens: List<Token>): NameMatch? =
        tokens.indices.firstNotNullOfOrNull { index -> categoryAt(tokens, index) }

    private fun categoryAt(tokens: List<Token>, index: Int): NameMatch? {
        if (index > 0 && numbers.startsAt(tokens, index - 1)) return null
        val hit = categories.matchAt(tokens, index) ?: return null
        return nameMatch(hit.ids)
    }

    // La operación.

    /**
     * Las operaciones que dicen los verbos. Más de una es una frase mezclada; ninguna verbo deja
     * decidir a lo que se nombró: una categoría es un gasto y un producto del catálogo, una venta.
     */
    private fun intents(kinds: List<VerbKind>, facts: PhraseFacts): List<PhraseIntent> {
        val main = kinds.filterNot { it.subordinate }.ifEmpty { costOverPrice(kinds) }
        if (main.isEmpty()) {
            // Una categoría sin verbo es un gasto, aunque otra palabra vaya delante: "el
            // alquiler de este mes, 800.000"; salvo que nombre productos del catálogo, como en
            // "the delivery came: 2 sacks of sugar".
            val namesGoods = facts.drafts.any {
                products.names(it.span) { true } && categories.matchAt(it.span, 0) == null
            }
            if (facts.category != null && !namesGoods) return listOf(PhraseIntent.EXPENSE)
            // Sin verbo y con una palabra desconocida delante, no se sabe qué pasó: se pide de
            // nuevo. Tampoco si nada dice cuánto y ningún producto se nombró sin error: "nuevo"
            // solo se parece a "huevo".
            if (leadingUnknown(facts.drafts)) return emptyList()
            val said = facts.drafts.any { it.quantity != null || products.namesExactly(it.span) }
            if (!said) return emptyList()
            val step = NO_VERB_STEPS.firstOrNull { holds(it, facts) } ?: return emptyList()
            return listOf(step.intent)
        }
        // Sin un verbo que decida solo, el primero que se resuelve con una venta o una compra
        // decide: "Miss Rose took 2 cakes and paid by card" es una venta pagada con tarjeta.
        val strong =
            main.firstNotNullOfOrNull { kind -> kind.intent.takeIf { kind.joins.isEmpty() } }
                ?: main.firstNotNullOfOrNull { kind ->
                    resolve(kind, null, facts)?.takeIf { it in CREDIT_INTENTS }
                }
        return main.mapNotNull { kind -> resolve(kind, strong, facts) }.distinct()
    }

    /**
     * "bought gas for the van, 40", "compré gasolina, 40 mil": comprar algo que es una categoría
     * de gasto y ningún producto del catálogo es un gasto.
     */
    private fun spent(intent: PhraseIntent, facts: PhraseFacts): PhraseIntent {
        val goods = facts.drafts.any { products.names(it.span) { true } }
        val expense = intent == PhraseIntent.PURCHASE && facts.category != null && !goods
        return if (expense) PhraseIntent.EXPENSE else intent
    }

    /**
     * Un costo y un precio dichos juntos son un costo: "subió la harina, ahora me cuesta 5000"
     * dice lo que me cuesta, no a cuánto la vendo.
     */
    private fun costOverPrice(kinds: List<VerbKind>): List<VerbKind> =
        if (VerbKind.COST in kinds) kinds.filterNot { it == VerbKind.PRICE } else kinds

    /**
     * La operación de un verbo. Un verbo de pago se suma al otro verbo de la frase, salvo que
     * nombre una categoría: "compré harina y pagué la luz" son dos operaciones.
     */
    private fun resolve(kind: VerbKind, strong: PhraseIntent?, facts: PhraseFacts): PhraseIntent? {
        val categorical = WeakStep.CATEGORY in kind.steps && facts.category != null
        if (strong != null && strong in kind.joins && !categorical) return strong
        val step = kind.steps.firstOrNull { holds(it, facts) } ?: return kind.intent
        return step.intent
    }

    private fun holds(step: WeakStep, facts: PhraseFacts): Boolean = when (step) {
        WeakStep.CATEGORY -> facts.category != null

        WeakStep.PURCHASE_PRODUCTS -> facts.drafts.any {
            products.names(it.span) { product -> product.forPurchase }
        }

        WeakStep.SALE_PRODUCTS -> facts.drafts.any { products.names(it.span) { true } }

        WeakStep.CUSTOMER -> facts.party?.customer != null

        WeakStep.BUYER -> facts.party?.customer != null || facts.someone

        WeakStep.SUPPLIER -> facts.party?.supplier != null

        WeakStep.PRICE_ONLY ->
            facts.drafts.none { it.quantity != null } && facts.drafts.any { it.unitPrice != null }

        WeakStep.GIFTED_PRODUCTS -> facts.drafts.any { products.names(it.span) { true } }

        WeakStep.LONE_CATEGORY -> facts.category != null && facts.party?.customer == null

        WeakStep.CUSTOMER_WITHOUT_GOODS ->
            facts.party?.customer != null &&
                facts.drafts.none { products.names(it.span) { true } }
    }

    // Cada tipo de lectura.

    /**
     * Venta, compra, producción o ajuste: líneas de productos. Sin verbo, la operación es una
     * suposición, así que no filtra los productos: "2 kilos de harina" deja elegir entre las
     * harinas aunque solo una se venda, en vez de elegir esa con confianza.
     */
    private fun goods(
        intent: PhraseIntent,
        kinds: List<VerbKind>,
        said: Described,
        facts: PhraseFacts,
        credit: Boolean,
    ): PhraseReading {
        val rule = if (kinds.isEmpty()) ANY_PRODUCT else RULES.getValue(intent)
        val reason = REASON_PRIORITY.firstOrNull { reason -> kinds.any { it.reason == reason } }
        val read = when {
            intent == PhraseIntent.PRODUCTION -> countedAfter(produced(facts))
            reason == StockAdjustmentReason.COUNT_CORRECTION -> facts.drafts.map(::counted)
            intent == PhraseIntent.STOCK_ADJUSTMENT -> countedAfter(facts.drafts)
            else -> facts.drafts
        }
        val drafts = if (leadingUnknown(read)) read.drop(1) else read
        val described = described(drafts, rule, said)
        // Un ajuste no guarda costos: lo dicho sale de la línea, con un aviso para que no se
        // pierda en silencio.
        val adjusted = intent == PhraseIntent.STOCK_ADJUSTMENT
        val costed = adjusted &&
            described.any { it.line.unitPrice != null || it.line.lineTotal != null }
        val lines = if (adjusted) described.map(::unpriced) else described
        // Producir algo que no existe tampoco tiene receta: se avisa, como con uno sin receta.
        val unknownMade = intent == PhraseIntent.PRODUCTION &&
            lines.any { it.line.product is ProductMatch.Unknown }
        val amount = facts.drafts.firstOrNull { it.span.isEmpty() && it.unitId == null }?.amount
        // Sin productos no hay qué registrar: "me robaron 50 mil", "got 20 from Mary" si no es
        // un cliente. Una venta o una compra con su total sí se deja, para elegir los productos.
        val totaled = intent == PhraseIntent.SALE || intent == PhraseIntent.PURCHASE
        if (lines.isEmpty() && (!totaled || (facts.total ?: amount) == null)) return NOTHING
        val onCredit = credit && intent in CREDIT_INTENTS
        val tail = facts.drafts.firstOrNull { it.tail.isNotEmpty() }?.tail
        val party = facts.party ?: tail?.let { PartyHit(null, null, it.spoken()) }
        return PhraseReading(
            intent = intent,
            reason = reason,
            lines = lines.map { it.line },
            payment = facts.payment ?: creditPayment(onCredit),
            party = partyFor(intent, party),
            total = (facts.total ?: amount)?.let(::money),
            credit = onCredit,
            issues = issues(read, lines) +
                setOfNotNull(
                    PhraseIssue.NO_RECIPE.takeIf { unknownMade },
                    PhraseIssue.COST_LEFT_OUT.takeIf { costed },
                ),
        )
    }

    /**
     * "ajusta el stock de pan a 30", "the bread is at 30": en un conteo, el número después de "a"
     * es lo que hay, no un precio. Lo que se dijo como lo que costó sigue siendo un costo.
     */
    private fun counted(draft: LineDraft): LineDraft {
        val atCount = draft.quantity == null && draft.unitPrice != null && !draft.costSaid
        return if (atCount) draft.copy(quantity = draft.unitPrice, unitPrice = null) else draft
    }

    /**
     * "the eggs broke, 3", "se dañaron los panes, 4": en un ajuste o una producción no hay
     * dinero, así que una cifra sola después de un producto sin cantidad es su cantidad.
     */
    private fun countedAfter(drafts: List<LineDraft>): List<LineDraft> {
        val result = mutableListOf<LineDraft>()
        for (draft in drafts) {
            val previous = result.lastOrNull()
            // Una unidad ya la juntó con la línea anterior la lectura de siempre.
            val bare = draft.span.isEmpty() && draft.quantity != null
            if (previous != null && bare && previous.span.isNotEmpty() &&
                previous.quantity == null
            ) {
                result[result.lastIndex] = previous.copy(quantity = draft.quantity)
            } else {
                result += draft
            }
        }
        return result
    }

    private fun unpriced(resolved: ResolvedLine): ResolvedLine =
        resolved.copy(line = resolved.line.copy(unitPrice = null, lineTotal = null))

    /**
     * Lo producido, sin los insumos que se dicen después de "con": "hice 40 panes con 2 kilos de
     * harina" produce 40 panes, y los insumos salen de la receta.
     */
    private fun produced(facts: PhraseFacts): List<LineDraft> {
        val rest = facts.rest
        val cut = (0 until rest.size - 1).firstOrNull { index ->
            index > 0 && rest[index].norm in lexicon.yieldLeads && numbers.startsAt(rest, index + 1)
        } ?: return facts.drafts
        return prepared(splitter.split(rest.subList(0, cut)).map(lineReader::read))
    }

    /** El método de pago a crédito, si la operación queda a crédito y el catálogo lo tiene. */
    private fun creditPayment(onCredit: Boolean): NameMatch? {
        val method = onAccount ?: return null
        return if (onCredit) NameMatch.Found(method.id) else null
    }

    /** Un gasto o un pago: el monto, y a quién o en qué. */
    private fun payment(intent: PhraseIntent, facts: PhraseFacts): PhraseReading = PhraseReading(
        intent = intent,
        payment = facts.payment,
        party = partyFor(intent, facts.party),
        category = if (intent == PhraseIntent.EXPENSE) facts.category else null,
        total = (facts.total ?: firstNumber(facts.rest))?.let(::money),
    )

    /** Un precio o un costo nuevo: una sola línea, con el monto en el precio por unidad. */
    private fun change(intent: PhraseIntent, facts: PhraseFacts): PhraseReading {
        val said = facts.rest.filterNot { it.isSeparator || it.norm in lexicon.changeFillers }
        // "cada empanada me cuesta 1200": el costo de una.
        val each = lexicon.recipeEach.matchAt(said, 0)?.end ?: 0
        val draft = lineReader.read(said.subList(each, said.size), amounts = true)
        val resolved = resolveLine(draft, RULES.getValue(intent))
        // "el arriendo sube a 900 mil" no es el precio de un producto: no se registra así.
        val unknown = resolved != null && resolved.line.product is ProductMatch.Unknown
        if (facts.category != null && unknown) return NOTHING
        val amount = (draft.unitPrice ?: draft.lineTotal)?.let(::money)
        val line = resolved?.let {
            it.line.copy(quantity = null, unitPrice = amount, lineTotal = null)
        }
        return PhraseReading(
            intent = intent,
            lines = listOfNotNull(line),
            issues = setOfNotNull(resolved?.issue),
        )
    }

    /**
     * Una receta: el producto que se hace, cuánto rinde y sus insumos. El producto va después de
     * "para hacer" o "receta de", o antes de "lleva"; los insumos, después de "lleva" o "uso", o de
     * los dos puntos.
     */
    private fun recipe(said: Described, verbs: List<PhraseHit<VerbKind>>): PhraseReading {
        val clean = said.tokens
        val yields = verbs.firstOrNull { it.value == VerbKind.RECIPE_YIELDS }
        if (yields != null) {
            // "con 1 kilo de cera me salen 8 velas": lo usado va antes, y lo que rinde, después.
            val from = if (clean[0].norm in lexicon.yieldLeads) 1 else 0
            val used = segment(clean, verbs, from, yields.start)
            return recipeOf(segment(clean, verbs, yields.end, clean.size), used, said)
        }
        val intro = verbs.firstOrNull { it.value == VerbKind.RECIPE_FOR }
        val uses = verbs.firstOrNull { it.value == VerbKind.RECIPE_USES }
        val targetStart = intro?.end ?: 0
        val usesAfter = uses?.start?.takeIf { it >= targetStart }
        val targetEnd = usesAfter ?: separatorFrom(clean, targetStart)
            .coerceAtMost(describedFrom(clean, targetStart))
        val ingredientsStart = minOf(uses?.end ?: (targetEnd + 1), clean.size)
        val ingredientsEnd = intro?.start?.takeIf { it > ingredientsStart } ?: clean.size
        val targetTokens = segment(clean, verbs, targetStart, targetEnd)
            .dropWhile { it.norm in lexicon.toPrepositions }
        return recipeOf(
            targetTokens,
            segment(clean, verbs, ingredientsStart, ingredientsEnd),
            said,
        )
    }

    private fun recipeOf(
        targetTokens: List<Token>,
        ingredientTokens: List<Token>,
        said: Described,
    ): PhraseReading {
        val targetSaid = targetTokens.filterNot { it.norm in lexicon.pronouns }
        val target = lineReader.read(targetSaid)
        val used = ingredientTokens.filterNot { it.norm in lexicon.pronouns }
        val ingredients = prepared(splitter.split(used).map(lineReader::read))
        val made = products.find(target.span, RECIPE_TARGET)?.match
        // Un producto no lleva de insumo a sí mismo: "naranjas" en la receta del jugo de naranja
        // es otro producto, uno nuevo.
        val lines = described(ingredients, ANY_PRODUCT, said).map { resolved ->
            val line = resolved.line
            if (made is ProductMatch.Found && line.product == made) {
                // Se lee de nuevo como uno nuevo: la unidad que no le servía al producto le sirve.
                val fresh = ProductMatch.Unknown(products.spokenName(resolved.draft.span))
                lineOf(ProductResolution(fresh, null), resolved.draft)
            } else {
                resolved
            }
        }
        // "una torta lleva", "a cup of coffee takes": el artículo no dice cuántas rinde.
        // Una cantidad de uno salió de una palabra: hay al menos una.
        val articled = target.quantity == Scale.MILLI && !targetSaid.first().looksNumeric()
        return PhraseReading(
            intent = PhraseIntent.RECIPE,
            lines = lines.map { it.line },
            target = made,
            targetQuantity = target.quantity.takeUnless { articled }?.let(::QuantityMilli),
            issues = issues(ingredients, lines),
        )
    }

    /**
     * Las palabras de [from] hasta antes de [to], sin las de otros verbos: "lleva 2 huevos y uso
     * sal" deja "sal".
     */
    private fun segment(
        tokens: List<Token>,
        verbs: List<PhraseHit<VerbKind>>,
        from: Int,
        to: Int,
    ): List<Token> = (from until to)
        .filter { index -> verbs.none { index >= it.start && index < it.end } }
        .map { tokens[it] }

    /** Dónde empieza lo que se dice del producto: "es", "is". */
    private fun describedFrom(tokens: List<Token>, start: Int): Int =
        (start until tokens.size).firstOrNull { tokens[it].norm in lexicon.descriptionWords }
            ?: tokens.size

    private fun separatorFrom(tokens: List<Token>, start: Int): Int =
        (start until tokens.size).firstOrNull { tokens[it].isSeparator } ?: tokens.size

    /**
     * Un producto nuevo: el nombre como se dijo, y el precio, el costo y la unidad si se dijeron.
     */
    private fun newProduct(said: Described, verbs: List<PhraseHit<VerbKind>>): PhraseReading {
        // Las palabras de precio y costo ("precio", "costo") quedan: dicen qué es cada número.
        val tokens = said.tokens.without(verbs.filter { it.value == VerbKind.NEW_PRODUCT })
        val nameStart = leadEnd(tokens)
        var position = nameStart
        while (position < tokens.size && !endsName(tokens, position)) position++
        val name = tokens.subList(nameStart, position)
            .dropWhile { it.norm in lexicon.trimmed }
            .dropLastWhile { it.norm in lexicon.trimmed }
        var price: Long? = null
        var cost: Long? = null
        var unitId: Long? = null
        while (position < tokens.size) {
            val priced = productAmountAt(tokens, position)
            val bare = if (priced == null) numbers.read(tokens, position) else null
            when {
                priced != null -> {
                    val amount = priced.number.milli
                    if (priced.isCost) cost = amount else price = amount
                    position = priced.number.end
                }

                bare != null -> {
                    if (price == null) price = bare.milli else cost = bare.milli
                    position = bare.end
                }

                else -> {
                    unitId = unitId ?: units.at(tokens, position, afterNumber = true)?.id
                    position++
                }
            }
        }
        val existing = if (name.isEmpty()) null else products.existing(name)
        // Todo lo que se dijo del producto es de este, el único de la frase.
        val traits =
            said.traits.fold(NewProductTraits.NONE) { all, trait -> all.with(trait.traits) }
        val proposed =
            if (name.isEmpty()) null else ProductMatch.Unknown(products.spokenName(name), traits)
        return PhraseReading(
            intent = PhraseIntent.NEW_PRODUCT,
            target = existing ?: proposed,
            price = price?.let(::money),
            cost = cost?.let(::money),
            unitId = unitId,
            issues = setOfNotNull(if (existing != null) PhraseIssue.ALREADY_EXISTS else null),
        )
    }

    private fun leadEnd(tokens: List<Token>): Int {
        var index = 0
        while (index < tokens.size) {
            val lead = lexicon.productLead.matchAt(tokens, index)?.end
            index = if (tokens[index].isSeparator) index + 1 else lead ?: break
        }
        return index
    }

    private fun endsName(tokens: List<Token>, position: Int): Boolean =
        tokens[position].isSeparator ||
            numbers.startsAt(tokens, position) ||
            productAmountAt(tokens, position) != null ||
            perUnitAt(tokens, position)

    /** "el kilo", "por kilo": la unidad en que se vende lo nuevo. */
    private fun perUnitAt(tokens: List<Token>, position: Int): Boolean {
        if (tokens[position].norm !in lexicon.perUnitLeads) return false
        return units.at(tokens, position + 1, afterNumber = true) != null
    }

    private fun productAmountAt(tokens: List<Token>, position: Int): ProductAmount? {
        val hit = lexicon.productAmounts.matchAt(tokens, position) ?: return null
        val number = numbers.read(tokens, hit.end, amount = true) ?: return null
        return ProductAmount(hit.value, number)
    }

    // Piezas comunes.

    /**
     * Las líneas de [drafts], con lo que se dijo de cada producto nuevo: cada descripción va al
     * producto nuevo que se nombró antes, o al primero después si no hay ninguno antes.
     */
    private fun described(
        drafts: List<LineDraft>,
        rule: ProductRule,
        said: Described,
    ): List<ResolvedLine> {
        val lines = drafts.mapNotNull { draft -> resolveLine(draft, rule)?.let { draft to it } }
        val fresh = lines.indices.filter { lines[it].second.line.product is ProductMatch.Unknown }
        val starts = fresh.map { index ->
            val first = lines[index].first.span[0]
            said.tokens.indexOfFirst { it === first }
        }
        val placed = fresh.zip(placeTraits(starts, said.traits)).toMap()
        return lines.mapIndexed { index, (_, resolved) ->
            val traits = placed[index] ?: return@mapIndexed resolved
            val unknown = resolved.line.product as ProductMatch.Unknown
            resolved.copy(line = resolved.line.copy(product = unknown.copy(traits = traits)))
        }
    }

    private fun resolveLine(draft: LineDraft, rule: ProductRule): ResolvedLine? {
        val resolution = products.find(draft.span, rule) ?: return null
        val better =
            insideContainer(draft.span, rule, resolution) ?: elliptical(draft, rule, resolution)
        return lineOf(better ?: resolution, draft)
    }

    /**
     * "y 2 de naranja": si la línea sola no es un producto, se prueba con la cabeza de la anterior.
     */
    private fun elliptical(
        draft: LineDraft,
        rule: ProductRule,
        whole: ProductResolution,
    ): ProductResolution? {
        if (whole.match !is ProductMatch.Unknown || draft.alternative.isEmpty()) return null
        // Aunque tampoco exista, el nombre completo ("Jugo de lulo") es mejor propuesta que "Lulo".
        return products.find(draft.alternative, rule)
    }

    /**
     * "3 botellas de leche": si el nombre entero no es un producto y empieza con un envase que no
     * es una unidad del catálogo, el producto es lo que va dentro.
     */
    private fun insideContainer(
        span: List<Token>,
        rule: ProductRule,
        whole: ProductResolution,
    ): ProductResolution? {
        if (whole.match !is ProductMatch.Unknown || span.size < CONTAINER_SPAN) return null
        val container =
            lexicon.singular(span[0].norm) in lexicon.containers &&
                span[1].norm in lexicon.connectors
        if (!container) return null
        val inside = products.find(span.subList(2, span.size), rule) ?: return null
        return if (inside.match is ProductMatch.Unknown) null else inside
    }

    private fun lineOf(resolution: ProductResolution, draft: LineDraft): ResolvedLine {
        val spread = spreadCount(resolution.match, draft)
        if (spread != null) {
            val line = PhraseLine(
                product = resolution.match,
                quantity = QuantityMilli(spread),
                unitId = null,
                unitPrice = null,
                lineTotal = draft.lineTotal?.let(::money),
            )
            return ResolvedLine(line, resolution.issue, unitFits = true, draft = draft)
        }
        // Una unidad que el producto no admite no se propone: queda el problema, y la base del
        // producto.
        val fits = unitFits(resolution.match, draft.unitId)
        val line = PhraseLine(
            product = resolution.match,
            quantity = draft.quantity?.let(::QuantityMilli),
            unitId = draft.unitId.takeIf { fits },
            unitPrice = draft.unitPrice?.let(::money),
            lineTotal = draft.lineTotal?.let(::money),
        )
        return ResolvedLine(line, resolution.issue, fits, draft)
    }

    /**
     * Docenas de un producto que se cuenta por unidad, en unidades: "3 docenas de panes" son 36
     * panes si el pan no se lleva por docena, y "media docena de huevos" son 6 aunque se lleve,
     * porque la docena no admite fracciones. El total de la línea sigue igual; con un precio por
     * docena, la línea se queda como se dijo, para no inventar el precio de una unidad. `null` si
     * la línea se queda como se dijo.
     */
    private fun spreadCount(match: ProductMatch, draft: LineDraft): Long? {
        val found = match as? ProductMatch.Found ?: return null
        val unitId = draft.unitId ?: return null
        val unit = units.byId(unitId)
        val factor = unit.systemKey?.let(lexicon.countFactors::get) ?: return null
        val product = products.byId(found.id)
        if (units.byId(product.baseUnitId).kind != UnitKind.COUNT) return null
        val quantity = draft.quantity ?: Scale.MILLI
        val whole = quantity % Scale.MILLI == 0L
        if (units.admits(product, unit) && whole) return null
        if (draft.unitPrice != null) return null
        return quantity * factor
    }

    private fun unitFits(match: ProductMatch, unitId: Long?): Boolean {
        val found = match as? ProductMatch.Found ?: return true
        val unit = units.byId(unitId ?: return true)
        return units.admits(products.byId(found.id), unit)
    }

    private fun issues(drafts: List<LineDraft>, lines: List<ResolvedLine>): Set<PhraseIssue> =
        buildSet {
            lines.mapNotNullTo(this) { it.issue }
            if (drafts.any { it.unreadable }) add(PhraseIssue.UNREADABLE_QUANTITY)
            if (drafts.any { it.unused }) add(PhraseIssue.UNUSED_WORDS)
            if (lines.any { !it.unitFits }) add(PhraseIssue.UNIT_NOT_FOR_PRODUCT)
        }

    private fun partyFor(intent: PhraseIntent, hit: PartyHit?): NameMatch? {
        if (hit == null) return null
        val side = PARTY_SIDES[intent] ?: return null
        val found = side(hit) ?: return NameMatch.Unknown(hit.spoken)
        return nameMatch(found.ids)
    }

    private fun firstNumber(tokens: List<Token>): Long? =
        tokens.indices.firstNotNullOfOrNull { numbers.read(tokens, it) }?.milli

    private fun nameMatch(ids: List<Long>): NameMatch =
        if (ids.size == 1) NameMatch.Found(ids[0]) else NameMatch.Choose(ids)

    /**
     * Milésimas de la unidad mayor a la unidad menor de la moneda, redondeando un empate lejos de
     * cero.
     */
    private fun money(milli: Long): Money =
        Money(roundDiv(milli * POWERS_OF_TEN[moneyDecimals], Scale.MILLI))

    private data class ResolvedLine(
        val line: PhraseLine,
        val issue: PhraseIssue?,
        val unitFits: Boolean,
        val draft: LineDraft,
    )

    private data class ProductAmount(val isCost: Boolean, val number: NumberRead)

    /** Las palabras de la frase sin sus descripciones, y las descripciones que se sacaron. */
    private data class Described(val tokens: List<Token>, val traits: List<PlacedTrait>)

    internal companion object {
        const val ON_ACCOUNT = "on_account"

        /** Lo que hace un cliente cuando lo dice un verbo de quien atiende. */
        val BY_CUSTOMER: Map<VerbKind, VerbKind> = mapOf(
            VerbKind.PURCHASE to VerbKind.SALE,
            VerbKind.SETTLE_OUT to VerbKind.PAY_IN,
        )

        /** Lo que hace un proveedor cuando lo dice un verbo de quien atiende. */
        val BY_SUPPLIER: Map<VerbKind, VerbKind> = mapOf(VerbKind.SALE to VerbKind.PURCHASE)

        /** La clave del método de pago en efectivo, el de un billete con que se pagó. */
        const val CASH = "cash"

        /** El signo de pregunta, en cualquier lugar de la frase. */
        const val QUESTION_MARK = '?'

        /** Un porcentaje no es un monto: sube o descuenta, y eso no se registra desde una frase. */
        const val PERCENT = '%'

        /**
         * Desde aquí, en milésimas, una cifra sin unidad después de "fueron" es un monto y no una
         * cantidad: mil unidades.
         */
        const val LARGE_COUNT = 1_000_000L

        /** Lo que se devuelve de una frase que no es una operación que se registre así. */
        val NOTHING = PhraseReading(intent = null, issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD))
        const val MAX_NAME_WORDS = 3

        /**
         * Largo mínimo de una palabra para buscarle un verbo parecido: las cortas se confunden con
         * todo.
         */
        const val CLOSE_VERB_LENGTH = 5

        /** Un envase, "de" y al menos una palabra del producto. */
        const val CONTAINER_SPAN = 3
        val POWERS_OF_TEN = longArrayOf(1L, 10L, 100L, 1_000L)

        /**
         * Sin verbo, decide lo nombrado: una categoría es un gasto y un producto del catálogo, una
         * venta.
         */
        val NO_VERB_STEPS = listOf(WeakStep.CATEGORY, WeakStep.SALE_PRODUCTS)

        /**
         * Si se dicen dos motivos, gana el más específico: "se dañó la leche por vencida" es un
         * vencimiento.
         */
        val REASON_PRIORITY = listOf(
            StockAdjustmentReason.EXPIRED,
            StockAdjustmentReason.LOST_OR_STOLEN,
            StockAdjustmentReason.DAMAGED,
            StockAdjustmentReason.OWN_USE,
            StockAdjustmentReason.GIFT_OR_SAMPLE,
            StockAdjustmentReason.COUNT_CORRECTION,
        )

        val CREDIT_INTENTS = setOf(PhraseIntent.SALE, PhraseIntent.PURCHASE)

        /** Las operaciones en que se nombra un producto nuevo, al que va su descripción. */
        val DESCRIBED_INTENTS = setOf(
            PhraseIntent.SALE,
            PhraseIntent.PURCHASE,
            PhraseIntent.RECIPE,
            PhraseIntent.NEW_PRODUCT,
        )

        val ANY_PRODUCT = ProductRule({ true }, null)

        /** El producto de una receta: mejor uno que ya tiene receta, si no, cualquiera. */
        val RECIPE_TARGET = ProductRule({ it.hasRecipe }, null)

        val RULES: Map<PhraseIntent, ProductRule> = mapOf(
            PhraseIntent.SALE to ProductRule({ it.forSale }, null),
            PhraseIntent.PURCHASE to ProductRule({ it.forPurchase }, null),
            PhraseIntent.PRODUCTION to ProductRule({ it.hasRecipe }, PhraseIssue.NO_RECIPE),
            PhraseIntent.STOCK_ADJUSTMENT to
                ProductRule({ it.stockMode == ProductStockMode.TRACKED }, null),
            PhraseIntent.COST_CORRECTION to
                ProductRule({ it.stockMode == ProductStockMode.TRACKED }, PhraseIssue.NO_STOCK),
            PhraseIntent.PRICE_CHANGE to ProductRule({ it.forSale }, null),
        )

        val PARTY_SIDES: Map<PhraseIntent, (PartyHit) -> NameHit?> = mapOf(
            PhraseIntent.SALE to { hit: PartyHit -> hit.customer },
            PhraseIntent.CUSTOMER_PAYMENT to { hit: PartyHit -> hit.customer },
            PhraseIntent.PURCHASE to { hit: PartyHit -> hit.supplier },
            PhraseIntent.SUPPLIER_PAYMENT to { hit: PartyHit -> hit.supplier },
        )
    }
}
