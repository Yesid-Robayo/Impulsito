package io.github.yesidrobayo.shopledger.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentRequest
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.stock.OpeningBalanceRule
import io.github.yesidrobayo.shopledger.domain.rule.stock.StockAdjustmentRules
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.OpeningBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.AdjustStockUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.StockAdjustmentSubject
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El ajuste de stock de un producto.
 *
 * Lo escrito se guarda tal cual y se lee como número para el resultado en vivo y al guardar, con
 * los símbolos del formato regional de [numbers]. Un error de un campo aparece al intentar
 * guardar, nunca mientras se escribe, y lo escrito nunca se pierde por un error. Guardar corre una
 * sola vez a la vez.
 *
 * Un producto sin movimientos vigentes abre en el inventario inicial: lo que ya tenía al empezar
 * no es un sobrante de conteo. Si después deja de admitirlo, pasa al conteo.
 */
class StockAdjustmentViewModel(
    private val operations: StockAdjustmentOperations,
    private val numbers: ProductNumberFormat,
    private val productId: Long,
) : ViewModel() {
    private val state = MutableStateFlow(StockAdjustmentUiState())
    val uiState: StateFlow<StockAdjustmentUiState> = state.asStateFlow()

    /** Si ya llegó el producto una vez: el modo inicial se elige solo entonces. */
    private var loaded = false

    /** El producto: una falla se dice con `Reintentar`, y lo escrito sigue en el estado. */
    private val reads = ScreenReads()

    init {
        viewModelScope.launch {
            reads.guard { operations.subject(productId) }
                .collect { subject -> state.update { current -> current.withSubject(subject) } }
        }
        viewModelScope.launch {
            reads.failed.collect { failed ->
                if (failed) state.update { it.copy(content = StockAdjustmentContent.ReadFailed) }
            }
        }
    }

    fun onAction(action: StockAdjustmentAction) {
        when (action) {
            StockAdjustmentAction.Retry -> {
                state.update { it.copy(content = StockAdjustmentContent.Loading) }
                reads.retry()
            }

            is StockAdjustmentAction.ModeChanged ->
                edit { copy(mode = action.mode, errors = emptyMap()) }

            is StockAdjustmentAction.QuantityChanged ->
                edit(AdjustmentField.QUANTITY) { copy(quantityText = action.text) }

            is StockAdjustmentAction.ReasonChanged ->
                edit(AdjustmentField.NOTE) {
                    copy(
                        reason = action.reason,
                        increase = increase && action.reason.allowsIncrease,
                    )
                }

            is StockAdjustmentAction.DirectionChanged -> edit { copy(increase = action.increase) }

            is StockAdjustmentAction.UnitCostChanged ->
                edit(AdjustmentField.UNIT_COST) { copy(unitCostText = action.text) }

            is StockAdjustmentAction.NoteChanged ->
                edit(AdjustmentField.NOTE) { copy(noteText = action.text) }

            StockAdjustmentAction.Save -> save()

            StockAdjustmentAction.Close ->
                state.update {
                    if (it.dirty) it.copy(confirmingDiscard = true) else it.copy(closed = true)
                }

            StockAdjustmentAction.KeepEditing -> state.update { it.copy(confirmingDiscard = false) }

            StockAdjustmentAction.Discard ->
                state.update { it.copy(confirmingDiscard = false, closed = true) }

            // Abre otra pantalla encima: lo escrito aquí se conserva al volver.
            StockAdjustmentAction.FixCost -> Unit
        }
    }

    /** Cambia lo escrito, borra el error de [field] y recalcula el resultado en vivo. */
    private fun edit(
        field: AdjustmentField? = null,
        change: StockAdjustmentUiState.() -> StockAdjustmentUiState,
    ) {
        state.update { current ->
            val changed = current.change()
            val errors = if (field == null) changed.errors else changed.errors - field
            changed.copy(errors = errors, saveFailed = false).withPreview()
        }
    }

    private fun StockAdjustmentUiState.withSubject(
        subject: StockAdjustmentSubject?,
    ): StockAdjustmentUiState {
        if (subject == null || subject.isArchived ||
            subject.stockMode != ProductStockMode.TRACKED
        ) {
            return copy(content = StockAdjustmentContent.Unavailable, preview = null)
        }
        val firstMode = if (subject.acceptsOpeningBalance) AdjustmentMode.OPENING else mode
        val chosen = if (loaded) mode else firstMode
        loaded = true
        val shown =
            if (chosen == AdjustmentMode.OPENING && !subject.acceptsOpeningBalance) {
                AdjustmentMode.COUNT
            } else {
                chosen
            }
        return copy(content = StockAdjustmentContent.Ready(subject), mode = shown).withPreview()
    }

    /** El resultado en vivo de lo escrito, o ninguno si todavía no es una cantidad. */
    private fun StockAdjustmentUiState.withPreview(): StockAdjustmentUiState {
        val subject = (content as? StockAdjustmentContent.Ready)?.subject
            ?: return copy(preview = null)
        val quantity = (readQuantity(quantityText, mode) as? NumberInput.Value)?.value
            ?: return copy(preview = null)
        val onHand = subject.cost.onHand
        val change = changeOf(this, onHand, quantity)
        val value = when {
            change == QuantityMilli.ZERO -> Money.ZERO

            mode == AdjustmentMode.OPENING -> {
                val cost = (readCost(unitCostText) as? NumberInput.Value)?.value ?: Money.ZERO
                OpeningBalanceRule.entry(subject.cost, quantity, cost).valueOrNull()?.totalCost
            }

            else -> StockAdjustmentRules.value(subject.cost, change).valueOrNull()?.totalCost
        }
        return copy(preview = AdjustmentPreview(onHand + change, change, value))
    }

    private fun changeOf(
        form: StockAdjustmentUiState,
        onHand: QuantityMilli,
        quantity: QuantityMilli,
    ): QuantityMilli = when (form.mode) {
        AdjustmentMode.COUNT -> StockAdjustmentRules.countDifference(onHand, quantity)
        AdjustmentMode.CHANGE -> if (form.showsDirection && form.increase) quantity else -quantity
        AdjustmentMode.OPENING -> quantity
    }

    private fun save() {
        val current = state.value
        if (current.saving) return
        val subject = (current.content as? StockAdjustmentContent.Ready)?.subject ?: return
        val errors = mutableMapOf<AdjustmentField, AdjustmentProblem>()
        val quantity = quantityOrProblem(current, subject, errors)
        val opening = current.mode == AdjustmentMode.OPENING
        val cost = if (opening) costOrProblem(current, errors) else null
        noteProblem(current)?.let { errors[AdjustmentField.NOTE] = it }
        if (errors.isNotEmpty() || quantity == null) {
            state.update { it.copy(errors = errors) }
            return
        }
        state.update { it.copy(saving = true, saveFailed = false, errors = emptyMap()) }
        viewModelScope.launch {
            val next = when (current.mode) {
                AdjustmentMode.OPENING ->
                    openingOutcome(
                        operations.registerOpening(productId, quantity, cost ?: Money.ZERO),
                        quantity,
                    )

                AdjustmentMode.COUNT, AdjustmentMode.CHANGE ->
                    adjustmentOutcome(operations.adjust(requestOf(current, quantity)))
            }
            state.update { next(it.copy(saving = false)) }
        }
    }

    private fun requestOf(form: StockAdjustmentUiState, quantity: QuantityMilli) =
        when (form.mode) {
            AdjustmentMode.COUNT ->
                StockAdjustmentRequest.Count(productId, quantity, form.noteText)

            AdjustmentMode.CHANGE, AdjustmentMode.OPENING ->
                StockAdjustmentRequest.Change(
                    productId = productId,
                    reason = form.reason,
                    quantity = quantity,
                    increase = form.showsDirection && form.increase,
                    note = form.noteText,
                )
        }

    /** La cantidad escrita, o `null` con su problema en [errors]. */
    private fun quantityOrProblem(
        form: StockAdjustmentUiState,
        subject: StockAdjustmentSubject,
        errors: MutableMap<AdjustmentField, AdjustmentProblem>,
    ): QuantityMilli? {
        val problem = when (val read = readQuantity(form.quantityText, form.mode)) {
            is NumberInput.Value -> quantityProblem(form.mode, read.value, subject)

            // Un conteo en blanco no dice cuánto hay; una salida en blanco no mueve nada.
            NumberInput.Blank ->
                if (form.mode == AdjustmentMode.COUNT) {
                    AdjustmentProblem.NotANumber
                } else {
                    AdjustmentProblem.Zero
                }

            NumberInput.Malformed -> AdjustmentProblem.NotANumber

            is NumberInput.TooManyDecimals -> AdjustmentProblem.TooManyDecimals(read.allowed)

            is NumberInput.TooLarge -> AdjustmentProblem.TooHigh
        }
        if (problem != null) {
            errors[AdjustmentField.QUANTITY] = problem
            return null
        }
        return (readQuantity(form.quantityText, form.mode) as NumberInput.Value).value
    }

    private fun quantityProblem(
        mode: AdjustmentMode,
        quantity: QuantityMilli,
        subject: StockAdjustmentSubject,
    ): AdjustmentProblem? {
        val allowsFraction = subject.unit.allowsFraction
        return when {
            mode != AdjustmentMode.COUNT && quantity == QuantityMilli.ZERO -> AdjustmentProblem.Zero
            !allowsFraction && !quantity.isWholeUnits -> AdjustmentProblem.WholeUnitsOnly
            else -> null
        }
    }

    private fun costOrProblem(
        form: StockAdjustmentUiState,
        errors: MutableMap<AdjustmentField, AdjustmentProblem>,
    ): Money? {
        val problem = when (val read = readCost(form.unitCostText)) {
            is NumberInput.Value -> return read.value

            // Sin costo también se guarda: queda marcado como producto sin costo.
            NumberInput.Blank -> return Money.ZERO

            NumberInput.Malformed -> AdjustmentProblem.NotANumber

            is NumberInput.TooManyDecimals -> AdjustmentProblem.TooManyDecimals(read.allowed)

            is NumberInput.TooLarge -> AdjustmentProblem.TooHigh
        }
        errors[AdjustmentField.UNIT_COST] = problem
        return null
    }

    private fun noteProblem(form: StockAdjustmentUiState): AdjustmentProblem? {
        val note = form.noteText.trim()
        return when {
            note.length > AdjustStockUseCase.MAX_NOTE_LENGTH ->
                AdjustmentProblem.TooLong(AdjustStockUseCase.MAX_NOTE_LENGTH)

            note.isEmpty() && form.mode == AdjustmentMode.CHANGE && form.reason.requiresNote ->
                AdjustmentProblem.NoteRequired

            else -> null
        }
    }

    private fun readQuantity(text: String, mode: AdjustmentMode): NumberInput<QuantityMilli> {
        // Un conteo es un saldo; una salida o una entrada, una cantidad de línea.
        val limit =
            if (mode == AdjustmentMode.COUNT) Limit.ON_HAND_MILLI else Limit.LINE_QUANTITY_MILLI
        return NumberInputParser.quantity(text, numbers.quantitySymbols, limit)
    }

    private fun readCost(text: String): NumberInput<Money> = NumberInputParser.money(
        text,
        numbers.moneySymbols,
        numbers.currencyDecimals,
        Limit.UNIT_MONEY,
    )

    private fun adjustmentOutcome(
        result: StockAdjustmentResult,
    ): (StockAdjustmentUiState) -> StockAdjustmentUiState = when (result) {
        is StockAdjustmentResult.Adjusted ->
            { form -> form.copy(outcome = AdjustmentOutcome.Saved(result.onHandAfter)) }

        StockAdjustmentResult.NoDifference ->
            { form -> form.copy(outcome = AdjustmentOutcome.NoDifference) }

        StockAdjustmentResult.NotFound,
        StockAdjustmentResult.ProductArchived,
        StockAdjustmentResult.NotTracked,
        -> { form -> form.copy(content = StockAdjustmentContent.Unavailable) }

        is StockAdjustmentResult.InvalidQuantity ->
            fieldError(AdjustmentField.QUANTITY, problemOf(result.check))

        is StockAdjustmentResult.AboveLimit ->
            fieldError(AdjustmentField.QUANTITY, AdjustmentProblem.TooHigh)

        StockAdjustmentResult.NoteRequired ->
            fieldError(AdjustmentField.NOTE, AdjustmentProblem.NoteRequired)

        is StockAdjustmentResult.NoteTooLong ->
            fieldError(AdjustmentField.NOTE, AdjustmentProblem.TooLong(result.maxLength))

        // La pantalla nunca pide una entrada con un motivo que solo resta; si pasara, no guarda.
        StockAdjustmentResult.IncreaseNotAllowed,
        StockAdjustmentResult.UnknownTimeZone,
        StockAdjustmentResult.CurrencyUnconfirmed,
        is StockAdjustmentResult.StorageFailed,
        -> { form -> form.copy(saveFailed = true) }
    }

    private fun openingOutcome(
        result: OpeningBalanceResult,
        quantity: QuantityMilli,
    ): (StockAdjustmentUiState) -> StockAdjustmentUiState = when (result) {
        // Con el libro efectivo vacío, el saldo era cero: queda lo que entró.
        is OpeningBalanceResult.Registered ->
            { form -> form.copy(outcome = AdjustmentOutcome.Saved(quantity)) }

        OpeningBalanceResult.NotFound,
        OpeningBalanceResult.ProductArchived,
        OpeningBalanceResult.NotTracked,
        -> { form -> form.copy(content = StockAdjustmentContent.Unavailable) }

        is OpeningBalanceResult.Invalid ->
            { form -> form.copy(errors = result.issues.mapNotNull(::issueError).toMap()) }

        is OpeningBalanceResult.AboveLimit ->
            fieldError(AdjustmentField.QUANTITY, AdjustmentProblem.TooHigh)

        // Otro movimiento entró mientras tanto: el formulario ya pasó al conteo.
        OpeningBalanceResult.AlreadyHasMovements,
        OpeningBalanceResult.UnknownTimeZone,
        OpeningBalanceResult.CurrencyUnconfirmed,
        is OpeningBalanceResult.StorageFailed,
        -> { form -> form.copy(saveFailed = true) }
    }

    private fun issueError(issue: ProductFormIssue): Pair<AdjustmentField, AdjustmentProblem>? =
        when (issue) {
            ProductFormIssue.INITIAL_STOCK_OUT_OF_RANGE ->
                AdjustmentField.QUANTITY to AdjustmentProblem.TooHigh

            ProductFormIssue.INITIAL_STOCK_FRACTION_NOT_ALLOWED ->
                AdjustmentField.QUANTITY to AdjustmentProblem.WholeUnitsOnly

            ProductFormIssue.COST_OUT_OF_RANGE ->
                AdjustmentField.UNIT_COST to
                    AdjustmentProblem.TooHigh

            // El inventario inicial solo revisa la cantidad y el costo.
            ProductFormIssue.NAME_EMPTY, ProductFormIssue.NAME_TOO_LONG,
            ProductFormIssue.SALE_PRICE_OUT_OF_RANGE, ProductFormIssue.COST_NOT_APPLICABLE,
            ProductFormIssue.MIN_STOCK_OUT_OF_RANGE,
            ProductFormIssue.MIN_STOCK_FRACTION_NOT_ALLOWED,
            ProductFormIssue.STOCK_NOT_APPLICABLE, ProductFormIssue.BARCODE_TOO_LONG,
            -> null
        }

    private fun problemOf(check: UnitQuantityCheck): AdjustmentProblem = when (check) {
        UnitQuantityCheck.NotPositive -> AdjustmentProblem.Zero

        UnitQuantityCheck.FractionNotAllowed -> AdjustmentProblem.WholeUnitsOnly

        // Una cantidad válida no vuelve como rechazo.
        UnitQuantityCheck.Valid -> AdjustmentProblem.NotANumber
    }

    private fun fieldError(
        field: AdjustmentField,
        problem: AdjustmentProblem,
    ): (StockAdjustmentUiState) -> StockAdjustmentUiState = { form ->
        form.copy(errors = form.errors + (field to problem))
    }
}
