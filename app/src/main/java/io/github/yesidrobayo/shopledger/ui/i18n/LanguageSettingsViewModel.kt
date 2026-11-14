package io.github.yesidrobayo.shopledger.ui.i18n

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.localization.LanguageChoice
import io.github.yesidrobayo.shopledger.localization.UiLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que se avisa después de elegir un idioma, una sola vez. */
enum class LanguageNotice {
    /** `Idioma actualizado`. */
    UPDATED,

    /** `No se pudo guardar el idioma. La app sigue en {language}.`, con `Reintentar`. */
    NOT_SAVED,
}

/**
 * El estado de la pantalla de idioma. No tiene estado de carga ni vacío: los idiomas vienen en la
 * app y la elección se lee antes de la primera composición.
 *
 * @property selected la etiqueta elegida, o `null` si sigue al teléfono.
 * @property installed las etiquetas de los idiomas que trae la app.
 * @property changing si se está aplicando una elección.
 * @property notice lo que hay que avisar, o `null`.
 */
@Immutable
data class LanguageSettingsUiState(
    val selected: String?,
    val installed: List<String>,
    val changing: Boolean = false,
    val notice: LanguageNotice? = null,
)

/** Lo que la persona puede hacer en la pantalla de idioma. */
sealed interface LanguageSettingsAction {
    /** Eligió un idioma, o `null` para seguir al teléfono. */
    data class Choose(val tag: String?) : LanguageSettingsAction

    /** `Reintentar` tras un idioma que no se pudo guardar. */
    data object Retry : LanguageSettingsAction

    /** Ya se mostró el aviso. */
    data object NoticeShown : LanguageSettingsAction
}

/**
 * La pantalla de idioma: muestra el elegido y aplica otro al tocarlo, sin botón de guardar.
 *
 * El idioma vive fuera de la base, así que esta pantalla no la lee ni la escribe: funciona también
 * cuando la base no abre, y elegir un idioma nunca toca el formato regional ni la moneda. Al
 * aplicarse, la actividad no se recrea: la misma pantalla vuelve a escribir sus textos en el
 * idioma nuevo, y el aviso sale ya en ese idioma.
 *
 * @param language el idioma elegido, que cambia también desde los ajustes de Android.
 * @param choose guarda y aplica un idioma.
 * @param installed las etiquetas de los idiomas que trae la app.
 */
class LanguageSettingsViewModel(
    language: StateFlow<UiLanguage>,
    private val choose: suspend (UiLanguage) -> LanguageChoice,
    installed: List<String>,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private var lastAttempt: UiLanguage? = null

    /** Lo que no sale del idioma guardado: si se está cambiando y qué avisar. */
    private data class Local(val changing: Boolean = false, val notice: LanguageNotice? = null)

    val uiState: StateFlow<LanguageSettingsUiState> =
        combine(language, local) { chosen, extra ->
            LanguageSettingsUiState(
                selected = (chosen as? UiLanguage.Chosen)?.tag,
                installed = installed,
                changing = extra.changing,
                notice = extra.notice,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            LanguageSettingsUiState((language.value as? UiLanguage.Chosen)?.tag, installed),
        )

    fun onAction(action: LanguageSettingsAction) {
        when (action) {
            is LanguageSettingsAction.Choose -> {
                val wanted = action.tag?.let(UiLanguage::of) ?: UiLanguage.System
                apply(wanted)
            }

            LanguageSettingsAction.Retry -> lastAttempt?.let(::apply)

            LanguageSettingsAction.NoticeShown -> local.update { it.copy(notice = null) }
        }
    }

    private fun apply(wanted: UiLanguage) {
        val current = uiState.value
        if (current.changing) return
        // Tocar el idioma que ya está elegido no recrea la pantalla ni avisa nada.
        if ((wanted as? UiLanguage.Chosen)?.tag == current.selected) return
        lastAttempt = wanted
        local.update { it.copy(changing = true, notice = null) }
        viewModelScope.launch {
            val notice =
                when (choose(wanted)) {
                    LanguageChoice.Applied -> LanguageNotice.UPDATED
                    LanguageChoice.NotSaved -> LanguageNotice.NOT_SAVED
                }
            local.update { Local(changing = false, notice = notice) }
        }
    }
}
