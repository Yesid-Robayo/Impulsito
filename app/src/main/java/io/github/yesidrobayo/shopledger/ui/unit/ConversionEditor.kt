package io.github.yesidrobayo.shopledger.ui.unit

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionResult
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Los campos de la hoja de una conversión que pueden llevar un error. */
enum class ConversionField {
    PRODUCT,
    UNIT,
    FACTOR,
    PRICE,
    BARCODE,
}

/** Por qué un campo de la conversión no se guarda así. La hoja lo dice con su texto. */
sealed interface ConversionProblem {
    /** Falta elegirlo. */
    data object Missing : ConversionProblem

    /** El factor es cero, o está en blanco. */
    data object Zero : ConversionProblem

    /** No es un número. */
    data object NotANumber : ConversionProblem

    /** Es demasiado alto: casi siempre, un error al escribir. */
    data object TooHigh : ConversionProblem

    /** La unidad alterna es la base del producto. */
    data object SameAsBase : ConversionProblem

    /** El producto ya tiene una conversión a esa unidad. */
    data object Taken : ConversionProblem

    /** El código ya es de [productName]. */
    data class DuplicateBarcode(val productName: String) : ConversionProblem

    /** Pasa de [max] caracteres. */
    data class TooLong(val max: Int) : ConversionProblem
}

/**
 * La hoja de una conversión mientras se escribe.
 *
 * [editingId] nulo es una conversión nueva. Al editar, el producto y la unidad quedan fijos; con
 * [productLocked], el producto también queda fijo en una nueva, porque se abrió desde él.
 * [originalFactorMilli] es el factor guardado: si cambia, primero se confirma, porque solo afecta a
 * documentos nuevos.
 */
@Immutable
data class ConversionEditor(
    val editingId: Long?,
    val productId: Long?,
    val productLocked: Boolean,
    val unitId: Long?,
    val factor: String = "",
    val price: String = "",
    val barcode: String = "",
    val forSale: Boolean = true,
    val forPurchase: Boolean = true,
    val originalFactorMilli: Long? = null,
    val errors: Map<ConversionField, ConversionProblem> = emptyMap(),
    val saving: Boolean = false,
    val saveFailed: SaveFailure? = null,
    val confirmingFactorChange: Boolean = false,
    val confirmingDelete: Boolean = false,
) {
    /** Si el producto y la unidad quedan fijos: al editar, o al abrirse desde el producto. */
    val unitLocked: Boolean get() = editingId != null
}

/** Lo que la persona hace en la hoja de una conversión. */
sealed interface ConversionEditorAction {
    data class Product(val productId: Long) : ConversionEditorAction

    data class AlternateUnit(val unitId: Long) : ConversionEditorAction

    data class Factor(val text: String) : ConversionEditorAction

    data class Price(val text: String) : ConversionEditorAction

    data class Barcode(val text: String) : ConversionEditorAction

    data class ForSale(val checked: Boolean) : ConversionEditorAction

    data class ForPurchase(val checked: Boolean) : ConversionEditorAction

    data object Save : ConversionEditorAction

    /** Confirmó cambiar el factor de una conversión guardada. */
    data object ConfirmFactorChange : ConversionEditorAction

    data object AskDelete : ConversionEditorAction

    data object ConfirmDelete : ConversionEditorAction

    /** Cerró un diálogo sin confirmar. */
    data object KeepEditing : ConversionEditorAction

    data object Dismiss : ConversionEditorAction
}

/**
 * La hoja de crear, editar o quitar una conversión, para la pantalla de unidades y para el
 * formulario de un producto.
 *
 * Lo escrito se guarda tal cual, y se lee como número solo al guardar, con los símbolos de [numbers].
 * Los errores aparecen al guardar, y lo escrito nunca se pierde por uno. Guardar corre una sola vez a
 * la vez.
 *
 * @param onSaved se llama con la hoja ya cerrada, tras guardar o quitar.
 */
class ConversionEditorModel(
    private val scope: CoroutineScope,
    private val numbers: ProductNumberFormat,
    private val save: suspend (Long?, ConversionDraft) -> SaveConversionResult,
    private val delete: suspend (Long) -> DeleteConversionResult,
    private val onSaved: (removed: Boolean) -> Unit,
) {
    private val state = MutableStateFlow<ConversionEditor?>(null)
    val editor: StateFlow<ConversionEditor?> = state.asStateFlow()

    /** Abre la hoja para una conversión nueva, del producto [productId] si ya se sabe cuál. */
    fun openNew(productId: Long? = null) {
        state.value =
            ConversionEditor(
                editingId = null,
                productId = productId,
                productLocked = productId != null,
                unitId = null,
            )
    }

    /** Abre la hoja con [conversion] para editarla. */
    fun openEdit(conversion: ProductConversion) {
        val details = conversion.details
        state.value =
            ConversionEditor(
                editingId = conversion.id,
                productId = conversion.productId,
                productLocked = true,
                unitId = details.unitId,
                factor = decimalText(
                    details.factorMilli,
                    QUANTITY_DECIMALS,
                    numbers.quantitySymbols.decimalSeparator,
                ),
                price =
                    details.salePrice?.let {
                        decimalText(
                            it.minor,
                            numbers.currencyDecimals,
                            numbers.moneySymbols.decimalSeparator,
                        )
                    }.orEmpty(),
                barcode = details.barcode.orEmpty(),
                forSale = details.forSale,
                forPurchase = details.forPurchase,
                originalFactorMilli = details.factorMilli,
            )
    }

    fun onAction(action: ConversionEditorAction) {
        when (action) {
            is ConversionEditorAction.Product ->
                edit(ConversionField.PRODUCT) { copy(productId = action.productId) }

            is ConversionEditorAction.AlternateUnit -> edit(ConversionField.UNIT) {
                copy(unitId = action.unitId)
            }

            is ConversionEditorAction.Factor -> edit(ConversionField.FACTOR) {
                copy(factor = action.text)
            }

            is ConversionEditorAction.Price -> edit(ConversionField.PRICE) {
                copy(price = action.text)
            }

            is ConversionEditorAction.Barcode ->
                edit(ConversionField.BARCODE) { copy(barcode = action.text) }

            is ConversionEditorAction.ForSale -> edit(null) { copy(forSale = action.checked) }

            is ConversionEditorAction.ForPurchase -> edit(null) {
                copy(forPurchase = action.checked)
            }

            ConversionEditorAction.Save -> save(confirmed = false)

            ConversionEditorAction.ConfirmFactorChange -> {
                change { copy(confirmingFactorChange = false) }
                save(confirmed = true)
            }

            ConversionEditorAction.AskDelete -> change { copy(confirmingDelete = true) }

            ConversionEditorAction.ConfirmDelete -> remove()

            ConversionEditorAction.KeepEditing ->
                change { copy(confirmingFactorChange = false, confirmingDelete = false) }

            ConversionEditorAction.Dismiss -> state.value = null
        }
    }

    private fun change(block: ConversionEditor.() -> ConversionEditor) {
        state.update { it?.block() }
    }

    /** Cambia lo escrito y borra el error de [field], si lo tenía. */
    private fun edit(field: ConversionField?, block: ConversionEditor.() -> ConversionEditor) {
        change {
            block().copy(errors = if (field == null) errors else errors - field, saveFailed = null)
        }
    }

    private fun save(confirmed: Boolean) {
        val current = state.value ?: return
        if (current.saving) return
        val errors = mutableMapOf<ConversionField, ConversionProblem>()
        val productId = current.productId
        val unitId = current.unitId
        if (productId == null) errors[ConversionField.PRODUCT] = ConversionProblem.Missing
        if (unitId == null) errors[ConversionField.UNIT] = ConversionProblem.Missing
        val factor = readFactor(current.factor) { errors[ConversionField.FACTOR] = it }
        val price = readPrice(current.price) { errors[ConversionField.PRICE] = it }
        val barcode = CatalogText.barcode(current.barcode)
        if (barcode != null && CatalogText.length(barcode) > CatalogText.BARCODE_MAX) {
            errors[ConversionField.BARCODE] = ConversionProblem.TooLong(CatalogText.BARCODE_MAX)
        }
        if (errors.isNotEmpty() || productId == null || unitId == null) {
            change { copy(errors = errors) }
            return
        }
        val original = current.originalFactorMilli
        if (!confirmed && original != null && original != factor.milli) {
            change { copy(confirmingFactorChange = true) }
            return
        }
        val draft =
            ConversionDraft(
                productId = productId,
                unitId = unitId,
                factorMilli = factor.milli,
                salePrice = price,
                barcode = barcode,
                forSale = current.forSale,
                forPurchase = current.forPurchase,
            )
        change { copy(saving = true, saveFailed = null) }
        scope.launch {
            val result = save(current.editingId, draft)
            if (result is SaveConversionResult.Saved) {
                state.value = null
                onSaved(false)
            } else {
                change { copy(saving = false).withProblem(result) }
            }
        }
    }

    private fun remove() {
        val current = state.value ?: return
        val id = current.editingId ?: return
        if (current.saving) return
        change { copy(confirmingDelete = false, saving = true, saveFailed = null) }
        scope.launch {
            when (val result = delete(id)) {
                // Ya no estaba: el resultado para la persona es el mismo.
                DeleteConversionResult.Deleted, DeleteConversionResult.NotFound -> {
                    state.value = null
                    onSaved(true)
                }

                is DeleteConversionResult.StorageFailed ->
                    change {
                        copy(saving = false, saveFailed = SaveFailure(result.failure.isOutOfSpace))
                    }
            }
        }
    }

    private fun ConversionEditor.withProblem(result: SaveConversionResult): ConversionEditor =
        when (result) {
            is SaveConversionResult.Saved -> this

            is SaveConversionResult.Invalid -> copy(errors = errors + errorsOf(result.errors))

            SaveConversionResult.UnitTaken ->
                copy(errors = errors + (ConversionField.UNIT to ConversionProblem.Taken))

            is SaveConversionResult.DuplicateBarcode ->
                copy(
                    errors =
                        errors +
                            (
                                ConversionField.BARCODE to
                                    ConversionProblem.DuplicateBarcode(result.productName)
                                ),
                )

            SaveConversionResult.ProductUnavailable ->
                copy(errors = errors + (ConversionField.PRODUCT to ConversionProblem.Missing))

            SaveConversionResult.UnitUnavailable ->
                copy(errors = errors + (ConversionField.UNIT to ConversionProblem.Missing))

            // Se quitó mientras se editaba: no se guardó nada, y lo escrito sigue.
            SaveConversionResult.NotFound -> copy(saveFailed = SaveFailure(outOfSpace = false))

            is SaveConversionResult.StorageFailed ->
                copy(saveFailed = SaveFailure(result.failure.isOutOfSpace))
        }

    private fun errorsOf(
        errors: Set<ConversionInputError>,
    ): Map<ConversionField, ConversionProblem> = errors.associate { error ->
        when (error) {
            ConversionInputError.FACTOR_NOT_POSITIVE ->
                ConversionField.FACTOR to
                    ConversionProblem.Zero

            ConversionInputError.FACTOR_TOO_HIGH ->
                ConversionField.FACTOR to
                    ConversionProblem.TooHigh

            ConversionInputError.SAME_AS_BASE ->
                ConversionField.UNIT to
                    ConversionProblem.SameAsBase

            ConversionInputError.PRICE_TOO_HIGH ->
                ConversionField.PRICE to
                    ConversionProblem.TooHigh

            ConversionInputError.BARCODE_TOO_LONG ->
                ConversionField.BARCODE to ConversionProblem.TooLong(CatalogText.BARCODE_MAX)
        }
    }

    /** El factor en milésimas de la unidad base. En blanco o cero es un error: algo debe traer. */
    private fun readFactor(text: String, problem: (ConversionProblem) -> Unit): QuantityMilli =
        when (
            val read = NumberInputParser.quantity(
                text,
                numbers.quantitySymbols,
                Limit.LINE_QUANTITY_MILLI,
            )
        ) {
            is NumberInput.Value ->
                read.value.also { if (it.milli < 1L) problem(ConversionProblem.Zero) }

            NumberInput.Blank -> QuantityMilli.ZERO.also { problem(ConversionProblem.Zero) }

            is NumberInput.TooLarge -> QuantityMilli.ZERO.also {
                problem(ConversionProblem.TooHigh)
            }

            NumberInput.Malformed, is NumberInput.TooManyDecimals ->
                QuantityMilli.ZERO.also { problem(ConversionProblem.NotANumber) }
        }

    /** El precio propio de una unidad entera, o `null` en blanco: precio calculado con el factor. */
    private fun readPrice(text: String, problem: (ConversionProblem) -> Unit): Money? = when (
        val read =
            NumberInputParser.money(
                text,
                numbers.moneySymbols,
                numbers.currencyDecimals,
                Limit.UNIT_MONEY,
            )
    ) {
        is NumberInput.Value -> read.value

        NumberInput.Blank -> null

        is NumberInput.TooLarge -> null.also { problem(ConversionProblem.TooHigh) }

        NumberInput.Malformed, is NumberInput.TooManyDecimals ->
            null.also { problem(ConversionProblem.NotANumber) }
    }

    private companion object {
        const val QUANTITY_DECIMALS = 3
        const val RADIX = 10L

        /** Un número como texto editable: cifras y el separador decimal, sin ceros de más. */
        fun decimalText(value: Long, decimals: Int, separator: Char): String {
            var scale = 1L
            repeat(decimals) { scale *= RADIX }
            val whole = value / scale
            val fraction = (value % scale).toString().padStart(decimals, '0').trimEnd('0')
            return if (fraction.isEmpty()) whole.toString() else "$whole$separator$fraction"
        }
    }
}
