package io.github.yesidrobayo.shopledger.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.region.NumberInput
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.model.settings.NoticeSettings
import io.github.yesidrobayo.shopledger.domain.rule.region.NumberInputParser
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.BusinessProfileChange
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetCostStaleDaysResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import io.github.yesidrobayo.shopledger.ui.payment.plainNumberText
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El estado de la entrada a Ajustes. [tipsEnabled] falta mientras se lee: la fila no se dibuja hasta
 * saber cómo está. [saveFailed] si el último cambio no se pudo guardar. [profile] es el perfil del
 * negocio, o `null` mientras se lee, y [profileSheet] la hoja de uno de sus datos, si está abierta.
 * [readFailed] si el perfil o las propinas no se pudieron leer: se dice, con `Reintentar`.
 * [costStaleDays] son los días para costo viejo, o `null` mientras se leen: la fila no se dibuja.
 */
@Immutable
data class SettingsEntryUiState(
    val tipsEnabled: Boolean? = null,
    val saveFailed: Boolean = false,
    val profile: BusinessProfile? = null,
    val profileSheet: ProfileSheet? = null,
    val readFailed: Boolean = false,
    val costStaleDays: Int? = null,
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
 * @param costStaleSource los días para costo viejo, de nuevo cada vez que cambian.
 * @param saveCostStale los cambia; también es un dato del negocio.
 * @param countSymbols los símbolos con que se leen y se escriben esos días.
 */
class SettingsEntryViewModel(
    tipsSource: () -> Flow<Boolean>,
    private val setTips: suspend (Boolean) -> SetTipsEnabledResult,
    profileSource: () -> Flow<BusinessProfile>,
    private val saveProfile: suspend (BusinessProfileChange) -> SaveBusinessProfileResult,
    private val percentSymbols: NumberSymbols,
    costStaleSource: () -> Flow<Int> = { emptyFlow() },
    private val saveCostStale: suspend (Int) -> SetCostStaleDaysResult =
        { SetCostStaleDaysResult.Saved },
    private val countSymbols: NumberSymbols = percentSymbols,
) : ViewModel() {
    private val saveFailed = MutableStateFlow(false)
    private val sheet = MutableStateFlow<ProfileSheet?>(null)

    /** El perfil y las propinas: una falla se dice con `Reintentar`, y no esconde sus grupos. */
    private val reads = ScreenReads()

    private val costStale: Flow<Int?> =
        reads.guard { costStaleSource().map<Int, Int?> { it } }.onStart { emit(null) }

    val uiState: StateFlow<SettingsEntryUiState> = combine(
        reads.guard { tipsSource().map<Boolean, Boolean?> { it } }.onStart { emit(null) },
        saveFailed,
        reads.guard { profileSource().map<BusinessProfile, BusinessProfile?> { it } }
            .onStart { emit(null) },
        sheet,
        reads.failed,
    ) { tips, failed, current, open, unreadable ->
        SettingsEntryUiState(tips, failed, current, open, unreadable)
    }
        .combine(costStale) { state, days -> state.copy(costStaleDays = days) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            SettingsEntryUiState(),
        )

    fun onRetry() = reads.retry()

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
                val text = savedText(action.field) ?: return
                sheet.value = ProfileSheet(action.field, text)
            }

            is ProfileAction.Text ->
                sheet.update { it?.copy(text = action.text, problem = null, saveFailed = null) }

            ProfileAction.Save -> save()

            ProfileAction.Dismiss -> sheet.value = null
        }
    }

    /** Lo guardado de [field], como se escribe en su hoja, o `null` si todavía no se leyó. */
    private fun savedText(field: ProfileField): String? {
        val state = uiState.value
        return when (field) {
            ProfileField.NAME -> state.profile?.let { it.businessName.orEmpty() }

            ProfileField.TARGET_MARGIN ->
                state.profile?.let {
                    plainNumberText(it.defaultTargetMargin.bp, PERCENT_DECIMALS, percentSymbols)
                }

            ProfileField.COST_STALE_DAYS ->
                state.costStaleDays?.let { plainNumberText(it.toLong(), 0, countSymbols) }
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

            ProfileField.COST_STALE_DAYS -> {
                saveDays(current.text)
                null
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

    /** Guarda los días escritos; si no sirven, su problema queda en la hoja. */
    private fun saveDays(text: String) {
        val days = readDays(text) ?: return
        sheet.update { it?.copy(saving = true, saveFailed = null) }
        viewModelScope.launch {
            when (val result = saveCostStale(days)) {
                SetCostStaleDaysResult.Saved -> sheet.value = null

                SetCostStaleDaysResult.OutOfRange -> problem(ProfileProblem.DAYS_OUT_OF_RANGE)

                is SetCostStaleDaysResult.Failed ->
                    sheet.update {
                        it?.copy(
                            saving = false,
                            saveFailed = SaveFailure(result.failure.isOutOfSpace),
                        )
                    }
            }
        }
    }

    /** Los días escritos, o `null` si no sirven. En blanco no son días: para apagar va un 0. */
    private fun readDays(text: String): Int? {
        val read = NumberInputParser.count(text, countSymbols, Limit.UNIT_MONEY)
        val problem = when (read) {
            is NumberInput.Value ->
                if (read.value <= NoticeSettings.MAX_COST_STALE_DAYS) {
                    return read.value.toInt()
                } else {
                    ProfileProblem.DAYS_OUT_OF_RANGE
                }

            NumberInput.Blank, NumberInput.Malformed, is NumberInput.TooManyDecimals ->
                ProfileProblem.NOT_A_NUMBER

            is NumberInput.TooLarge -> ProfileProblem.DAYS_OUT_OF_RANGE
        }
        problem(problem)
        return null
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
