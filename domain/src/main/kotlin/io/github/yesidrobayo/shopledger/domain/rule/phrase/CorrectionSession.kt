package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv

/**
 * Aplica una frase corta sobre una tarjeta, con las mismas piezas que usa el intérprete para leer
 * una frase entera: números, unidades, productos, pagos y personas. Ver `correctedBy`.
 */
internal class CorrectionSession(
    private val lexicon: PhraseLexicon,
    private val words: CorrectionVocabulary,
    style: NumberStyle,
    private val moneyDecimals: Int,
    catalog: PhraseCatalog,
) {
    private val numbers = NumberReader(lexicon, style)
    private val units = UnitIndex(catalog.units, lexicon)
    private val shaper = WordShaper(lexicon, numbers, units)
    private val products = ProductFinder(catalog.products, shaper, lexicon)
    private val lineReader = LineReader(lexicon, numbers, units, products)
    private val splitter = LineSplitter(lexicon, numbers, units, products)
    private val factsReader = CorrectionFactsReader(lexicon, numbers, shaper, catalog)
    private val productsById = catalog.products.associateBy { it.id }
    private val unitsById = catalog.units.associateBy { it.id }
    private val onAccount =
        catalog.paymentMethods.firstOrNull { it.systemKey == PhraseSession.ON_ACCOUNT }?.id

    fun correct(draft: PhraseDraft, text: String): PhraseDraft? {
        val tokens = PhraseTokenizer.tokenize(text).let { it.without(lexicon.fillers.findAll(it)) }
        val body = afterLeads(tokens)
        val removal = words.removals.matchAt(body, 0)
        if (removal != null) return removed(draft, body.subList(removal.end, body.size))
        val addition = words.additions.matchAt(body, 0)
        val separator = if (addition == null) lexicon.separators.matchAt(body, 0) else null
        val said = body.subList(addition?.end ?: separator?.end ?: 0, body.size)
        val shape = shapeOf(draft.intent)
        // Un precio o un costo dicho con su verbo ("vale 600") corrige la tarjeta de un precio o
        // de un costo; en las demás, esas palabras se quedan, porque "ahora a 600" es un precio.
        val verbs = lexicon.verbs.findAll(said).filter {
            it.value.intent != null && (!it.value.subordinate || shape == Shape.SINGLE_LINE)
        }
        val rest = said.without(verbs).filterNot { it.norm in lexicon.pronouns }
        // Un verbo de operación con productos es otra frase, no una corrección.
        if (verbs.any { !it.value.subordinate } && namesProducts(rest)) return null
        val facts = factsReader.read(draft.intent, rest)
        val base = withFacts(draft, facts) ?: return null
        val content = facts.rest
        if (shaper.shape(content.without(words.copulas.findAll(content))).words.isEmpty()) {
            return base.takeIf { facts.any }
        }
        return when {
            addition != null -> added(base, content, loose = false)
            separator != null -> added(base, content, loose = true)
            else -> changed(base, content.filterNot(Token::isSeparator), shape)
        }
    }

    private fun afterLeads(tokens: List<Token>): List<Token> {
        var index = 0
        while (index < tokens.size) {
            val lead = words.leads.matchAt(tokens, index)?.end
            index = if (tokens[index].isSeparator) index + 1 else lead ?: break
        }
        return tokens.subList(index, tokens.size)
    }

    private fun namesProducts(tokens: List<Token>): Boolean =
        splitter.split(tokens).any { line -> products.names(lineReader.read(line).span) { true } }

    // Pago, crédito, persona, categoría y total.

    private fun withFacts(draft: PhraseDraft, facts: CorrectionFacts): PhraseDraft? {
        val intent = draft.intent
        val misplaced = (facts.credit && intent !in CREDIT_INTENTS) ||
            (facts.payment != null && intent !in PAYMENT_INTENTS) ||
            (facts.total != null && intent !in TOTAL_INTENTS)
        if (misplaced) return null
        // Varios métodos o varias personas igual de posibles dejan el hueco: no se adivina.
        val paymentId = when {
            facts.payment != null -> facts.payment.ids.singleOrNull()
            facts.credit -> onAccount ?: draft.paymentMethodId
            else -> draft.paymentMethodId
        }
        val credit = when {
            facts.credit -> true
            facts.payment == null -> draft.credit
            else -> intent in CREDIT_INTENTS && paymentId != null && paymentId == onAccount
        }
        return draft.copy(
            paymentMethodId = paymentId,
            credit = credit,
            partyId = if (facts.party == null) draft.partyId else facts.party.ids.singleOrNull(),
            categoryId = if (facts.category == null) {
                draft.categoryId
            } else {
                facts.category.ids.singleOrNull()
            },
            total = facts.total?.let(::money) ?: draft.total,
        )
    }

    // Líneas que salen o se suman.

    private fun removed(draft: PhraseDraft, tokens: List<Token>): PhraseDraft? {
        if (shapeOf(draft.intent) != Shape.LINES) return null
        val index = referred(draft.lines, tokens, toHoles = true).singleOrNull() ?: return null
        return draft.copy(lines = draft.lines.filterIndexed { position, _ -> position != index })
    }

    /**
     * Las líneas nuevas. Después de "y" o "también" cada una debe nombrar un producto del catálogo
     * o decir cuántos: así "y la cuenta" no se vuelve un producto nuevo.
     */
    private fun added(draft: PhraseDraft, tokens: List<Token>, loose: Boolean): PhraseDraft? {
        if (shapeOf(draft.intent) != Shape.LINES) return null
        val rule = ruleOf(draft.intent)
        val lines = splitter.split(tokens).map { tokensOfLine ->
            val line = lineReader.read(tokensOfLine)
            if (!named(line.span)) return null
            val match = matchOf(line.span, rule)
            val unknown = match is ProductMatch.Unknown && line.quantity == null
            if (line.unreadable || (loose && unknown)) return null
            val product = targetOf(match, draft.intent)
            if (!fits(product, line.unitId)) return null
            DraftLine(
                product = product,
                quantity = line.quantity?.let(::QuantityMilli),
                unitId = line.unitId,
                unitPrice = line.unitPrice?.let(::money),
                lineTotal = line.lineTotal?.let(::money),
            )
        }
        return draft.copy(lines = draft.lines + lines)
    }

    // Lo que cambia en una línea, en el monto o en el producto nuevo.

    private fun changed(draft: PhraseDraft, tokens: List<Token>, shape: Shape): PhraseDraft? {
        val copula = words.copulas.findAll(tokens).firstOrNull()
        val ref = if (copula == null) emptyList() else tokens.subList(0, copula.start)
        val spec = lineReader.read(
            priceFirst(if (copula == null) tokens else tokens.subList(copula.end, tokens.size)),
        )
        if (spec.unreadable) return null
        return when (shape) {
            Shape.LINES -> lineChange(draft, ref, spec, said(spec))
            Shape.SINGLE_LINE -> lineChange(draft, ref, spec, price(spec))
            Shape.AMOUNT -> amountChange(draft, ref, spec)
            Shape.NEW_PRODUCT -> newProductChange(draft, tokens.without(listOfNotNull(copula)))
        }
    }

    /**
     * "a 6 cada": un precio al principio de la corrección. La línea salta los artículos antes de la
     * cantidad, y en portugués "a" es artículo y también marca un precio; una coma delante deja la
     * marca donde la línea la busca.
     */
    private fun priceFirst(tokens: List<Token>): List<Token> {
        val marker = lexicon.amountMarkers.matchAt(tokens, 0) ?: return tokens
        return if (numbers.startsAt(
                tokens,
                marker.end,
            )
        ) {
            listOf(Token.separator) + tokens
        } else {
            tokens
        }
    }

    private fun said(spec: LineDraft) = Said(
        quantity = spec.quantity?.let(::QuantityMilli),
        unitId = spec.unitId,
        unitPrice = spec.unitPrice?.let(::money),
        lineTotal = spec.lineTotal?.let(::money),
    )

    /** En un precio o un costo nuevo, el número dicho es el monto, aunque vaya sin "a". */
    private fun price(spec: LineDraft) = Said(
        quantity = null,
        unitId = spec.unitId,
        unitPrice = (spec.unitPrice ?: spec.lineTotal ?: spec.quantity)?.let(::money),
        lineTotal = null,
    )

    private fun lineChange(
        draft: PhraseDraft,
        ref: List<Token>,
        spec: LineDraft,
        value: Said,
    ): PhraseDraft? {
        val name = spec.span.filterNot { it.norm in lexicon.pronounHeads }
        val rename = if (named(name)) name else emptyList()
        if (rename.isEmpty() && !value.any) return null
        val aim = aim(draft.lines, if (named(ref)) ref else emptyList(), rename, value.any)
            ?: return null
        val lines = draft.lines.toMutableList()
        val rule = ruleOf(draft.intent)
        if (aim.indices.size == 1) {
            val index = aim.indices[0]
            lines[index] = changedLine(lines[index], aim, value, rule, draft.intent) ?: return null
        } else {
            // Varias líneas pueden ser: lo que se corrigió queda en hueco en todas.
            for (index in aim.indices) lines[index] = holed(lines[index], aim, value)
        }
        return draft.copy(lines = lines)
    }

    /**
     * A qué líneas apunta la corrección. Lo nombrado antes de "era" las elige; si no, el producto
     * dicho junto a un valor ("eran 3 panes") elige la línea de ese producto; y si nada elige, es la
     * única o la última línea, la última que se dijo.
     */
    private fun aim(
        lines: List<DraftLine>,
        ref: List<Token>,
        rename: List<Token>,
        valued: Boolean,
    ): Aim? {
        if (ref.isNotEmpty()) {
            val hits = referred(lines, ref, toHoles = true)
            return if (hits.isEmpty()) null else Aim(hits, ref, rename)
        }
        val byName = if (valued && rename.isNotEmpty()) {
            referred(lines, rename, toHoles = false)
        } else {
            emptyList()
        }
        return when {
            byName.isNotEmpty() -> Aim(byName, emptyList(), emptyList())
            lines.isEmpty() -> null
            else -> Aim(listOf(lines.lastIndex), emptyList(), rename)
        }
    }

    /**
     * Las líneas cuyo producto nombran [tokens]. Si nombran productos del catálogo que no están en
     * ninguna línea, y [toHoles], las líneas que todavía no tienen producto: "la harina" es la línea
     * en que se pidió elegir entre las harinas.
     */
    private fun referred(lines: List<DraftLine>, tokens: List<Token>, toHoles: Boolean): List<Int> {
        val ids = when (val match = matchOf(tokens, ANY_PRODUCT)) {
            is ProductMatch.Found -> listOf(match.id)
            is ProductMatch.Choose -> match.ids
            is ProductMatch.Unknown -> emptyList()
        }
        val spoken = shaper.shape(tokens).texts
        val named = lines.indices.filter { names(lines[it].product, ids, spoken) }
        if (named.isNotEmpty() || ids.isEmpty() || !toHoles) return named
        return lines.indices.filter { lines[it].product == null }
    }

    private fun names(product: DraftTarget?, ids: List<Long>, spoken: List<String>): Boolean =
        when (product) {
            is DraftTarget.Existing -> product.productId in ids

            is DraftTarget.New -> spoken.isNotEmpty() &&
                PhraseWords.cover(spoken, shapedName(product.name)).matched == spoken.size

            null -> false
        }

    private fun changedLine(
        line: DraftLine,
        aim: Aim,
        value: Said,
        rule: ProductRule,
        intent: PhraseIntent,
    ): DraftLine? {
        val product = if (aim.rename.isEmpty()) {
            line.product
        } else {
            val match = renamed(line.product, aim, rule) ?: return null
            targetOf(match, intent)
        }
        // La unidad dicha debe servirle al producto; la que ya tenía se olvida si no le sirve.
        val unitId = value.unitId ?: line.unitId
        val fits = fits(product, unitId)
        if (!fits && value.unitId != null) return null
        val kept = unitId.takeIf { fits }
        // Un precio dicho antes era de otro producto o de otra unidad: registraría un monto
        // equivocado. Solo queda el que diga la misma corrección.
        val stale = product != line.product || unitOf(product, kept) != unitOf(product, line.unitId)
        val priced = if (stale) unpriced(line) else line
        return valued(priced, value, product, value.quantity ?: line.quantity, kept)
    }

    private fun holed(line: DraftLine, aim: Aim, value: Said): DraftLine {
        val renamed = aim.rename.isNotEmpty()
        return valued(
            if (renamed) unpriced(line) else line,
            value,
            product = line.product.takeUnless { renamed },
            quantity = line.quantity.takeIf { value.quantity == null },
            unitId = line.unitId,
        )
    }

    /** La unidad en que va la línea: la dicha o, si no se dijo, la base del producto. */
    private fun unitOf(product: DraftTarget?, unitId: Long?): Long? {
        if (unitId != null) return unitId
        val existing = product as? DraftTarget.Existing ?: return null
        return productsById[existing.productId]?.baseUnitId
    }

    private fun unpriced(line: DraftLine): DraftLine = line.copy(unitPrice = null, lineTotal = null)

    /** Un precio por unidad reemplaza al total de la línea, y al revés. */
    private fun valued(
        line: DraftLine,
        value: Said,
        product: DraftTarget?,
        quantity: QuantityMilli?,
        unitId: Long?,
    ) = DraftLine(
        product = product,
        quantity = quantity,
        unitId = unitId,
        unitPrice = value.unitPrice ?: line.unitPrice.takeIf { value.lineTotal == null },
        lineTotal = value.lineTotal ?: line.lineTotal.takeIf { value.unitPrice == null },
    )

    /**
     * El producto nuevo de una línea: lo nombrado con lo que era ("la harina" + "de maíz"), lo dicho
     * solo ("la gaseosa grande") o la cabeza del producto que tenía con lo dicho ("Gaseosa" +
     * "grande", de "era la grande"). Gana el primero que da con algo del catálogo; si ninguno da,
     * no se entendió.
     */
    private fun renamed(product: DraftTarget?, aim: Aim, rule: ProductRule): ProductMatch? {
        val probes = listOfNotNull(
            (aim.ref + aim.rename).takeIf { aim.ref.isNotEmpty() },
            aim.rename,
            head(product)?.plus(aim.rename),
        )
        return probes.firstNotNullOfOrNull { probe ->
            matchOf(probe, rule).takeUnless { it is ProductMatch.Unknown }
        }
    }

    /** Las palabras del nombre antes de "de", sin su medida: "Gaseosa" de "Gaseosa 400 ml". */
    private fun head(product: DraftTarget?): List<Token>? {
        val name = when (product) {
            is DraftTarget.Existing -> productsById[product.productId]?.name
            is DraftTarget.New -> product.name
            null -> null
        } ?: return null
        return PhraseTokenizer.tokenize(name)
            .takeWhile { it.norm !in lexicon.nameConnectors }
            .filterNot { it.looksNumeric() || units.at(listOf(it), 0, afterNumber = true) != null }
    }

    private fun amountChange(draft: PhraseDraft, ref: List<Token>, spec: LineDraft): PhraseDraft? {
        val amount = spec.amount ?: return null
        if (named(ref) || named(spec.span)) return null
        return draft.copy(total = money(amount))
    }

    /** "a 600" o "600" es el precio; "costo 400", el costo. */
    private fun newProductChange(draft: PhraseDraft, tokens: List<Token>): PhraseDraft? {
        val clean = tokens.dropWhile { it.norm in lexicon.articles }
        val marker = lexicon.productAmounts.matchAt(clean, 0)
        val amount = numbers.read(clean, marker?.end ?: 0) ?: return null
        val money = money(amount.milli)
        return if (marker?.value == true) draft.copy(cost = money) else draft.copy(price = money)
    }

    // Piezas comunes.

    private fun targetOf(match: ProductMatch, intent: PhraseIntent): DraftTarget? = when (match) {
        is ProductMatch.Found -> DraftTarget.Existing(match.id)

        is ProductMatch.Choose -> null

        is ProductMatch.Unknown ->
            if (intent in NEW_TARGET_INTENTS) DraftTarget.New(match.spokenName) else null
    }

    /** Si el producto admite la unidad. Lo que no está en el catálogo no se puede revisar. */
    private fun fits(product: DraftTarget?, unitId: Long?): Boolean {
        val existing = product as? DraftTarget.Existing ?: return true
        val unit = unitsById[unitId ?: return true] ?: return true
        val item = productsById[existing.productId] ?: return true
        return units.admits(item, unit)
    }

    /** El producto que nombran [tokens]; si no nombran nada, como "el", uno desconocido. */
    private fun matchOf(tokens: List<Token>, rule: ProductRule): ProductMatch {
        val resolution = products.find(tokens, rule) ?: return ProductMatch.Unknown("")
        return resolution.match
    }

    private fun named(tokens: List<Token>): Boolean = shaper.shape(tokens).words.isNotEmpty()

    private fun shapedName(name: String): List<String> =
        shaper.shape(PhraseTokenizer.tokenize(name)).texts

    private fun ruleOf(intent: PhraseIntent): ProductRule =
        PhraseSession.RULES[intent] ?: PhraseSession.ANY_PRODUCT

    /**
     * Milésimas de la unidad mayor a la unidad menor de la moneda, como en el intérprete.
     */
    private fun money(milli: Long): Money =
        Money(roundDiv(milli * PhraseSession.POWERS_OF_TEN[moneyDecimals], Scale.MILLI))

    /** Lo que la corrección dice de una línea. */
    private data class Said(
        val quantity: QuantityMilli?,
        val unitId: Long?,
        val unitPrice: Money?,
        val lineTotal: Money?,
    ) {
        val any: Boolean
            get() = quantity != null || unitId != null || unitPrice != null || lineTotal != null
    }

    /**
     * Las líneas a corregir, lo que las nombró antes de "era" ([ref]) y lo que se dijo como nuevo
     * producto ([rename]), vacío si el producto no cambia.
     */
    private data class Aim(val indices: List<Int>, val ref: List<Token>, val rename: List<Token>)

    /** Cómo se corrige cada operación. */
    private enum class Shape {
        /** Varias líneas de productos: venta, compra, producción, ajuste y receta. */
        LINES,

        /** Una sola línea con su monto: costo o precio nuevo. */
        SINGLE_LINE,

        /** Un monto sin líneas: gasto o pago. */
        AMOUNT,

        /** Un producto que se crea. */
        NEW_PRODUCT,
    }

    private companion object {
        val ANY_PRODUCT = PhraseSession.ANY_PRODUCT
        val CREDIT_INTENTS = PhraseSession.CREDIT_INTENTS
        val PAYMENT_INTENTS = setOf(
            PhraseIntent.SALE,
            PhraseIntent.PURCHASE,
            PhraseIntent.EXPENSE,
            PhraseIntent.CUSTOMER_PAYMENT,
            PhraseIntent.SUPPLIER_PAYMENT,
        )
        val TOTAL_INTENTS = PAYMENT_INTENTS

        /** Donde un producto que no está se propone para crearlo, como al leer la frase. */
        val NEW_TARGET_INTENTS =
            setOf(PhraseIntent.SALE, PhraseIntent.PURCHASE, PhraseIntent.RECIPE)

        fun shapeOf(intent: PhraseIntent): Shape = when (intent) {
            PhraseIntent.SALE,
            PhraseIntent.PURCHASE,
            PhraseIntent.PRODUCTION,
            PhraseIntent.STOCK_ADJUSTMENT,
            PhraseIntent.RECIPE,
            -> Shape.LINES

            PhraseIntent.COST_CORRECTION, PhraseIntent.PRICE_CHANGE -> Shape.SINGLE_LINE

            PhraseIntent.EXPENSE,
            PhraseIntent.CUSTOMER_PAYMENT,
            PhraseIntent.SUPPLIER_PAYMENT,
            -> Shape.AMOUNT

            PhraseIntent.NEW_PRODUCT -> Shape.NEW_PRODUCT
        }
    }
}
