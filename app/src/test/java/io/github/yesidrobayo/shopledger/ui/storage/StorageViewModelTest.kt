package io.github.yesidrobayo.shopledger.ui.storage

import io.github.yesidrobayo.shopledger.domain.model.erase.EraseDestination
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseOutcome
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseSummary
import io.github.yesidrobayo.shopledger.domain.model.erase.StorageUsage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * `Almacenamiento y borrado`: mide sin bloquear, `Borrar mis datos` va paso a paso y solo borra
 * con la palabra escrita, se detiene con su razón, y un borrado en espera aparece al abrir.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val usage = StorageUsage(2_000, 38_000, 4_000, 600, 1_000_000)
    private var summary =
        EraseSummary(
            records = 120,
            products = 40,
            photos = 3,
            unsyncedChanges = 2,
            photosPending = null,
            destination = EraseDestination.WEBDAV,
            providerLabel = null,
            backupFailed = false,
            restoreInProgress = false,
            pending = null,
        )
    private var outcome: EraseOutcome = EraseOutcome.Finished
    private var failUsage = false
    private val calls = mutableListOf<String>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(fromAndroid: Boolean = false): StorageViewModel {
        val model =
            StorageViewModel(
                usage = {
                    if (failUsage) throw IOException("sin permiso")
                    usage
                },
                summary = { summary },
                erase = { scope ->
                    calls += "erase:$scope"
                    outcome
                },
                resume = {
                    calls += "resume"
                    outcome
                },
                deviceOnly = {
                    calls += "deviceOnly"
                    outcome
                },
                backupNow = { calls += "backup" },
                confirmWord = "BORRAR",
                computation = dispatcher,
                fromAndroid = fromAndroid,
            )
        advanceUntilIdle()
        return model
    }

    private fun StorageViewModel.toConfirm(scope: EraseScope) {
        onAction(StorageAction.OpenErase)
        onAction(StorageAction.ChooseScope(scope))
        onAction(StorageAction.Continue)
        onAction(StorageAction.Continue)
    }

    @Test
    fun `it measures and reads what would be lost, and remembers it came from android`() =
        runTest(dispatcher) {
            val model = model(fromAndroid = true)

            assertEquals(usage, model.uiState.value.usage)
            assertEquals(summary, model.uiState.value.summary)
            assertTrue(model.uiState.value.fromAndroid)
            assertEquals(2L, model.uiState.value.unsyncedChanges)
            assertNull(model.uiState.value.flow)
        }

    @Test
    fun `a measure that fails leaves every row unavailable and erasing still possible`() =
        runTest(dispatcher) {
            failUsage = true
            val model = model()

            assertEquals(StorageUsage(null, null, null, null, null), model.uiState.value.usage)
            assertTrue(model.uiState.value.eraseAvailable)
        }

    @Test
    fun `nothing is chosen first, and continuing needs a choice`() = runTest(dispatcher) {
        val model = model()
        model.onAction(StorageAction.OpenErase)
        model.onAction(StorageAction.Continue)

        assertEquals(EraseFlow.Scope(null), model.uiState.value.flow)

        model.onAction(StorageAction.ChooseScope(EraseScope.DEVICE_ONLY))
        model.onAction(StorageAction.Continue)
        assertEquals(EraseFlow.Review(EraseScope.DEVICE_ONLY), model.uiState.value.flow)
        model.onAction(StorageAction.StepBack)
        assertEquals(EraseFlow.Scope(EraseScope.DEVICE_ONLY), model.uiState.value.flow)
        model.onAction(StorageAction.StepBack)
        assertNull(model.uiState.value.flow)
    }

    @Test
    fun `it only erases with the word typed, and then the app starts over`() = runTest(dispatcher) {
        val model = model()
        model.toConfirm(EraseScope.DEVICE_AND_COPIES)
        model.onAction(StorageAction.Typed("borra"))
        model.onAction(StorageAction.EraseAuthorized)
        advanceUntilIdle()
        assertTrue(calls.isEmpty())

        model.onAction(StorageAction.Typed(" borrar "))
        model.onAction(StorageAction.EraseAuthorized)
        assertEquals(EraseFlow.Running(cloud = true), model.uiState.value.flow)
        // Mientras borra no se cierra.
        model.onAction(StorageAction.Dismiss)
        assertEquals(EraseFlow.Running(cloud = true), model.uiState.value.flow)
        advanceUntilIdle()

        assertEquals(listOf("erase:DEVICE_AND_COPIES"), calls)
        assertTrue(model.uiState.value.finished)
    }

    @Test
    fun `a stop says why, retry resumes, and this phone only asks again`() = runTest(dispatcher) {
        outcome = EraseOutcome.Stopped(EraseStop.REFUSED)
        val model = model()
        model.toConfirm(EraseScope.DEVICE_AND_COPIES)
        model.onAction(StorageAction.Typed("BORRAR"))
        model.onAction(StorageAction.EraseAuthorized)
        advanceUntilIdle()
        assertEquals(EraseFlow.Stopped(EraseStop.REFUSED), model.uiState.value.flow)

        model.onAction(StorageAction.Retry)
        advanceUntilIdle()
        model.onAction(StorageAction.DeviceOnly)
        assertEquals(EraseFlow.ConfirmDeviceOnly, model.uiState.value.flow)
        outcome = EraseOutcome.Finished
        model.onAction(StorageAction.DeviceOnlyAuthorized)
        advanceUntilIdle()

        assertEquals(listOf("erase:DEVICE_AND_COPIES", "resume", "deviceOnly"), calls)
        assertTrue(model.uiState.value.finished)
    }

    @Test
    fun `a failure while erasing never says this phone was erased`() = runTest(dispatcher) {
        val model =
            StorageViewModel(
                usage = { usage },
                summary = { summary },
                erase = { throw IOException("disco") },
                resume = { null },
                deviceOnly = { throw IOException("disco") },
                backupNow = {},
                confirmWord = "BORRAR",
                computation = dispatcher,
                fromAndroid = false,
            )
        advanceUntilIdle()
        model.toConfirm(EraseScope.DEVICE_AND_COPIES)
        model.onAction(StorageAction.Typed("BORRAR"))
        model.onAction(StorageAction.EraseAuthorized)
        advanceUntilIdle()
        assertEquals(EraseFlow.Stopped(EraseStop.UNREACHABLE), model.uiState.value.flow)

        model.onAction(StorageAction.DeviceOnly)
        model.onAction(StorageAction.DeviceOnlyAuthorized)
        advanceUntilIdle()
        assertEquals(EraseFlow.Stopped(EraseStop.LOCAL_FAILED), model.uiState.value.flow)
        assertFalse(model.uiState.value.finished)
    }

    @Test
    fun `an erase waiting for the cloud is offered when the screen opens`() = runTest(dispatcher) {
        summary = summary.copy(pending = ErasePhase.REMOTE)

        val model = model()

        assertEquals(EraseFlow.Stopped(EraseStop.PARTIAL), model.uiState.value.flow)
        model.onAction(StorageAction.Retry)
        advanceUntilIdle()
        assertEquals(listOf("resume"), calls)
    }

    @Test
    fun `a pending local erase is offered as unfinished`() = runTest(dispatcher) {
        summary = summary.copy(pending = ErasePhase.LOCAL)

        assertEquals(EraseFlow.Stopped(EraseStop.LOCAL_FAILED), model().uiState.value.flow)
    }

    @Test
    fun `during a restore erasing does not open, and back up now is asked`() = runTest(dispatcher) {
        summary = summary.copy(restoreInProgress = true, destination = EraseDestination.NONE)
        val model = model()

        model.onAction(StorageAction.OpenErase)
        model.onAction(StorageAction.BackupNow)
        model.onAction(StorageAction.Refresh)
        advanceUntilIdle()

        assertFalse(model.uiState.value.eraseAvailable)
        assertNull(model.uiState.value.flow)
        assertEquals(listOf("backup"), calls)
    }

    @Test
    fun `this phone only without copies does not go through the cloud`() = runTest(dispatcher) {
        val model = model()
        model.toConfirm(EraseScope.DEVICE_ONLY)
        model.onAction(StorageAction.Typed("BORRAR"))
        model.onAction(StorageAction.EraseAuthorized)

        assertEquals(EraseFlow.Running(cloud = false), model.uiState.value.flow)
    }
}
