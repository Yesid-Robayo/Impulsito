package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.BusinessProfileChange
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileResult
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
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
 * saber cómo está. [saveFailed] si el último cambio no se pudo guardar. [profile] es el perfil del
 * negocio, o `null` mientras se lee, y [profileSheet] la hoja de uno de sus datos, si está abierta.
 */
@Immutable
data class SettingsEntryUiState(
    val tipsEnabled: Boolean? = null,
    val saveFailed: Boolean = false,
    val profile: BusinessProfile? = null,
    val profileSheet: ProfileSheet? = null,
)

/**
 * La entrada a Ajustes mientras no exista la pantalla completa: el perfil del negocio, región e
 * idioma, y las propinas del cobro. Cambiar el perfil o las propinas es un dato del negocio y
 * cuenta para el respaldo, así que se guarda con su caso de uso, en su propia transacción.
 *
 * @param tipsSource si las propinas están encendidas, de nuevo cada vez que cambian.
 * @param setTips las enciende o las apaga.
 * @param profileSource el perfil del negocio, de nuevo cada vez que cambia.
 * @param saveProfile cambia un dato del perfil.
 * @param percentSymbols los símbolos con que se lee y se escribe el margen objetivo.
 */
class SettingsEntryViewModel(
    tipsSource: () -> Flow<Boolean>,
    private val setTips: suspend (Boolean) -> SetTipsEnabledResult,
    profileSource: () -> Flow<BusinessProfile>,
    private val saveProfile: suspend (BusinessProfileChange) -> SaveBusinessProfileResult,
    private val percentSymbols: NumberSymbols,
) : ViewModel() {
    private val saveFailed = MutableStateFlow(false)
    private val sheet = MutableStateFlow<ProfileSheet?>(null)

    val uiState: StateFlow<SettingsEntryUiState> = combine(
        tipsSource().map<Boolean, Boolean?> { it }.catch { emit(null) },
        saveFailed,
        profileSource().map<BusinessProfile, BusinessProfile?> { it }.catch { emit(null) },
        sheet,
    ) { tips, failed, current, open -> SettingsEntryUiState(tips, failed, current, open) }
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

    fun onProfileAction(action: ProfileAction) {
        when (action) {
            is ProfileAction.Open -> {
                val current = uiState.value.profile ?: return
                val text = when (action.field) {
                    ProfileField.NAME -> current.businessName.orEmpty()

                    ProfileField.TARGET_MARGIN ->
                        plainNumberText(
                            current.defaultTargetMargin.bp,
                            PERCENT_DECIMALS,
                            percentSymbols,
                        )
                }
                sheet.value = ProfileSheet(action.field, text)
            }

            is ProfileAction.Text ->
                sheet.update { it?.copy(text = action.text, problem = null, saveFailed = null) }

            ProfileAction.Save -> save()

            ProfileAction.Dismiss -> sheet.value = null
        }
    }

    private fun save() {
        val current = sheet.value ?: return
        if (current.saving) return
        val change = when (current.field) {
            ProfileField.NAME -> BusinessProfileChange.Name(current.text)

            ProfileField.TARGET_MARGIN -> readTarget(current.text)?.let {
                BusinessProfileChange.TargetMargin(it)
            }
        } ?: return
        sheet.update { it?.copy(saving = true, saveFailed = null) }
        viewModelScope.launch {
            when (val result = saveProfile(change)) {
                SaveBusinessProfileResult.Saved -> sheet.value = null

                SaveBusinessProfileResult.NameTooLong ->
                    problem(ProfileProblem.NAME_TOO_LONG)

                SaveBusinessProfileResult.TargetMarginOutOfRange ->
                    problem(ProfileProblem.OUT_OF_RANGE)

                is SaveBusinessProfileResult.Failed ->
                    sheet.update {
                        it?.copy(
                            saving = false,
                            saveFailed = SaveFailure(result.failure.isOutOfSpace),
                        )
                    }
            }
        }
    }

    /** El margen escrito, o `null` si no sirve: su problema queda en la hoja. En blanco no es un margen. */
    private fun readTarget(text: String): BasisPoints? = when (
        val read = NumberInputParser.percent(
            text,
            percentSymbols,
            BasisPoints.MAX_TARGET_MARGIN,
        )
    ) {
        is NumberInput.Value -> read.value

        NumberInput.Blank, NumberInput.Malformed -> {
            problem(ProfileProblem.NOT_A_NUMBER)
            null
        }

        is NumberInput.TooManyDecimals -> {
            problem(ProfileProblem.TOO_MANY_DECIMALS)
            null
        }

        is NumberInput.TooLarge -> {
            problem(ProfileProblem.OUT_OF_RANGE)
            null
        }
    }

    private fun problem(problem: ProfileProblem) {
        sheet.update { it?.copy(problem = problem, saving = false) }
    }

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Los decimales de un porcentaje escrito: hasta centésimas, como 32,5 %. */
        const val PERCENT_DECIMALS = 2
    }
}
