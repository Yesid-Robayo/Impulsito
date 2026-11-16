package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.backup.UndoRestoreCopy
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Volver a como estaba antes de restaurar desde la pantalla del respaldo: la acción aparece solo
 * con la copia local de seguridad vigente, pide confirmar con su día, y al confirmar vuelve a esa
 * misma copia con la misma pantalla de progreso. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupUndoRestoreTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-26T21:38:00Z")
    private val safetyCopy = UndoRestoreCopy(now - 2.days)

    private class FakeDestination : BackupDestinationSource {
        override suspend fun describe(): BackupDestinationInfo? =
            BackupDestinationInfo.WebDav("negocio", "dav.example.com")

        override suspend fun listCopies(): BackupCopyList = BackupCopyList.Listed(emptyList())

        override suspend fun latestHandoff(): HandoffCopy? = null

        override fun hasNetwork() = true
    }

    /** Ofrece [offered], como la capa de datos solo con una copia de menos de 30 días. */
    private class FakeRestore(var offered: UndoRestoreCopy?) : BackupRestorePort {
        val started = mutableListOf<String>()
        var next: RestoreProgress = RestoreProgress.ReadyToRestart(0)

        override suspend fun restoreCopy(copyKey: String, code: String?): RestoreProgress =
            error("Esta prueba no restaura copias de la lista")

        override suspend fun restoreFile(uri: String, code: String?): RestoreProgress =
            error("Esta prueba no restaura archivos")

        override suspend fun beforeUpdateCopy(): BeforeUpdateCopy? = null

        override suspend fun restoreBeforeUpdate(): RestoreProgress =
            error("Esta prueba no vuelve a antes de la actualización")

        override suspend fun undoRestoreCopy(): UndoRestoreCopy? = offered

        override suspend fun restoreUndo(copy: UndoRestoreCopy): RestoreProgress {
            started += "undo:${copy.createdAt}"
            return next
        }

        override suspend fun answer(answer: RestoreAnswer): RestoreProgress = next

        override suspend fun cancel(): Boolean = true
    }

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(restore: FakeRestore): BackupViewModel {
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
            destination = BackupDestinationReader(FakeDestination()),
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
    fun `without a recent safety copy the action is not offered and does nothing`() =
        runTest(dispatcher) {
            val restore = FakeRestore(offered = null)
            val model = viewModel(restore)

            assertNull(model.uiState.value.undoRestore)
            model.onAction(BackupAction.UndoRestore)
            model.onAction(BackupAction.StartRestore)
            advanceUntilIdle()

            assertEquals(RestoreUi.Idle, model.uiState.value.restore)
            assertTrue(restore.started.isEmpty())
        }

    @Test
    fun `with a recent safety copy the action is offered with its day`() = runTest(dispatcher) {
        val model = viewModel(FakeRestore(offered = safetyCopy))

        assertEquals(safetyCopy, model.uiState.value.undoRestore)
    }

    @Test
    fun `the action asks to confirm first and then goes back to that same copy`() =
        runTest(dispatcher) {
            val restore = FakeRestore(offered = safetyCopy)
            val model = viewModel(restore)

            model.onAction(BackupAction.UndoRestore)
            assertEquals(RestoreUi.ConfirmUndoRestore(safetyCopy), model.uiState.value.restore)
            assertTrue("Nada empieza sin confirmar", restore.started.isEmpty())

            model.onAction(BackupAction.StartRestore)
            assertEquals(
                RestoreUi.Working(RestoreOrigin.UNDO_RESTORE),
                model.uiState.value.restore,
            )
            advanceUntilIdle()

            assertEquals(listOf("undo:2026-11-24T21:38:00Z"), restore.started)
            assertEquals(RestoreUi.Ready, model.uiState.value.restore)
        }

    @Test
    fun `closing the confirmation leaves everything as it was`() = runTest(dispatcher) {
        val restore = FakeRestore(offered = safetyCopy)
        val model = viewModel(restore)

        model.onAction(BackupAction.UndoRestore)
        model.onAction(BackupAction.CloseRestore)
        advanceUntilIdle()

        assertEquals(RestoreUi.Idle, model.uiState.value.restore)
        assertTrue(restore.started.isEmpty())
        assertEquals("La acción sigue ofrecida", safetyCopy, model.uiState.value.undoRestore)
    }

    @Test
    fun `a copy that is no longer there stops with a notice and changes nothing`() =
        runTest(dispatcher) {
            val restore = FakeRestore(offered = safetyCopy)
            restore.next = RestoreProgress.Refused(RestoreProblem.NOT_AVAILABLE)
            val model = viewModel(restore)

            model.onAction(BackupAction.UndoRestore)
            model.onAction(BackupAction.StartRestore)
            advanceUntilIdle()

            assertEquals(
                RestoreUi.Stopped(RestoreNotice.OTHER, RestoreOrigin.UNDO_RESTORE),
                model.uiState.value.restore,
            )
        }

    @Test
    fun `a damaged safety copy says so like any other copy`() {
        assertEquals(
            RestoreNotice.DAMAGED,
            noticeOf(
                RestoreProgress.Refused(RestoreProblem.DAMAGED),
                RestoreOrigin.UNDO_RESTORE,
            ),
        )
    }
}
