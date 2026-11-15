package io.github.yesidrobayo.shopledger.ui.privacy

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountCheck
import io.github.yesidrobayo.shopledger.domain.model.safety.CloudAccountKind
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La lista de la cuenta en pantalla: lee el destino y lo marcado para él, una marca cambia al
 * tocarla y vuelve atrás si no se guardó, sin destino nada se marca, y un acceso perdido lo dice.
 * Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudAccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var destination: BackupDestinationInfo? =
        BackupDestinationInfo.WebDav(account = "negocio", host = "example.com")
    private var destinationFails = false
    private val stored = mutableMapOf<CloudAccountKind, Set<CloudAccountCheck>>()
    private var saves = true
    private var markFails = false
    private val marks = mutableListOf<Triple<CloudAccountKind, CloudAccountCheck, Boolean>>()
    private val status = MutableStateFlow(BackupStatus.UP_TO_DATE)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model(backupStatus: Flow<BackupStatus> = status) = CloudAccountViewModel(
        describeDestination = {
            if (destinationFails) throw IOException("no se pudo leer")
            destination
        },
        checkedFor = { kind -> stored[kind].orEmpty() },
        mark = { kind, check, marked ->
            marks += Triple(kind, check, marked)
            if (markFails) throw IOException("sin espacio")
            if (saves) {
                val current = stored[kind].orEmpty()
                stored[kind] = if (marked) current + check else current - check
                stored[kind]
            } else {
                null
            }
        },
        backupStatus = backupStatus,
        computation = dispatcher,
    )

    private fun TestScope.started(
        backupStatus: Flow<BackupStatus> = status,
    ): CloudAccountViewModel {
        val model = model(backupStatus)
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    @Test
    fun `it reads the destination and what was marked for it`() = runTest(dispatcher) {
        stored[CloudAccountKind.WEBDAV] = setOf(CloudAccountCheck.TWO_STEP)
        stored[CloudAccountKind.DOCUMENT_SLOTS] = setOf(CloudAccountCheck.RECOVERY_PHONE)

        val state = started().uiState.value

        assertFalse(state.loading)
        assertEquals(Fact.Known(destination), state.destination)
        assertEquals(CloudAccountKind.WEBDAV, state.kind)
        assertEquals(setOf(CloudAccountCheck.TWO_STEP), state.checked)
        assertTrue(state.canMark)
        assertFalse(state.accessLost)
    }

    @Test
    fun `a saved mark stays checked for that destination`() = runTest(dispatcher) {
        val model = started()

        model.onAction(CloudAccountAction.Mark(CloudAccountCheck.RECOVERY_EMAIL, true))
        advanceUntilIdle()

        assertEquals(setOf(CloudAccountCheck.RECOVERY_EMAIL), model.uiState.value.checked)
        assertEquals(
            listOf(Triple(CloudAccountKind.WEBDAV, CloudAccountCheck.RECOVERY_EMAIL, true)),
            marks,
        )
        assertFalse(model.uiState.value.saveFailed)
    }

    @Test
    fun `a mark that was not saved goes back and says so`() = runTest(dispatcher) {
        stored[CloudAccountKind.WEBDAV] = setOf(CloudAccountCheck.TWO_STEP)
        saves = false
        val model = started()

        model.onAction(CloudAccountAction.Mark(CloudAccountCheck.TWO_STEP, false))
        advanceUntilIdle()

        assertEquals(setOf(CloudAccountCheck.TWO_STEP), model.uiState.value.checked)
        assertTrue(model.uiState.value.saveFailed)

        model.onAction(CloudAccountAction.MessageShown)
        advanceUntilIdle()
        assertFalse(model.uiState.value.saveFailed)
    }

    @Test
    fun `a mark that throws is not saved`() = runTest(dispatcher) {
        markFails = true
        val model = started()

        model.onAction(CloudAccountAction.Mark(CloudAccountCheck.TWO_STEP, true))
        advanceUntilIdle()

        assertEquals(emptySet<CloudAccountCheck>(), model.uiState.value.checked)
        assertTrue(model.uiState.value.saveFailed)
    }

    @Test
    fun `without a destination nothing can be marked`() = runTest(dispatcher) {
        destination = null
        val model = started()

        model.onAction(CloudAccountAction.Mark(CloudAccountCheck.TWO_STEP, true))
        advanceUntilIdle()

        assertEquals(Fact.Known(null), model.uiState.value.destination)
        assertFalse(model.uiState.value.canMark)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun `a destination that cannot be read is unavailable and nothing is marked`() =
        runTest(dispatcher) {
            destinationFails = true
            val model = started()

            model.onAction(CloudAccountAction.Mark(CloudAccountCheck.TWO_STEP, true))
            advanceUntilIdle()

            assertEquals(Fact.Unavailable, model.uiState.value.destination)
            assertFalse(model.uiState.value.canMark)
            assertTrue(marks.isEmpty())

            destinationFails = false
            model.onAction(CloudAccountAction.Refresh)
            advanceUntilIdle()
            assertTrue(model.uiState.value.canMark)
        }

    @Test
    fun `while loading nothing can be marked`() = runTest(dispatcher) {
        val model = model()

        model.onAction(CloudAccountAction.Mark(CloudAccountCheck.TWO_STEP, true))

        assertTrue(model.uiState.value.loading)
        assertFalse(model.uiState.value.canMark)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun `the marks of the other destination do not show`() = runTest(dispatcher) {
        stored[CloudAccountKind.WEBDAV] = setOf(CloudAccountCheck.TWO_STEP)
        destination = BackupDestinationInfo.Slots(providerLabel = "Drive")

        val state = started().uiState.value

        assertEquals(CloudAccountKind.DOCUMENT_SLOTS, state.kind)
        assertEquals(emptySet<CloudAccountCheck>(), state.checked)
    }

    @Test
    fun `lost access or a closed session shows reconnect`() = runTest(dispatcher) {
        val model = started()
        assertFalse(model.uiState.value.accessLost)

        status.value = BackupStatus.PERMISSION_LOST
        advanceUntilIdle()
        assertTrue(model.uiState.value.accessLost)

        status.value = BackupStatus.UP_TO_DATE
        destination = BackupDestinationInfo.Slots(providerLabel = "Drive", sessionRequired = true)
        model.onAction(CloudAccountAction.Refresh)
        advanceUntilIdle()
        assertTrue(model.uiState.value.accessLost)
    }

    @Test
    fun `a backup status that cannot be read warns of nothing`() = runTest(dispatcher) {
        val failing = flow<BackupStatus> { throw IOException("la base no responde") }

        val state = started(failing).uiState.value

        assertFalse(state.accessLost)
        assertFalse(state.loading)
    }

    @Test
    fun `navigation actions do not change the state`() = runTest(dispatcher) {
        val model = started()
        val before = model.uiState.value

        for (action in listOf(
            CloudAccountAction.Back,
            CloudAccountAction.OpenProvider,
            CloudAccountAction.OpenGuide(AccountGuide.QUOTA),
            CloudAccountAction.ChooseDestination,
            CloudAccountAction.Reconnect,
        )) {
            model.onAction(action)
        }
        advanceUntilIdle()

        assertEquals(before, model.uiState.value)
    }
}
