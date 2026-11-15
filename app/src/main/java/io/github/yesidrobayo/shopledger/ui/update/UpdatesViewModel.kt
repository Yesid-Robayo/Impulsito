package io.github.yesidrobayo.shopledger.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.update.ApkCheckResult
import io.github.yesidrobayo.shopledger.update.InstalledApp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `Actualizaciones y verificar APK`: muestra la versión y la huella de la app instalada, y compara
 * un archivo elegido con ella. Nada de esto usa la red, y nada instala.
 *
 * Un archivo a la vez: mientras se comprueba uno, elegir otro no hace nada. Una falla inesperada
 * al comprobar dice que no se pudo leer, sin cerrar la pantalla.
 *
 * @param installedApp la versión y la huella de la app instalada, o nulo si no se pudieron leer.
 * @param verifyApk compara el archivo de una dirección del selector con la app instalada.
 */
class UpdatesViewModel(
    private val installedApp: suspend () -> InstalledApp?,
    private val verifyApk: suspend (String) -> ApkCheckResult,
) : ViewModel() {
    private val state = MutableStateFlow(UpdatesUiState())

    val uiState: StateFlow<UpdatesUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val read =
                try {
                    installedApp()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            state.update { it.copy(installedLoading = false, installed = read) }
        }
    }

    fun onAction(action: UpdatesAction) {
        when (action) {
            is UpdatesAction.FileChosen -> action.uri?.let(::verify)

            // Las resuelven la pantalla y la navegación.
            UpdatesAction.Back,
            UpdatesAction.ChooseFile,
            -> Unit
        }
    }

    private fun verify(uri: String) {
        if (state.value.verify == VerifyUi.Checking) return
        state.update { it.copy(verify = VerifyUi.Checking) }
        viewModelScope.launch {
            val result =
                try {
                    verifyApk(uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ApkCheckResult.ReadFailed
                }
            state.update { it.copy(verify = VerifyUi.Done(result)) }
        }
    }
}
