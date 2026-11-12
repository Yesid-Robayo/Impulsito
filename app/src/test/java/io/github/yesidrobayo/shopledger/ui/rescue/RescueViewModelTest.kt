package io.github.yesidrobayo.shopledger.ui.rescue

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreNotice
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El rescate sin la base: qué acción manda según la causa y el destino guardado, la lista que se
 * lee solo al tocar `Restaurar`, sus estados, y restaurar una copia o un archivo. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RescueViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val copy = BackupCopySummary(
        key = "copia-1",
        createdAt = Instant.parse("2026-11-07T14:00:00Z"),
        sizeBytes = 2_400_000,
        origin = CopyOrigin.THIS_PHONE,
        slot = null,
        records = 812,
        newest = true,
        pinned = false,
        withDifferences = false,
        restorable = true,
    )

    private class FakeDestination : BackupDestinationSource {
        var network = true
        var lists = 0
        var copies: BackupCopyList = BackupCopyList.Listed(emptyList())

        override suspend fun describe(): BackupDestinationInfo? = null

        override suspend fun listCopies(): BackupCopyList {
            lists += 1
            return copies
        }

        override suspend fun latestHandoff(): HandoffCopy? = null

        override fun hasNetwork() = network
    }

    private class FakeRestore : BackupRestorePort {
        val started = mutableListOf<String>()
        var next: RestoreProgress = RestoreProgress.ReadyToRestart(0)

        override suspend fun restoreCopy(copyKey: String): RestoreProgress {
            started += "copy:$copyKey"
            return next
        }

        override suspend fun restoreFile(uri: String): RestoreProgress {
            started += "file:$uri"
            return next
        }

        var beforeUpdate: BeforeUpdateCopy? = null

        override suspend fun beforeUpdateCopy(): BeforeUpdateCopy? = beforeUpdate

        override suspend fun restoreBeforeUpdate(): RestoreProgress {
            started += "before-update"
            return next
        }

        override suspend fun answer(answer: RestoreAnswer): RestoreProgress = next

        override suspend fun cancel(): Boolean = true
    }

    private val destination = FakeDestination()
    private val restore = FakeRestore()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(
        cause: RescueReason = RescueReason.OpenFailed,
        kind: BackupDestinationKind? = BackupDestinationKind.WEBDAV,
        damaged: Boolean = false,
        pending: Boolean = false,
    ): RescueViewModel {
        val model =
            RescueViewModel(
                cause = cause,
                destination = BackupDestinationReader(destination),
                destinationKind = { kind },
                hasDamagedOriginal = { damaged },
                hasPendingRestore = { pending },
                use = RestoreBackupUseCase(restore),
                computation = dispatcher,
            )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `with a saved destination restore leads and nothing is listed until it is touched`() =
        runTest(dispatcher) {
            val model = model()

            assertEquals(RescuePrimary.RESTORE, model.uiState.value.primary)
            assertEquals(0, destination.lists)
            assertFalse(model.uiState.value.copiesOpen)
        }

    @Test
    fun `without a saved destination finding a file leads and the list is never asked`() =
        runTest(dispatcher) {
            val model = model(kind = null)

            assertEquals(RescuePrimary.FIND_FILE, model.uiState.value.primary)
            assertFalse(model.uiState.value.canRestoreFromDestination)
            model.onAction(RescueAction.ShowCopies)
            advanceUntilIdle()
            assertFalse(model.uiState.value.copiesOpen)
            assertEquals(0, destination.lists)
        }

    @Test
    fun `without space to migrate retry leads`() = runTest(dispatcher) {
        val model = model(cause = RescueReason.NotEnoughSpace(30_000_000))

        assertEquals(RescuePrimary.RETRY, model.uiState.value.primary)
        assertTrue(model.uiState.value.canRestoreFromDestination)
    }

    @Test
    fun `a damaged original set aside by a restore is offered`() = runTest(dispatcher) {
        val model = model(damaged = true)

        assertTrue(model.uiState.value.damagedAvailable)
        model.onAction(RescueAction.ShareDamaged)
        assertTrue(model.uiState.value.sharing)
        model.onAction(RescueAction.ConfirmShareDamaged)
        assertFalse(model.uiState.value.sharing)
    }

    @Test
    fun `restore opens the sheet and lists the copies of the saved destination`() =
        runTest(dispatcher) {
            destination.copies = BackupCopyList.Listed(listOf(copy))
            val model = model()

            model.onAction(RescueAction.ShowCopies)
            assertTrue(model.uiState.value.copiesOpen)
            assertNull(model.uiState.value.copies)
            advanceUntilIdle()

            assertEquals(BackupCopyList.Listed(listOf(copy)), model.uiState.value.copies)
            assertEquals(1, destination.lists)
        }

    @Test
    fun `a listing that fails is a failure and retrying lists again`() = runTest(dispatcher) {
        destination.copies = BackupCopyList.Failed
        val model = model()

        model.onAction(RescueAction.ShowCopies)
        advanceUntilIdle()
        assertEquals(BackupCopyList.Failed, model.uiState.value.copies)

        destination.copies = BackupCopyList.Listed(listOf(copy))
        model.onAction(RescueAction.RetryCopies)
        advanceUntilIdle()
        assertEquals(BackupCopyList.Listed(listOf(copy)), model.uiState.value.copies)
        assertEquals(2, destination.lists)
    }

    @Test
    fun `a copy of the list closes the sheet and restores after confirming`() =
        runTest(dispatcher) {
            destination.copies = BackupCopyList.Listed(listOf(copy))
            val model = model()
            model.onAction(RescueAction.ShowCopies)
            advanceUntilIdle()

            model.onAction(RescueAction.Restore(BackupAction.OpenCopy(copy.key)))
            assertFalse(model.uiState.value.copiesOpen)
            assertEquals(RestoreUi.CopySheet(copy), model.uiState.value.restore)
            model.onAction(RescueAction.Restore(BackupAction.ConfirmCopy(copy.key)))
            model.onAction(RescueAction.Restore(BackupAction.StartRestore))
            advanceUntilIdle()

            assertEquals(listOf("copy:copia-1"), restore.started)
            assertEquals(RestoreUi.Ready, model.uiState.value.restore)
        }

    @Test
    fun `with webdav and no network a copy does not start and says so`() = runTest(dispatcher) {
        destination.copies = BackupCopyList.Listed(listOf(copy))
        val model = model()
        model.onAction(RescueAction.ShowCopies)
        advanceUntilIdle()
        destination.network = false

        model.onAction(RescueAction.Restore(BackupAction.ConfirmCopy(copy.key)))
        model.onAction(RescueAction.Restore(BackupAction.StartRestore))
        advanceUntilIdle()

        assertTrue(restore.started.isEmpty())
        val stopped = model.uiState.value.restore as RestoreUi.Stopped
        assertEquals(RestoreNotice.NO_INTERNET, stopped.notice)
    }

    @Test
    fun `a picked file is confirmed and restored without any destination`() = runTest(dispatcher) {
        val model = model(kind = null)

        model.onAction(RescueAction.Restore(BackupAction.FilePicked("content://pruebas/copia")))
        assertEquals(
            RestoreUi.ConfirmFile("content://pruebas/copia"),
            model.uiState.value.restore,
        )
        model.onAction(RescueAction.Restore(BackupAction.StartRestore))
        advanceUntilIdle()

        assertEquals(listOf("file:content://pruebas/copia"), restore.started)
        assertEquals(RestoreUi.Ready, model.uiState.value.restore)
    }

    @Test
    fun `reading the saved destination that fails leaves the rescue open without a list`() =
        runTest(dispatcher) {
            val model =
                RescueViewModel(
                    cause = RescueReason.DatabaseMissing,
                    destination = BackupDestinationReader(destination),
                    destinationKind = { error("link.json no se pudo leer") },
                    hasDamagedOriginal = { error("la carpeta no se pudo leer") },
                    hasPendingRestore = { false },
                    use = RestoreBackupUseCase(restore),
                    computation = dispatcher,
                )
            advanceUntilIdle()

            assertEquals(RescuePrimary.FIND_FILE, model.uiState.value.primary)
            assertFalse(model.uiState.value.damagedAvailable)
        }

    @Test
    fun `a failed migration with the copy before the update leads to going back to it`() =
        runTest(dispatcher) {
            val copyAt = Instant.parse("2026-11-01T10:00:00Z")
            restore.beforeUpdate = BeforeUpdateCopy(copyAt)
            val model = model(cause = RescueReason.MigrationFailed)

            assertEquals(RescuePrimary.BEFORE_UPDATE, model.uiState.value.primary)
            model.onAction(RescueAction.BeforeUpdate)
            assertEquals(RestoreUi.ConfirmBeforeUpdate(copyAt), model.uiState.value.restore)
            model.onAction(RescueAction.Restore(BackupAction.StartRestore))
            advanceUntilIdle()

            assertEquals(listOf("before-update"), restore.started)
            assertEquals(RestoreUi.Ready, model.uiState.value.restore)
        }

    @Test
    fun `a copy that this version cannot migrate says to install the newer one`() =
        runTest(dispatcher) {
            restore.beforeUpdate = BeforeUpdateCopy(Instant.parse("2026-11-01T10:00:00Z"))
            restore.next = RestoreProgress.Refused(RestoreProblem.NEEDS_UPDATE)
            val model = model(cause = RescueReason.MigrationFailed)

            model.onAction(RescueAction.BeforeUpdate)
            model.onAction(RescueAction.Restore(BackupAction.StartRestore))
            advanceUntilIdle()

            val stopped = model.uiState.value.restore as RestoreUi.Stopped
            assertEquals(RestoreNotice.NEEDS_UPDATE, stopped.notice)
        }

    @Test
    fun `without the copy before the update nothing is offered and a migration failure waits`() =
        runTest(dispatcher) {
            val model = model(cause = RescueReason.MigrationFailed)

            assertNull(model.uiState.value.beforeUpdate)
            assertEquals(RescuePrimary.RESTORE, model.uiState.value.primary)
            model.onAction(RescueAction.BeforeUpdate)
            assertEquals(RestoreUi.Idle, model.uiState.value.restore)
        }

    @Test
    fun `safe mode leads with retry opening and still offers the copies`() = runTest(dispatcher) {
        val model = model(cause = RescueReason.SafeMode)

        assertEquals(RescuePrimary.RETRY_OPEN, model.uiState.value.primary)
        assertFalse(model.uiState.value.onlyRetry)
        model.onAction(RescueAction.ShowCopies)
        assertTrue(model.uiState.value.copiesOpen)
    }

    @Test
    fun `safe mode with a readable unfinished journal offers nothing that restores`() =
        runTest(dispatcher) {
            val model = model(cause = RescueReason.SafeMode, pending = true)

            assertTrue(model.uiState.value.onlyRetry)
            model.onAction(RescueAction.ShowCopies)
            advanceUntilIdle()
            assertFalse(model.uiState.value.copiesOpen)
            assertEquals(0, destination.lists)
        }

    @Test
    fun `a journal outside safe mode does not limit the rescue`() = runTest(dispatcher) {
        val model = model(cause = RescueReason.OpenFailed, pending = true)

        assertFalse(model.uiState.value.onlyRetry)
        assertEquals(RescuePrimary.RESTORE, model.uiState.value.primary)
    }

    @Test
    fun `a journal that cannot be checked counts as pending in safe mode`() = runTest(dispatcher) {
        val model =
            RescueViewModel(
                cause = RescueReason.SafeMode,
                destination = BackupDestinationReader(destination),
                destinationKind = { BackupDestinationKind.WEBDAV },
                hasDamagedOriginal = { false },
                hasPendingRestore = { error("el diario no se pudo mirar") },
                use = RestoreBackupUseCase(restore),
                computation = dispatcher,
            )
        advanceUntilIdle()

        assertTrue(model.uiState.value.onlyRetry)
    }

    @Test
    fun `support and language open and close`() = runTest(dispatcher) {
        val model = model()

        model.onAction(RescueAction.ShowSupport)
        assertTrue(model.uiState.value.supportOpen)
        model.onAction(RescueAction.CloseSupport)
        assertFalse(model.uiState.value.supportOpen)
        model.onAction(RescueAction.ShowLanguage)
        assertTrue(model.uiState.value.languageOpen)
        model.onAction(RescueAction.CloseLanguage)
        assertFalse(model.uiState.value.languageOpen)
    }

    @Test
    fun `every cause has a stable code for support and none carries data`() {
        val causes =
            listOf(
                RescueReason.DatabaseMissing,
                RescueReason.NewerVersion,
                RescueReason.OpenFailed,
                RescueReason.MigrationFailed,
                RescueReason.PreMigrationCopyFailed,
                RescueReason.NotEnoughSpace(123_456_789),
                RescueReason.RestoreJournalUnreadable,
                RescueReason.RestoreLeftNoDatabase,
            )
        val codes = causes.map { it.code }

        assertEquals(causes.size, codes.toSet().size)
        assertTrue(codes.all { Regex("[A-Z_]+").matches(it) })
    }

    @Test
    fun `the copied support text is one line per field`() {
        val text =
            supportText(
                listOf(SupportLine("Versión", "1.0.0 (1)"), SupportLine("Último error", "X")),
            ) { label, value -> "$label: $value" }

        assertEquals("Versión: 1.0.0 (1)\nÚltimo error: X", text)
    }
}
