package io.github.yesidrobayo.shopledger.ui.settings

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
 * su caso de uso y dice si no se pudo. Sin base, no aparece.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val tips = MutableStateFlow(false)
    private val saved = mutableListOf<Boolean>()
    private var result: SetTipsEnabledResult = SetTipsEnabledResult.Saved

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
    fun `a read failure hides the row`() = runTest(dispatcher) {
        val model = SettingsEntryViewModel(
            tipsSource = { flow { error("Falla inventada") } },
            setTips = { SetTipsEnabledResult.Saved },
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()

        assertNull(model.uiState.value.tipsEnabled)
    }
}
