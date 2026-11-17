package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseCatalog
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseDraft
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIntent
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseIssue
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseName
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhrasePrices
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseProduct
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseReading
import io.github.yesidrobayo.shopledger.domain.model.phrase.PhraseUnit
import io.github.yesidrobayo.shopledger.domain.model.phrase.ProductMatch
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.phrase.estimatedLineAmounts
import io.github.yesidrobayo.shopledger.domain.rule.phrase.estimatedTotal
import io.github.yesidrobayo.shopledger.domain.rule.phrase.gaps
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity
import io.github.yesidrobayo.shopledger.domain.usecase.phrase.PhraseReadResult
import io.github.yesidrobayo.shopledger.ui.ReadOutcome
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText
import io.github.yesidrobayo.shopledger.ui.readOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `Decir o escribir`: lee una frase, la muestra en una tarjeta para confirmar y la registra solo al
 * tocar `Registrar`.
 *
 * - **Nada se registra solo.** La tarjeta es una propuesta en memoria: cerrar la hoja la
 *   descarta, y lo único que sobrevive a que Android cierre el proceso es lo escrito en el campo.
 * - **Preguntar antes que adivinar.** Lo dudoso queda como hueco en su lugar de la tarjeta, y
 *   `Registrar` dice qué falta en vez de registrar.
 * - **Corregir sin empezar de nuevo.** Con la tarjeta a la vista, una frase corta la corrige; si no
 *   es una corrección, se lee como una frase nueva. Cada línea y cada dato también se tocan y se
 *   cambian a mano.
 * - **Un error nunca borra lo escrito**, ni la tarjeta.
 *
 * @param read lee la frase con el catálogo del negocio.
 * @param correct aplica una frase corta sobre la propuesta, o `null` si no es una corrección.
 * @param register registra la propuesta con el caso de uso de esa operación, en una transacción.
 * @param numbers los símbolos para leer lo que la persona escribe al corregir un número.
 * @param computation donde se corrige y se arma la tarjeta, fuera del hilo principal.
 * @param voiceReady si la voz directa está encendida y el teléfono tiene el servicio.
 * @param initialText una frase que llega ya escrita: se lee al abrir.
 */
class PhraseViewModel(
    private val read: suspend (String) -> PhraseReadResult,
    private val correct: (PhraseDraft, String, PhraseCatalog) -> PhraseDraft?,
    private val register: suspend (PhraseDraft) -> PhraseOutcome,
    private val savedState: SavedStateHandle,
    private val numbers: PhraseInputFormat,
    private val computation: CoroutineDispatcher,
    voiceReady: suspend () -> Boolean = { false },
    initialText: String? = null,
) : ViewModel() {
    private val state =
        MutableStateFlow(PhraseUiState(text = savedState.get<String>(KEY_TEXT).orEmpty()))
    val uiState: StateFlow<PhraseUiState> = state.asStateFlow()

    /** La propuesta a la vista, con lo que hace falta para preguntar lo dudoso. */
    private var session: Session? = null
    private var work: Job? = null

    init {
        viewModelScope.launch {
            val ready = voiceReady()
            // La voz directa se abre sola una vez por entrada, como si se hubiera tocado `Decir`.
            val first = savedState.get<Boolean>(KEY_DICTATED) != true
            val dictateNow =
                ready && first && initialText.isNullOrBlank() && state.value.text.isEmpty()
            if (dictateNow) savedState[KEY_DICTATED] = true
            state.update { it.copy(voiceReady = ready, dictate = it.dictate || dictateNow) }
        }
        if (!initialText.isNullOrBlank()) {
            setText(initialText)
            understand()
        }
    }

    fun onAction(action: PhraseAction) {
        when (action) {
            is PhraseAction.TextChange -> setText(action.text)

            PhraseAction.Understand -> understand()

            is PhraseAction.Heard -> {
                setText(action.text)
                understand()
            }

            PhraseAction.Dictate -> state.update { it.copy(dictate = true, voiceFailed = false) }

            is PhraseAction.DictateHandled ->
                state.update { it.copy(dictate = false, voiceFailed = !action.opened) }

            PhraseAction.Retry -> understand()

            PhraseAction.StartOver -> startOver()

            is PhraseAction.ChooseProduct ->
                editLine(action.index) { it.copy(product = DraftTarget.Existing(action.productId)) }

            is PhraseAction.CreateProduct ->
                createProduct(PickPurpose.LineProduct(action.index), action.name)

            is PhraseAction.PickProduct -> openPick(PickPurpose.LineProduct(action.index))

            is PhraseAction.OpenLine -> openLine(action.index)

            is PhraseAction.LineQuantityChange -> updateSheet<PhraseSheet.Line> {
                it.copy(quantityText = action.text, quantityError = false)
            }

            is PhraseAction.LinePriceChange -> updateSheet<PhraseSheet.Line> {
                it.copy(priceText = action.text, priceError = false)
            }

            PhraseAction.SaveLine -> saveLine()

            PhraseAction.RemoveLine -> removeLine()

            // El método `A crédito` que trae la app es lo que deja la venta o la compra fiada.
            is PhraseAction.ChoosePayment -> {
                val onAccount = session?.isOnAccount(action.methodId) == true
                editDraft { it.copy(paymentMethodId = action.methodId, credit = onAccount) }
            }

            is PhraseAction.UseLastCost -> {
                val cost =
                    (uiState.value.stage as? PhraseStage.Card)
                        ?.card
                        ?.lines
                        ?.getOrNull(action.index)
                        ?.lastCost
                if (cost != null) editLine(action.index) { it.copy(unitPrice = cost) }
            }

            is PhraseAction.ChooseParty -> {
                editDraft { it.copy(partyId = action.id) }
                closeSheet()
            }

            PhraseAction.PickParty -> openPick(PickPurpose.Party)

            is PhraseAction.ChooseCategory -> editDraft { it.copy(categoryId = action.categoryId) }

            PhraseAction.PickTarget -> openPick(PickPurpose.Target)

            is PhraseAction.ChooseTarget -> {
                editDraft { it.copy(target = DraftTarget.Existing(action.productId)) }
                closeSheet()
            }

            is PhraseAction.CreateTarget ->
                if (session?.draft?.intent == PhraseIntent.NEW_PRODUCT) {
                    // El producto nuevo se crea al registrar: aquí solo cambia su nombre.
                    editDraft { it.copy(target = DraftTarget.New(action.name.trim())) }
                    closeSheet()
                } else {
                    createProduct(PickPurpose.Target, action.name)
                }

            is PhraseAction.OpenAmount -> openAmount(action.field)

            is PhraseAction.AmountChange -> updateSheet<PhraseSheet.Amount> {
                it.copy(text = action.text, error = false)
            }

            PhraseAction.SaveAmount -> saveAmount()

            is PhraseAction.QueryChange -> updateSheet<PhraseSheet.Pick> { sheet ->
                val current = session ?: return@updateSheet sheet
                val items = pickItems(current, sheet.purpose, action.query)
                sheet.copy(query = action.query, items = items)
            }

            PhraseAction.CloseSheet -> closeSheet()

            PhraseAction.Register -> registerCard()

            PhraseAction.RegisteredShown -> state.update { it.copy(registered = null) }

            // Cerrar lo resuelve la navegación.
            PhraseAction.Close -> Unit
        }
    }

    private fun setText(text: String) {
        savedState[KEY_TEXT] = text
        state.update { it.copy(text = text, textMissing = false, voiceFailed = false) }
    }

    /**
     * Lee lo escrito. Con una tarjeta a la vista, primero lo prueba como corrección: "no, eran 3"
     * cambia la tarjeta; "vendí 2 panes" no la corrige y se lee como una frase nueva.
     */
    private fun understand() {
        val text = state.value.text.trim()
        if (text.isEmpty()) {
            state.update { it.copy(textMissing = true) }
            return
        }
        if (work?.isActive == true || registering()) return
        val current = session
        work = viewModelScope.launch {
            if (current != null) {
                val corrected = withContext(computation) {
                    correct(current.draft, text, current.catalog)
                }
                if (corrected != null) {
                    val next = current.corrected(corrected)
                    session = next
                    val card = withContext(computation) { cardOf(next) }
                    setText("")
                    state.update {
                        it.copy(
                            stage = PhraseStage.Card(card),
                            sheet = null,
                            cardShown = it.cardShown + 1,
                        )
                    }
                    return@launch
                }
            }
            state.update { it.copy(stage = PhraseStage.Reading, sheet = null) }
            when (val outcome = readOutcome { read(text) }) {
                // El catálogo no se pudo leer: lo escrito sigue, con `Reintentar`.
                ReadOutcome.Failed -> state.update { it.copy(stage = PhraseStage.ReadFailed) }

                is ReadOutcome.Read -> show(outcome.value, previous = current)
            }
        }
    }

    /**
     * Muestra lo que se leyó. Si no se entendió y había una tarjeta, la tarjeta sigue como estaba,
     * con el aviso arriba: una frase mal dicha no borra lo ya revisado.
     */
    private suspend fun show(result: PhraseReadResult, previous: Session?) {
        val draft = result.draft
        if (draft == null) {
            val mixed = PhraseIssue.MIXED_OPERATIONS in result.reading.issues
            if (previous != null) {
                val card = withContext(computation) { cardOf(previous) }
                val failure = if (mixed) PhraseFailure.Mixed else PhraseFailure.NotUnderstood
                state.update { it.copy(stage = PhraseStage.Card(card, failure = failure)) }
                return
            }
            session = null
            val stage = if (mixed) PhraseStage.Mixed else PhraseStage.NotUnderstood
            // Lo escrito queda en el campo, para decirlo de otra forma sin empezar de cero.
            state.update { it.copy(stage = stage) }
            return
        }
        val next = Session.of(result.reading, result.catalog, draft, result.prices)
        session = next
        val card = withContext(computation) { cardOf(next) }
        setText("")
        state.update { it.copy(stage = PhraseStage.Card(card), cardShown = it.cardShown + 1) }
    }

    private fun startOver() {
        if (registering()) return
        work?.cancel()
        session = null
        state.update { it.copy(stage = PhraseStage.Empty, sheet = null) }
    }

    private fun registering(): Boolean =
        (state.value.stage as? PhraseStage.Card)?.registering == true

    /** Cambia la propuesta con [change] y vuelve a dibujar la tarjeta, sin el aviso anterior. */
    private fun editDraft(change: (PhraseDraft) -> PhraseDraft) {
        if (registering()) return
        val current = session ?: return
        val next = current.edited(change(current.draft))
        session = next
        state.update { it.copy(stage = PhraseStage.Card(cardOf(next))) }
    }

    private fun editLine(index: Int, change: (DraftLine) -> DraftLine) {
        val lines = session?.draft?.lines ?: return
        if (index !in lines.indices) return
        editDraft { draft ->
            draft.copy(
                lines = lines.mapIndexed { i, line -> if (i == index) change(line) else line },
            )
        }
        closeSheet()
    }

    private inline fun <reified T : PhraseSheet> updateSheet(
        crossinline change: (T) -> PhraseSheet,
    ) {
        state.update { current ->
            val sheet = current.sheet as? T ?: return@update current
            current.copy(sheet = change(sheet))
        }
    }

    private fun closeSheet() {
        state.update { it.copy(sheet = null) }
    }

    private fun openLine(index: Int) {
        val current = session ?: return
        val line = current.draft.lines.getOrNull(index) ?: return
        val existing =
            (line.product as? DraftTarget.Existing)?.let { current.product(it.productId) }
        val unit = (line.unitId ?: existing?.baseUnitId)?.let(current::unit)?.toCardUnit()
        val title =
            when (val product = line.product) {
                is DraftTarget.Existing -> existing?.name
                is DraftTarget.New -> product.name
                null -> null
            }
        state.update {
            it.copy(
                sheet =
                    PhraseSheet.Line(
                        index = index,
                        title = title,
                        unit = unit,
                        quantityText =
                            line.quantity?.let { q ->
                                plainNumberText(q.milli, QUANTITY_DECIMALS, numbers.quantitySymbols)
                            }.orEmpty(),
                        priceText = line.unitPrice?.let(::moneyText).orEmpty(),
                        showsPrice = current.draft.intent in PRICED_LINES,
                        showsQuantity = current.draft.intent !in PRICE_ONLY,
                    ),
            )
        }
    }

    private fun saveLine() {
        val sheet = state.value.sheet as? PhraseSheet.Line ?: return
        val line = session?.draft?.lines?.getOrNull(sheet.index) ?: return
        val quantityRead =
            NumberInputParser.quantity(sheet.quantityText, numbers.quantitySymbols, LINE_LIMIT)
        val quantity =
            when (quantityRead) {
                is NumberInput.Value -> quantityRead.value.takeIf { it > QuantityMilli.ZERO }

                NumberInput.Blank,
                NumberInput.Malformed,
                is NumberInput.TooManyDecimals,
                is NumberInput.TooLarge,
                -> null
            }
        val quantityOk = quantity != null || !sheet.showsQuantity
        val priceRead =
            NumberInputParser.money(
                sheet.priceText,
                numbers.moneySymbols,
                numbers.currencyDecimals,
                Limit.UNIT_MONEY,
            )
        val price: Money?
        val priceOk: Boolean
        when (priceRead) {
            is NumberInput.Value -> {
                price = priceRead.value
                priceOk = priceRead.value >= Money.ZERO
            }

            // Sin precio escrito, sale del producto al registrar.
            NumberInput.Blank -> {
                price = null
                priceOk = true
            }

            NumberInput.Malformed,
            is NumberInput.TooManyDecimals,
            is NumberInput.TooLarge,
            -> {
                price = null
                priceOk = false
            }
        }
        val checkPrice = sheet.showsPrice
        if (!quantityOk || (checkPrice && !priceOk)) {
            state.update {
                it.copy(
                    sheet =
                        sheet.copy(
                            quantityError = !quantityOk,
                            priceError = checkPrice && !priceOk,
                        ),
                )
            }
            return
        }
        val priceChanged = checkPrice && price != line.unitPrice
        editLine(sheet.index) {
            it.copy(
                quantity = if (sheet.showsQuantity) quantity else it.quantity,
                unitPrice = if (checkPrice) price else it.unitPrice,
                // Un precio nuevo manda sobre el total que se dijo para la línea.
                lineTotal = if (priceChanged) null else it.lineTotal,
            )
        }
    }

    private fun removeLine() {
        val sheet = state.value.sheet as? PhraseSheet.Line ?: return
        val lines = session?.draft?.lines ?: return
        if (sheet.index !in lines.indices) return
        editDraft { draft -> draft.copy(lines = lines.filterIndexed { i, _ -> i != sheet.index }) }
        closeSheet()
    }

    private fun openAmount(field: AmountField) {
        val draft = session?.draft ?: return
        val amount =
            when (field) {
                AmountField.TOTAL -> draft.total
                AmountField.PRICE -> draft.price
                AmountField.COST -> draft.cost
            }
        state.update {
            it.copy(sheet = PhraseSheet.Amount(field, amount?.let(::moneyText).orEmpty()))
        }
    }

    private fun saveAmount() {
        val sheet = state.value.sheet as? PhraseSheet.Amount ?: return
        val limit = if (sheet.field == AmountField.TOTAL) Limit.DOCUMENT_TOTAL else Limit.UNIT_MONEY
        val read =
            NumberInputParser.money(
                sheet.text,
                numbers.moneySymbols,
                numbers.currencyDecimals,
                limit,
            )
        val amount =
            when (read) {
                is NumberInput.Value -> read.value.takeIf { it > Money.ZERO }

                NumberInput.Blank,
                NumberInput.Malformed,
                is NumberInput.TooManyDecimals,
                is NumberInput.TooLarge,
                -> null
            }
        // Un precio o un costo vacío se quita. Un total vacío solo vale si se suma de las líneas.
        val intent = session?.draft?.intent
        val summed = intent != null && intent in SUMMED
        val blankAllowed =
            read == NumberInput.Blank && (sheet.field != AmountField.TOTAL || summed)
        if (amount == null && !blankAllowed) {
            state.update { it.copy(sheet = sheet.copy(error = true)) }
            return
        }
        editDraft { draft ->
            when (sheet.field) {
                AmountField.TOTAL -> draft.copy(total = amount)
                AmountField.PRICE -> draft.copy(price = amount)
                AmountField.COST -> draft.copy(cost = amount)
            }
        }
        closeSheet()
    }

    private fun openPick(purpose: PickPurpose) {
        val current = session ?: return
        val intent = current.draft.intent
        val canCreate =
            when (purpose) {
                is PickPurpose.LineProduct -> intent in CREATES_LINE_PRODUCTS
                PickPurpose.Target -> true
                PickPurpose.Party -> false
            }
        state.update {
            it.copy(
                sheet =
                    PhraseSheet.Pick(
                        purpose = purpose,
                        query = "",
                        items = pickItems(current, purpose, ""),
                        canCreate = canCreate,
                    ),
            )
        }
    }

    private fun pickItems(current: Session, purpose: PickPurpose, query: String): List<CardOption> {
        val catalog = current.catalog
        val intent = current.draft.intent
        val all =
            when (purpose) {
                is PickPurpose.LineProduct ->
                    catalog.products.filter { it.fits(intent) }.map { CardOption(it.id, it.name) }

                // Un producto nuevo no se elige de la lista: solo se le pone otro nombre.
                PickPurpose.Target ->
                    if (intent == PhraseIntent.NEW_PRODUCT) {
                        emptyList()
                    } else {
                        catalog.products.map { CardOption(it.id, it.name) }
                    }

                PickPurpose.Party ->
                    current.partyNames().map { CardOption(it.id, it.name) }
            }
        val wanted = TextNormalizer.normalize(query.trim())
        if (wanted.isEmpty()) return all
        return all.filter { TextNormalizer.normalize(it.name).contains(wanted) }
    }

    private fun registerCard() {
        val current = session ?: return
        val stage = state.value.stage as? PhraseStage.Card ?: return
        if (stage.registering || work?.isActive == true) return
        if (current.draft.gaps().isNotEmpty()) {
            state.update { it.copy(stage = stage.copy(failure = PhraseFailure.Incomplete)) }
            return
        }
        val draft = current.draft
        work = viewModelScope.launch {
            state.update {
                it.copy(stage = stage.copy(registering = true, failure = null), sheet = null)
            }
            val outcome =
                when (val result = readOutcome { register(draft) }) {
                    // Una falla inesperada no cierra la app: la escritura no quedó a medias.
                    ReadOutcome.Failed -> PhraseOutcome.SaveFailed(outOfSpace = false)

                    is ReadOutcome.Read -> result.value
                }
            when (outcome) {
                is PhraseOutcome.Registered -> {
                    session = null
                    setText("")
                    state.update {
                        it.copy(
                            stage = PhraseStage.Empty,
                            sheet = null,
                            registered = PhraseRegistered(outcome.intent, outcome.documentId),
                        )
                    }
                }

                is PhraseOutcome.Incomplete -> fail(stage, PhraseFailure.Incomplete)

                PhraseOutcome.Rejected -> fail(stage, PhraseFailure.Rejected)

                is PhraseOutcome.SaveFailed ->
                    fail(stage, PhraseFailure.SaveFailed(outcome.outOfSpace))
            }
        }
    }

    /**
     * `Crear «…»`: crea el producto con su nombre, como un producto nuevo dicho con una frase, y lo
     * elige en la línea o en la receta. Ningún caso de uso crea un producto dentro de la escritura
     * de una venta, una compra o una receta, así que se crea antes, en su propia transacción. La
     * unidad dicha en la línea es su unidad base.
     */
    private fun createProduct(purpose: PickPurpose, name: String) {
        val current = session ?: return
        val stage = state.value.stage as? PhraseStage.Card ?: return
        val clean = name.trim()
        if (clean.isEmpty() || stage.registering || work?.isActive == true) return
        val unitId =
            (purpose as? PickPurpose.LineProduct)?.let { current.draft.lines.getOrNull(it.index) }
                ?.unitId
        val request =
            PhraseDraft(
                intent = PhraseIntent.NEW_PRODUCT,
                target = DraftTarget.New(clean),
                unitId = unitId,
            )
        work = viewModelScope.launch {
            state.update {
                it.copy(stage = stage.copy(registering = true, failure = null), sheet = null)
            }
            val outcome =
                when (val result = readOutcome { register(request) }) {
                    ReadOutcome.Failed -> PhraseOutcome.SaveFailed(outOfSpace = false)
                    is ReadOutcome.Read -> result.value
                }
            val productId = (outcome as? PhraseOutcome.Registered)?.documentId
            when {
                productId != null -> {
                    val named = current.withCreated(productId, clean)
                    val draft = named.draft
                    val chosen = DraftTarget.Existing(productId)
                    val next =
                        when (purpose) {
                            is PickPurpose.LineProduct ->
                                named.edited(
                                    draft.copy(
                                        lines =
                                            draft.lines.mapIndexed { i, line ->
                                                if (i == purpose.index) {
                                                    line.copy(product = chosen)
                                                } else {
                                                    line
                                                }
                                            },
                                    ),
                                )

                            PickPurpose.Target -> named.edited(draft.copy(target = chosen))

                            PickPurpose.Party -> named
                        }
                    session = next
                    state.update { it.copy(stage = PhraseStage.Card(cardOf(next))) }
                }

                outcome is PhraseOutcome.SaveFailed ->
                    fail(stage, PhraseFailure.SaveFailed(outcome.outOfSpace))

                // Un nombre que ya existe u otro rechazo: la línea sigue igual, para elegirlo.
                else -> fail(stage, PhraseFailure.Rejected)
            }
        }
    }

    private fun fail(stage: PhraseStage.Card, failure: PhraseFailure) {
        state.update { it.copy(stage = stage.copy(registering = false, failure = failure)) }
    }

    private fun moneyText(amount: Money): String =
        plainNumberText(amount.minor, numbers.currencyDecimals, numbers.moneySymbols)

    /**
     * La propuesta y lo que la frase dejó dudoso: los candidatos de cada línea y lo que se dijo,
     * para preguntar en su lugar. [edited] dice si la persona ya la cambió: entonces los avisos de
     * la lectura dejan de valer. [prices] son los de la última lectura: siguen valiendo tras una
     * corrección, que no vuelve a leer la base.
     */
    private class Session(
        val reading: PhraseReading,
        val catalog: PhraseCatalog,
        val draft: PhraseDraft,
        val lineCandidates: List<List<Long>>,
        val lineSpoken: List<String?>,
        val edited: Boolean,
        val created: Map<Long, String> = emptyMap(),
        val prices: PhrasePrices = PhrasePrices(),
    ) {
        private val products: Map<Long, PhraseProduct> = catalog.products.associateBy { it.id }
        private val units: Map<Long, PhraseUnit> = catalog.units.associateBy { it.id }

        fun product(id: Long): PhraseProduct? = products[id]

        /** El nombre de [id]: el del catálogo, o el de un producto creado desde esta tarjeta. */
        fun productName(id: Long): String? = products[id]?.name ?: created[id]

        /** La misma sesión, sabiendo que [id] es el producto [name] que se acaba de crear. */
        fun withCreated(id: Long, name: String): Session = Session(
            reading,
            catalog,
            draft,
            lineCandidates,
            lineSpoken,
            edited,
            created + (id to name),
            prices,
        )

        /** Si [id] es el método `A crédito` que trae la app. */
        fun isOnAccount(id: Long): Boolean =
            catalog.paymentMethods.any { it.id == id && it.systemKey == ON_ACCOUNT_KEY }

        /**
         * El último costo de [product] en [unitId], la unidad de la línea: el de la unidad base, o
         * llevado con la conversión guardada. `null` si no hay o la unidad no tiene conversión.
         */
        fun lastCostIn(product: PhraseProduct, unitId: Long?): Money? {
            val base = prices.lastCost[product.id] ?: return null
            if (unitId == null || unitId == product.baseUnitId) return base
            val factor = product.conversions.firstOrNull { it.unitId == unitId } ?: return null
            return base.timesQuantity(QuantityMilli(factor.factorMilli)).valueOrNull()
        }

        fun unit(id: Long): PhraseUnit? = units[id]

        fun partyNames(): List<PhraseName> =
            if (draft.intent in SUPPLIER_PARTY) catalog.suppliers else catalog.customers

        /** La misma sesión con otra propuesta, cambiada a mano. */
        fun edited(next: PhraseDraft): Session = realigned(next, edited = true)

        /** La misma sesión con la propuesta que dejó una frase de corrección. */
        fun corrected(next: PhraseDraft): Session = realigned(next, edited = true)

        /**
         * Lleva los candidatos de cada línea a la propuesta nueva: con las mismas líneas, por
         * posición; si se agregó o se quitó una, por la línea igual que quedó.
         */
        private fun realigned(next: PhraseDraft, edited: Boolean): Session {
            val old = draft.lines
            val sameCount = next.lines.size == old.size
            val candidates = next.lines.mapIndexed { i, line ->
                val from = if (sameCount) i else old.indexOf(line)
                lineCandidates.getOrNull(from).orEmpty()
            }
            val spoken = next.lines.mapIndexed { i, line ->
                val from = if (sameCount) i else old.indexOf(line)
                lineSpoken.getOrNull(from)
            }
            return Session(reading, catalog, next, candidates, spoken, edited, created, prices)
        }

        companion object {
            fun of(
                reading: PhraseReading,
                catalog: PhraseCatalog,
                draft: PhraseDraft,
                prices: PhrasePrices,
            ): Session {
                // La propuesta sale de la lectura línea por línea, en el mismo orden.
                val candidates = draft.lines.indices.map { i ->
                    when (val match = reading.lines.getOrNull(i)?.product) {
                        is ProductMatch.Choose -> match.ids
                        is ProductMatch.Found, is ProductMatch.Unknown, null -> emptyList()
                    }
                }
                val spoken = draft.lines.indices.map { i ->
                    (reading.lines.getOrNull(i)?.product as? ProductMatch.Unknown)?.spokenName
                }
                return Session(
                    reading,
                    catalog,
                    draft,
                    candidates,
                    spoken,
                    edited = false,
                    prices = prices,
                )
            }
        }
    }

    /** Arma la tarjeta de [session], con los nombres del catálogo. */
    private fun cardOf(session: Session): PhraseCard {
        val draft = session.draft
        val intent = draft.intent
        val reading = session.reading
        val catalog = session.catalog
        // Lo que pondrá la operación de siempre: lo dicho, o en una venta el precio del producto.
        val estimated = draft.estimatedLineAmounts(session.prices, catalog)
        val lines =
            draft.lines.mapIndexed { index, line ->
                val existing =
                    (line.product as? DraftTarget.Existing)?.let { session.product(it.productId) }
                val product =
                    when (val target = line.product) {
                        is DraftTarget.Existing ->
                            session.productName(target.productId)
                                ?.let { CardProduct.Chosen(target.productId, it) }
                                ?: CardProduct.Missing(emptyList(), null, canCreate = false)

                        is DraftTarget.New -> CardProduct.New(target.name, needsCreating = true)

                        null ->
                            CardProduct.Missing(
                                candidates =
                                    session.lineCandidates.getOrNull(index).orEmpty()
                                        .mapNotNull(session::product)
                                        .map { CardOption(it.id, it.name) },
                                spoken = session.lineSpoken.getOrNull(index),
                                canCreate = intent in CREATES_LINE_PRODUCTS,
                            )
                    }
                val unit = (line.unitId ?: existing?.baseUnitId)?.let(session::unit)?.toCardUnit()
                val amount = estimated.getOrNull(index)
                // Una compra sin costo dicho lo pide; el último se ofrece, nunca se pone solo.
                val unpricedPurchase =
                    intent == PhraseIntent.PURCHASE &&
                        line.unitPrice == null &&
                        line.lineTotal == null
                val lastCost =
                    existing
                        ?.takeIf { unpricedPurchase }
                        ?.let { session.lastCostIn(it, line.unitId) }
                CardLine(
                    index = index,
                    product = product,
                    quantity = line.quantity,
                    unit = unit,
                    unitPrice = line.unitPrice,
                    amount = amount,
                    otherOperation = existing != null && !existing.fits(intent),
                    lastCost = lastCost,
                )
            }
        val payment =
            if (intent in PAID) {
                CardChoice(
                    selectedId = draft.paymentMethodId,
                    options = catalog.paymentMethods.map { CardOption(it.id, it.name) },
                )
            } else {
                null
            }
        val party =
            if (intent in WITH_PARTY) {
                val names = session.partyNames().associateBy { it.id }
                CardParty(
                    supplier = intent in SUPPLIER_PARTY,
                    chosen = draft.partyId?.let(names::get)?.let { CardOption(it.id, it.name) },
                    candidates =
                        (reading.party as? NameMatch.Choose)?.ids.orEmpty()
                            .mapNotNull(names::get)
                            .map { CardOption(it.id, it.name) },
                    spoken = (reading.party as? NameMatch.Unknown)?.spoken,
                )
            } else {
                null
            }
        val category =
            if (intent == PhraseIntent.EXPENSE) {
                CardChoice(
                    selectedId = draft.categoryId,
                    options = catalog.expenseCategories.map { CardOption(it.id, it.name) },
                )
            } else {
                null
            }
        val target =
            if (intent in WITH_TARGET) {
                when (val chosen = draft.target) {
                    is DraftTarget.Existing ->
                        session.productName(chosen.productId)
                            ?.let { CardProduct.Chosen(chosen.productId, it) }
                            ?: CardProduct.Missing(emptyList(), null, canCreate = true)

                    is DraftTarget.New ->
                        CardProduct.New(
                            chosen.name,
                            needsCreating = intent != PhraseIntent.NEW_PRODUCT,
                        )

                    null ->
                        CardProduct.Missing(
                            candidates =
                                (reading.target as? ProductMatch.Choose)?.ids.orEmpty()
                                    .mapNotNull(session::product)
                                    .map { CardOption(it.id, it.name) },
                            spoken = (reading.target as? ProductMatch.Unknown)?.spokenName,
                            canCreate = true,
                        )
                }
            } else {
                null
            }
        val gaps = draft.gaps()
        val total = draft.estimatedTotal(session.prices, catalog)
        val notices =
            reading.issues.filter { issue ->
                when (issue) {
                    // Se dice mientras falte alguna cantidad.
                    PhraseIssue.UNREADABLE_QUANTITY ->
                        draft.lines.any { it.quantity == null }

                    // Valen para la frase tal como se dijo: tras cambiarla, ya no se sabe.
                    PhraseIssue.UNIT_NOT_FOR_PRODUCT,
                    PhraseIssue.NO_RECIPE,
                    PhraseIssue.NO_STOCK,
                    PhraseIssue.ALREADY_EXISTS,
                    -> !session.edited

                    // Sin propuesta no hay tarjeta: estos se muestran en su propio estado.
                    PhraseIssue.MIXED_OPERATIONS, PhraseIssue.NOTHING_UNDERSTOOD -> false
                }
            }.sortedBy { it.ordinal }
        return PhraseCard(
            intent = intent,
            reason = draft.reason,
            lines = lines,
            payment = payment,
            credit = draft.credit,
            party = party,
            category = category,
            target = target,
            targetQuantity = draft.targetQuantity,
            price = draft.price,
            cost = draft.cost,
            unit = draft.unitId?.let(session::unit)?.toCardUnit(),
            total = total,
            sensitive = intent in SENSITIVE,
            gaps = gaps,
            notices = notices,
        )
    }

    private companion object {
        const val KEY_TEXT = "phrase_text"
        const val KEY_DICTATED = "phrase_dictated"

        /** La clave del método `A crédito` que trae la app, como la lee el intérprete. */
        const val ON_ACCOUNT_KEY = "on_account"
        const val QUANTITY_DECIMALS = 3
        val LINE_LIMIT = Limit.LINE_QUANTITY_MILLI

        /** Las operaciones que llevan método de pago. */
        val PAID =
            setOf(
                PhraseIntent.SALE,
                PhraseIntent.PURCHASE,
                PhraseIntent.EXPENSE,
                PhraseIntent.CUSTOMER_PAYMENT,
                PhraseIntent.SUPPLIER_PAYMENT,
            )

        /** Las que nombran a una persona: un cliente o un proveedor. */
        val WITH_PARTY =
            setOf(
                PhraseIntent.SALE,
                PhraseIntent.PURCHASE,
                PhraseIntent.CUSTOMER_PAYMENT,
                PhraseIntent.SUPPLIER_PAYMENT,
            )

        /** Las que la nombran como proveedor. */
        val SUPPLIER_PARTY = setOf(PhraseIntent.PURCHASE, PhraseIntent.SUPPLIER_PAYMENT)

        /** Las que tienen un producto aparte de sus líneas. */
        val WITH_TARGET = setOf(PhraseIntent.RECIPE, PhraseIntent.NEW_PRODUCT)

        /** Las que suman sus líneas en un total. */
        val SUMMED = setOf(PhraseIntent.SALE, PhraseIntent.PURCHASE)

        /** Las que dejan crear el producto de una línea que no existe. */
        val CREATES_LINE_PRODUCTS =
            setOf(PhraseIntent.SALE, PhraseIntent.PURCHASE, PhraseIntent.RECIPE)

        /** Las que llevan un precio o un costo por unidad en cada línea. */
        val PRICED_LINES =
            setOf(
                PhraseIntent.SALE,
                PhraseIntent.PURCHASE,
                PhraseIntent.PRICE_CHANGE,
                PhraseIntent.COST_CORRECTION,
            )

        /** Las que solo cambian un precio o un costo, sin cantidad. */
        val PRICE_ONLY = setOf(PhraseIntent.PRICE_CHANGE, PhraseIntent.COST_CORRECTION)

        /** Las que muestran costos, que el modo discreto oculta. */
        val SENSITIVE = setOf(PhraseIntent.PURCHASE, PhraseIntent.COST_CORRECTION)
    }
}

/** Si el producto es de [intent]: en una venta, que se venda; en una compra, que se compre. */
private fun PhraseProduct.fits(intent: PhraseIntent): Boolean = when (intent) {
    PhraseIntent.SALE -> forSale

    PhraseIntent.PURCHASE -> forPurchase

    PhraseIntent.PRODUCTION -> hasRecipe

    PhraseIntent.EXPENSE,
    PhraseIntent.STOCK_ADJUSTMENT,
    PhraseIntent.CUSTOMER_PAYMENT,
    PhraseIntent.SUPPLIER_PAYMENT,
    PhraseIntent.RECIPE,
    PhraseIntent.NEW_PRODUCT,
    PhraseIntent.COST_CORRECTION,
    PhraseIntent.PRICE_CHANGE,
    -> true
}

private fun PhraseUnit.toCardUnit(): CardUnit = CardUnit(id, name, symbol, systemKey, kind)
