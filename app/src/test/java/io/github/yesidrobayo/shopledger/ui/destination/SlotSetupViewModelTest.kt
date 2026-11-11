package io.github.yesidrobayo.shopledger.ui.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileRejection
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupConfirm
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupFile
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupLook
import io.github.yesidrobayo.shopledger.domain.port.SlotFilesSetupPort
import io.github.yesidrobayo.shopledger.domain.usecase.backup.PrepareSlotFilesUseCase
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
 * `Preparar archivos en la nube`: un archivo por vez, el que sigue resaltado, cancelar a mitad sin
 * perder nada, cada rechazo con su salida, la confirmación sola con los tres y nunca con otro
 * destino.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlotSetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val port = FakeSetup()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = SlotSetupViewModel(PrepareSlotFilesUseCase(port), dispatcher)

    @Test
    fun `it opens with the three files missing and the first one next`() = runTest(dispatcher) {
        val model = model()
        assertNull("Mientras lee, sin filas", model.uiState.value.look)

        advanceUntilIdle()

        assertEquals(BackupSlot.A, model.uiState.value.next)
        assertFalse(model.uiState.value.ready)
        assertEquals(0, port.confirms)
    }

    @Test
    fun `each accepted file moves to the next and the third confirms`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        for (slot in BackupSlot.entries) {
            model.onAction(SlotSetupAction.FilePicked("content://cloud/${slot.name}"))
            advanceUntilIdle()
        }

        assertEquals(listOf(BackupSlot.A, BackupSlot.B, BackupSlot.C), port.added)
        assertEquals(1, port.confirms)
        assertTrue(model.uiState.value.ready)
        model.onAction(SlotSetupAction.Continue)
        assertEquals(SlotSetupTarget.BACKUP, model.uiState.value.target)
    }

    @Test
    fun `closing the picker keeps the created files and says so`() = runTest(dispatcher) {
        port.statuses[BackupSlot.A] = SlotFileStatus.CREATED
        val model = model()
        advanceUntilIdle()

        model.onAction(SlotSetupAction.FilePicked(null))
        advanceUntilIdle()

        assertEquals(SlotSetupMessage.CANCELLED, model.uiState.value.message)
        assertEquals(BackupSlot.B, model.uiState.value.next)
        assertTrue(port.added.isEmpty())
        model.onAction(SlotSetupAction.MessageShown)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `each rejection shows its notice and creating another clears it`() = runTest(dispatcher) {
        val expected =
            mapOf(
                SlotFileRejection.OUTSIDE_PROVIDERS to SlotSetupNotice.OutsideProviders,
                SlotFileRejection.NO_ACCESS to SlotSetupNotice.NotWritable,
                SlotFileRejection.NOT_WRITABLE to SlotSetupNotice.NotWritable,
                SlotFileRejection.SIZE_UNKNOWN to SlotSetupNotice.SizeUnknown,
                SlotFileRejection.NOT_EMPTY to SlotSetupNotice.NotEmpty,
            )
        val model = model()
        advanceUntilIdle()
        for ((reason, notice) in expected) {
            port.rejection = SlotFileOutcome.Rejected(reason)
            model.onAction(SlotSetupAction.FilePicked("content://other/x"))
            advanceUntilIdle()
            assertEquals(notice, model.uiState.value.notice)
            assertEquals("Sigue el mismo archivo", BackupSlot.A, model.uiState.value.next)
        }
        port.rejection = SlotFileOutcome.Rejected(SlotFileRejection.SAME_FILE, BackupSlot.A)
        model.onAction(SlotSetupAction.FilePicked("content://cloud/A"))
        advanceUntilIdle()
        assertEquals(SlotSetupNotice.SameFile(1), model.uiState.value.notice)

        model.onAction(SlotSetupAction.CreateAnotherFile)
        assertNull(model.uiState.value.notice)
        assertEquals(0, port.confirms)
    }

    @Test
    fun `each notice action leads to its screen`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        val expected =
            mapOf(
                SlotSetupAction.ConnectService to SlotSetupTarget.WEBDAV_CONNECT,
                SlotSetupAction.GoToLocalCopies to SlotSetupTarget.LOCAL_COPIES,
                SlotSetupAction.ExportPhotos to SlotSetupTarget.LOCAL_COPIES,
                SlotSetupAction.ChooseOtherService to SlotSetupTarget.DESTINATION_CHOICE,
                SlotSetupAction.Help to SlotSetupTarget.HELP,
                SlotSetupAction.Back to SlotSetupTarget.BACK,
            )
        for ((action, target) in expected) {
            model.onAction(action)
            assertEquals(target, model.uiState.value.target)
            model.onAction(SlotSetupAction.TargetHandled)
            assertNull(model.uiState.value.target)
        }
    }

    @Test
    fun `with another destination the files are created but never confirmed`() =
        runTest(dispatcher) {
            port.otherDestination = true
            val model = model()
            advanceUntilIdle()

            for (slot in BackupSlot.entries) {
                model.onAction(SlotSetupAction.FilePicked("content://cloud/${slot.name}"))
                advanceUntilIdle()
            }

            assertEquals(0, port.confirms)
            assertFalse(model.uiState.value.ready)
            assertTrue(model.uiState.value.look?.otherDestination == true)
            model.onAction(SlotSetupAction.Continue)
            assertEquals(SlotSetupTarget.BACKUP, model.uiState.value.target)
        }

    @Test
    fun `a confirmation cut short is finished when the screen opens again`() = runTest(dispatcher) {
        BackupSlot.entries.forEach { port.statuses[it] = SlotFileStatus.READY }

        val model = model()
        advanceUntilIdle()

        assertEquals(1, port.confirms)
        assertTrue(model.uiState.value.ready)
    }

    @Test
    fun `a failed confirmation says so and continue tries again`() = runTest(dispatcher) {
        BackupSlot.entries.forEach { port.statuses[it] = SlotFileStatus.CREATED }
        port.confirmResult = SlotSetupConfirm.Failed
        val model = model()
        advanceUntilIdle()

        assertTrue(model.uiState.value.confirmFailed)
        assertEquals(SlotSetupMessage.SAVE_FAILED, model.uiState.value.message)
        assertFalse(model.uiState.value.ready)

        port.confirmResult = SlotSetupConfirm.Done(hasData = true)
        model.onAction(SlotSetupAction.Continue)
        advanceUntilIdle()

        assertEquals(2, port.confirms)
        assertTrue(model.uiState.value.ready)
    }

    @Test
    fun `if something changed meanwhile the files are read again`() = runTest(dispatcher) {
        BackupSlot.entries.forEach { port.statuses[it] = SlotFileStatus.CREATED }
        port.confirmResult = SlotSetupConfirm.Incomplete
        port.afterConfirm = { port.statuses[BackupSlot.B] = SlotFileStatus.MISSING }
        val model = model()
        advanceUntilIdle()

        assertEquals(BackupSlot.B, model.uiState.value.next)
        assertFalse(model.uiState.value.ready)
    }

    @Test
    fun `a disk that cannot keep the file says so and asks for the same file`() =
        runTest(dispatcher) {
            port.rejection = SlotFileOutcome.Unavailable
            val model = model()
            advanceUntilIdle()

            model.onAction(SlotSetupAction.FilePicked("content://cloud/A"))
            advanceUntilIdle()

            assertEquals(SlotSetupMessage.SAVE_FAILED, model.uiState.value.message)
            assertEquals(BackupSlot.A, model.uiState.value.next)
        }

    private class FakeSetup : SlotFilesSetupPort {
        val statuses = BackupSlot.entries.associateWith { SlotFileStatus.MISSING }.toMutableMap()
        val added = ArrayList<BackupSlot>()
        var rejection: SlotFileOutcome? = null
        var otherDestination = false
        var confirms = 0
        var confirmResult: SlotSetupConfirm = SlotSetupConfirm.Done(hasData = false)
        var afterConfirm: () -> Unit = {}

        override suspend fun look(): SlotSetupLook = SlotSetupLook(
            providerLabel = "Drive",
            files = BackupSlot.entries.map { SlotSetupFile(it, statuses.getValue(it), null) },
            otherDestination = otherDestination,
            done = false,
        )

        override suspend fun addFile(slot: BackupSlot, uri: String): SlotFileOutcome {
            rejection?.let { return it }
            added += slot
            statuses[slot] = SlotFileStatus.CREATED
            return SlotFileOutcome.Accepted(look())
        }

        override suspend fun confirm(): SlotSetupConfirm {
            confirms++
            afterConfirm()
            return confirmResult
        }
    }
}
