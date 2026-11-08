package io.github.yesidrobayo.shopledger.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDraft
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateEntry
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateInputError
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxSetup
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.rule.tax.TaxRateCheck
import io.github.yesidrobayo.shopledger.domain.rule.tax.TaxRateRules
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ArchiveTaxRateResult
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ArchiveTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ChangeTaxSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ChangeTaxSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.CreateTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.DeleteTaxRateResult
import io.github.yesidrobayo.shopledger.domain.usecase.tax.DeleteTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.EditTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ObserveTaxSetupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ReactivateTaxRateResult
import io.github.yesidrobayo.shopledger.domain.usecase.tax.ReactivateTaxRateUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.tax.SaveTaxRateResult
import io.github.yesidrobayo.shopledger.domain.usecase.tax.TaxSettingsChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Los casos de uso de la pantalla de impuestos, armados con los puertos de la base. */
class TaxSettingsUseCases(
    val observe: ObserveTaxSetupUseCase,
    val create: CreateTaxRateUseCase,
    val edit: EditTaxRateUseCase,
    val archive: ArchiveTaxRateUseCase,
    val reactivate: ReactivateTaxRateUseCase,
    val delete: DeleteTaxRateUseCase,
    val change: ChangeTaxSettingsUseCase,
)

/**
 * El estado de la pantalla de impuestos y sus acciones.
 *
 * Lo guardado viene de la base y se vuelve a leer con cada cambio confirmado; la hoja, la
 * confirmación y el aviso viven aquí. Una falla al guardar nunca borra lo escrito en la hoja.
 */
class TaxSettingsViewModel(private val useCases: TaxSettingsUseCases) : ViewModel() {
    private val setup: StateFlow<TaxSetup?> =
        useCases.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val overlay = MutableStateFlow(Overlay())

    val uiState: StateFlow<TaxSettingsUiState> =
        combine(setup.filterNotNull(), overlay) { current, shown ->
            TaxSettingsUiState.Ready(
                mode = current.settings.mode,
                purchaseTaxRecoverable = current.settings.purchaseTaxRecoverable,
                missingDefaultRate = current.isMissingDefaultRate,
                active = current.active.map(::rowOf),
                archived = current.archived.map(::rowOf),
                editor = shown.editor,
                dialog = shown.dialog,
                message = shown.message,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            TaxSettingsUiState.Loading,
        )

    fun onAction(action: TaxSettingsAction) {
        when (action) {
            // Volver lo resuelve la navegación.
            TaxSettingsAction.Back -> Unit

            is TaxSettingsAction.SelectMode -> {
                if (action.mode != setup.value?.settings?.mode) {
                    overlay.update { it.copy(dialog = TaxDialog.ChangeMode(action.mode)) }
                }
            }

            is TaxSettingsAction.SetPurchaseTaxRecoverable -> launchChange(
                TaxSettingsChange.PurchaseTaxRecoverable(action.recoverable),
            )

            TaxSettingsAction.NewRate -> overlay.update {
                it.copy(editor = TaxRateEditor(null, "", "", useAsDefault = false))
            }

            is TaxSettingsAction.EditRate -> openEditor(action.id, action.symbols)

            is TaxSettingsAction.AskArchive -> entryOf(action.id)?.let { entry ->
                overlay.update { it.copy(dialog = TaxDialog.Archive(action.id, entry.name)) }
            }

            is TaxSettingsAction.AskDelete -> entryOf(action.id)?.let { entry ->
                overlay.update { it.copy(dialog = TaxDialog.Delete(action.id, entry.name)) }
            }

            is TaxSettingsAction.Reactivate -> viewModelScope.launch {
                val message =
                    when (val result = useCases.reactivate(action.id)) {
                        ReactivateTaxRateResult.Reactivated,
                        ReactivateTaxRateResult.AlreadyActive,
                        ReactivateTaxRateResult.NotFound,
                        -> null

                        ReactivateTaxRateResult.NameTaken -> TaxMessage.NameTaken

                        is ReactivateTaxRateResult.StorageFailed ->
                            failed(result.failure.isOutOfSpace)
                    }
                if (message != null) overlay.update { it.copy(message = message) }
            }

            TaxSettingsAction.ConfirmDialog -> confirmDialog()

            TaxSettingsAction.DismissDialog -> overlay.update { it.copy(dialog = null) }

            is TaxSettingsAction.EditorName -> updateEditor {
                it.copy(name = action.name, nameError = null, saveFailure = null)
            }

            is TaxSettingsAction.EditorPercent -> updateEditor {
                it.copy(percent = action.percent, percentError = null, saveFailure = null)
            }

            is TaxSettingsAction.EditorDefault -> updateEditor {
                it.copy(useAsDefault = action.useAsDefault, saveFailure = null)
            }

            is TaxSettingsAction.SaveRate -> save(action.symbols)

            TaxSettingsAction.DismissEditor -> overlay.update { it.copy(editor = null) }

            TaxSettingsAction.MessageShown -> overlay.update { it.copy(message = null) }
        }
    }

    private fun openEditor(id: Long, symbols: NumberSymbols) {
        val entry = entryOf(id) ?: return
        overlay.update {
            it.copy(
                editor =
                    TaxRateEditor(
                        editingId = id,
                        name = entry.name,
                        percent = percentInputText(entry.rate, symbols),
                        useAsDefault = entry.isDefault,
                    ),
            )
        }
    }

    private fun confirmDialog() {
        val dialog = overlay.value.dialog ?: return
        overlay.update { it.copy(dialog = null) }
        when (dialog) {
            is TaxDialog.ChangeMode -> launchChange(TaxSettingsChange.Mode(dialog.mode))

            is TaxDialog.Archive -> viewModelScope.launch {
                val result = useCases.archive(dialog.id)
                if (result is ArchiveTaxRateResult.StorageFailed) {
                    show(failed(result.failure.isOutOfSpace))
                }
            }

            is TaxDialog.Delete -> viewModelScope.launch {
                val result = useCases.delete(dialog.id)
                if (result is DeleteTaxRateResult.StorageFailed) {
                    show(failed(result.failure.isOutOfSpace))
                }
            }
        }
    }

    private fun launchChange(change: TaxSettingsChange) {
        viewModelScope.launch {
            val result = useCases.change(change)
            if (result is ChangeTaxSettingsResult.StorageFailed) {
                show(failed(result.failure.isOutOfSpace))
            }
        }
    }

    private fun save(symbols: NumberSymbols) {
        val editor = overlay.value.editor ?: return
        if (editor.saving) return
        val read =
            NumberInputParser.percent(editor.percent, symbols, BasisPoints.ONE_HUNDRED_PERCENT)
        val rate = (read as? NumberInput.Value)?.value
        if (rate == null) {
            // Sin un porcentaje legible no se guarda nada, pero el nombre se revisa igual: todos
            // los errores a la vez. Que el nombre esté repetido solo lo sabe la base.
            val check = TaxRateRules.check(TaxRateDraft(editor.name, BasisPoints.ZERO, false))
            val nameErrors = (check as? TaxRateCheck.Invalid)?.errors.orEmpty()
            val percentError =
                if (read is NumberInput.TooManyDecimals) {
                    TaxPercentError.DECIMALS
                } else {
                    TaxPercentError.RANGE
                }
            updateEditor {
                it.copy(nameError = nameErrorOf(nameErrors), percentError = percentError)
            }
            return
        }
        updateEditor { it.copy(saving = true, saveFailure = null) }
        viewModelScope.launch {
            val draft = TaxRateDraft(editor.name, rate, editor.useAsDefault)
            val id = editor.editingId
            val result = if (id == null) useCases.create(draft) else useCases.edit(id, draft)
            applySaveResult(result)
        }
    }

    private fun applySaveResult(result: SaveTaxRateResult) {
        when (result) {
            is SaveTaxRateResult.Saved -> overlay.update {
                it.copy(editor = null, message = TaxMessage.RateSaved)
            }

            is SaveTaxRateResult.Invalid -> updateEditor {
                it.copy(
                    saving = false,
                    nameError = nameErrorOf(result.errors),
                    percentError =
                        TaxPercentError.RANGE.takeIf {
                            TaxRateInputError.RATE_OUT_OF_RANGE in result.errors
                        },
                )
            }

            // Se borró o se archivó mientras se editaba: la hoja ya no tiene qué guardar.
            SaveTaxRateResult.NotFound,
            SaveTaxRateResult.Archived,
            -> overlay.update {
                it.copy(editor = null, message = TaxMessage.Failed(SaveFailure(outOfSpace = false)))
            }

            is SaveTaxRateResult.StorageFailed -> updateEditor {
                it.copy(saving = false, saveFailure = SaveFailure(result.failure.isOutOfSpace))
            }
        }
    }

    private fun show(message: TaxMessage) {
        overlay.update { it.copy(message = message) }
    }

    private fun updateEditor(change: (TaxRateEditor) -> TaxRateEditor) {
        overlay.update { shown -> shown.copy(editor = shown.editor?.let(change)) }
    }

    private fun entryOf(id: Long): TaxRateRow? {
        val current = setup.value ?: return null
        return (current.active + current.archived).firstOrNull { it.rate.id == id }?.let(::rowOf)
    }

    private data class Overlay(
        val editor: TaxRateEditor? = null,
        val dialog: TaxDialog? = null,
        val message: TaxMessage? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun failed(outOfSpace: Boolean): TaxMessage = TaxMessage.Failed(SaveFailure(outOfSpace))

private fun rowOf(entry: TaxRateEntry): TaxRateRow = TaxRateRow(
    id = entry.rate.id,
    name = entry.rate.details.name,
    rate = entry.rate.details.rate,
    isDefault = entry.isDefault,
    inUse = entry.isInUse,
)

private fun nameErrorOf(errors: Set<TaxRateInputError>): TaxNameError? = when {
    TaxRateInputError.NAME_BLANK in errors -> TaxNameError.EMPTY
    TaxRateInputError.NAME_TOO_LONG in errors -> TaxNameError.TOO_LONG
    TaxRateInputError.NAME_TAKEN in errors -> TaxNameError.DUPLICATE
    else -> null
}

/**
 * El porcentaje de [rate] como lo escribiría la persona con [symbols]: `21`, `8,25` o `10,5`, sin
 * ceros de más, para que editar una tasa muestre lo que se escribió.
 */
internal fun percentInputText(rate: BasisPoints, symbols: NumberSymbols): String {
    val whole = rate.bp / PERCENT_SCALE
    val fraction = rate.bp % PERCENT_SCALE
    if (fraction == 0L) return whole.toString()
    val digits = fraction.toString().padStart(2, '0').trimEnd('0')
    return "$whole${symbols.decimalSeparator}$digits"
}

/** Puntos básicos en un punto porcentual. */
private const val PERCENT_SCALE = 100L
