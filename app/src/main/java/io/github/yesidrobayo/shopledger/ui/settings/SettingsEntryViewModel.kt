package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El estado de la entrada a Ajustes. [tipsEnabled] falta mientras se lee: la fila no se dibuja hasta
 * saber cómo está. [saveFailed] si el último cambio no se pudo guardar.
 */
@Immutable
data class SettingsEntryUiState(val tipsEnabled: Boolean? = null, val saveFailed: Boolean = false)

/**
 * La entrada a Ajustes mientras no exista la pantalla completa: abre región e idioma, y enciende o
 * apaga las propinas del cobro. Cambiar las propinas es un dato del negocio y cuenta para el
 * respaldo, así que se guarda con su caso de uso, en su propia transacción.
 *
 * @param tipsSource si las propinas están encendidas, de nuevo cada vez que cambian.
 * @param setTips las enciende o las apaga.
 */
class SettingsEntryViewModel(
    tipsSource: () -> Flow<Boolean>,
    private val setTips: suspend (Boolean) -> SetTipsEnabledResult,
) : ViewModel() {
    private val saveFailed = MutableStateFlow(false)

    val uiState: StateFlow<SettingsEntryUiState> = combine(
        tipsSource().map<Boolean, Boolean?> { it }.catch { emit(null) },
        saveFailed,
    ) { tips, failed -> SettingsEntryUiState(tips, failed) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            SettingsEntryUiState(),
        )

    fun onTipsChange(enabled: Boolean) {
        saveFailed.value = false
        viewModelScope.launch {
            when (setTips(enabled)) {
                SetTipsEnabledResult.Saved -> Unit
                is SetTipsEnabledResult.Failed -> saveFailed.update { true }
            }
        }
    }

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
