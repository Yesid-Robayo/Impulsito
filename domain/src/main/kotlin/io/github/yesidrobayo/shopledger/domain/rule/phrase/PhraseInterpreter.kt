package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
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
)

/** Una lectura con todos los índices del catálogo armados para el idioma de la frase. */
internal class PhraseSession(
    private val lexicon: PhraseLexicon,
    style: NumberStyle,
    private val moneyDecimals: Int,
    private val catalog: PhraseCatalog,
) {
    private val numbers = NumberReader(lexicon, style)
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
    )
    private val onAccount = catalog.paymentMethods.firstOrNull { it.systemKey == ON_ACCOUNT }

    fun read(text: String): PhraseReading {
        val tokens = PhraseTokenizer.tokenize(text).let { it.without(lexicon.fillers.findAll(it)) }
        val creditHits = lexicon.credit.findAll(tokens)
        val clean = tokens.without(creditHits)
        val verbs = lexicon.verbs.findAll(clean).ifEmpty {
            closeVerb(clean)
        }.map { yieldOrCost(it, clean) }
        val kinds = verbs.map { it.value }
        val facts = facts(clean.without(verbs).filterNot { it.norm in lexicon.pronouns }, clean)
        val asked = clean.isNotEmpty() && clean[0].norm in lexicon.questions
        val intents = if (asked) emptyList() else intents(kinds, facts)
        val intent = intents.singleOrNull()
        return when {
            intents.size > 1 -> PhraseReading(
                intent = null,
                issues = setOf(PhraseIssue.MIXED_OPERATIONS),
            )

            intent == null -> PhraseReading(
                intent = null,
                issues = setOf(PhraseIssue.NOTHING_UNDERSTOOD),
            )

            else -> reading(intent, kinds, verbs, clean, facts, credit = creditHits.isNotEmpty())
        }
    }

    private fun reading(
        intent: PhraseIntent,
        kinds: List<VerbKind>,
        verbs: List<PhraseHit<VerbKind>>,
        clean: List<Token>,
        facts: PhraseFacts,
        credit: Boolean,
    ): PhraseReading = when (intent) {
        PhraseIntent.RECIPE -> recipe(clean, verbs)

        PhraseIntent.NEW_PRODUCT -> newProduct(clean, verbs)

        PhraseIntent.EXPENSE,
        PhraseIntent.CUSTOMER_PAYMENT,
        PhraseIntent.SUPPLIER_PAYMENT,
        -> payment(intent, facts)

        PhraseIntent.COST_CORRECTION, PhraseIntent.PRICE_CHANGE -> change(intent, facts)

        else -> goods(intent, kinds, facts, credit)
    }

    /**
     * Un verbo mal dictado al principio de la frase, como "bendí" por "vendí". Solo se busca si no
     * hay ningún verbo exacto, en la primera palabra, si es larga y si no se parece a ninguna
     * palabra de un producto: así "queso" no se lee como "quedó".
     */
    private fun closeVerb(tokens: List<Token>): List<PhraseHit<VerbKind>> {
        val first = tokens.firstOrNull() ?: return emptyList()
        val short = first.norm.length < CLOSE_VERB_LENGTH
        if (short || products.knowsWord(first.norm)) return emptyList()
        return listOfNotNull(lexicon.verbs.closeAt(tokens, 0))
    }

    /**
     * "me salen" dice lo que rinde una receta solo si la frase empieza con lo que se usa: "con 1
     * kilo de cera me salen 8 velas". Sin eso es un costo: "las velas me salen a 5000".
     */
    private fun yieldOrCost(hit: PhraseHit<VerbKind>, clean: List<Token>): PhraseHit<VerbKind> {
        val yields = hit.value == VerbKind.RECIPE_YIELDS
        return if (yields &&
            clean[0].norm !in lexicon.yieldLeads
        ) {
            hit.copy(value = VerbKind.COST)
        } else {
            hit
        }
    }

    // Lo que se saca de la frase antes de partirla en líneas.

    /**
     * La categoría se busca en toda la frase, con los verbos, porque a veces el verbo la dice:
     * "recargué 20000".
     */
    private fun facts(body: List<Token>, clean: List<Token>): PhraseFacts {
        val total = findTotal(body)
        val payment = findPayment(total.rest)
        val party = findParty(payment.rest)
        val rest = party.rest
        return PhraseFacts(
            total = total.value,
            payment = payment.value,
            party = party.value,
            category = findCategory(clean),
            rest = rest,
            drafts = prepared(splitter.split(rest).map(lineReader::read)),
        )
    }

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
                result[result.lastIndex] =
                    previous.copy(quantity = draft.quantity, unitId = draft.unitId)
            } else {
                result.add(draft.copy(alternative = completed(draft, previous)))
            }
        }
        return result
    }

    private fun measuresPrevious(draft: LineDraft, previous: LineDraft): Boolean =
        draft.span.isEmpty() && draft.unitId != null && previous.span.isNotEmpty() &&
            previous.quantity == null

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
        return products.find(first.span, ANY_PRODUCT)?.match is ProductMatch.Unknown
    }

    private fun findTotal(tokens: List<Token>): Extracted<Long> =
        tokens.indices.firstNotNullOfOrNull { totalBefore(tokens, it) ?: totalAfter(tokens, it) }
            ?: Extracted(null, tokens)

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

    /**
     * "en efectivo", "con tarjeta": un nombre del catálogo después de "en" o "con", o un sinónimo
     * suelto.
     */
    private fun findPayment(tokens: List<Token>): Extracted<NameMatch> =
        tokens.indices.firstNotNullOfOrNull { paymentAt(tokens, it) } ?: Extracted(null, tokens)

    private fun paymentAt(tokens: List<Token>, index: Int): Extracted<NameMatch>? {
        val prepositioned = tokens[index].norm in lexicon.paymentPrepositions
        val afterPreposition = if (prepositioned) payments.matchAt(tokens, index + 1) else null
        val hit = afterPreposition ?: loosePayments.matchAt(tokens, index) ?: return null
        return Extracted(nameMatch(hit.ids), tokens.cut(index, hit.end))
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

    private fun unknownNameEnd(tokens: List<Token>, start: Int, preposition: String): Int? {
        if (preposition !in lexicon.unknownPartyPrepositions) return null
        var end = start
        while (end < tokens.size && end - start < MAX_NAME_WORDS && isNameWord(tokens[end])) end++
        val named = (start until end).any { tokens[it].norm !in lexicon.honorifics }
        return if (named) end else null
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
        val main = kinds.filterNot { it.subordinate }.ifEmpty { kinds }
        if (main.isEmpty()) {
            // Sin verbo y con una palabra desconocida delante, no se sabe qué pasó: se pide de
            // nuevo.
            if (leadingUnknown(facts.drafts)) return emptyList()
            val step = NO_VERB_STEPS.firstOrNull { holds(it, facts) } ?: return emptyList()
            return listOf(step.intent)
        }
        val strong = main.firstOrNull { it.joins.isEmpty() }?.intent
        return main.mapNotNull { kind -> resolve(kind, strong, facts) }.distinct()
    }

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

        WeakStep.CUSTOMER, WeakStep.BUYER -> facts.party?.customer != null

        WeakStep.SUPPLIER -> facts.party?.supplier != null
    }

    // Cada tipo de lectura.

    /** Venta, compra, producción o ajuste: líneas de productos. */
    private fun goods(
        intent: PhraseIntent,
        kinds: List<VerbKind>,
        facts: PhraseFacts,
        credit: Boolean,
    ): PhraseReading {
        val rule = RULES.getValue(intent)
        val drafts = if (leadingUnknown(facts.drafts)) facts.drafts.drop(1) else facts.drafts
        val lines = drafts.mapNotNull { resolveLine(it, rule) }
        val amount = facts.drafts.firstOrNull { it.span.isEmpty() && it.unitId == null }?.amount
        val onCredit = credit && intent in CREDIT_INTENTS
        val tail = facts.drafts.firstOrNull { it.tail.isNotEmpty() }?.tail
        val party = facts.party ?: tail?.let { PartyHit(null, null, it.spoken()) }
        return PhraseReading(
            intent = intent,
            reason = REASON_PRIORITY.firstOrNull { reason -> kinds.any { it.reason == reason } },
            lines = lines.map { it.line },
            payment = facts.payment ?: creditPayment(onCredit),
            party = partyFor(intent, party),
            total = (facts.total ?: amount)?.let(::money),
            credit = onCredit,
            issues = issues(facts.drafts, lines),
        )
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
        val tokens = facts.rest.filterNot { it.isSeparator || it.norm in lexicon.changeFillers }
        val draft = lineReader.read(tokens)
        val resolved = resolveLine(draft, RULES.getValue(intent))
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
    private fun recipe(clean: List<Token>, verbs: List<PhraseHit<VerbKind>>): PhraseReading {
        val yields = verbs.firstOrNull { it.value == VerbKind.RECIPE_YIELDS }
        if (yields != null) {
            // "con 1 kilo de cera me salen 8 velas": lo usado va antes, y lo que rinde, después.
            val used = segment(clean, verbs, 1, yields.start)
            return recipeOf(segment(clean, verbs, yields.end, clean.size), used)
        }
        val intro = verbs.firstOrNull { it.value == VerbKind.RECIPE_FOR }
        val uses = verbs.firstOrNull { it.value == VerbKind.RECIPE_USES }
        val targetStart = intro?.end ?: 0
        val usesAfter = uses?.start?.takeIf { it >= targetStart }
        val targetEnd = usesAfter ?: separatorFrom(clean, targetStart)
        val ingredientsStart = minOf(uses?.end ?: (targetEnd + 1), clean.size)
        val ingredientsEnd = intro?.start?.takeIf { it > ingredientsStart } ?: clean.size
        val targetTokens = segment(clean, verbs, targetStart, targetEnd)
            .dropWhile { it.norm in lexicon.toPrepositions }
        return recipeOf(targetTokens, segment(clean, verbs, ingredientsStart, ingredientsEnd))
    }

    private fun recipeOf(targetTokens: List<Token>, ingredientTokens: List<Token>): PhraseReading {
        val target = lineReader.read(targetTokens.filterNot { it.norm in lexicon.pronouns })
        val used = ingredientTokens.filterNot { it.norm in lexicon.pronouns }
        val ingredients = prepared(splitter.split(used).map(lineReader::read))
        val lines = ingredients.mapNotNull { resolveLine(it, ANY_PRODUCT) }
        return PhraseReading(
            intent = PhraseIntent.RECIPE,
            lines = lines.map { it.line },
            target = products.find(target.span, RECIPE_TARGET)?.match,
            targetQuantity = target.quantity?.let(::QuantityMilli),
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

    private fun separatorFrom(tokens: List<Token>, start: Int): Int =
        (start until tokens.size).firstOrNull { tokens[it].isSeparator } ?: tokens.size

    /**
     * Un producto nuevo: el nombre como se dijo, y el precio, el costo y la unidad si se dijeron.
     */
    private fun newProduct(clean: List<Token>, verbs: List<PhraseHit<VerbKind>>): PhraseReading {
        // Las palabras de precio y costo ("precio", "costo") quedan: dicen qué es cada número.
        val tokens = clean.without(verbs.filter { it.value == VerbKind.NEW_PRODUCT })
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
        val proposed = if (name.isEmpty()) null else ProductMatch.Unknown(products.spokenName(name))
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
        val number = numbers.read(tokens, hit.end) ?: return null
        return ProductAmount(hit.value, number)
    }

    // Piezas comunes.

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
        return ResolvedLine(line, resolution.issue, fits)
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
    )

    private data class ProductAmount(val isCost: Boolean, val number: NumberRead)

    internal companion object {
        const val ON_ACCOUNT = "on_account"
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
