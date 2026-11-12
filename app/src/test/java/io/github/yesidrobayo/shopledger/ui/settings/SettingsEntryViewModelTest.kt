package io.github.yesidrobayo.shopledger.ui.settings

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.BusinessProfileChange
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveBusinessProfileResult
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El interruptor de las propinas en la entrada a Ajustes: muestra lo guardado, guarda el cambio con
 * su caso de uso y dice si no se pudo. Sin base, no aparece. Y el perfil del negocio: cada dato en su
 * hoja, con lo guardado, sus errores y su falla. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val tips = MutableStateFlow(false)
    private val saved = mutableListOf<Boolean>()
    private var result: SetTipsEnabledResult = SetTipsEnabledResult.Saved
    private val profile = MutableStateFlow(BusinessProfile(null, BasisPoints(3_000L)))
    private val profileChanges = mutableListOf<BusinessProfileChange>()
    private var profileResult: SaveBusinessProfileResult = SaveBusinessProfileResult.Saved

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = SettingsEntryViewModel(
        tipsSource = { tips },
        setTips = { enabled ->
            saved += enabled
            if (result == SetTipsEnabledResult.Saved) tips.value = enabled
            result
        },
        profileSource = { profile },
        saveProfile = { change ->
            profileChanges += change
            if (profileResult == SaveBusinessProfileResult.Saved) {
                profile.value = when (change) {
                    is BusinessProfileChange.Name ->
                        profile.value.copy(businessName = change.raw.trim().ifEmpty { null })

                    is BusinessProfileChange.TargetMargin ->
                        profile.value.copy(defaultTargetMargin = change.value)
                }
            }
            profileResult
        },
        percentSymbols = SYMBOLS,
    )

    @Test
    fun `it shows what is saved and saves the change`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(false, model.uiState.value.tipsEnabled)

        model.onTipsChange(true)
        advanceUntilIdle()

        assertEquals(listOf(true), saved)
        assertEquals(true, model.uiState.value.tipsEnabled)
        assertFalse(model.uiState.value.saveFailed)
    }

    @Test
    fun `a failed save is said and nothing changes`() = runTest(dispatcher) {
        result = SetTipsEnabledResult.Failed(StorageFailure.DISK_FULL)
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }

        model.onTipsChange(true)
        advanceUntilIdle()

        assertTrue(model.uiState.value.saveFailed)
        assertEquals(false, model.uiState.value.tipsEnabled)
    }

    @Test
    fun `a read failure says so with retry, and retry reads again`() = runTest(dispatcher) {
        var failing = true
        val model = SettingsEntryViewModel(
            tipsSource = { if (failing) flow { error("Falla inventada") } else flowOf(true) },
            setTips = { SetTipsEnabledResult.Saved },
            profileSource = { flow { error("Falla inventada") } },
            saveProfile = { SaveBusinessProfileResult.Saved },
            percentSymbols = SYMBOLS,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        assertNull(model.uiState.value.tipsEnabled)
        assertNull(model.uiState.value.profile)
        assertTrue(model.uiState.value.readFailed)

        failing = false
        model.onRetry()
        advanceUntilIdle()

        // Las propinas ya se leen; el perfil sigue fallando, y lo sigue diciendo.
        assertEquals(true, model.uiState.value.tipsEnabled)
        assertTrue(model.uiState.value.readFailed)
    }

    @Test
    fun `the target margin opens with what is saved and saves the new one`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        model.onProfileAction(ProfileAction.Open(ProfileField.TARGET_MARGIN))
        advanceUntilIdle()
        assertEquals("30", model.uiState.value.profileSheet?.text)

        model.onProfileAction(ProfileAction.Text("40,5"))
        advanceUntilIdle()
        model.onProfileAction(ProfileAction.Save)
        advanceUntilIdle()

        assertEquals(
            listOf(BusinessProfileChange.TargetMargin(BasisPoints(4_050L))),
            profileChanges,
        )
        assertNull(model.uiState.value.profileSheet)
        assertEquals(BasisPoints(4_050L), model.uiState.value.profile?.defaultTargetMargin)
    }

    @Test
    fun `a margin above 99 percent or not a number is not saved`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        model.onProfileAction(ProfileAction.Open(ProfileField.TARGET_MARGIN))
        advanceUntilIdle()

        model.onProfileAction(ProfileAction.Text("99,01"))
        advanceUntilIdle()
        model.onProfileAction(ProfileAction.Save)
        advanceUntilIdle()
        assertEquals(ProfileProblem.OUT_OF_RANGE, model.uiState.value.profileSheet?.problem)

        model.onProfileAction(ProfileAction.Text(""))
        advanceUntilIdle()
        model.onProfileAction(ProfileAction.Save)
        advanceUntilIdle()
        assertEquals(ProfileProblem.NOT_A_NUMBER, model.uiState.value.profileSheet?.problem)

        model.onProfileAction(ProfileAction.Text("12,345"))
        advanceUntilIdle()
        model.onProfileAction(ProfileAction.Save)
        advanceUntilIdle()
        assertEquals(ProfileProblem.TOO_MANY_DECIMALS, model.uiState.value.profileSheet?.problem)

        // Escribir de nuevo borra el error.
        model.onProfileAction(ProfileAction.Text("1"))
        advanceUntilIdle()
        assertNull(model.uiState.value.profileSheet?.problem)
        advanceUntilIdle()
        assertTrue(profileChanges.isEmpty())
    }

    @Test
    fun `the name sheet says what the use case rejects or could not save`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        model.onProfileAction(ProfileAction.Open(ProfileField.NAME))
        advanceUntilIdle()
        assertEquals("", model.uiState.value.profileSheet?.text)

        profileResult = SaveBusinessProfileResult.NameTooLong
        model.onProfileAction(ProfileAction.Text("Tienda de prueba"))
        advanceUntilIdle()
        model.onProfileAction(ProfileAction.Save)
        advanceUntilIdle()
        assertEquals(ProfileProblem.NAME_TOO_LONG, model.uiState.value.profileSheet?.problem)

        profileResult = SaveBusinessProfileResult.TargetMarginOutOfRange
        model.onProfileAction(ProfileAction.Save)
        advanceUntilIdle()
        assertEquals(ProfileProblem.OUT_OF_RANGE, model.uiState.value.profileSheet?.problem)

        profileResult = SaveBusinessProfileResult.Failed(StorageFailure.DISK_FULL)
        model.onProfileAction(ProfileAction.Save)
        advanceUntilIdle()
        val sheet = model.uiState.value.profileSheet
        assertEquals(SaveFailure(outOfSpace = true), sheet?.saveFailed)
        assertEquals("Tienda de prueba", sheet?.text)
        assertFalse(sheet?.saving ?: true)

        profileResult = SaveBusinessProfileResult.Saved
        model.onProfileAction(ProfileAction.Save)
        advanceUntilIdle()
        assertEquals("Tienda de prueba", model.uiState.value.profile?.businessName)
        model.onProfileAction(ProfileAction.Open(ProfileField.NAME))
        advanceUntilIdle()
        assertEquals("Tienda de prueba", model.uiState.value.profileSheet?.text)
        model.onProfileAction(ProfileAction.Dismiss)
        advanceUntilIdle()
        assertNull(model.uiState.value.profileSheet)
    }

    private companion object {
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    }
}
