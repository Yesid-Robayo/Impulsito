package io.github.yesidrobayo.shopledger.ui.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * `Dónde guardar las copias`: lee qué destino está conectado sin red, la nube del teléfono pasa por
 * `Antes de elegir`, la opción conectada lleva al respaldo y `Ahora no` solo existe sin destino.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DestinationChoiceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model(
        firstRun: Boolean = false,
        describe: suspend () -> BackupDestinationInfo? = { null },
    ) = DestinationChoiceViewModel(
        describe = describe,
        firstRun = firstRun,
        slotsHandoff = SlotsHandoff.AUTOMATIC,
        computation = dispatcher,
    )

    @Test
    fun `without a destination it offers not now and leaves without saving`() =
        runTest(dispatcher) {
            val model = model(firstRun = true)
            assertEquals(ConnectedDestination.UNKNOWN, model.uiState.value.connected)
            assertFalse(model.uiState.value.showsNotNow)

            advanceUntilIdle()

            val state = model.uiState.value
            assertEquals(ConnectedDestination.NONE, state.connected)
            assertTrue(state.showsNotNow)
            assertTrue(state.firstRun)
            model.onAction(DestinationChoiceAction.NotNow)
            assertEquals(DestinationChoiceTarget.NOT_NOW, model.uiState.value.target)
            model.onAction(DestinationChoiceAction.TargetHandled)
            assertNull(model.uiState.value.target)
        }

    @Test
    fun `the phone cloud asks first and closing the sheet changes nothing`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(DestinationChoiceAction.ChooseSlots)
        assertTrue(model.uiState.value.slotsWarning)
        assertNull(model.uiState.value.target)

        model.onAction(DestinationChoiceAction.DismissSlotsWarning)
        assertFalse(model.uiState.value.slotsWarning)
        assertNull(model.uiState.value.target)

        model.onAction(DestinationChoiceAction.ChooseSlots)
        model.onAction(DestinationChoiceAction.ConfirmSlots)
        assertFalse(model.uiState.value.slotsWarning)
        assertEquals(DestinationChoiceTarget.DOCUMENT_SLOTS, model.uiState.value.target)
    }

    @Test
    fun `webdav goes straight to connecting`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(DestinationChoiceAction.ChooseWebDav)

        assertEquals(DestinationChoiceTarget.WEBDAV_CONNECT, model.uiState.value.target)
    }

    @Test
    fun `the connected webdav is current and leads to the backup`() = runTest(dispatcher) {
        val model = model { BackupDestinationInfo.WebDav("copias", "dav.example.com") }
        advanceUntilIdle()

        assertEquals(ConnectedDestination.WEBDAV, model.uiState.value.connected)
        assertFalse(model.uiState.value.showsNotNow)
        model.onAction(DestinationChoiceAction.ChooseWebDav)
        assertEquals(DestinationChoiceTarget.BACKUP, model.uiState.value.target)

        // La otra opción sigue: cambiar de destino lo confirma la pantalla siguiente.
        model.onAction(DestinationChoiceAction.TargetHandled)
        model.onAction(DestinationChoiceAction.ChooseSlots)
        assertTrue(model.uiState.value.slotsWarning)
    }

    @Test
    fun `the connected phone cloud skips the sheet and leads to the backup`() =
        runTest(dispatcher) {
            val model = model { BackupDestinationInfo.Slots("Drive") }
            advanceUntilIdle()

            model.onAction(DestinationChoiceAction.ChooseSlots)

            assertFalse(model.uiState.value.slotsWarning)
            assertEquals(DestinationChoiceTarget.BACKUP, model.uiState.value.target)
            model.onAction(DestinationChoiceAction.TargetHandled)
            model.onAction(DestinationChoiceAction.ChooseWebDav)
            assertEquals(DestinationChoiceTarget.WEBDAV_CONNECT, model.uiState.value.target)
        }

    @Test
    fun `a failed read keeps both options and not now`() = runTest(dispatcher) {
        val model = model { error("disco") }
        advanceUntilIdle()

        assertEquals(ConnectedDestination.NONE, model.uiState.value.connected)
        assertTrue(model.uiState.value.showsNotNow)
    }

    @Test
    fun `back and data transparency are navigation`() = runTest(dispatcher) {
        val model = model()
        model.onAction(DestinationChoiceAction.Back)
        assertEquals(DestinationChoiceTarget.BACK, model.uiState.value.target)
        model.onAction(DestinationChoiceAction.OpenDataTransparency)
        assertEquals(DestinationChoiceTarget.DATA_TRANSPARENCY, model.uiState.value.target)
    }
}
