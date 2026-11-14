package io.github.yesidrobayo.shopledger.ui.variant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantAxes
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText
import io.github.yesidrobayo.shopledger.domain.rule.catalog.VariantRules
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateVariantsResult
import io.github.yesidrobayo.shopledger.ui.product.FieldProblem
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** El producto principal con su unidad y sus variantes, archivadas o no. */
data class VariantParent(val product: Product, val unit: MeasureUnit, val variants: List<Product>)

/**
 * Lo que la pantalla de variantes le pide al dominio. Es una interfaz para que las pruebas del
 * ViewModel den respuestas fijas sin una base.
 */
interface VariantsOperations {
    /** El principal, de nuevo con cada cambio; `null` si ya no existe. */
    fun parent(): Flow<VariantParent?>

    suspend fun create(axes: VariantAxes, drafts: List<VariantDraft>): CreateVariantsResult
}

/** Lo que la persona hace en la pantalla de variantes. */
sealed interface VariantsAction {
    data class AxisChange(val axis: VariantAxis, val text: String) : VariantsAction

    data object ShowSecondAxis : VariantsAction

    data class NewValueChange(val axis: VariantAxis, val text: String) : VariantsAction

    data class AddValue(val axis: VariantAxis) : VariantsAction

    data class RemoveValue(val axis: VariantAxis, val value: String) : VariantsAction

    data class ToggleCombo(val combo: VariantCombo) : VariantsAction

    data class ApplyAllChange(val field: VariantField, val text: String) : VariantsAction

    data object ApplyToAll : VariantsAction

    data class InputChange(val combo: VariantCombo, val field: VariantField, val text: String) :
        VariantsAction

    data class Expand(val combo: VariantCombo) : VariantsAction

    data object Save : VariantsAction

    data object Close : VariantsAction

    data object Discard : VariantsAction

    data object KeepEditing : VariantsAction

    data object Retry : VariantsAction

    /** Va a confirmar la moneda del negocio: el aviso ya no hace falta. */
    data object ConfirmCurrency : VariantsAction
}

/**
 * La pantalla de variantes: arma la matriz con los ejes y sus valores, y crea en una sola
 * transacción todas las combinaciones marcadas que todavía no existen.
 *
 * Lee los montos y cantidades con los símbolos del formato regional, igual que el formulario de
 * producto, y un error nunca borra lo escrito: se marca en su campo o arriba del botón.
 */
class VariantsViewModel(
    private val operations: VariantsOperations,
    private val numbers: ProductNumberFormat,
) : ViewModel() {
    private val state = MutableStateFlow(VariantsUiState())
    val uiState: StateFlow<VariantsUiState> = state.asStateFlow()

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            operations.parent()
                .catch { state.update { it.copy(loading = false, readFailed = true) } }
                .collect { parent -> state.update { loaded(it, parent) } }
        }
    }

    private fun loaded(current: VariantsUiState, parent: VariantParent?): VariantsUiState {
        if (parent == null) {
            return current.copy(loading = false, notice = VariantsNotice.ParentNotAllowed)
        }
        val existing = parent.variants.map(::existingOf)
        val base = current.copy(
            loading = false,
            readFailed = false,
            parentName = parent.product.details.name,
            unit = parent.unit,
            tracksStock = parent.product.details.stockMode == ProductStockMode.TRACKED,
            existing = existing,
        )
        // Solo la primera vez: después la persona ya puede haber escrito valores nuevos.
        if (!current.loading || existing.isEmpty()) return base
        val first = parent.variants.first().details
        return base.copy(
            firstAxis = first.variantAxis1.orEmpty(),
            secondAxis = first.variantAxis2,
            firstValues = existing.map { it.combo.first }.distinctBy(TextNormalizer::normalize),
            secondValues = existing.mapNotNull { it.combo.second }
                .distinctBy(TextNormalizer::normalize),
        )
    }

    fun onAction(action: VariantsAction) {
        when (action) {
            is VariantsAction.AxisChange -> state.update { current ->
                val axisProblems = current.axisProblems - action.axis
                when (action.axis) {
                    VariantAxis.FIRST ->
                        current.copy(firstAxis = action.text, axisProblems = axisProblems)

                    VariantAxis.SECOND ->
                        current.copy(secondAxis = action.text, axisProblems = axisProblems)
                }
            }

            VariantsAction.ShowSecondAxis -> state.update {
                if (it.axesLocked || it.secondAxis != null) it else it.copy(secondAxis = "")
            }

            is VariantsAction.NewValueChange -> state.update { current ->
                val problems = current.valueProblems - action.axis
                when (action.axis) {
                    VariantAxis.FIRST ->
                        current.copy(newFirstValue = action.text, valueProblems = problems)

                    VariantAxis.SECOND ->
                        current.copy(newSecondValue = action.text, valueProblems = problems)
                }
            }

            is VariantsAction.AddValue -> state.update { addValue(it, action.axis) }

            is VariantsAction.RemoveValue -> state.update { removeValue(it, action) }

            is VariantsAction.ToggleCombo -> state.update { current ->
                val key = action.combo.key
                if (key in current.existingKeys) {
                    current
                } else if (key in current.unchecked) {
                    current.copy(unchecked = current.unchecked - key, notice = null)
                } else {
                    current.copy(unchecked = current.unchecked + key)
                }
            }

            is VariantsAction.ApplyAllChange -> state.update {
                it.copy(applyAll = it.applyAll.with(action.field, action.text))
            }

            VariantsAction.ApplyToAll -> state.update { withPrices(applyToAll(it)) }

            is VariantsAction.InputChange -> state.update { current ->
                val key = action.combo.key
                val input = (current.inputs[key] ?: VariantInput()).with(action.field, action.text)
                val problems = current.fieldProblems[key].orEmpty() - action.field
                withPrices(
                    current.copy(
                        inputs = current.inputs + (key to input),
                        fieldProblems = current.fieldProblems + (key to problems),
                    ),
                )
            }

            is VariantsAction.Expand -> state.update {
                val key = action.combo.key
                it.copy(expanded = if (it.expanded == key) null else key)
            }

            VariantsAction.Save -> save()

            VariantsAction.Close -> state.update {
                if (it.dirty) it.copy(confirmingDiscard = true) else it.copy(closed = true)
            }

            VariantsAction.Discard -> state.update {
                it.copy(confirmingDiscard = false, closed = true)
            }

            VariantsAction.KeepEditing -> state.update { it.copy(confirmingDiscard = false) }

            VariantsAction.Retry -> {
                state.update { it.copy(loading = true, readFailed = false) }
                observe()
            }

            VariantsAction.ConfirmCurrency -> state.update { it.copy(notice = null) }
        }
    }

    private fun addValue(current: VariantsUiState, axis: VariantAxis): VariantsUiState {
        val raw = when (axis) {
            VariantAxis.FIRST -> current.newFirstValue
            VariantAxis.SECOND -> current.newSecondValue
        }
        val values = when (axis) {
            VariantAxis.FIRST -> current.firstValues
            VariantAxis.SECOND -> current.secondValues
        }
        val value = raw.trim()
        val problem = when {
            TextNormalizer.normalize(value).isEmpty() -> VariantTextProblem.EMPTY

            CatalogText.length(value) > VariantRules.TEXT_MAX -> VariantTextProblem.TOO_LONG

            values.any { TextNormalizer.normalize(it) == TextNormalizer.normalize(value) } ->
                VariantTextProblem.REPEATED

            else -> null
        }
        if (problem != null) {
            return current.copy(valueProblems = current.valueProblems + (axis to problem))
        }
        return when (axis) {
            VariantAxis.FIRST ->
                current.copy(firstValues = values + value, newFirstValue = "", notice = null)

            VariantAxis.SECOND ->
                current.copy(secondValues = values + value, newSecondValue = "", notice = null)
        }
    }

    /** Quita un valor que todavía no es parte de ninguna variante creada. */
    private fun removeValue(
        current: VariantsUiState,
        action: VariantsAction.RemoveValue,
    ): VariantsUiState {
        val normalized = TextNormalizer.normalize(action.value)
        val used = current.existing.any {
            val value = when (action.axis) {
                VariantAxis.FIRST -> it.combo.first
                VariantAxis.SECOND -> it.combo.second
            }
            value != null && TextNormalizer.normalize(value) == normalized
        }
        if (used) return current
        return when (action.axis) {
            VariantAxis.FIRST ->
                current.copy(firstValues = current.firstValues - action.value)

            VariantAxis.SECOND ->
                current.copy(secondValues = current.secondValues - action.value)
        }
    }

    /** [current] con el precio leído de cada fila, el que se ve con la fila cerrada. */
    private fun withPrices(current: VariantsUiState): VariantsUiState = current.copy(
        prices = current.inputs.mapNotNull { (key, input) ->
            if (input.price.isBlank()) return@mapNotNull null
            var valid = true
            val price = money(input.price) { valid = false }
            if (valid) key to price else null
        }.toMap(),
    )

    /** Llena en cada variante nueva lo que esté vacío con lo de `Aplicar a todas`. */
    private fun applyToAll(current: VariantsUiState): VariantsUiState {
        val all = current.applyAll
        val inputs = current.newCombos.associate { combo ->
            val input = current.inputs[combo.key] ?: VariantInput()
            combo.key to input.copy(
                price = input.price.ifBlank { all.price },
                cost = input.cost.ifBlank { all.cost },
                minStock = input.minStock.ifBlank { all.minStock },
            )
        }
        return current.copy(inputs = current.inputs + inputs)
    }

    private fun save() {
        val current = state.value
        if (current.saving || current.loading) return
        val combos = current.newCombos
        val axisProblems = axisProblemsOf(current)
        if (combos.isEmpty() || axisProblems.isNotEmpty()) {
            state.update {
                it.copy(
                    axisProblems = axisProblems,
                    notice = if (combos.isEmpty()) VariantsNotice.NothingSelected else it.notice,
                )
            }
            return
        }
        val fieldProblems = mutableMapOf<Pair<String, String?>, Map<VariantField, FieldProblem>>()
        val drafts = combos.map { combo ->
            val problems = mutableMapOf<VariantField, FieldProblem>()
            val draft = draftOf(combo, current.inputs[combo.key] ?: VariantInput(), problems)
            if (problems.isNotEmpty()) fieldProblems[combo.key] = problems
            draft
        }
        if (fieldProblems.isNotEmpty()) {
            state.update {
                it.copy(fieldProblems = fieldProblems, expanded = fieldProblems.keys.first())
            }
            return
        }
        val axes = VariantAxes(current.firstAxis, current.secondAxis)
        state.update { it.copy(saving = true, notice = null, comboProblems = emptyMap()) }
        viewModelScope.launch {
            val result = operations.create(axes, drafts)
            state.update { resultOf(it.copy(saving = false), combos, result) }
        }
    }

    private fun axisProblemsOf(current: VariantsUiState): Map<VariantAxis, VariantTextProblem> {
        val axes = listOfNotNull(
            VariantAxis.FIRST to current.firstAxis,
            current.secondAxis?.let { VariantAxis.SECOND to it },
        )
        return axes.mapNotNull { (axis, text) ->
            val trimmed = text.trim()
            when {
                TextNormalizer.normalize(trimmed).isEmpty() -> axis to VariantTextProblem.EMPTY

                CatalogText.length(trimmed) > VariantRules.TEXT_MAX ->
                    axis to VariantTextProblem.TOO_LONG

                else -> null
            }
        }.toMap()
    }

    private fun draftOf(
        combo: VariantCombo,
        input: VariantInput,
        problems: MutableMap<VariantField, FieldProblem>,
    ): VariantDraft = VariantDraft(
        values = VariantValues(combo.first, combo.second),
        salePrice = money(input.price) { problems[VariantField.PRICE] = it },
        cost = money(input.cost) { problems[VariantField.COST] = it },
        initialStock = quantity(input.initialStock, Limit.LINE_QUANTITY_MILLI) {
            problems[VariantField.INITIAL_STOCK] = it
        },
        minStock = quantity(input.minStock, Limit.ON_HAND_MILLI) {
            problems[VariantField.MIN_STOCK] = it
        },
        barcode = input.barcode.ifBlank { null },
    )

    private fun resultOf(
        current: VariantsUiState,
        combos: List<VariantCombo>,
        result: CreateVariantsResult,
    ): VariantsUiState = when (result) {
        is CreateVariantsResult.Created -> current.copy(
            createdCount = result.productIds.size,
            inputs = emptyMap(),
            applyAll = VariantInput(),
            unchecked = emptySet(),
            prices = emptyMap(),
        )

        is CreateVariantsResult.Invalid -> invalid(current, combos, result)

        is CreateVariantsResult.DuplicateName -> current.copy(
            comboProblems = mapOf(combos[result.index].key to VariantComboProblem.REPEATED),
            expanded = combos[result.index].key,
        )

        is CreateVariantsResult.DuplicateBarcode -> {
            val key = combos[result.index].key
            current.copy(
                fieldProblems = mapOf(
                    key to mapOf(
                        VariantField.BARCODE to FieldProblem.Duplicate(result.existingName),
                    ),
                ),
                expanded = key,
            )
        }

        CreateVariantsResult.ParentHasStock ->
            current.copy(notice = VariantsNotice.ParentHasStock)

        CreateVariantsResult.ParentNotFound, CreateVariantsResult.ParentArchived,
        CreateVariantsResult.ParentIsVariant, CreateVariantsResult.ParentMadeOfComponents,
        -> current.copy(notice = VariantsNotice.ParentNotAllowed)

        CreateVariantsResult.CurrencyUnconfirmed ->
            current.copy(notice = VariantsNotice.CurrencyUnconfirmed)

        CreateVariantsResult.UnknownTimeZone, is CreateVariantsResult.AboveLimit ->
            current.copy(notice = VariantsNotice.SaveFailed(outOfSpace = false))

        is CreateVariantsResult.StorageFailed ->
            current.copy(notice = VariantsNotice.SaveFailed(result.failure.isOutOfSpace))
    }

    private fun invalid(
        current: VariantsUiState,
        combos: List<VariantCombo>,
        result: CreateVariantsResult.Invalid,
    ): VariantsUiState {
        val axisProblems = result.axisIssues.mapNotNull { issue ->
            when (issue) {
                VariantIssue.AXIS_EMPTY -> VariantAxis.FIRST to VariantTextProblem.EMPTY

                VariantIssue.AXIS_TOO_LONG -> VariantAxis.FIRST to VariantTextProblem.TOO_LONG

                VariantIssue.AXES_CHANGED, VariantIssue.NO_VARIANTS, VariantIssue.VALUE_EMPTY,
                VariantIssue.VALUE_TOO_LONG, VariantIssue.VALUES_DO_NOT_MATCH_AXES,
                VariantIssue.DUPLICATE_COMBINATION, VariantIssue.NAME_TOO_LONG,
                VariantIssue.BARCODE_REPEATED,
                -> null
            }
        }.toMap()
        val comboProblems = result.variantIssues.mapNotNull { (index, issues) ->
            val problem = when {
                VariantIssue.NAME_TOO_LONG in issues -> VariantComboProblem.NAME_TOO_LONG
                else -> VariantComboProblem.REPEATED
            }
            combos.getOrNull(index)?.let { it.key to problem }
        }.toMap()
        val fieldProblems = result.formIssues.mapNotNull { (index, issues) ->
            combos.getOrNull(index)?.let { it.key to issues.associate(::fieldProblemOf) }
        }.toMap() + result.variantIssues.filterValues { VariantIssue.BARCODE_REPEATED in it }
            .mapNotNull { (index, _) ->
                combos.getOrNull(index)?.let {
                    it.key to mapOf(VariantField.BARCODE to FieldProblem.Duplicate(it.label))
                }
            }
        return current.copy(
            axisProblems = axisProblems,
            comboProblems = comboProblems,
            fieldProblems = fieldProblems,
            expanded = (comboProblems.keys + fieldProblems.keys).firstOrNull(),
            notice = if (VariantIssue.NO_VARIANTS in result.axisIssues) {
                VariantsNotice.NothingSelected
            } else {
                null
            },
        )
    }

    private fun fieldProblemOf(issue: ProductFormIssue): Pair<VariantField, FieldProblem> =
        when (issue) {
            ProductFormIssue.SALE_PRICE_OUT_OF_RANGE,
            ProductFormIssue.REFERENCE_PRICE_OUT_OF_RANGE,
            ProductFormIssue.NAME_EMPTY, ProductFormIssue.NAME_TOO_LONG,
            -> VariantField.PRICE to FieldProblem.TooHigh

            ProductFormIssue.COST_OUT_OF_RANGE, ProductFormIssue.COST_NOT_APPLICABLE,
            ProductFormIssue.REPLACEMENT_COST_OUT_OF_RANGE,
            ProductFormIssue.REPLACEMENT_COST_NOT_APPLICABLE,
            -> VariantField.COST to FieldProblem.TooHigh

            ProductFormIssue.INITIAL_STOCK_OUT_OF_RANGE, ProductFormIssue.STOCK_NOT_APPLICABLE ->
                VariantField.INITIAL_STOCK to FieldProblem.TooHigh

            ProductFormIssue.INITIAL_STOCK_FRACTION_NOT_ALLOWED ->
                VariantField.INITIAL_STOCK to FieldProblem.FractionNotAllowed

            ProductFormIssue.MIN_STOCK_OUT_OF_RANGE ->
                VariantField.MIN_STOCK to FieldProblem.TooHigh

            ProductFormIssue.MIN_STOCK_FRACTION_NOT_ALLOWED ->
                VariantField.MIN_STOCK to FieldProblem.FractionNotAllowed

            ProductFormIssue.BARCODE_TOO_LONG ->
                VariantField.BARCODE to FieldProblem.TooLong(CatalogText.BARCODE_MAX)
        }

    /** Un monto por unidad entera; en blanco es cero. Un problema se informa por [problem]. */
    private fun money(text: String, problem: (FieldProblem) -> Unit): Money = when (
        val read = NumberInputParser.money(
            text,
            numbers.moneySymbols,
            numbers.currencyDecimals,
            Limit.UNIT_MONEY,
        )
    ) {
        is NumberInput.Value -> read.value

        NumberInput.Blank -> Money.ZERO

        else -> {
            problem(problemOf(read))
            Money.ZERO
        }
    }

    /** Una cantidad en milésimas hasta [limit]; en blanco es cero. */
    private fun quantity(
        text: String,
        limit: Limit,
        problem: (FieldProblem) -> Unit,
    ): QuantityMilli =
        when (val read = NumberInputParser.quantity(text, numbers.quantitySymbols, limit)) {
            is NumberInput.Value -> read.value

            NumberInput.Blank -> QuantityMilli.ZERO

            else -> {
                problem(problemOf(read))
                QuantityMilli.ZERO
            }
        }

    private fun problemOf(read: NumberInput<*>): FieldProblem = when (read) {
        is NumberInput.TooManyDecimals -> FieldProblem.TooManyDecimals(read.allowed)
        is NumberInput.TooLarge -> FieldProblem.TooHigh
        is NumberInput.Value, NumberInput.Blank, NumberInput.Malformed -> FieldProblem.NotANumber
    }

    private companion object {
        fun existingOf(product: Product): ExistingVariant {
            val details = product.details
            return ExistingVariant(
                id = product.id,
                name = details.name,
                combo = VariantCombo(details.variantValue1.orEmpty(), details.variantValue2),
                salePrice = product.salePrice,
                onHand = product.onHand,
                archived = product.isArchived,
            )
        }
    }
}

/** [this] con [text] en [field]. */
internal fun VariantInput.with(field: VariantField, text: String): VariantInput = when (field) {
    VariantField.PRICE -> copy(price = text)
    VariantField.COST -> copy(cost = text)
    VariantField.INITIAL_STOCK -> copy(initialStock = text)
    VariantField.MIN_STOCK -> copy(minStock = text)
    VariantField.BARCODE -> copy(barcode = text)
}

/** El texto de [field] en [this]. */
internal fun VariantInput.of(field: VariantField): String = when (field) {
    VariantField.PRICE -> price
    VariantField.COST -> cost
    VariantField.INITIAL_STOCK -> initialStock
    VariantField.MIN_STOCK -> minStock
    VariantField.BARCODE -> barcode
}
