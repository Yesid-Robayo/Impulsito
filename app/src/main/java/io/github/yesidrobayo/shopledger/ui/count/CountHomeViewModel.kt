package io.github.yesidrobayo.shopledger.ui.count

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CountOverview
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que el inicio del conteo le pide al dominio, para probarlo sin una base. */
interface CountHomeOperations {
    fun overview(): Flow<CountOverview>

    fun categories(): Flow<List<Category>>

    suspend fun open(request: OpenCountRequest): OpenCountResult
}

/**
 * El inicio del conteo: los abiertos, que se siguen, los anteriores y la hoja de uno nuevo.
 *
 * Un conteo abierto vive en la base, así que sobrevive a cerrar la app: al volver, aparece aquí
 * con su progreso. Una ubicación tiene a lo sumo uno abierto; pedir otro lleva al que ya existe.
 */
class CountHomeViewModel(private val operations: CountHomeOperations) : ViewModel() {
    private val state = MutableStateFlow(CountHomeUiState())
    val uiState: StateFlow<CountHomeUiState> = state.asStateFlow()

    private val reads = ScreenReads()

    /** Las categorías vigentes, para la hoja de un conteo nuevo. */
    private var categories: List<Category> = emptyList()

    init {
        viewModelScope.launch {
            reads.guard { operations.overview() }.collect { overview ->
                state.update { it.copy(content = CountHomeContent.Ready(overview)) }
            }
        }
        viewModelScope.launch {
            reads.failed.collect { failed ->
                if (failed) state.update { it.copy(content = CountHomeContent.ReadError) }
            }
        }
        viewModelScope.launch {
            operations.categories().collect { list ->
                editSheet { it.copy(categories = list) }
                categories = list
            }
        }
    }

    fun onAction(action: CountHomeAction) {
        when (action) {
            // La navegación las resuelve: aquí no cambia nada.
            CountHomeAction.Back,
            is CountHomeAction.OpenSession,
            CountHomeAction.ChooseZone,
            CountHomeAction.CreateProduct,
            -> Unit

            CountHomeAction.Retry -> {
                state.update { it.copy(content = CountHomeContent.Loading) }
                reads.retry()
            }

            CountHomeAction.NewCount -> {
                val overview = (state.value.content as? CountHomeContent.Ready)?.overview
                val first = overview?.activeLocations?.firstOrNull()?.id
                state.update {
                    it.copy(sheet = NewCountSheet(locationId = first, categories = categories))
                }
            }

            is CountHomeAction.SheetLocation ->
                editSheet { it.copy(locationId = action.locationId, error = null) }

            CountHomeAction.OpenCategories -> editSheet { it.copy(choosingCategory = true) }

            is CountHomeAction.SheetCategory -> editSheet {
                it.copy(categoryId = action.categoryId, choosingCategory = false, error = null)
            }

            is CountHomeAction.SheetBlind -> editSheet { it.copy(blind = action.blind) }

            is CountHomeAction.SheetNote -> editSheet { it.copy(note = action.text, error = null) }

            CountHomeAction.Start -> start()

            CountHomeAction.DismissSheet -> state.update { it.copy(sheet = null) }

            CountHomeAction.OpenedShown -> state.update { it.copy(openedId = null) }
        }
    }

    private fun start() {
        val sheet = state.value.sheet ?: return
        val locationId = sheet.locationId ?: return
        if (sheet.starting) return
        editSheet { it.copy(starting = true, error = null, failedOutOfSpace = null) }
        viewModelScope.launch {
            val request = OpenCountRequest(locationId, sheet.categoryId, sheet.blind, sheet.note)
            when (val result = operations.open(request)) {
                is OpenCountResult.Opened ->
                    state.update { it.copy(sheet = null, openedId = result.sessionId) }

                // Ya se cuenta allí: se sigue con ese, que es lo mismo que se quería.
                is OpenCountResult.AlreadyOpen ->
                    state.update { it.copy(sheet = null, openedId = result.sessionId) }

                OpenCountResult.NothingToCount -> fail(NewCountError.SCOPE_EMPTY)

                // La categoría se borró mientras se elegía: también queda sin nada que contar.
                OpenCountResult.CategoryNotFound -> fail(NewCountError.SCOPE_EMPTY)

                OpenCountResult.NoteTooLong -> fail(NewCountError.NOTE_TOO_LONG)

                OpenCountResult.LocationUnavailable -> fail(NewCountError.LOCATION_UNAVAILABLE)

                OpenCountResult.UnknownTimeZone -> fail(NewCountError.UNKNOWN_ZONE)

                is OpenCountResult.StorageFailed -> editSheet {
                    it.copy(starting = false, failedOutOfSpace = result.failure.isOutOfSpace)
                }
            }
        }
    }

    private fun fail(error: NewCountError) = editSheet { it.copy(starting = false, error = error) }

    private fun editSheet(change: (NewCountSheet) -> NewCountSheet) {
        state.update { current -> current.copy(sheet = current.sheet?.let(change)) }
    }
}
