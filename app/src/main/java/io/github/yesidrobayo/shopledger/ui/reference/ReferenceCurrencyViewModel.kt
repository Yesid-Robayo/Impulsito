package io.github.yesidrobayo.shopledger.ui.reference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.pricing.FxRate
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ReferenceRateRules
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.RecordReferenceRateResult
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.ReferenceRateView
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.SetReferenceCurrencyResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * La moneda de referencia y su cotización manual.
 *
 * Lo guardado llega de la base; la hoja de registrar vive aquí hasta guardarla, y una falla nunca
 * borra lo escrito. Nada se descarga ni se recalcula: registrar una cotización solo agrega una fila,
 * y ningún precio cambia. Cambiar una moneda ya elegida pide confirmar, porque las referencias de
 * los productos quedan en la anterior hasta revisarlas.
 *
 * @param symbols los separadores con que la persona escribe la cotización, los del formato regional.
 */
class ReferenceCurrencyViewModel(
    observe: () -> Flow<ReferenceRateView>,
    calendar: () -> Flow<RateCalendar?>,
    private val setReference: suspend (ReferenceCurrency?) -> SetReferenceCurrencyResult,
    private val record: suspend (Long, String?, BusinessDate?) -> RecordReferenceRateResult,
    private val symbols: NumberSymbols,
) : ViewModel() {
    private val reads = ScreenReads()
    private val local = MutableStateFlow(Local())

    private val saved: StateFlow<ReferenceRateView?> =
        reads.guard(observe).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val today: StateFlow<RateCalendar?> =
        reads.guard(calendar).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<ReferenceCurrencyUiState> =
        combine(saved, today, local, reads.failed) { view, calendarNow, shown, failed ->
            when {
                failed -> ReferenceCurrencyUiState.ReadFailed
                view == null -> ReferenceCurrencyUiState.Loading
                else -> readyOf(view, calendarNow, shown)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            ReferenceCurrencyUiState.Loading,
        )

    fun onAction(action: ReferenceCurrencyAction) {
        when (action) {
            // Volver lo resuelve la navegación.
            ReferenceCurrencyAction.Back -> Unit

            ReferenceCurrencyAction.Retry -> reads.retry()

            ReferenceCurrencyAction.ChooseCurrency ->
                local.update { it.copy(choosing = true, query = "") }

            is ReferenceCurrencyAction.Query -> local.update { it.copy(query = action.text) }

            is ReferenceCurrencyAction.PickCurrency -> pick(action.code, action.isoDecimals)

            is ReferenceCurrencyAction.PickDecimals -> {
                val current = saved.value?.reference ?: return
                if (current.decimals != action.decimals) {
                    persist(current.copy(decimals = action.decimals))
                }
            }

            ReferenceCurrencyAction.ConfirmChange -> {
                val pending = local.value.pendingChange ?: return
                local.update { it.copy(pendingChange = null) }
                persist(pending)
            }

            ReferenceCurrencyAction.DismissDialog ->
                local.update { it.copy(choosing = false, pendingChange = null, sheet = null) }

            ReferenceCurrencyAction.StartRate -> local.update { it.copy(sheet = RateSheet()) }

            is ReferenceCurrencyAction.RateText -> editSheet {
                it.copy(rateText = action.text, problem = null, saveFailed = null)
            }

            is ReferenceCurrencyAction.RateDate -> editSheet {
                it.copy(date = action.date, futureDate = false, saveFailed = null)
            }

            is ReferenceCurrencyAction.RateNote -> editSheet {
                it.copy(note = action.text, noteTooLong = false, saveFailed = null)
            }

            ReferenceCurrencyAction.SaveRate -> saveRate()

            ReferenceCurrencyAction.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    /**
     * Elige [code] con los decimales de la norma. Sin moneda guardada se guarda enseguida; con
     * otra distinta, primero se confirma.
     */
    private fun pick(code: String, isoDecimals: Int) {
        val currency = CurrencyCode.of(code) ?: return
        val chosen = ReferenceCurrency(currency, isoDecimals)
        val current = saved.value?.reference
        local.update { it.copy(choosing = false) }
        when {
            current == null -> persist(chosen)
            current.code == currency -> Unit
            else -> local.update { it.copy(pendingChange = chosen) }
        }
    }

    private fun persist(reference: ReferenceCurrency) {
        viewModelScope.launch {
            when (val result = setReference(reference)) {
                SetReferenceCurrencyResult.Saved -> Unit

                // La lista no ofrece la moneda del negocio ni decimales fuera de rango: si llega,
                // cambió algo por debajo y no se guardó.
                is SetReferenceCurrencyResult.Rejected ->
                    local.update { it.copy(message = ReferenceMessage.Failed(outOfSpace = false)) }

                is SetReferenceCurrencyResult.Failed -> local.update {
                    it.copy(message = ReferenceMessage.Failed(result.failure.isOutOfSpace))
                }
            }
        }
    }

    private fun saveRate() {
        val sheet = local.value.sheet ?: return
        if (sheet.saving) return
        val rate = when (val read = readRate(sheet.rateText)) {
            is RateRead.Valid -> read.rateMicro

            is RateRead.Invalid -> {
                editSheet { it.copy(problem = read.problem) }
                return
            }
        }
        editSheet { it.copy(saving = true, saveFailed = null) }
        viewModelScope.launch {
            val result = record(rate, sheet.note, sheet.date)
            local.update { shown ->
                val open = shown.sheet ?: return@update shown
                when (result) {
                    is RecordReferenceRateResult.Recorded ->
                        shown.copy(sheet = null, message = ReferenceMessage.RateSaved)

                    RecordReferenceRateResult.RateOutOfRange ->
                        shown.copy(
                            sheet = open.copy(saving = false, problem = RateProblem.TOO_HIGH),
                        )

                    RecordReferenceRateResult.NoteTooLong ->
                        shown.copy(sheet = open.copy(saving = false, noteTooLong = true))

                    RecordReferenceRateResult.FutureDate ->
                        shown.copy(sheet = open.copy(saving = false, futureDate = true))

                    is RecordReferenceRateResult.Failed -> shown.copy(
                        sheet = open.copy(
                            saving = false,
                            saveFailed = SaveFailure(result.failure.isOutOfSpace),
                        ),
                    )

                    // La moneda se quitó o la zona cambió mientras la hoja estaba abierta.
                    RecordReferenceRateResult.NoReferenceCurrency,
                    RecordReferenceRateResult.UnknownTimeZone,
                    -> shown.copy(
                        sheet = open.copy(saving = false, saveFailed = SaveFailure(false)),
                    )
                }
            }
        }
    }

    private fun editSheet(change: (RateSheet) -> RateSheet) {
        local.update { shown -> shown.sheet?.let { shown.copy(sheet = change(it)) } ?: shown }
    }

    private fun readRate(text: String): RateRead = when (
        val read = NumberInputParser.rateMicro(text, symbols, Limit.DOCUMENT_TOTAL)
    ) {
        is NumberInput.Value -> if (read.value > 0L) {
            RateRead.Valid(read.value)
        } else {
            RateRead.Invalid(RateProblem.NOT_POSITIVE)
        }

        NumberInput.Blank -> RateRead.Invalid(RateProblem.NOT_POSITIVE)

        NumberInput.Malformed, is NumberInput.TooManyDecimals ->
            RateRead.Invalid(RateProblem.NOT_A_NUMBER)

        is NumberInput.TooLarge -> RateRead.Invalid(RateProblem.TOO_HIGH)
    }

    private fun readyOf(
        view: ReferenceRateView,
        calendarNow: RateCalendar?,
        shown: Local,
    ): ReferenceCurrencyUiState.Ready {
        val rows = rowsOf(view.history)
        val previous = view.latest?.content?.rateMicro
        val sheet = shown.sheet?.let { open ->
            val typed = (readRate(open.rateText) as? RateRead.Valid)?.rateMicro
            open.copy(
                date = open.date ?: calendarNow?.today,
                variation = typed?.let { ReferenceRateRules.variation(previous, it) },
                jumpWarning = typed != null && ReferenceRateRules.isLargeJump(previous, typed),
            )
        }
        return ReferenceCurrencyUiState.Ready(
            reference = view.reference,
            latest = rows.firstOrNull { it.rate.id == view.latest?.id },
            history = rows,
            choosing = shown.choosing,
            query = shown.query,
            pendingChange = shown.pendingChange,
            sheet = sheet,
            calendar = calendarNow,
            message = shown.message,
        )
    }

    /** Cada cotización con su variación frente a la siguiente de la lista, que es la anterior. */
    private fun rowsOf(history: List<FxRate>): List<RateRow> = history.mapIndexed { index, rate ->
        val previous = history.getOrNull(index + 1)?.content?.rateMicro
        RateRow(rate, ReferenceRateRules.variation(previous, rate.content.rateMicro))
    }

    private sealed interface RateRead {
        data class Valid(val rateMicro: Long) : RateRead

        data class Invalid(val problem: RateProblem) : RateRead
    }

    private data class Local(
        val choosing: Boolean = false,
        val query: String = "",
        val pendingChange: ReferenceCurrency? = null,
        val sheet: RateSheet? = null,
        val message: ReferenceMessage? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
