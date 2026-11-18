package io.github.yesidrobayo.shopledger.ui.phrase

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.phrase.CardFollowUp
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftTarget
import io.github.yesidrobayo.shopledger.domain.model.phrase.NameMatch
import io.github.yesidrobayo.shopledger.domain.model.phrase.NewProductTraits
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
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.phrase.asOperation
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
 * - **Corregir y sumar sin empezar de nuevo.** Con la tarjeta a la vista, lo que se dice o se
 *   escribe se prueba primero como corrección, y cambia solo lo nombrado. Si no corrige nada, se
 *   lee entera: lo dicho sin verbo, o con el de la misma operación, suma sus líneas a la tarjeta o
 *   corrige las del mismo producto. Lo que dice de un producto que ya existe deja la tarjeta como
 *   estaba y ofrece abrir el producto. Otra operación pregunta antes de empezar otra tarjeta, salvo
 *   después de "no". Ante la duda, la tarjeta sigue como estaba y dice que no se entendió. Cada
 *   línea, cada dato y la operación también se cambian a mano.
 * - **Un error nunca borra lo escrito**, ni la tarjeta. Una línea solo sale si la persona lo pide.
 *
 * @param read lee la frase con el catálogo del negocio.
 * @param correct aplica una frase corta sobre la propuesta, o `null` si no es una corrección.
 * @param follow lo que hace con la propuesta una frase que no la corrigió, ya leída entera.
 * @param register registra la propuesta con el caso de uso de esa operación, en una transacción.
 * @param numbers los símbolos para leer lo que la persona escribe al corregir un número.
 * @param computation donde se corrige y se arma la tarjeta, fuera del hilo principal.
 * @param voiceReady si escuchar está encendido y el teléfono tiene el servicio de voz.
 * @param initialText una frase que llega ya escrita u oída: se lee al abrir.
 * @param initialNotice por qué la hoja abre con el teclado, si `Decir` no pudo escuchar.
 */
class PhraseViewModel(
    private val read: suspend (String) -> PhraseReadResult,
    private val correct: (PhraseDraft, String, PhraseCatalog) -> PhraseDraft?,
    private val follow: (PhraseDraft, String, PhraseReadResult) -> CardFollowUp,
    private val register: suspend (PhraseDraft) -> PhraseOutcome,
    private val savedState: SavedStateHandle,
    private val numbers: PhraseInputFormat,
    private val computation: CoroutineDispatcher,
    voiceReady: suspend () -> Boolean = { false },
    initialText: String? = null,
    initialNotice: VoiceNotice? = null,
) : ViewModel() {
    private val state =
        MutableStateFlow(
            PhraseUiState(
                text = savedState.get<String>(KEY_TEXT).orEmpty(),
                voiceNotice = initialNotice,
            ),
        )
    val uiState: StateFlow<PhraseUiState> = state.asStateFlow()

    /** La propuesta a la vista, con lo que hace falta para preguntar lo dudoso. */
    private var session: Session? = null

    /** La tarjeta de otra operación que se dijo, mientras se pregunta si empezarla. */
    private var pending: Session? = null
    private var work: Job? = null

    init {
        // `Decir` escucha antes de abrir la hoja: aquí solo se sabe si el campo ofrece micrófono.
        viewModelScope.launch {
            val ready = voiceReady()
            state.update { it.copy(voiceReady = ready) }
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
                state.update { it.copy(listening = false) }
                setText(action.text)
                understand()
            }

            PhraseAction.Listen -> state.update { it.copy(listening = true, voiceNotice = null) }

            is PhraseAction.ListenEnded ->
                state.update { it.copy(listening = false, voiceNotice = action.notice) }

            PhraseAction.Retry -> understand()

            PhraseAction.StartOver -> startOver()

            PhraseAction.StartNewCard -> startNewCard()

            PhraseAction.KeepCard -> keepCard()

            is PhraseAction.ChooseOperation ->
                editDraft { it.asOperation(action.operation.intent, action.operation.reason) }

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
                    // El producto nuevo se crea al registrar: aquí solo cambia su nombre, y lo que
                    // se dijo de él queda.
                    editDraft { draft ->
                        val name = action.name.trim()
                        val said = (draft.target as? DraftTarget.New)?.copy(name = name)
                        draft.copy(target = said ?: DraftTarget.New(name))
                    }
                    closeSheet()
                } else {
                    createProduct(PickPurpose.Target, action.name)
                }

            is PhraseAction.PickNewCategory -> openPick(PickPurpose.NewCategory(action.index))

            is PhraseAction.ChooseNewCategory ->
                editNewProduct(action.index, NewProductTraits(categoryId = action.categoryId))

            is PhraseAction.NameNewCategory -> {
                val name = action.name.trim()
                // Con mayúscula inicial, como se propone una categoría dicha.
                val shown = name.replaceFirstChar { it.uppercaseChar() }
                if (name.isNotEmpty()) {
                    editNewProduct(action.index, NewProductTraits(newCategory = shown))
                }
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
            PhraseAction.SellByHand,
            PhraseAction.MicHelp,
            PhraseAction.OpenMicSettings,
            is PhraseAction.OpenProduct,
            PhraseAction.Close,
            -> Unit
        }
    }

    private fun setText(text: String) {
        savedState[KEY_TEXT] = text
        state.update { it.copy(text = text, textMissing = false, voiceNotice = null) }
    }

    /**
     * Lee lo escrito. Con una tarjeta a la vista, primero lo prueba como corrección: "no, eran 3"
     * cambia la tarjeta; "vendí 2 panes" no la corrige, se lee entera y se suma a ella o pregunta,
     * ver [followUp].
     */
    private fun understand() {
        val text = state.value.text.trim()
        if (text.isEmpty()) {
            state.update { it.copy(textMissing = true) }
            return
        }
        if (work?.isActive == true || registering()) return
        val current = session
        pending = null
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

                is ReadOutcome.Read ->
                    if (current == null) {
                        show(outcome.value, outcome.value.draft)
                    } else {
                        followUp(current, text, outcome.value)
                    }
            }
        }
    }

    /**
     * Muestra [draft], la tarjeta de lo que se leyó en [result]. Sin tarjeta, lo que no se entendió
     * deja lo escrito en el campo, para decirlo de otra forma sin empezar de cero.
     */
    private suspend fun show(result: PhraseReadResult, draft: PhraseDraft?) {
        if (draft == null) {
            session = null
            val mixed = PhraseIssue.MIXED_OPERATIONS in result.reading.issues
            val stage = if (mixed) PhraseStage.Mixed else PhraseStage.NotUnderstood
            state.update { it.copy(stage = stage) }
            return
        }
        showSession(Session.of(result.reading, result.catalog, draft, result.prices))
    }

    /** Deja [next] a la vista como la tarjeta, recién leída o sumada, y vacía el campo. */
    private suspend fun showSession(next: Session) {
        session = next
        val card = withContext(computation) { cardOf(next) }
        setText("")
        state.update { it.copy(stage = PhraseStage.Card(card), cardShown = it.cardShown + 1) }
    }

    /**
     * Lo que hace con la tarjeta [current] una frase que no la corrigió, leída en [result]. Nunca
     * la borra en silencio: la suma, la deja con un aviso, o pregunta antes de empezar otra. Solo
     * "no" con otra operación la reemplaza, como corrección de lo que se dijo antes.
     */
    private suspend fun followUp(current: Session, text: String, result: PhraseReadResult) {
        val next = withContext(computation) { follow(current.draft, text, result) }
        when (next) {
            is CardFollowUp.Joined -> showSession(current.joined(next.draft, result))

            is CardFollowUp.Replaced -> show(result, next.draft)

            is CardFollowUp.OtherOperation -> {
                pending = Session.of(result.reading, result.catalog, next.draft, result.prices)
                val notice = CardNotice.OtherOperation(next.draft.intent, next.draft.reason)
                keep(current, notice = notice)
            }

            is CardFollowUp.KnownProduct -> {
                val name = result.catalog.products.firstOrNull { it.id == next.productId }?.name
                if (name == null) {
                    keep(current, failure = PhraseFailure.NotUnderstood, clears = false)
                } else {
                    keep(current, notice = CardNotice.KnownProduct(next.productId, name))
                }
            }

            CardFollowUp.NotUnderstood -> {
                val mixed = PhraseIssue.MIXED_OPERATIONS in result.reading.issues
                val failure = if (mixed) PhraseFailure.Mixed else PhraseFailure.NotUnderstood
                keep(current, failure = failure, clears = false)
            }
        }
    }

    /**
     * Deja la tarjeta [current] como estaba, con [notice] o [failure] arriba. Si [clears], la frase
     * ya se atendió y el campo queda vacío; si no, queda para decirla de otra forma.
     */
    private suspend fun keep(
        current: Session,
        notice: CardNotice? = null,
        failure: PhraseFailure? = null,
        clears: Boolean = true,
    ) {
        val card = withContext(computation) { cardOf(current) }
        if (clears) setText("")
        state.update {
            it.copy(stage = PhraseStage.Card(card, failure = failure, notice = notice))
        }
    }

    private fun startOver() {
        if (registering()) return
        work?.cancel()
        session = null
        pending = null
        state.update { it.copy(stage = PhraseStage.Empty, sheet = null) }
    }

    /** `Empezar otra`: la tarjeta de la otra operación reemplaza a la de la vista. */
    private fun startNewCard() {
        if (registering()) return
        val next = pending ?: return
        pending = null
        session = next
        state.update {
            it.copy(
                stage = PhraseStage.Card(cardOf(next)),
                sheet = null,
                cardShown = it.cardShown + 1,
            )
        }
    }

    /** `Seguir con esta`: la frase de otra operación se descarta y la tarjeta sigue como estaba. */
    private fun keepCard() {
        pending = null
        val current = session ?: return
        state.update { it.copy(stage = PhraseStage.Card(cardOf(current))) }
    }

    private fun registering(): Boolean =
        (state.value.stage as? PhraseStage.Card)?.registering == true

    /** Cambia la propuesta con [change] y vuelve a dibujar la tarjeta, sin el aviso anterior. */
    private fun editDraft(change: (PhraseDraft) -> PhraseDraft) {
        if (registering()) return
        val current = session ?: return
        // Cambiar la tarjeta a mano responde la pregunta: sigue esta.
        pending = null
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

    /**
     * Cambia lo que se sabe del producto por crear de la línea [index], o del producto nuevo o el
     * de la receta con `null`, con [said] encima, y cierra la hoja. Si ya no es un producto por
     * crear, solo cierra la hoja.
     */
    private fun editNewProduct(index: Int?, said: NewProductTraits) {
        val draft = session?.draft
        if (draft == null) {
            closeSheet()
            return
        }
        if (index == null) {
            val target = draft.target as? DraftTarget.New
            if (target != null) {
                editDraft { it.copy(target = target.copy(traits = target.traits.with(said))) }
            }
            closeSheet()
            return
        }
        val product = draft.lines.getOrNull(index)?.product as? DraftTarget.New
        if (product == null) {
            closeSheet()
            return
        }
        editLine(index) { it.copy(product = product.copy(traits = product.traits.with(said))) }
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
                is PickPurpose.NewCategory -> true
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

                is PickPurpose.NewCategory ->
                    catalog.productCategories.map { CardOption(it.id, it.name) }
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
        val line =
            (purpose as? PickPurpose.LineProduct)?.let { current.draft.lines.getOrNull(it.index) }
        val unitId = line?.unitId
        // Lo que se dijo del producto, como su uso o su categoría, se usa al crearlo.
        val spoken = if (line != null) line.product else current.draft.target
        val traits = (spoken as? DraftTarget.New)?.traits ?: NewProductTraits.NONE
        val request =
            PhraseDraft(
                intent = PhraseIntent.NEW_PRODUCT,
                target = DraftTarget.New(clean, traits),
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

                            is PickPurpose.NewCategory, PickPurpose.Party -> named
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
         * La sesión con [next], esta tarjeta con otra frase sumada, leída en [result]: el catálogo
         * y los precios son los de esa lectura, que ya conoce lo creado desde la tarjeta. Una línea
         * que ya estaba guarda sus candidatos y lo dicho; una nueva, los de su propia lectura.
         */
        fun joined(next: PhraseDraft, result: PhraseReadResult): Session {
            val read = result.draft?.lines.orEmpty()
            val readSpoken = read.indices.map { i ->
                (result.reading.lines.getOrNull(i)?.product as? ProductMatch.Unknown)?.spokenName
            }
            val candidates = next.lines.map { line ->
                val mine = draft.lines.indexOf(line)
                if (mine >= 0) lineCandidates.getOrNull(mine).orEmpty() else line.candidates
            }
            val spoken = next.lines.map { line ->
                val mine = draft.lines.indexOf(line)
                if (mine >=
                    0
                ) {
                    lineSpoken.getOrNull(mine)
                } else {
                    readSpoken.getOrNull(read.indexOf(line))
                }
            }
            return Session(
                reading,
                result.catalog,
                next,
                candidates,
                spoken,
                edited = true,
                created,
                result.prices,
            )
        }

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

                        is DraftTarget.New -> newProduct(target, needsCreating = true, catalog)

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
        // Tras pasar de venta a compra, la persona leída es del otro lado: no se ofrece.
        val readParty = reading.party.takeIf { reading.intent == intent }
        val party =
            if (intent in WITH_PARTY) {
                val names = session.partyNames().associateBy { it.id }
                CardParty(
                    supplier = intent in SUPPLIER_PARTY,
                    chosen = draft.partyId?.let(names::get)?.let { CardOption(it.id, it.name) },
                    candidates =
                        (readParty as? NameMatch.Choose)?.ids.orEmpty()
                            .mapNotNull(names::get)
                            .map { CardOption(it.id, it.name) },
                    spoken = (readParty as? NameMatch.Unknown)?.spoken,
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
                        newProduct(
                            chosen,
                            needsCreating = intent != PhraseIntent.NEW_PRODUCT,
                            catalog,
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
                    PhraseIssue.UNUSED_WORDS,
                    PhraseIssue.COST_LEFT_OUT,
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
            operations = operationsOf(session),
        )
    }

    /**
     * Las operaciones que se eligen a mano arriba de la tarjeta, solo si la suya es una de ellas:
     * venta, compra, conteo y producción, y el ajuste con el motivo que se dijo, si se dijo uno.
     * Las demás no tienen las mismas líneas, y cambiarlas perdería lo dicho.
     */
    private fun operationsOf(session: Session): List<CardOperation> {
        if (session.draft.intent !in BY_HAND) return emptyList()
        val adjusted =
            listOfNotNull(session.draft.reason, session.reading.reason)
                .firstOrNull { it != StockAdjustmentReason.COUNT_CORRECTION }
        return listOfNotNull(
            CardOperation(PhraseIntent.SALE),
            CardOperation(PhraseIntent.PURCHASE),
            CardOperation(PhraseIntent.STOCK_ADJUSTMENT, StockAdjustmentReason.COUNT_CORRECTION),
            CardOperation(PhraseIntent.PRODUCTION),
            adjusted?.let { CardOperation(PhraseIntent.STOCK_ADJUSTMENT, it) },
        )
    }

    private companion object {
        const val KEY_TEXT = "phrase_text"

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

        /** Las que se eligen a mano arriba de la tarjeta, sin perder sus líneas. */
        val BY_HAND =
            setOf(
                PhraseIntent.SALE,
                PhraseIntent.PURCHASE,
                PhraseIntent.PRODUCTION,
                PhraseIntent.STOCK_ADJUSTMENT,
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

/**
 * Un producto por crear en la tarjeta, con lo que se dijo de él: su uso, si es un servicio y su
 * categoría por su nombre. Una categoría que ya no está en el catálogo no se nombra.
 */
private fun newProduct(
    target: DraftTarget.New,
    needsCreating: Boolean,
    catalog: PhraseCatalog,
): CardProduct.New {
    val traits = target.traits
    return CardProduct.New(
        name = target.name,
        needsCreating = needsCreating,
        usage = traits.usage,
        service = traits.stockMode == ProductStockMode.UNTRACKED,
        category = traits.categoryId?.let { id ->
            catalog.productCategories.firstOrNull { it.id == id }?.name
        },
        newCategory = traits.newCategory,
    )
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
