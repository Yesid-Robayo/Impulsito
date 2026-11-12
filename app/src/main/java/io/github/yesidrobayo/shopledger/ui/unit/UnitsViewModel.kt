package io.github.yesidrobayo.shopledger.ui.unit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitsOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UnitLifecycleResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lo que la pantalla de unidades le pide al dominio. Es una interfaz para que las pruebas del
 * ViewModel den respuestas fijas sin una base.
 */
interface UnitsOperations {
    fun units(): Flow<UnitsOverview>

    fun conversions(): Flow<List<ConversionEntry>>

    /** Los productos vigentes, que se pueden elegir para una conversión nueva. */
    fun products(): Flow<List<Product>>

    suspend fun saveUnit(editingId: Long?, draft: UnitDraft): SaveUnitResult

    suspend fun archiveUnit(id: Long): UnitLifecycleResult

    suspend fun reactivateUnit(id: Long): UnitLifecycleResult

    suspend fun deleteUnit(id: Long): UnitLifecycleResult

    suspend fun saveConversion(editingId: Long?, draft: ConversionDraft): SaveConversionResult

    suspend fun deleteConversion(id: Long): DeleteConversionResult
}

/**
 * La pantalla de unidades y conversiones: las unidades agrupadas por tipo, las conversiones de los
 * productos, y las hojas para crearlas y editarlas.
 *
 * Una unidad con uso no se elimina: el menú solo ofrece archivarla, y la de conteo inicial ni eso.
 * Cada acción que cambia algo pregunta antes si no se puede deshacer en el acto, y avisa al terminar.
 */
class UnitsViewModel(
    private val operations: UnitsOperations,
    private val names: UnitNames,
    numbers: ProductNumberFormat,
) : ViewModel() {
    private val state = MutableStateFlow(UnitsUiState())
    val uiState: StateFlow<UnitsUiState> = state.asStateFlow()

    /** La hoja de una unidad, abierta o no. */
    val unitEditor =
        UnitEditorModel(
            scope = viewModelScope,
            names = names,
            activeUnits = { state.value.active },
            save = operations::saveUnit,
            onSaved = { show(UnitsMessage.UnitSaved) },
        )

    /** La hoja de una conversión, abierta o no. */
    val conversionEditor =
        ConversionEditorModel(
            scope = viewModelScope,
            numbers = numbers,
            save = operations::saveConversion,
            delete = operations::deleteConversion,
            onSaved = { removed -> if (!removed) show(UnitsMessage.ConversionSaved) },
        )

    /** Unidades, conversiones y productos: una falla se dice con `Reintentar`, sin cerrar nada. */
    private val reads = ScreenReads()

    init {
        viewModelScope.launch {
            reads.guard { operations.units() }.collect { overview ->
                state.update {
                    it.copy(
                        loading = false,
                        active = overview.active,
                        archived = overview.archived,
                        inUse = overview.inUse,
                    )
                }
            }
        }
        viewModelScope.launch {
            reads.guard { operations.conversions() }.collect { list ->
                state.update { it.copy(conversions = list) }
            }
        }
        viewModelScope.launch {
            reads.guard { operations.products() }.collect { list ->
                state.update { it.copy(products = list) }
            }
        }
        viewModelScope.launch {
            reads.failed.collect { failed ->
                state.update { it.copy(readFailed = failed, loading = it.loading && !failed) }
            }
        }
    }

    fun onAction(action: UnitsAction) {
        when (action) {
            // La pantalla vuelve atrás: aquí no cambia nada.
            UnitsAction.Back -> Unit

            UnitsAction.Retry -> {
                state.update { it.copy(loading = true) }
                reads.retry()
            }

            is UnitsAction.SelectTab -> state.update { it.copy(tab = action.tab) }

            UnitsAction.NewUnit -> unitEditor.openNew()

            is UnitsAction.EditUnit -> unitOf(action.unitId)?.let(unitEditor::openEdit)

            is UnitsAction.AskArchive ->
                unitOf(action.unitId)?.let { unit ->
                    state.update {
                        it.copy(dialog = UnitsDialog.Archive(unit.id, names.name(unit)))
                    }
                }

            is UnitsAction.AskDelete ->
                unitOf(action.unitId)?.let { unit ->
                    state.update { it.copy(dialog = UnitsDialog.Delete(unit.id, names.name(unit))) }
                }

            is UnitsAction.Reactivate -> lifecycle { operations.reactivateUnit(action.unitId) }

            UnitsAction.ConfirmDialog -> confirmDialog()

            UnitsAction.DismissDialog -> state.update { it.copy(dialog = null) }

            UnitsAction.NewConversion -> conversionEditor.openNew()

            is UnitsAction.EditConversion ->
                state.value.conversions
                    .firstOrNull { it.conversion.id == action.conversionId }
                    ?.let { conversionEditor.openEdit(it.conversion) }

            UnitsAction.MessageShown -> state.update { it.copy(message = null) }
        }
    }

    private fun unitOf(id: Long) = state.value.allUnits.firstOrNull { it.id == id }

    private fun confirmDialog() {
        val dialog = state.value.dialog ?: return
        state.update { it.copy(dialog = null) }
        when (dialog) {
            is UnitsDialog.Archive -> lifecycle { operations.archiveUnit(dialog.unitId) }
            is UnitsDialog.Delete -> lifecycle { operations.deleteUnit(dialog.unitId) }
        }
    }

    private fun lifecycle(block: suspend () -> UnitLifecycleResult) {
        viewModelScope.launch {
            val message =
                when (val result = block()) {
                    // La lista ya muestra el cambio: no hace falta un aviso.
                    UnitLifecycleResult.Done, UnitLifecycleResult.Unchanged,
                    UnitLifecycleResult.NotFound,
                    -> null

                    UnitLifecycleResult.NameTaken -> UnitsMessage.NameTaken

                    // El menú no los ofrece; si llegan, cambió algo por debajo y no se hizo nada.
                    UnitLifecycleResult.Protected, UnitLifecycleResult.InUse ->
                        UnitsMessage.Failed(outOfSpace = false)

                    is UnitLifecycleResult.StorageFailed ->
                        UnitsMessage.Failed(result.failure.isOutOfSpace)
                }
            if (message != null) show(message)
        }
    }

    private fun show(message: UnitsMessage) {
        state.update { it.copy(message = message) }
    }
}
