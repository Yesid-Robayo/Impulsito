package io.github.yesidrobayo.shopledger.ui.priceindex

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexView
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.report.RealTermsRules
import io.github.yesidrobayo.shopledger.domain.usecase.report.DeletePriceIndexResult
import io.github.yesidrobayo.shopledger.domain.usecase.report.SavePriceIndexResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth

/** Lo que muestra `Índice de precios`. */
sealed interface PriceIndexUiState {
    data object Loading : PriceIndexUiState

    /** Una lectura falló: se dice con `Reintentar`. */
    data object ReadFailed : PriceIndexUiState

    /**
     * El índice leído.
     *
     * @param entries los meses, del más reciente al más antiguo.
     * @param missing los meses sin valor entre el primero y el último, del más antiguo al más
     *   reciente.
     * @param currentMonth el mes de hoy, o `null` si el teléfono no conoce la zona del negocio:
     *   sin él no se puede agregar un mes.
     * @param sheet la hoja de agregar o editar un mes, o `null`.
     * @param confirmingDelete el mes cuyo borrado se pide confirmar, o `null`.
     * @param message lo que se avisa con un mensaje de la navegación, o `null`.
     */
    @Immutable
    data class Ready(
        val entries: List<PriceIndexEntry>,
        val missing: List<YearMonth>,
        val currentMonth: YearMonth?,
        val sheet: IndexSheet? = null,
        val confirmingDelete: YearMonth? = null,
        val message: IndexMessage? = null,
    ) : PriceIndexUiState
}

/** Por qué no se puede guardar el cambio escrito. La hoja lo dice debajo del campo. */
enum class ChangeProblem {
    /** Vacío o no es un número. */
    NOT_A_NUMBER,

    /** Tiene más de dos decimales. */
    TOO_MANY_DECIMALS,

    /** Es de `−100 %` o menos. */
    TOO_LOW,

    /** Pasa del tope. */
    TOO_HIGH,
}

/**
 * La hoja de un mes: lo escrito tal cual y lo que impide guardarlo. Con [editing], el mes ya tiene
 * valor y no se cambia; sin él, se elige con las flechas, hasta el mes de hoy.
 *
 * @param monthTaken el mes elegido ya tiene valor: se edita desde su fila.
 * @param futureMonth el mes elegido es posterior al de hoy.
 * @param saveFailed una falla al guardar, que se dice en la hoja; `true` si fue por espacio.
 */
@Immutable
data class IndexSheet(
    val month: YearMonth,
    val editing: Boolean,
    val changeText: String = "",
    val source: String = "",
    val problem: ChangeProblem? = null,
    val monthTaken: Boolean = false,
    val futureMonth: Boolean = false,
    val sourceTooLong: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean? = null,
)

/** Lo que se avisa con un mensaje de la navegación. */
sealed interface IndexMessage {
    /** No se pudo borrar el mes; [outOfSpace] si fue por espacio. */
    data class DeleteFailed(val outOfSpace: Boolean) : IndexMessage
}

/** Lo que la persona hace en `Índice de precios`. */
sealed interface PriceIndexAction {
    data object Back : PriceIndexAction

    data object Retry : PriceIndexAction

    /** Abre la hoja para agregar un mes: el primero que falta o, sin faltantes, el de hoy. */
    data object Add : PriceIndexAction

    /** Abre la hoja de un mes que falta, desde el aviso. */
    data class AddMonth(val month: YearMonth) : PriceIndexAction

    /** Abre la hoja de un mes que ya tiene valor. */
    data class Edit(val month: YearMonth) : PriceIndexAction

    data object PreviousMonth : PriceIndexAction

    data object NextMonth : PriceIndexAction

    data class ChangeText(val text: String) : PriceIndexAction

    data class SourceText(val text: String) : PriceIndexAction

    data object Save : PriceIndexAction

    /** Pide confirmar el borrado del mes de la hoja. */
    data object AskDelete : PriceIndexAction

    data object ConfirmDelete : PriceIndexAction

    /** Cierra la hoja o el diálogo sin cambiar nada. */
    data object Dismiss : PriceIndexAction

    data object MessageShown : PriceIndexAction

    /** Abre la importación, que ya sabe leer un índice de precios. */
    data object Import : PriceIndexAction

    /** Abre `Números` en la comparación real. */
    data object Compare : PriceIndexAction
}

/**
 * `Índice de precios`: el cambio de precios de cada mes, que la persona escribe o importa. Nunca
 * se descarga. Agrega, edita y quita un mes por vez, cada uno en su transacción.
 *
 * @param observe el índice, de nuevo con cada cambio.
 * @param currentMonth el mes de hoy, o `null` si el teléfono no conoce la zona del negocio.
 * @param save guarda un mes: el mes, el cambio, la fuente y si se edita.
 * @param delete quita un mes.
 * @param symbols los símbolos con que se lee un porcentaje escrito.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PriceIndexViewModel(
    private val observe: () -> Flow<PriceIndexView>,
    private val currentMonth: Flow<YearMonth?>,
    private val save: suspend (YearMonth, BasisPoints, String?, Boolean) -> SavePriceIndexResult,
    private val delete: suspend (YearMonth) -> DeletePriceIndexResult,
    private val symbols: NumberSymbols,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val attempts = MutableStateFlow(0)

    val uiState: StateFlow<PriceIndexUiState> = attempts.flatMapLatest { attempt ->
        combine(observe(), currentMonth, local) { view, month, shown ->
            val ready: PriceIndexUiState =
                PriceIndexUiState.Ready(
                    entries = view.entries,
                    missing = view.missing,
                    currentMonth = month,
                    sheet = shown.sheet,
                    confirmingDelete = shown.confirmingDelete,
                    message = shown.message,
                )
            ready
        }
            .onStart { if (attempt > 0) emit(PriceIndexUiState.Loading) }
            .catch { emit(PriceIndexUiState.ReadFailed) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_AFTER_MS),
        PriceIndexUiState.Loading,
    )

    /** Lo último leído, para abrir la hoja sin esperar otra lectura. */
    private val last: PriceIndexUiState.Ready?
        get() = uiState.value as? PriceIndexUiState.Ready

    fun onAction(action: PriceIndexAction) {
        when (action) {
            PriceIndexAction.Retry -> attempts.value += 1

            PriceIndexAction.Add -> {
                val shown = last ?: return
                val month = shown.missing.firstOrNull() ?: shown.currentMonth ?: return
                openSheet(month)
            }

            is PriceIndexAction.AddMonth -> openSheet(action.month)

            is PriceIndexAction.Edit -> openSheet(action.month)

            PriceIndexAction.PreviousMonth -> moveMonth { it.minusMonth() }

            PriceIndexAction.NextMonth -> moveMonth { it.plusMonth() }

            is PriceIndexAction.ChangeText -> editSheet {
                it.copy(changeText = action.text, problem = null, saveFailed = null)
            }

            is PriceIndexAction.SourceText -> editSheet {
                it.copy(source = action.text, sourceTooLong = false, saveFailed = null)
            }

            PriceIndexAction.Save -> saveSheet()

            PriceIndexAction.AskDelete -> {
                val sheet = local.value.sheet ?: return
                if (sheet.editing) local.update { it.copy(confirmingDelete = sheet.month) }
            }

            PriceIndexAction.ConfirmDelete -> confirmDelete()

            PriceIndexAction.Dismiss -> local.update {
                if (it.confirmingDelete != null) {
                    it.copy(confirmingDelete = null)
                } else {
                    it.copy(sheet = null)
                }
            }

            PriceIndexAction.MessageShown -> local.update { it.copy(message = null) }

            // Lo resuelve la navegación.
            PriceIndexAction.Back, PriceIndexAction.Import, PriceIndexAction.Compare -> Unit
        }
    }

    /** Abre la hoja de [month]: con su valor y su fuente si ya los tiene. */
    private fun openSheet(month: YearMonth) {
        val entry = last?.entries?.firstOrNull { it.month == month }
        val percent = entry?.let { editableText(it.change) }.orEmpty()
        local.update {
            it.copy(
                sheet =
                    IndexSheet(
                        month = month,
                        editing = entry != null,
                        changeText = percent,
                        source = entry?.sourceNote.orEmpty(),
                    ),
            )
        }
    }

    /** Cambia el mes de una hoja nueva, sin pasar del mes de hoy. */
    private fun moveMonth(step: (YearMonth) -> YearMonth) {
        val current = last?.currentMonth
        editSheet { sheet ->
            val month = step(sheet.month)
            if (sheet.editing || (current != null && month > current)) {
                sheet
            } else {
                sheet.copy(month = month, monthTaken = false, futureMonth = false)
            }
        }
    }

    private fun saveSheet() {
        val sheet = local.value.sheet ?: return
        if (sheet.saving) return
        val change =
            when (val read = readChange(sheet.changeText)) {
                is ChangeRead.Valid -> read.change

                is ChangeRead.Invalid -> {
                    editSheet { it.copy(problem = read.problem) }
                    return
                }
            }
        editSheet { it.copy(saving = true, saveFailed = null) }
        viewModelScope.launch {
            val result = save(sheet.month, change, sheet.source, sheet.editing)
            local.update { shown ->
                val open = shown.sheet ?: return@update shown
                val idle = open.copy(saving = false)
                when (result) {
                    SavePriceIndexResult.Saved -> shown.copy(sheet = null)

                    SavePriceIndexResult.MonthTaken ->
                        shown.copy(sheet = idle.copy(monthTaken = true))

                    SavePriceIndexResult.FutureMonth ->
                        shown.copy(sheet = idle.copy(futureMonth = true))

                    SavePriceIndexResult.ChangeOutOfRange ->
                        shown.copy(sheet = idle.copy(problem = ChangeProblem.TOO_HIGH))

                    SavePriceIndexResult.NoteTooLong ->
                        shown.copy(sheet = idle.copy(sourceTooLong = true))

                    // Sin zona no hay mes de hoy: se dice como una falla al guardar.
                    SavePriceIndexResult.UnknownTimeZone ->
                        shown.copy(sheet = idle.copy(saveFailed = false))

                    is SavePriceIndexResult.Failed ->
                        shown.copy(sheet = idle.copy(saveFailed = result.failure.isOutOfSpace))
                }
            }
        }
    }

    private fun confirmDelete() {
        val month = local.value.confirmingDelete ?: return
        local.update { it.copy(confirmingDelete = null) }
        viewModelScope.launch {
            when (val result = delete(month)) {
                DeletePriceIndexResult.Deleted -> local.update { it.copy(sheet = null) }

                is DeletePriceIndexResult.Failed -> local.update {
                    it.copy(message = IndexMessage.DeleteFailed(result.failure.isOutOfSpace))
                }
            }
        }
    }

    /** Lee el cambio escrito: un porcentaje con signo, hasta dos decimales, mayor que −100 %. */
    private fun readChange(text: String): ChangeRead =
        when (val read = NumberInputParser.percent(text, symbols, RealTermsRules.MAX_CHANGE)) {
            is NumberInput.Value ->
                if (RealTermsRules.isValidChange(read.value)) {
                    ChangeRead.Valid(read.value)
                } else {
                    ChangeRead.Invalid(ChangeProblem.TOO_LOW)
                }

            NumberInput.Blank, NumberInput.Malformed ->
                ChangeRead.Invalid(ChangeProblem.NOT_A_NUMBER)

            is NumberInput.TooManyDecimals -> ChangeRead.Invalid(ChangeProblem.TOO_MANY_DECIMALS)

            is NumberInput.TooLarge -> ChangeRead.Invalid(ChangeProblem.TOO_HIGH)
        }

    /**
     * [change] como se escribe en el campo, con el separador decimal de la región y sin ceros de
     * más: 190 es `1,9`, −25 es `-0,25` y 300 es `3`. Se lee de vuelta igual.
     */
    private fun editableText(change: BasisPoints): String {
        val magnitude = if (change.bp < 0) -change.bp else change.bp
        val whole = magnitude / PERCENT_UNIT
        val fraction = (magnitude % PERCENT_UNIT).toString().padStart(2, '0').trimEnd('0')
        val sign = if (change.bp < 0) "-" else ""
        val separator = symbols.decimalSeparator
        return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole$separator$fraction"
    }

    private fun editSheet(change: (IndexSheet) -> IndexSheet) {
        local.update { shown -> shown.sheet?.let { shown.copy(sheet = change(it)) } ?: shown }
    }

    private sealed interface ChangeRead {
        data class Valid(val change: BasisPoints) : ChangeRead

        data class Invalid(val problem: ChangeProblem) : ChangeRead
    }

    /** Lo que eligió la persona: no depende de la base. */
    private data class Local(
        val sheet: IndexSheet? = null,
        val confirmingDelete: YearMonth? = null,
        val message: IndexMessage? = null,
    )

    private companion object {
        /** Tras girar la pantalla, la lectura sigue viva este tiempo y no se repite. */
        const val STOP_AFTER_MS = 5_000L

        /** Puntos básicos en un punto porcentual. */
        const val PERCENT_UNIT = 100L
    }
}
