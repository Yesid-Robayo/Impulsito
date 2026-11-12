package io.github.yesidrobayo.shopledger.ui.localcopies

import io.github.yesidrobayo.shopledger.domain.model.backup.ConnectFolderResult
import io.github.yesidrobayo.shopledger.domain.model.backup.FoundLocalCopies
import io.github.yesidrobayo.shopledger.domain.model.backup.FullCopyExport
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView
import io.github.yesidrobayo.shopledger.domain.port.SecondDestinationPort
import io.github.yesidrobayo.shopledger.domain.usecase.backup.SecondDestinationUseCase
import kotlin.time.Instant
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El segundo destino en `Copias locales`: elegir carpeta, una que no es del teléfono se rechaza y se
 * dice, `Mantener al día esta carpeta`, quitarla solo tras confirmar, y los problemas de la carpeta
 * que llegan del archivo guardado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecondDestinationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-12T03:07:00Z")

    private val port =
        object : SecondDestinationPort {
            var view: SecondDestinationView = SecondDestinationView.None
            var result = ConnectFolderResult.CONNECTED
            val connected = mutableListOf<Pair<String, String>>()
            var removed = 0

            override suspend fun view(): SecondDestinationView = view

            override suspend fun connect(treeUri: String, appName: String): ConnectFolderResult {
                connected += treeUri to appName
                if (result == ConnectFolderResult.CONNECTED) {
                    view = SecondDestinationView.Connected("Tienda", null, false, null)
                }
                return result
            }

            override suspend fun setFollowMainBackup(follow: Boolean) {
                view = (view as SecondDestinationView.Connected).copy(followMainBackup = follow)
            }

            override suspend fun remove() {
                removed += 1
                view = SecondDestinationView.None
            }

            override suspend fun find(treeUri: String, appName: String): FoundLocalCopies =
                FoundLocalCopies.NoCopies

            override suspend fun reconnectAndAdopt(treeUri: String, appName: String) =
                ConnectFolderResult.CONNECTED
        }

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): LocalCopiesViewModel {
        val model =
            LocalCopiesViewModel(
                export = { FullCopyExport.Failed },
                encrypted = { false },
                zone = { null },
                now = { now },
                computation = dispatcher,
                second = SecondDestinationUseCase(port),
                appName = "Tienda",
            )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `without a folder it offers to choose one`() = runTest(dispatcher) {
        assertEquals(SecondDestinationView.None, viewModel().uiState.value.second)
    }

    @Test
    fun `a chosen folder is saved with the app name`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(LocalCopiesAction.FolderPicked("content://arbol"))
        advanceUntilIdle()

        assertEquals(listOf("content://arbol" to "Tienda"), port.connected)
        assertTrue(model.uiState.value.second is SecondDestinationView.Connected)
        assertFalse(model.uiState.value.folderRejected)
    }

    @Test
    fun `a folder that is not of the phone is rejected and said until another is chosen`() =
        runTest(dispatcher) {
            port.result = ConnectFolderResult.NOT_LOCAL
            val model = viewModel()

            model.onAction(LocalCopiesAction.FolderPicked("content://nube"))
            advanceUntilIdle()
            assertTrue(model.uiState.value.folderRejected)
            assertEquals(SecondDestinationView.None, model.uiState.value.second)

            port.result = ConnectFolderResult.CONNECTED
            model.onAction(LocalCopiesAction.FolderPicked("content://arbol"))
            advanceUntilIdle()
            assertFalse(model.uiState.value.folderRejected)
        }

    @Test
    fun `a folder that could not be saved says so briefly`() = runTest(dispatcher) {
        port.result = ConnectFolderResult.FAILED
        val model = viewModel()

        model.onAction(LocalCopiesAction.FolderPicked("content://arbol"))
        advanceUntilIdle()

        assertEquals(LocalCopiesMessage.FOLDER_FAILED, model.uiState.value.message)
    }

    @Test
    fun `following the main copy is saved`() = runTest(dispatcher) {
        port.view = SecondDestinationView.Connected("Tienda", null, false, null)
        val model = viewModel()

        model.onAction(LocalCopiesAction.SetFollow(true))
        advanceUntilIdle()

        assertEquals(
            true,
            (model.uiState.value.second as SecondDestinationView.Connected).followMainBackup,
        )
    }

    @Test
    fun `removing asks first`() = runTest(dispatcher) {
        port.view = SecondDestinationView.Connected("Tienda", null, false, null)
        val model = viewModel()

        model.onAction(LocalCopiesAction.AskRemove)
        assertTrue(model.uiState.value.confirmRemove)
        model.onAction(LocalCopiesAction.CloseRemove)
        advanceUntilIdle()
        assertEquals(0, port.removed)

        model.onAction(LocalCopiesAction.AskRemove)
        model.onAction(LocalCopiesAction.ConfirmRemove)
        advanceUntilIdle()
        assertEquals(1, port.removed)
        assertFalse(model.uiState.value.confirmRemove)
        assertEquals(SecondDestinationView.None, model.uiState.value.second)
    }

    @Test
    fun `a problem of the folder is read again when the screen returns`() = runTest(dispatcher) {
        port.view = SecondDestinationView.Connected("Tienda", null, false, null)
        val model = viewModel()

        port.view =
            SecondDestinationView.Connected(
                "Tienda",
                null,
                false,
                SecondDestinationProblem.FOLDER_MISSING,
            )
        model.onAction(LocalCopiesAction.Resumed)
        advanceUntilIdle()

        assertEquals(
            SecondDestinationProblem.FOLDER_MISSING,
            (model.uiState.value.second as SecondDestinationView.Connected).problem,
        )
    }
}
