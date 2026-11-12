package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Restaurar desde la pantalla del respaldo: la hoja, la confirmación, el progreso, las preguntas,
 * cancelar sin tocar los datos, los avisos de cada rechazo y `Buscar un archivo`. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreFlowTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-07T15:00:00Z")

    private val copy = BackupCopySummary(
        key = "copia-1",
        createdAt = now - 1.hours,
        sizeBytes = 2_400_000,
        origin = CopyOrigin.OTHER_PHONE,
        slot = null,
        records = 812,
        newest = true,
        pinned = false,
        withDifferences = false,
        restorable = true,
    )
    private val fatal = copy.copy(key = "copia-2", newest = false, restorable = false)

    private class FakeDestination : BackupDestinationSource {
        var network = true
        var info: BackupDestinationInfo? = BackupDestinationInfo.WebDav(
            "negocio",
            "dav.example.com",
        )
        var copies: BackupCopyList = BackupCopyList.Listed(emptyList())

        override suspend fun describe() = info

        override suspend fun listCopies() = copies

        override suspend fun latestHandoff(): HandoffCopy? = null

        override fun hasNetwork() = network
    }

    private class FakeRestore : BackupRestorePort {
        val started = mutableListOf<String>()
        val answers = mutableListOf<RestoreAnswer>()
        var cancels = 0
        var next: RestoreProgress = RestoreProgress.ReadyToRestart(0)
        var afterAnswer: RestoreProgress = RestoreProgress.ReadyToRestart(0)
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun restoreCopy(copyKey: String): RestoreProgress {
            started += "copy:$copyKey"
            gate?.await()
            return next
        }

        override suspend fun restoreFile(uri: String): RestoreProgress {
            started += "file:$uri"
            gate?.await()
            return next
        }

        override suspend fun beforeUpdateCopy(): BeforeUpdateCopy? = null

        override suspend fun restoreBeforeUpdate(): RestoreProgress = RestoreProgress.Failed

        override suspend fun answer(answer: RestoreAnswer): RestoreProgress {
            answers += answer
            return afterAnswer
        }

        override suspend fun cancel(): Boolean {
            cancels += 1
            return true
        }
    }

    private val destination = FakeDestination()
    private val restore = FakeRestore()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): BackupViewModel {
        destination.copies = BackupCopyList.Listed(listOf(copy, fatal))
        val overview = BackupOverview(
            report = BackupStatusReport(BackupStatus.UP_TO_DATE, 0, savingSoon = false),
            destinationKind = BackupDestinationKind.WEBDAV,
            lastSuccessAt = now,
            failureCode = null,
            staleReason = null,
            meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
            nextMeteredUploadAt = null,
            lastFullReadAt = null,
            computedAt = now,
        )
        val model = BackupViewModel(
            overview = { flowOf(overview) },
            destination = BackupDestinationReader(destination),
            calendar = MutableStateFlow(BusinessCalendar.of("Etc/GMT+5", DayOfWeek.MONDAY)),
            requestBackup = {},
            recordCheck = { true },
            computation = dispatcher,
            restore = RestoreBackupUseCase(restore),
        )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `a copy opens its sheet, asks to confirm and only then starts`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(BackupAction.OpenCopy(copy.key))
        assertEquals(RestoreUi.CopySheet(copy), model.uiState.value.restore)
        model.onAction(BackupAction.ConfirmCopy(copy.key))
        assertEquals(RestoreUi.ConfirmCopy(copy), model.uiState.value.restore)
        assertTrue(restore.started.isEmpty())

        model.onAction(BackupAction.StartRestore)
        advanceUntilIdle()

        assertEquals(listOf("copy:copia-1"), restore.started)
        assertEquals(RestoreUi.Ready, model.uiState.value.restore)
    }

    @Test
    fun `a copy that cannot be restored has no sheet`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(BackupAction.OpenCopy(fatal.key))

        assertEquals(RestoreUi.Idle, model.uiState.value.restore)
    }

    @Test
    fun `without internet a webdav copy does not start`() = runTest(dispatcher) {
        destination.network = false
        val model = viewModel()
        model.onAction(BackupAction.ConfirmCopy(copy.key))

        model.onAction(BackupAction.StartRestore)
        advanceUntilIdle()

        assertTrue(restore.started.isEmpty())
        assertEquals(
            RestoreUi.Stopped(RestoreNotice.NO_INTERNET, RestoreOrigin.LIST),
            model.uiState.value.restore,
        )
    }

    @Test
    fun `a picked file warns first and a damaged one asks for another`() = runTest(dispatcher) {
        restore.next = RestoreProgress.Refused(RestoreProblem.DAMAGED)
        val model = viewModel()

        model.onAction(BackupAction.FilePicked("content://example/slot-a"))
        assertEquals(RestoreUi.ConfirmFile("content://example/slot-a"), model.uiState.value.restore)
        model.onAction(BackupAction.StartRestore)
        advanceUntilIdle()

        assertEquals(listOf("file:content://example/slot-a"), restore.started)
        assertEquals(
            RestoreUi.Stopped(RestoreNotice.TRY_ANOTHER_FILE, RestoreOrigin.FILE),
            model.uiState.value.restore,
        )
        model.onAction(BackupAction.CloseRestore)
        assertEquals(RestoreUi.Idle, model.uiState.value.restore)
    }

    @Test
    fun `a question waits for the answer and goes on`() = runTest(dispatcher) {
        restore.next = RestoreProgress.AskRepair(3)
        val model = viewModel()
        model.onAction(BackupAction.ConfirmCopy(copy.key))
        model.onAction(BackupAction.StartRestore)
        advanceUntilIdle()
        assertEquals(
            RestoreUi.Asking(RestoreProgress.AskRepair(3), RestoreOrigin.LIST),
            model.uiState.value.restore,
        )

        model.onAction(BackupAction.Answer(RestoreAnswer.Repair))
        advanceUntilIdle()

        assertEquals(listOf<RestoreAnswer>(RestoreAnswer.Repair), restore.answers)
        assertEquals(RestoreUi.Ready, model.uiState.value.restore)
    }

    @Test
    fun `cancelling a question or a running restore leaves the data as it was`() =
        runTest(dispatcher) {
            restore.next = RestoreProgress.AskEmptyCopy
            val model = viewModel()
            model.onAction(BackupAction.ConfirmCopy(copy.key))
            model.onAction(BackupAction.StartRestore)
            advanceUntilIdle()

            model.onAction(BackupAction.CancelRestore)
            advanceUntilIdle()

            assertEquals(1, restore.cancels)
            assertEquals(
                RestoreUi.Stopped(RestoreNotice.CANCELLED, RestoreOrigin.LIST),
                model.uiState.value.restore,
            )

            restore.gate = CompletableDeferred()
            model.onAction(BackupAction.FilePicked("content://example/file"))
            model.onAction(BackupAction.StartRestore)
            advanceUntilIdle()
            assertEquals(RestoreUi.Working(RestoreOrigin.FILE), model.uiState.value.restore)

            model.onAction(BackupAction.CancelRestore)
            advanceUntilIdle()

            assertEquals(
                RestoreUi.Stopped(RestoreNotice.CANCELLED, RestoreOrigin.FILE),
                model.uiState.value.restore,
            )
        }

    @Test
    fun `each stop has its notice`() {
        val list = RestoreOrigin.LIST
        val cases = mapOf(
            RestoreProgress.Refused(RestoreProblem.NOT_A_BACKUP) to RestoreNotice.NOT_A_BACKUP,
            RestoreProgress.Refused(RestoreProblem.DAMAGED) to RestoreNotice.DAMAGED,
            RestoreProgress.Refused(RestoreProblem.MARKED_FATAL) to RestoreNotice.DAMAGED,
            RestoreProgress.Refused(RestoreProblem.NEWER_VERSION) to RestoreNotice.NEWER_VERSION,
            RestoreProgress.Refused(RestoreProblem.TOO_LARGE) to RestoreNotice.TOO_LARGE,
            RestoreProgress.Refused(RestoreProblem.ENCRYPTED) to RestoreNotice.OTHER,
            RestoreProgress.Refused(RestoreProblem.NOT_AVAILABLE) to RestoreNotice.OTHER,
            RestoreProgress.NotEnoughSpace(5_000_000) to RestoreNotice.NO_SPACE,
            RestoreProgress.Failed to RestoreNotice.OTHER,
            RestoreProgress.Busy to RestoreNotice.OTHER,
        )
        for ((progress, notice) in cases) {
            assertEquals(
                "$progress",
                notice,
                noticeOf(progress, list),
            )
        }
        assertEquals(null, noticeOf(RestoreProgress.AskEmptyCopy, list))
        assertEquals(null, noticeOf(RestoreProgress.ReadyToRestart(0), list))
    }

    @Test
    fun `not enough space keeps how much is missing`() = runTest(dispatcher) {
        restore.next = RestoreProgress.NotEnoughSpace(12_345_678)
        val model = viewModel()
        model.onAction(BackupAction.FilePicked("content://example/file"))
        model.onAction(BackupAction.StartRestore)
        advanceUntilIdle()

        assertEquals(
            RestoreUi.Stopped(RestoreNotice.NO_SPACE, RestoreOrigin.FILE, 12_345_678),
            model.uiState.value.restore,
        )
    }
}
