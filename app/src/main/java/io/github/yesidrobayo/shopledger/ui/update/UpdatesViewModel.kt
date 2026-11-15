package io.github.yesidrobayo.shopledger.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.update.UpdateCheckOutcome
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `Actualizaciones y verificar APK`: muestra la versión y la huella de la app instalada, busca una
 * versión nueva solo cuando la persona toca el botón, y compara un archivo elegido con la app.
 * Nada de esto bloquea la app ni los datos, y nada instala.
 *
 * - **La búsqueda** solo corre con `Permitir buscar actualizaciones` encendido, una a la vez. Sin
 *   red o con un aviso que no se pudo leer o comprobar, lo dice y se puede reintentar.
 * - **Un archivo a la vez:** mientras se comprueba uno, elegir otro no hace nada. Si la búsqueda
 *   de esta visita leyó un aviso, dice también si el SHA-256 del archivo coincide con el suyo.
 * - Una falla inesperada en cualquiera de las dos dice que no se pudo leer, sin cerrar la
 *   pantalla.
 *
 * @param installedApp la versión y la huella de la app instalada, o nulo si no se pudieron leer.
 * @param verifyApk compara el archivo de una dirección del selector con la app instalada.
 * @param searchEnabled si la búsqueda está permitida, de nuevo cada vez que cambia.
 * @param setSearchEnabled guarda el interruptor; `false` si no quedó guardado.
 * @param checkForUpdate lee el aviso de versión y lo compara con la app instalada.
 * @param lastSearchAt la última búsqueda en este teléfono, o nula si nunca.
 * @param zone la zona del negocio, o nula si todavía no se eligió: entonces las fechas van en UTC.
 * @param now el reloj del teléfono.
 */
class UpdatesViewModel(
    private val installedApp: suspend () -> InstalledApp?,
    private val verifyApk: suspend (String) -> ApkCheckResult,
    searchEnabled: Flow<Boolean>,
    private val setSearchEnabled: suspend (Boolean) -> Boolean,
    private val checkForUpdate: suspend () -> UpdateCheckOutcome,
    private val lastSearchAt: suspend () -> Instant?,
    zone: Flow<BusinessTimeZone?>,
    private val now: () -> Instant,
) : ViewModel() {
    private val state = MutableStateFlow(UpdatesUiState(now = now()))

    /** La huella del APK del último aviso leído en esta visita, o nula si no se leyó ninguno. */
    private var manifestSha256: String? = null

    val uiState: StateFlow<UpdatesUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val read = attempt(null) { installedApp() }
            state.update { it.copy(installedLoading = false, installed = read) }
        }
        viewModelScope.launch {
            val last = attempt(null) { lastSearchAt() }
            state.update { it.copy(lastSearchAt = last) }
        }
        viewModelScope.launch {
            searchEnabled
                .map<Boolean, Boolean?> { it }
                .catch { emit(null) }
                .collect { enabled -> state.update { it.copy(searchEnabled = enabled) } }
        }
        viewModelScope.launch {
            zone
                .catch { emit(null) }
                .collect { read ->
                    state.update { it.copy(zone = read ?: UpdatesUiState.UTC) }
                }
        }
    }

    fun onAction(action: UpdatesAction) {
        when (action) {
            UpdatesAction.Search -> search()

            is UpdatesAction.SetSearchEnabled -> saveSearchEnabled(action.enabled)

            UpdatesAction.MessageShown -> state.update { it.copy(saveFailed = false) }

            is UpdatesAction.FileChosen -> action.uri?.let(::verify)

            // Las resuelven la pantalla y la navegación.
            UpdatesAction.Back,
            UpdatesAction.ChooseFile,
            -> Unit
        }
    }

    private fun search() {
        val current = state.value
        if (current.searchEnabled != true || current.search == SearchUi.Searching) return
        state.update { it.copy(search = SearchUi.Searching) }
        viewModelScope.launch {
            val outcome = attempt(UpdateCheckOutcome.Unreadable) { checkForUpdate() }
            when (outcome) {
                is UpdateCheckOutcome.Latest -> manifestSha256 = outcome.apkSha256
                is UpdateCheckOutcome.NewVersion -> manifestSha256 = outcome.apkSha256
                else -> Unit
            }
            val last = attempt(null) { lastSearchAt() }
            state.update {
                it.copy(
                    search = SearchUi.Done(outcome),
                    lastSearchAt = last ?: it.lastSearchAt,
                    now = now(),
                )
            }
        }
    }

    private fun saveSearchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val saved = attempt(false) { setSearchEnabled(enabled) }
            state.update {
                when {
                    !saved -> it.copy(saveFailed = true)

                    // Apagada, la búsqueda no deja nada a la vista: ni su resultado.
                    !enabled -> it.copy(search = SearchUi.Idle)

                    else -> it
                }
            }
        }
    }

    private fun verify(uri: String) {
        if (state.value.verify == VerifyUi.Checking) return
        state.update { it.copy(verify = VerifyUi.Checking) }
        viewModelScope.launch {
            val result = attempt<ApkCheckResult>(ApkCheckResult.ReadFailed) { verifyApk(uri) }
            val expected = manifestSha256
            val matches =
                result is ApkCheckResult.Match &&
                    expected != null &&
                    result.sha256.equals(expected, ignoreCase = true)
            state.update { it.copy(verify = VerifyUi.Done(result, manifestMatch = matches)) }
        }
    }

    /** [block], o [fallback] si falla. Una cancelación sigue su curso. */
    private suspend fun <T> attempt(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback
    }
}
