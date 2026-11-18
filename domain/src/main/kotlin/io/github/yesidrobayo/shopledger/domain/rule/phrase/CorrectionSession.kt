package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.phrase.CardFollowUp
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
import io.github.yesidrobayo.shopledger.domain.model.phrase.NumberStyle
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.rule.roundDiv

/**
 * Aplica una frase corta sobre una tarjeta, con las mismas piezas que usa el intérprete para leer
 * una frase entera: números, unidades, productos, pagos y personas. Ver `correctedBy`.
 */
internal class CorrectionSession(
    private val lexicon: PhraseLexicon,
    private val words: CorrectionVocabulary,
    private val style: NumberStyle,
    private val moneyDecimals: Int,
    private val catalog: PhraseCatalog,
) {
    private val numbers = NumberReader(lexicon, style, cents = moneyDecimals > 0)
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
    private val traitReader = TraitReader(lexicon, numbers, shaper, catalog.productCategories)

    fun correct(draft: PhraseDraft, text: String): PhraseDraft? {
        val tokens = PhraseTokenizer.tokenize(text).let { it.without(lexicon.fillers.findAll(it)) }
        val body = afterLeads(tokens)
        return inputsSaid(draft, body) ?: correctBody(draft, body, led = body.size < tokens.size)
    }

    /**
     * Lo que lleva lo que se produce, dicho con la tarjeta de una producción de un producto abierta:
     * "lleva 200 g de harina y 3 huevos" o "el pastel lleva…", "con 250 g de harina", y sin verbo,
     * "200 g de harina y 3 huevos", si lo que se produce no tiene receta o ya se dijo algo que lleva.
     * Lo dicho de un insumo que ya está corrige su cantidad, como en una receta, salvo con "otros",
     * que la suma. Un insumo nuevo se agrega al final.
     *
     * `null` si no son insumos de esta producción: otra tarjeta, otro verbo, otro producto antes de
     * "lleva", lo producido nombrado ("eran 3 pasteles"), o una palabra que no es un producto del
     * catálogo ni dice cuánto.
     */
    private fun inputsSaid(draft: PhraseDraft, body: List<Token>): PhraseDraft? {
        if (draft.intent != PhraseIntent.PRODUCTION) return null
        // Lo producido puede ser del catálogo, uno nuevo que se crea al registrar, o uno por elegir.
        val madeProduct = (draft.lines.singleOrNull() ?: return null).product
        val made = (madeProduct as? DraftTarget.Existing)?.productId
        // "agrega 2 huevos", "y 3 huevos": lo que abre una línea más no es parte de lo dicho.
        val start = body.indexOfFirst { !it.isSeparator }.coerceAtLeast(0)
        val spoken = body.subList(words.additions.matchAt(body, start)?.end ?: start, body.size)
        val verbs = lexicon.verbs.findAll(spoken)
        val uses = verbs.filter { it.value == VerbKind.RECIPE_USES }
        if (uses.size != verbs.size) return null
        val opened = when (val first = uses.firstOrNull()) {
            null -> {
                val led = spoken.firstOrNull()?.norm in lexicon.yieldLeads
                val open = led || draft.inputs.isNotEmpty() ||
                    made?.let(productsById::get)?.hasRecipe != true
                if (!open) return null
                spoken.subList(if (led) 1 else 0, spoken.size)
            }

            else -> {
                // "it takes", "se usa": un pronombre antes del verbo no nombra otro producto.
                val before = spoken.subList(0, first.start)
                    .filterNot { it.isSeparator || it.norm in lexicon.pronouns }
                val ids = idsOf(matchOf(before, ANY_PRODUCT))
                if (named(before) && !names(madeProduct, ids, shaper.shape(before).texts)) {
                    return null
                }
                (first.end until spoken.size)
                    .filter { index -> uses.none { index >= it.start && index < it.end } }
                    .map { spoken[it] }
            }
        }
        val others = words.others.findAll(opened)
        val said = opened.without(others)
        val copula = words.copulas.matchAt(said, 0)?.end ?: 0
        val session = PhraseSession(lexicon, style, moneyDecimals, catalog)
        val lines = session.inputsOf(said.subList(copula, said.size))
        val fits = lines.isNotEmpty() && lines.all { line ->
            val product = line.product
            val unknown = product is ProductMatch.Unknown && line.quantity == null
            !unknown && made !in idsOf(product)
        }
        if (!fits) return null
        val inputs = PhraseReading(intent = PhraseIntent.PRODUCTION, inputs = lines)
            .draftAs(PhraseIntent.PRODUCTION)
            .inputs
        return draft.copy(
            inputs = joinedLines(draft.inputs, inputs, others.isNotEmpty(), ::unitOfLine),
        )
    }

    /**
     * Lo que hace con [draft] la frase [text], que no se aplicó como corrección, leída entera en
     * [next]. Ver `followedBy`.
     */
    fun follow(draft: PhraseDraft, text: String, next: PhraseReading): CardFollowUp {
        val tokens = PhraseTokenizer.tokenize(text).let { it.without(lexicon.fillers.findAll(it)) }
        val body = afterLeads(tokens)
        // "y vendí otros 2 panes": "otros" solo dice que se suma, y no es parte del producto.
        val others = words.others.findAll(body)
        val read = if (others.isEmpty()) {
            next
        } else {
            PhraseSession(lexicon, style, moneyDecimals, catalog)
                .read(body.without(others).joinToString(" ") { it.raw })
        }
        val stated = lexicon.verbs.findAll(body).any { it.value.intent != null }
        if (!stated) return unstated(draft, body, read)
        val said = read.toDraft() ?: return CardFollowUp.NotUnderstood
        return when {
            sameOperation(draft, said) ->
                CardFollowUp.Joined(draft.joinedWith(said, sums(draft, body), ::unitOfLine))

            body.size < tokens.size -> CardFollowUp.Replaced(said)

            else -> CardFollowUp.OtherOperation(said)
        }
    }

    /**
     * Una frase sin verbo nunca es otra operación. Si dice el tipo o la categoría de un producto
     * que ya existe, es de ese producto. Si no, lo que nombra se lee como de la operación de la
     * tarjeta y se suma a ella; la persona leída solo sirve si la lectura era de esa operación,
     * porque un cliente no es un proveedor.
     */
    private fun unstated(draft: PhraseDraft, body: List<Token>, next: PhraseReading): CardFollowUp {
        val scan = traitReader.scan(body)
        if (scan.traits.isNotEmpty()) return known(draft, scan.rest)
        if (next.lines.isEmpty() || shapeOf(draft.intent) != Shape.LINES) {
            return CardFollowUp.NotUnderstood
        }
        val inherited = next.copy(
            reason = draft.reason,
            party = next.party.takeIf { next.intent == draft.intent },
        ).draftAs(draft.intent)
        return CardFollowUp.Joined(draft.joinedWith(inherited, sums(draft, body), ::unitOfLine))
    }

    /**
     * El producto del catálogo del que se dice el tipo o la categoría: el que se nombra antes del
     * número o de "es", o sin nombre, el último de la tarjeta. Entre varios que se llaman parecido,
     * el que está en la tarjeta; si sigue habiendo varios, o ninguno, no se entendió.
     */
    private fun known(draft: PhraseDraft, rest: List<Token>): CardFollowUp {
        val ref = rest.filterNot { it.isSeparator }
        val number = ref.indices.firstOrNull { numbers.startsAt(ref, it) } ?: ref.size
        val copula = words.copulas.findAll(ref).firstOrNull()?.start ?: ref.size
        val name = ref.subList(0, minOf(number, copula))
        val onCard = (draft.lines.map { it.product } + draft.target)
            .filterIsInstance<DraftTarget.Existing>()
            .map { it.productId }
        val ids = if (named(name)) idsOf(matchOf(name, ANY_PRODUCT)) else onCard.takeLast(1)
        val id = ids.filter { it in onCard }.ifEmpty { ids }.singleOrNull()
            ?: return CardFollowUp.NotUnderstood
        return CardFollowUp.KnownProduct(id)
    }

    /**
     * Si [said] es la misma operación que la tarjeta y se puede sumar a ella: una de las que tienen
     * líneas, con el mismo motivo si es un ajuste y, en una receta, del mismo producto.
     */
    private fun sameOperation(card: PhraseDraft, said: PhraseDraft): Boolean =
        card.intent == said.intent && card.reason == said.reason &&
            shapeOf(card.intent) == Shape.LINES &&
            (said.target == null || sameProduct(card.target, said.target))

    /**
     * Si lo dicho de un producto que ya está en la tarjeta se suma. En un conteo o en una receta,
     * lo dicho es lo que hay o lo que lleva, y corrige, salvo que diga "otros". En las demás, se
     * suma si se agrega con "y", "también", "agrega" u "otros"; si no, la frase repite lo dicho y
     * corrige.
     */
    private fun sums(draft: PhraseDraft, body: List<Token>): Boolean {
        val others = words.others.findAll(body).isNotEmpty()
        val adds = words.additions.findAll(body).isNotEmpty() ||
            lexicon.separators.matchAt(body, 0) != null
        return others || (adds && !restates(draft))
    }

    private fun idsOf(match: ProductMatch): List<Long> = when (match) {
        is ProductMatch.Found -> listOf(match.id)
        is ProductMatch.Choose -> match.ids
        is ProductMatch.Unknown -> emptyList()
    }

    /** La unidad en que va la línea: la dicha o, si no se dijo, la base de su producto. */
    private fun unitOfLine(line: DraftLine): Long? = unitOf(line.product, line.unitId)

    /**
     * Lo que dice [body], ya sin "no" ni "perdón" delante; [led] dice si lo traía. Primero lo que
     * solo una corrección dice: el nombre nuevo del producto que se crea, lo que es ese producto y
     * la operación que era. Después, lo de siempre: quitar, agregar o cambiar.
     */
    private fun correctBody(draft: PhraseDraft, body: List<Token>, led: Boolean): PhraseDraft? {
        val rename = words.renames.matchAt(body, 0)
        if (rename != null) return renamedNew(draft, body.subList(rename.end, body.size))
        // En un gasto o un pago, "la categoría" es la del gasto: no se busca un producto nuevo.
        val scan = if (shapeOf(draft.intent) in DESCRIBED) traitReader.scan(body) else null
        if (scan != null && scan.traits.isNotEmpty()) return described(draft, scan)
        val operation = operationAt(body)
        if (operation != null) {
            val switched = switchedTo(draft, operation.value) ?: return null
            val rest = body.subList(operation.end, body.size)
            return if (rest.isEmpty()) switched else correctBody(switched, rest, led)
        }
        val removal = words.removals.matchAt(body, 0)
        if (removal != null) return removed(draft, body.subList(removal.end, body.size))
        val addition = words.additions.matchAt(body, 0)
        val separator = if (addition == null) lexicon.separators.matchAt(body, 0) else null
        val opened = body.subList(addition?.end ?: separator?.end ?: 0, body.size)
        // "y otros 2 panes", "2 panes más": lo nombrado se suma al que ya estaba.
        val others = words.others.findAll(opened)
        val said = opened.without(others)
        val shape = shapeOf(draft.intent)
        // Un precio o un costo dicho con su verbo ("vale 600") corrige la tarjeta de un precio o
        // de un costo; en las demás, esas palabras se quedan, porque "ahora a 600" es un precio.
        val verbs = lexicon.verbs.findAll(said).filter {
            it.value.intent != null && (!it.value.subordinate || shape == Shape.SINGLE_LINE)
        }
        // Los pronombres quedan para leer el pago y el crédito ("I still owe it") y salen de lo
        // que nombra líneas.
        val spoken = said.without(verbs)
        val rest = spoken.filterNot { it.norm in lexicon.pronouns }
        val main = verbs.filterNot { it.value.subordinate }
        // Un verbo de operación con productos es otra frase, no una corrección; salvo después de
        // un "no" y con un solo producto: "no, vendí 3 panes" corrige la línea del pan.
        val oneLine = led && splitter.split(rest).size == 1
        if (main.isNotEmpty() && namesProducts(rest) && !oneLine) return null
        // "no, compré": la operación era otra, y lo demás queda. Solo entre venta y compra; en
        // otra operación el verbo se salta, como siempre.
        val switchTo = main.mapNotNull { SWITCHING_VERBS[it.value] }.distinct().singleOrNull()
        // Sin "no" delante, el verbo de otra operación es otra tarjeta, y se pregunta antes.
        if (!led && switchTo != null && switchTo != draft.intent) return null
        val switched =
            switchTo?.takeIf { draft.intent in SWITCHABLE }?.let { switchedTo(draft, it) }
        // "no, se vencieron": el motivo de un ajuste era otro.
        val reason = main.mapNotNull { it.value.reason }.distinct().singleOrNull()
            ?.takeIf { draft.intent == PhraseIntent.STOCK_ADJUSTMENT && it != draft.reason }
        val current = (switched ?: draft).let {
            if (reason ==
                null
            ) {
                it
            } else {
                it.copy(reason = reason)
            }
        }
        val facts = factsReader.read(current.intent, spoken)
        val base = withFacts(current, facts) ?: return null
        val content = facts.rest.filterNot { it.norm in lexicon.pronouns }
        if (shaper.shape(content.without(words.copulas.findAll(content))).words.isEmpty()) {
            return base.takeIf { facts.any || switched != null || reason != null }
        }
        val sums = others.isNotEmpty() || !restates(base)
        return when {
            addition != null -> added(base, content, loose = false, sums = sums)
            separator != null || others.isNotEmpty() -> added(base, content, loose = true, sums)
            else -> changed(base, content.filterNot(Token::isSeparator), shape, led)
        }
    }

    // El producto nuevo y la operación.

    /**
     * "se llama harina integral": el nombre nuevo del producto que se va a crear. Va al producto
     * nuevo de la tarjeta o, si hay varios en las líneas, al último que se dijo, con lo que ya se
     * sabía de él.
     */
    private fun renamedNew(draft: PhraseDraft, tokens: List<Token>): PhraseDraft? {
        if (!named(tokens)) return null
        val name = products.spokenName(tokens)
        val target = draft.target
        if (target is DraftTarget.New) return draft.copy(target = target.copy(name = name))
        val index = draft.lines.indexOfLast { it.product is DraftTarget.New }
        if (index < 0) return null
        val line = draft.lines[index]
        val product = line.product as DraftTarget.New
        val renamed = line.copy(product = product.copy(name = name))
        return draft.copy(
            lines = draft.lines.mapIndexed { at, old -> if (at == index) renamed else old },
        )
    }

    /**
     * "la harina es insumo", "es materia prima", "en la categoría bebidas": lo que es el producto
     * que se va a crear. Va al producto nuevo de la tarjeta, o a la línea nueva que se nombra; sin
     * nombrar ninguna, a la última línea nueva. Lo que no se dice de nuevo queda como estaba. Si
     * además dice un número ("y son 3 kilos"), eso se corrige también. Si agrega una línea
     * ("agrega 2 kilos de azúcar, es un insumo"), va a lo nuevo que agrega.
     */
    private fun described(draft: PhraseDraft, scan: TraitScan): PhraseDraft? {
        val said = scan.traits.fold(NewProductTraits.NONE) { all, trait -> all.with(trait.traits) }
        val opening =
            words.additions.matchAt(scan.rest, 0) ?: lexicon.separators.matchAt(scan.rest, 0)
        if (opening != null && named(scan.rest.subList(opening.end, scan.rest.size))) {
            val grown = correctBody(draft, scan.rest, led = false) ?: return null
            return grown.copy(
                lines = grown.lines.mapIndexed { index, line ->
                    val added = index >= draft.lines.size && line.product is DraftTarget.New
                    if (added) describedLine(line, said) else line
                },
            )
        }
        val target = draft.target
        val rest = scan.rest.filterNot { it.isSeparator }
        val withTraits = if (target is DraftTarget.New) {
            draft.copy(target = target.copy(traits = target.traits.with(said)))
        } else {
            val aimed = describedLines(draft.lines, rest).ifEmpty { return null }
            draft.copy(
                lines = draft.lines.mapIndexed { index, line ->
                    if (index in aimed) describedLine(line, said) else line
                },
            )
        }
        val counted = rest.indices.any { numbers.startsAt(rest, it) }
        return if (counted) correctBody(withTraits, scan.rest, led = false) else withTraits
    }

    /** Las líneas nuevas que nombra [ref], o la última nueva si no nombra nada. */
    private fun describedLines(lines: List<DraftLine>, ref: List<Token>): List<Int> {
        val fresh = lines.indices.filter { lines[it].product is DraftTarget.New }
        // El nombre va antes del número o de "son": "la harina, son 3 kilos".
        val number = ref.indices.firstOrNull { numbers.startsAt(ref, it) } ?: ref.size
        val copula = words.copulas.findAll(ref).firstOrNull()?.start ?: ref.size
        val name = ref.subList(0, minOf(number, copula))
        if (!named(name)) return listOfNotNull(fresh.lastOrNull())
        val spoken = shaper.shape(name).texts
        return fresh.filter { names(lines[it].product, emptyList(), spoken) }
    }

    private fun describedLine(line: DraftLine, said: NewProductTraits): DraftLine {
        val product = line.product as DraftTarget.New
        return line.copy(product = product.copy(traits = product.traits.with(said)))
    }

    /** "fue una compra", "era venta": la operación que era, al principio de lo dicho. */
    private fun operationAt(body: List<Token>): PhraseHit<PhraseIntent>? {
        var index = words.copulas.matchAt(body, 0)?.end ?: 0
        if (index < body.size && body[index].norm in lexicon.traitArticles) index++
        val hit = words.operations.matchAt(body, index) ?: return null
        return PhraseHit(0, hit.end, hit.value)
    }

    /**
     * La misma tarjeta como otra operación: una venta que era una compra, o un conteo que era una
     * venta. Las líneas, sus cantidades y precios, el pago y el total quedan; la persona no,
     * porque un cliente no es un proveedor. [intent] es siempre una venta o una compra, y solo se
     * cambia desde una operación que también se elige a mano en la tarjeta; desde otra, la frase
     * se lee como nueva.
     */
    private fun switchedTo(draft: PhraseDraft, intent: PhraseIntent): PhraseDraft? = when {
        intent == draft.intent -> draft
        draft.intent in CHOSEN_BY_HAND -> draft.asOperation(intent, reason = null)
        else -> null
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
        val total = facts.total?.let(::money) ?: draft.total
        return draft.copy(
            // Con una sola línea sin monto, "todo por" es el total de esa línea, como al leer la
            // frase entera.
            lines = if (facts.total == null) draft.lines else totaledLines(draft.lines, total),
            paymentMethodId = paymentId,
            credit = credit,
            partyId = if (facts.party == null) draft.partyId else facts.party.ids.singleOrNull(),
            categoryId = if (facts.category == null) {
                draft.categoryId
            } else {
                facts.category.ids.singleOrNull()
            },
            total = total,
        )
    }

    /**
     * Solo una venta o una compra tienen líneas y admiten un total: las demás operaciones con
     * líneas ya rechazaron el total en [withFacts].
     */
    private fun totaledLines(lines: List<DraftLine>, total: Money?): List<DraftLine> {
        val only = lines.singleOrNull() ?: return lines
        val unpriced = only.unitPrice == null && only.lineTotal == null
        return if (unpriced) listOf(only.copy(lineTotal = total)) else lines
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
    private fun added(
        draft: PhraseDraft,
        tokens: List<Token>,
        loose: Boolean,
        sums: Boolean,
    ): PhraseDraft? {
        if (shapeOf(draft.intent) != Shape.LINES) return null
        val rule = ruleOf(draft.intent)
        val lines = splitter.split(tokens).map { tokensOfLine ->
            val line = lineReader.read(tokensOfLine)
            if (!named(line.span)) return null
            val match = matchOf(line.span, rule)
            val unknown = match is ProductMatch.Unknown && line.quantity == null
            if (line.unreadable || (loose && unknown)) return null
            val product = targetOf(match, draft)
            if (!fits(product, line.unitId)) return null
            DraftLine(
                product = product,
                quantity = line.quantity?.let(::QuantityMilli),
                unitId = line.unitId,
                unitPrice = line.unitPrice?.let(::money),
                lineTotal = line.lineTotal?.let(::money),
                candidates = (match as? ProductMatch.Choose)?.ids.orEmpty(),
            )
        }
        return draft.copy(lines = joinedLines(draft.lines, lines, sums, ::unitOfLine))
    }

    // Lo que cambia en una línea, en el monto o en el producto nuevo.

    private fun changed(
        draft: PhraseDraft,
        tokens: List<Token>,
        shape: Shape,
        led: Boolean,
    ): PhraseDraft? {
        val copula = words.copulas.findAll(tokens).firstOrNull()
        val ref = if (copula == null) emptyList() else tokens.subList(0, copula.start)
        val spec = lineReader.read(
            priceFirst(if (copula == null) tokens else tokens.subList(copula.end, tokens.size)),
        )
        if (spec.unreadable) return null
        // Sin "no" ni "era", un producto del catálogo que no está en la tarjeta, con su cantidad o
        // su precio, es una línea más: "un kilo de harina" no cambia el queso por harina.
        val adds = !led && copula == null && shape == Shape.LINES && addsLine(draft.lines, spec)
        if (adds) return added(draft, tokens, loose = false, sums = false)
        return when (shape) {
            Shape.LINES -> lineChange(draft, ref, spec, said(spec), keepsPrice = false)
            Shape.SINGLE_LINE -> lineChange(draft, ref, spec, price(spec, draft), keepsPrice = true)
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

    /**
     * En un precio o un costo nuevo, el número dicho es el monto, aunque vaya sin "a", y con los
     * miles del que había si no los dice: ver [withThousands].
     */
    private fun price(spec: LineDraft, draft: PhraseDraft) = Said(
        quantity = null,
        unitId = spec.unitId,
        unitPrice = (spec.unitPrice ?: spec.lineTotal ?: spec.quantity)?.let { said ->
            withThousands(said, draft.lines.singleOrNull()?.unitPrice)
        },
        lineTotal = null,
    )

    /**
     * [keepsPrice]: en un precio o un costo nuevo, el monto dicho es del producto que se corrige,
     * aunque cambie: "no, el pan integral" sobre "el pan ahora vale 600" es el pan integral a 600.
     */
    private fun lineChange(
        draft: PhraseDraft,
        ref: List<Token>,
        spec: LineDraft,
        value: Said,
        keepsPrice: Boolean,
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
            lines[index] = changedLine(lines[index], aim, value, rule, draft, keepsPrice)
                ?: return null
        } else {
            // Varias líneas pueden ser: lo que se corrigió queda en hueco en todas.
            for (index in aim.indices) lines[index] = holed(lines[index], aim, value)
        }
        return draft.copy(lines = lines)
    }

    /**
     * Si [spec] nombra, con algo que registrar, un producto del catálogo que ninguna línea tiene ni
     * espera elegir: entonces es una línea nueva y no un cambio de la última.
     */
    private fun addsLine(lines: List<DraftLine>, spec: LineDraft): Boolean {
        val name = spec.span.filterNot { it.norm in lexicon.pronounHeads }
        if (!named(name) || !said(spec).any) return false
        val ids = idsOf(matchOf(name, ANY_PRODUCT))
        val waiting = lines.any { line ->
            line.product == null && line.candidates.any { it in ids }
        }
        return ids.isNotEmpty() && !waiting && referred(lines, name, toHoles = false).isEmpty()
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
        // "no, la harina de trigo": el producto nuevo va a la línea que se le parece, no a la
        // última. Si ninguna se parece, o varias, es la última, como siempre.
        val akin = if (valued) emptyList() else akin(lines, rename)
        return when {
            byName.isNotEmpty() -> Aim(byName, emptyList(), emptyList())
            akin.size == 1 -> Aim(akin, emptyList(), rename)
            lines.isEmpty() -> null
            else -> Aim(listOf(lines.lastIndex), emptyList(), rename)
        }
    }

    /**
     * Las líneas a las que se parece el producto dicho: las que esperan elegir si lo dicho es del
     * catálogo, las de un producto por crear cuyo nombre está entero en lo dicho ("Harina" en
     * "harina integral"), y las de un producto cuya cabeza está en lo dicho ("Harina de maíz" en
     * "harina de trigo").
     */
    private fun akin(lines: List<DraftLine>, rename: List<Token>): List<Int> {
        val spoken = shaper.shape(rename).texts
        val known = matchOf(rename, ANY_PRODUCT) !is ProductMatch.Unknown
        return lines.indices.filter { index ->
            when (val product = lines[index].product) {
                null -> known

                is DraftTarget.New -> covers(spoken, shapedName(product.name))

                is DraftTarget.Existing ->
                    covers(spoken, shaper.shape(head(product).orEmpty()).texts)
            }
        }
    }

    /** Si todas las palabras de [name], que no está vacío, están en [spoken]. */
    private fun covers(spoken: List<String>, name: List<String>): Boolean =
        name.isNotEmpty() && PhraseWords.cover(name, spoken).matched == name.size

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
        if (named.isNotEmpty()) return named
        // "without the coffee" con una línea de "Cup of coffee": el nombre de la línea lleva lo
        // dicho, aunque el catálogo prefiera otro café.
        val worded = lines.indices.filter { worded(lines[it].product, spoken) }
        if (worded.size == 1) return worded
        if (ids.isEmpty() || !toHoles) return named
        return lines.indices.filter { lines[it].product == null }
    }

    /** Si el nombre del producto que ya tiene la línea lleva todo lo dicho. */
    private fun worded(product: DraftTarget?, spoken: List<String>): Boolean {
        val existing = product as? DraftTarget.Existing ?: return false
        val name = (productsById[existing.productId] ?: return false).name
        return spoken.isNotEmpty() &&
            PhraseWords.cover(spoken, shapedName(name)).matched == spoken.size
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
        draft: PhraseDraft,
        keepsPrice: Boolean,
    ): DraftLine? {
        val chosen = aim.rename.takeIf { line.product == null && line.candidates.isNotEmpty() }
            ?.let { products.among(it, line.candidates) }
        val product = if (aim.rename.isEmpty()) {
            line.product
        } else if (chosen is ProductMatch.Found) {
            // "las grandes": elige entre los productos por los que se preguntó.
            DraftTarget.Existing(chosen.id)
        } else {
            // Un producto que podría ser varios deja la línea en hueco, para elegir.
            val match = renamed(line.product, aim, rule)
            if (match != null) {
                targetOf(match, draft)
            } else {
                extendedNew(line.product, aim) ?: return null
            }
        }
        // La unidad dicha debe servirle al producto; la que ya tenía se olvida si no le sirve.
        val unitId = value.unitId ?: line.unitId
        val fits = fits(product, unitId)
        if (!fits && value.unitId != null) return null
        val kept = unitId.takeIf { fits }
        // Un precio dicho antes era de otro producto o de otra unidad: registraría un monto
        // equivocado. Solo queda el que diga la misma corrección.
        val stale = !keepsPrice &&
            (product != line.product || unitOf(product, kept) != unitOf(product, line.unitId))
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
        // Con el producto elegido, ya no hay entre qué elegir.
        candidates = line.candidates.takeIf { product == null }.orEmpty(),
    )

    /**
     * Si lo dicho no da con nada del catálogo, un producto por crear con el nombre dicho, si ese
     * nombre lleva entero el que tenía: "Harina" pasa a "Harina integral" con lo que ya se sabía de
     * ella. Si no, `null`: no se entendió.
     */
    private fun extendedNew(product: DraftTarget?, aim: Aim): DraftTarget.New? {
        val fresh = product as? DraftTarget.New ?: return null
        val spoken = shaper.shape(aim.rename).texts
        if (!covers(spoken, shapedName(fresh.name))) return null
        return fresh.copy(name = products.spokenName(aim.rename))
    }

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
        return draft.copy(total = withThousands(amount, draft.total))
    }

    /**
     * "no, 90" sobre un gasto de 80 mil: se corrige el número que se dijo, y los miles se dan por
     * dichos, como en la conversación. Solo si lo dicho es menor que mil, el monto que había es
     * de miles y con los miles queda cerca de él, a menos de diez veces; si no, vale como se dijo.
     */
    private fun withThousands(milli: Long, before: Money?): Money {
        val said = money(milli)
        val previous = before ?: return said
        val thousands = money(milli * THOUSAND)
        val small = milli < THOUSAND * Scale.MILLI && milli % Scale.MILLI == 0L
        val near =
            previous.minor >= thousands.minor / NEAR && previous.minor <= thousands.minor * NEAR
        return if (small && previous >= money(THOUSAND * Scale.MILLI) && near) thousands else said
    }

    /** "a 600" o "600" es el precio; "costo 400", el costo. */
    private fun newProductChange(draft: PhraseDraft, tokens: List<Token>): PhraseDraft? {
        val clean = tokens.dropWhile { it.norm in lexicon.articles }
        val marker = lexicon.productAmounts.matchAt(clean, 0)
        // "o custo é 6": el "é" que sigue a la marca no es parte del monto.
        val said = clean.drop(marker?.end ?: 0).dropWhile { it.norm in lexicon.changeFillers }
        val amount = numbers.read(said, 0) ?: return null
        val money = money(amount.milli)
        return if (marker?.value == true) draft.copy(cost = money) else draft.copy(price = money)
    }

    // Piezas comunes.

    /** El producto de una línea de [draft]; uno que no está es nuevo donde se crea al registrar. */
    private fun targetOf(match: ProductMatch, draft: PhraseDraft): DraftTarget? = when (match) {
        is ProductMatch.Found -> DraftTarget.Existing(match.id)

        is ProductMatch.Choose -> null

        is ProductMatch.Unknown ->
            if (draft.createsLineProducts()) DraftTarget.New(match.spokenName) else null
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
        /** Mil: los miles que una corrección da por dichos. */
        const val THOUSAND = 1_000L

        /** Cuántas veces más o menos que el monto anterior puede quedar el corregido. */
        const val NEAR = 10L

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

        /** Las formas de tarjeta donde se dice lo que es un producto nuevo. */
        val DESCRIBED = setOf(Shape.LINES, Shape.NEW_PRODUCT)

        /** Las operaciones que una corrección puede cambiar entre sí: venta y compra. */
        val SWITCHABLE = setOf(PhraseIntent.SALE, PhraseIntent.PURCHASE)

        /**
         * Las operaciones con líneas que también se eligen a mano en la tarjeta, y desde las que
         * "no, fue una venta" cambia la operación sin perder las líneas.
         */
        val CHOSEN_BY_HAND = setOf(
            PhraseIntent.SALE,
            PhraseIntent.PURCHASE,
            PhraseIntent.PRODUCTION,
            PhraseIntent.STOCK_ADJUSTMENT,
        )

        /** Los verbos que dicen sin duda una de esas operaciones, "vendí" y "compré", y cuál. */
        val SWITCHING_VERBS = mapOf(
            VerbKind.SALE to PhraseIntent.SALE,
            VerbKind.PURCHASE to PhraseIntent.PURCHASE,
        )

        /**
         * Si lo dicho de un producto que ya está en la tarjeta la corrige en vez de sumarse: en un
         * conteo, lo dicho es lo que hay; en una receta, lo que lleva.
         */
        fun restates(draft: PhraseDraft): Boolean = draft.intent == PhraseIntent.RECIPE ||
            draft.reason == StockAdjustmentReason.COUNT_CORRECTION

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
