package io.github.yesidrobayo.shopledger.ui.pricing

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListInputError
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import io.github.yesidrobayo.shopledger.domain.rule.pricing.PriceListFormRules
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SavePriceListResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetListPriceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Los separadores con que se leen el ajuste y el precio escritos. */
data class PriceListNumberFormat(
    val percentSymbols: NumberSymbols,
    val moneySymbols: NumberSymbols,
    val currencyDecimals: Int,
)

/** Por qué no se guarda el nombre de una lista. */
enum class ListNameProblem {
    EMPTY,
    TOO_LONG,
    DUPLICATE,
}

/** Por qué no sirve la lista base elegida. */
enum class ListBaseProblem {
    MISSING,

    /** Se archivó o se eliminó mientras se editaba. */
    UNAVAILABLE,

    /** Parte, directa o indirectamente, de la lista que se edita. */
    CYCLE,
}

/** Por qué no sirve el ajuste escrito. */
enum class ListAdjustmentProblem {
    MISSING,

    /** No es un porcentaje, o queda fuera de −100 % a +10.000 %. */
    OUT_OF_RANGE,
}

/** Un guardado que no se pudo hacer: [outOfSpace] si fue por falta de espacio. */
@Immutable
data class ListSaveFailure(val outOfSpace: Boolean)

/**
 * La hoja de una lista mientras se escribe. [editingId] nulo es una lista nueva. [bases] son las
 * listas vigentes que puede tomar como base, sin ella misma. [hasOwnPrices] dice que la lista es fija
 * con precios propios y no puede pasar a relativa.
 */
@Immutable
data class PriceListEditor(
    val editingId: Long?,
    val name: String,
    val kind: PriceListKind,
    val baseId: Long?,
    val adjustmentText: String,
    val bases: List<PriceListSummary>,
    val nameProblem: ListNameProblem? = null,
    val baseProblem: ListBaseProblem? = null,
    val adjustmentProblem: ListAdjustmentProblem? = null,
    val hasOwnPrices: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: ListSaveFailure? = null,
)

/** Lo que la persona hace en la hoja de una lista. */
sealed interface PriceListEditorAction {
    data class Name(val text: String) : PriceListEditorAction

    data class Kind(val kind: PriceListKind) : PriceListEditorAction

    data class Base(val listId: Long) : PriceListEditorAction

    data class Adjustment(val text: String) : PriceListEditorAction

    data object Save : PriceListEditorAction

    data object Dismiss : PriceListEditorAction
}

/**
 * La hoja de crear o editar una lista: nombre, tipo y, si es relativa, base y ajuste.
 *
 * Los errores aparecen al guardar, nunca mientras se escribe, y lo escrito no se pierde por un
 * error. La base no puede formar un ciclo: el dominio lo rechaza y la hoja lo dice en su campo.
 * Guardar corre una sola vez a la vez.
 *
 * @param formatAdjustment escribe un ajuste guardado como lo leería el campo, para editarlo.
 * @param onSaved recibe el id de la lista guardada, con la hoja ya cerrada.
 */
class PriceListEditorModel(
    private val scope: CoroutineScope,
    private val numbers: PriceListNumberFormat,
    private val formatAdjustment: (BasisPoints) -> String,
    private val save: suspend (Long?, PriceListDraft) -> SavePriceListResult,
    private val onSaved: (Long) -> Unit,
) {
    private val state = MutableStateFlow<PriceListEditor?>(null)
    val editor: StateFlow<PriceListEditor?> = state.asStateFlow()

    /** Abre la hoja vacía, para una lista fija nueva, con [bases] para elegir si pasa a relativa. */
    fun openNew(bases: List<PriceListSummary>) {
        state.value = PriceListEditor(null, "", PriceListKind.FIXED, null, "", bases)
    }

    /** Abre la hoja con [list], ya nombrada como [name], para editarla. */
    fun openEdit(list: PriceList, name: String, bases: List<PriceListSummary>) {
        val relative = list.pricing as? PriceListPricing.Relative
        state.value =
            PriceListEditor(
                editingId = list.id,
                name = name,
                kind = list.pricing.kind,
                baseId = relative?.baseId,
                adjustmentText = relative?.let { formatAdjustment(it.adjustment) }.orEmpty(),
                bases = bases.filterNot { it.list.id == list.id },
            )
    }

    fun onAction(action: PriceListEditorAction) {
        when (action) {
            is PriceListEditorAction.Name ->
                change { copy(name = action.text, nameProblem = null, saveFailed = null) }

            is PriceListEditorAction.Kind ->
                change { copy(kind = action.kind, hasOwnPrices = false, saveFailed = null) }

            is PriceListEditorAction.Base ->
                change { copy(baseId = action.listId, baseProblem = null, saveFailed = null) }

            is PriceListEditorAction.Adjustment ->
                change {
                    copy(adjustmentText = action.text, adjustmentProblem = null, saveFailed = null)
                }

            PriceListEditorAction.Save -> save()

            PriceListEditorAction.Dismiss -> state.value = null
        }
    }

    private fun change(block: PriceListEditor.() -> PriceListEditor) {
        state.update { it?.block() }
    }

    private fun save() {
        val current = state.value ?: return
        if (current.saving) return
        val adjustment =
            if (current.kind ==
                PriceListKind.RELATIVE
            ) {
                parseAdjustment(current.adjustmentText)
            } else {
                null
            }
        if (adjustment is Parsed.Bad) {
            change { copy(adjustmentProblem = adjustment.problem) }
            return
        }
        val draft =
            PriceListDraft(
                name = current.name,
                kind = current.kind,
                basePriceListId = current.baseId,
                adjustment = (adjustment as? Parsed.Good)?.value,
            )
        change { copy(saving = true, saveFailed = null) }
        scope.launch {
            val result = save(current.editingId, draft)
            if (result is SavePriceListResult.Saved) {
                state.value = null
                onSaved(result.id)
            } else {
                change { copy(saving = false).withProblem(result) }
            }
        }
    }

    /** El ajuste escrito, o su problema. En blanco lo dice el dominio, con los demás campos. */
    private fun parseAdjustment(text: String): Parsed = when (
        val input =
            NumberInputParser.percent(
                text,
                numbers.percentSymbols,
                PriceListFormRules.MAX_ADJUSTMENT,
            )
    ) {
        is NumberInput.Value -> Parsed.Good(input.value)

        NumberInput.Blank -> Parsed.Good(null)

        NumberInput.Malformed, is NumberInput.TooManyDecimals, is NumberInput.TooLarge ->
            Parsed.Bad(ListAdjustmentProblem.OUT_OF_RANGE)
    }

    private sealed interface Parsed {
        data class Good(val value: BasisPoints?) : Parsed

        data class Bad(val problem: ListAdjustmentProblem) : Parsed
    }

    private fun PriceListEditor.withProblem(result: SavePriceListResult): PriceListEditor =
        when (result) {
            is SavePriceListResult.Saved -> this

            is SavePriceListResult.Invalid -> {
                val errors = result.errors
                copy(
                    nameProblem =
                        when {
                            PriceListInputError.NAME_EMPTY in errors -> ListNameProblem.EMPTY
                            PriceListInputError.NAME_TOO_LONG in errors -> ListNameProblem.TOO_LONG
                            else -> null
                        },
                    baseProblem = ListBaseProblem.MISSING.takeIf {
                        PriceListInputError.BASE_MISSING in errors
                    },
                    adjustmentProblem =
                        when {
                            PriceListInputError.ADJUSTMENT_MISSING in errors ->
                                ListAdjustmentProblem.MISSING

                            PriceListInputError.ADJUSTMENT_OUT_OF_RANGE in errors ->
                                ListAdjustmentProblem.OUT_OF_RANGE

                            else -> null
                        },
                )
            }

            SavePriceListResult.DuplicateName -> copy(nameProblem = ListNameProblem.DUPLICATE)

            SavePriceListResult.BaseUnavailable -> copy(baseProblem = ListBaseProblem.UNAVAILABLE)

            SavePriceListResult.CreatesCycle -> copy(baseProblem = ListBaseProblem.CYCLE)

            SavePriceListResult.HasOwnPrices -> copy(hasOwnPrices = true)

            // Se archivó mientras se editaba, o es la inicial, que la pantalla no ofrece editar: no
            // se guardó nada, y lo escrito sigue.
            SavePriceListResult.NotFound, SavePriceListResult.Protected ->
                copy(saveFailed = ListSaveFailure(outOfSpace = false))

            is SavePriceListResult.StorageFailed ->
                copy(saveFailed = ListSaveFailure(result.failure.isOutOfSpace))
        }

    companion object {
        /** Largo máximo del nombre, para el mensaje de error. */
        const val NAME_MAX: Int = PriceListFormRules.NAME_MAX
    }
}

/** Por qué no se guarda el precio escrito en una lista. */
enum class ListPriceProblem {
    /** No es un monto, o pasa del tope por unidad. */
    INVALID,

    /** Cero en una lista fija: para volver al general, se quita el precio propio. */
    ZERO,
}

/**
 * La hoja del precio de un producto en una lista. [hasOwnPrice] ofrece `Quitar precio propio`, y
 * [pricing] mide el margen del precio escrito, que se muestra mientras se escribe.
 */
@Immutable
data class ListPriceEditor(
    val listId: Long,
    val productId: Long,
    val productName: String,
    val priceText: String,
    val hasOwnPrice: Boolean,
    val pricing: ProductPricing,
    val typedPrice: Money?,
    val problem: ListPriceProblem? = null,
    val saving: Boolean = false,
    val saveFailed: ListSaveFailure? = null,
)

/** Lo que la persona hace en la hoja del precio. */
sealed interface ListPriceEditorAction {
    data class Price(val text: String) : ListPriceEditorAction

    data object Save : ListPriceEditorAction

    data object RemoveOwn : ListPriceEditorAction

    data object Dismiss : ListPriceEditorAction
}

/**
 * La hoja del precio de un producto en una lista fija, o en la inicial, donde es el precio de venta.
 * Guarda o quita el precio propio, una sola vez a la vez, y lo escrito no se pierde por un error.
 *
 * @param formatPrice escribe un monto como lo leería el campo.
 * @param onSaved corre con la hoja ya cerrada.
 */
class ListPriceEditorModel(
    private val scope: CoroutineScope,
    private val numbers: PriceListNumberFormat,
    private val formatPrice: (Money) -> String,
    private val set: suspend (Long, Long, Money?) -> SetListPriceResult,
    private val onSaved: () -> Unit,
) {
    private val state = MutableStateFlow<ListPriceEditor?>(null)
    val editor: StateFlow<ListPriceEditor?> = state.asStateFlow()

    /** Abre la hoja de [row] en [listId], con su precio de ahora ya escrito. */
    fun open(listId: Long, row: ListProductRow, hasOwnPrice: Boolean, pricing: ProductPricing) {
        val text = row.price?.let(formatPrice).orEmpty()
        state.value =
            ListPriceEditor(
                listId = listId,
                productId = row.productId,
                productName = row.name,
                priceText = text,
                hasOwnPrice = hasOwnPrice,
                pricing = pricing,
                typedPrice = row.price,
            )
    }

    fun onAction(action: ListPriceEditorAction) {
        when (action) {
            is ListPriceEditorAction.Price ->
                state.update {
                    it?.copy(
                        priceText = action.text,
                        typedPrice = (parse(action.text) as? NumberInput.Value)?.value,
                        problem = null,
                        saveFailed = null,
                    )
                }

            ListPriceEditorAction.Save -> {
                val current = state.value ?: return
                val price = (parse(current.priceText) as? NumberInput.Value)?.value
                if (price == null) {
                    state.update { it?.copy(problem = ListPriceProblem.INVALID) }
                    return
                }
                run(price)
            }

            ListPriceEditorAction.RemoveOwn -> run(null)

            ListPriceEditorAction.Dismiss -> state.value = null
        }
    }

    private fun run(price: Money?) {
        val current = state.value ?: return
        if (current.saving) return
        state.update { it?.copy(saving = true, saveFailed = null) }
        scope.launch {
            val result = set(current.listId, current.productId, price)
            val closed =
                when (result) {
                    is SetListPriceResult.Changed, SetListPriceResult.Unchanged -> true

                    SetListPriceResult.ZeroOwnPrice -> {
                        state.update { it?.copy(saving = false, problem = ListPriceProblem.ZERO) }
                        false
                    }

                    SetListPriceResult.PriceOutOfRange -> {
                        state.update {
                            it?.copy(saving = false, problem = ListPriceProblem.INVALID)
                        }
                        false
                    }

                    is SetListPriceResult.StorageFailed -> {
                        failed(result.failure.isOutOfSpace)
                        false
                    }

                    // La lista o el producto cambiaron por debajo: no se guardó nada.
                    SetListPriceResult.DefaultPriceRequired, SetListPriceResult.ListNotFound,
                    SetListPriceResult.ListArchived, SetListPriceResult.ListIsCalculated,
                    SetListPriceResult.ProductNotFound, SetListPriceResult.ProductArchived,
                    SetListPriceResult.UnknownTimeZone, SetListPriceResult.CurrencyUnconfirmed,
                    -> {
                        failed(outOfSpace = false)
                        false
                    }
                }
            if (closed) {
                state.value = null
                onSaved()
            }
        }
    }

    private fun failed(outOfSpace: Boolean) {
        state.update { it?.copy(saving = false, saveFailed = ListSaveFailure(outOfSpace)) }
    }

    private fun parse(text: String): NumberInput<Money> = NumberInputParser.money(
        text,
        numbers.moneySymbols,
        numbers.currencyDecimals,
        Limit.UNIT_MONEY,
    )
}
