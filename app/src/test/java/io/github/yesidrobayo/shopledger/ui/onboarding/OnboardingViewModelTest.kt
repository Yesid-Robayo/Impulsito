package io.github.yesidrobayo.shopledger.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.CompleteOnboardingResult
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.OnboardingResume
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import io.github.yesidrobayo.shopledger.lock.AuthOutcome
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El primer uso: los pasos en orden y hacia atrás, retomar donde quedó, `Ahora no` sin red, buscar
 * las copias del servicio recién conectado, ofrecer restaurar o empezar de cero, y terminar. Y el
 * bloqueo preseleccionado: marcado si el teléfono tiene bloqueo de pantalla, se enciende solo tras la
 * verificación, se puede rechazar, y sin bloqueo de pantalla nadie queda fuera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-07T15:00:00Z")

    private val copy = BackupCopySummary(
        key = "copia-1",
        createdAt = now,
        sizeBytes = 2_400_000,
        origin = CopyOrigin.OTHER_PHONE,
        slot = null,
        records = 812,
        newest = true,
        pinned = false,
        withDifferences = false,
        restorable = true,
    )

    private class FakeDestination : BackupDestinationSource {
        var info: BackupDestinationInfo? = null
        var copies: BackupCopyList = BackupCopyList.Listed(emptyList())
        var network = true
        var lists = 0

        override suspend fun describe() = info

        override suspend fun listCopies(): BackupCopyList {
            lists += 1
            return copies
        }

        override suspend fun latestHandoff(): HandoffCopy? = null

        override fun hasNetwork() = network
    }

    private class FakeRestore : BackupRestorePort {
        val started = mutableListOf<String>()

        override suspend fun restoreCopy(copyKey: String, code: String?): RestoreProgress {
            started += copyKey
            return RestoreProgress.ReadyToRestart(0)
        }

        override suspend fun restoreFile(uri: String, code: String?): RestoreProgress {
            started += uri
            return RestoreProgress.ReadyToRestart(0)
        }

        override suspend fun beforeUpdateCopy(): BeforeUpdateCopy? = null

        override suspend fun restoreBeforeUpdate(): RestoreProgress = RestoreProgress.Failed

        override suspend fun answer(answer: RestoreAnswer) = RestoreProgress.ReadyToRestart(0)

        override suspend fun cancel() = true
    }

    private val destination = FakeDestination()
    private val restore = FakeRestore()
    private var resume = OnboardingResume.LANGUAGE
    private var completeResult: CompleteOnboardingResult = CompleteOnboardingResult.Completed
    private var completes = 0
    private var lockResult: ChangePreferenceResult = ChangePreferenceResult.Saved
    private var locks = 0
    private var demoReady: () -> Boolean = { true }
    private var demoStarts = 0
    private val saved = SavedStateHandle()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): OnboardingViewModel {
        val model = OnboardingViewModel(
            resume = { resume },
            complete = {
                completes += 1
                completeResult
            },
            enableLock = {
                locks += 1
                lockResult
            },
            destination = BackupDestinationReader(destination),
            slotsLabel = { "Drive" },
            calendar = MutableStateFlow(BusinessCalendar.of("Etc/GMT+5", DayOfWeek.MONDAY)),
            restore = RestoreBackupUseCase(restore),
            savedState = saved,
            computation = dispatcher,
            enterDemo = {
                demoStarts += 1
                demoReady()
            },
        )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `the steps go forward and back without losing anything`() = runTest(dispatcher) {
        val model = model()
        assertEquals(OnboardingStepId.LANGUAGE, model.uiState.value.step)
        assertEquals("Drive", model.uiState.value.slotsLabel)
        assertEquals("Etc/GMT+5", model.uiState.value.zone?.id)

        model.onAction(OnboardingAction.LanguageDone)
        assertEquals(OnboardingStepId.REGION, model.uiState.value.step)
        model.onAction(OnboardingAction.Back)
        assertEquals(OnboardingStepId.LANGUAGE, model.uiState.value.step)
        model.onAction(OnboardingAction.Back)
        assertEquals(OnboardingStepId.LANGUAGE, model.uiState.value.step)

        model.onAction(OnboardingAction.LanguageDone)
        model.onAction(OnboardingAction.RegionDone)
        assertEquals(OnboardingStepId.WELCOME, model.uiState.value.step)
        model.onAction(OnboardingAction.NotNow)
        assertEquals(OnboardingStepId.PROTECTION, model.uiState.value.step)
        model.onAction(OnboardingAction.Back)
        assertEquals(OnboardingStepId.WELCOME, model.uiState.value.step)
    }

    @Test
    fun `a confirmed region resumes at the welcome and the step survives`() = runTest(dispatcher) {
        resume = OnboardingResume.WELCOME
        val first = model()
        assertEquals(OnboardingStepId.WELCOME, first.uiState.value.step)
        first.onAction(OnboardingAction.NotNow)

        resume = OnboardingResume.LANGUAGE
        val again = model()
        assertEquals(OnboardingStepId.PROTECTION, again.uiState.value.step)
    }

    @Test
    fun `an already completed onboarding closes at once`() = runTest(dispatcher) {
        resume = OnboardingResume.DONE

        assertTrue(model().uiState.value.done)
    }

    @Test
    fun `not now works without internet and says it`() = runTest(dispatcher) {
        resume = OnboardingResume.WELCOME
        destination.network = false
        val model = model()

        assertTrue(model.uiState.value.offline)
        model.onAction(OnboardingAction.NotNow)
        assertEquals(OnboardingStepId.PROTECTION, model.uiState.value.step)
    }

    @Test
    fun `a new service with copies offers to restore them`() = runTest(dispatcher) {
        resume = OnboardingResume.WELCOME
        val model = model()
        destination.info = BackupDestinationInfo.WebDav("copias", "dav.example.com")
        destination.copies = BackupCopyList.Listed(listOf(copy))

        model.onAction(OnboardingAction.Resumed)
        advanceUntilIdle()

        assertEquals(FoundCopies.Found(listOf(copy)), model.uiState.value.found)
        model.onAction(OnboardingAction.Restore(BackupAction.ConfirmCopy(copy.key)))
        assertEquals(RestoreUi.ConfirmCopy(copy), model.uiState.value.restore)
        model.onAction(OnboardingAction.Restore(BackupAction.StartRestore))
        advanceUntilIdle()
        assertEquals(listOf(copy.key), restore.started)
        assertEquals(RestoreUi.Ready, model.uiState.value.restore)

        // Volver otra vez no busca de nuevo.
        model.onAction(OnboardingAction.Resumed)
        advanceUntilIdle()
        assertEquals(1, destination.lists)
    }

    @Test
    fun `starting over is confirmed and goes on`() = runTest(dispatcher) {
        resume = OnboardingResume.WELCOME
        val model = model()
        destination.info = BackupDestinationInfo.WebDav("copias", "dav.example.com")
        destination.copies = BackupCopyList.Listed(listOf(copy))
        model.onAction(OnboardingAction.Resumed)
        advanceUntilIdle()

        model.onAction(OnboardingAction.AskStartOver)
        assertTrue(model.uiState.value.startOver)
        model.onAction(OnboardingAction.ConfirmStartOver)

        assertFalse(model.uiState.value.startOver)
        assertNull(model.uiState.value.found)
        assertEquals(OnboardingStepId.PROTECTION, model.uiState.value.step)
    }

    @Test
    fun `a failed list is never taken as empty`() = runTest(dispatcher) {
        resume = OnboardingResume.WELCOME
        val model = model()
        destination.info = BackupDestinationInfo.WebDav("copias", "dav.example.com")
        destination.copies = BackupCopyList.Failed

        model.onAction(OnboardingAction.Resumed)
        advanceUntilIdle()
        assertEquals(FoundCopies.Failed, model.uiState.value.found)
        assertEquals(OnboardingStepId.WELCOME, model.uiState.value.step)

        destination.copies = BackupCopyList.Listed(emptyList())
        model.onAction(OnboardingAction.RetrySearch)
        advanceUntilIdle()
        assertNull(model.uiState.value.found)
        assertEquals(OnboardingStepId.PROTECTION, model.uiState.value.step)
    }

    @Test
    fun `the cloud on the phone has no list and goes on`() = runTest(dispatcher) {
        resume = OnboardingResume.WELCOME
        val model = model()
        destination.info = BackupDestinationInfo.Slots("Drive")

        model.onAction(OnboardingAction.Resumed)
        advanceUntilIdle()

        assertEquals(0, destination.lists)
        assertEquals(OnboardingStepId.PROTECTION, model.uiState.value.step)
        assertTrue(model.uiState.value.connected)
    }

    @Test
    fun `finishing saves once and a failure says so`() = runTest(dispatcher) {
        completeResult = CompleteOnboardingResult.Failed(StorageFailure.DISK_FULL)
        val model = model()

        model.onAction(OnboardingAction.Finish)
        advanceUntilIdle()
        assertTrue(model.uiState.value.saveFailed)
        assertFalse(model.uiState.value.done)

        completeResult = CompleteOnboardingResult.Completed
        model.onAction(OnboardingAction.Finish)
        advanceUntilIdle()
        assertTrue(model.uiState.value.done)
        assertEquals(2, completes)
    }

    @Test
    fun `a picked file asks first`() = runTest(dispatcher) {
        resume = OnboardingResume.WELCOME
        val model = model()
        model.onAction(OnboardingAction.OpenBring)
        assertTrue(model.uiState.value.bringSheet)
        model.onAction(OnboardingAction.FindFile)
        assertFalse(model.uiState.value.bringSheet)

        model.onAction(OnboardingAction.Restore(BackupAction.FilePicked("content://example/a")))

        assertEquals(RestoreUi.ConfirmFile("content://example/a"), model.uiState.value.restore)
    }

    @Test
    fun `with a screen lock the lock comes chosen and is turned on after the check`() =
        runTest(dispatcher) {
            val model = model()
            model.onAction(OnboardingAction.DeviceLock(secure = true))
            assertTrue(model.uiState.value.lockOffered)
            assertTrue(model.uiState.value.lockChosen)

            model.onAction(OnboardingAction.Finish)
            advanceUntilIdle()
            // Antes de la verificación no se enciende ni termina nada.
            assertTrue(model.uiState.value.lockCheck)
            assertEquals(0, locks)
            assertEquals(0, completes)

            model.onAction(OnboardingAction.LockChecked(AuthOutcome.Success))
            advanceUntilIdle()
            assertFalse(model.uiState.value.lockCheck)
            assertEquals(1, locks)
            assertEquals(1, completes)
            assertTrue(model.uiState.value.done)
        }

    @Test
    fun `rejecting the lock finishes without it`() = runTest(dispatcher) {
        val model = model()
        model.onAction(OnboardingAction.DeviceLock(secure = true))

        model.onAction(OnboardingAction.LockChoice(enabled = false))
        model.onAction(OnboardingAction.Finish)
        advanceUntilIdle()

        assertFalse(model.uiState.value.lockCheck)
        assertEquals(0, locks)
        assertTrue(model.uiState.value.done)
    }

    @Test
    fun `without a screen lock it is not offered and finishing never locks anyone out`() =
        runTest(dispatcher) {
            val model = model()
            model.onAction(OnboardingAction.DeviceLock(secure = false))
            assertFalse(model.uiState.value.lockOffered)

            model.onAction(OnboardingAction.Finish)
            advanceUntilIdle()

            assertEquals(0, locks)
            assertTrue(model.uiState.value.done)
        }

    @Test
    fun `a cancelled or failed check stays on the step`() = runTest(dispatcher) {
        val model = model()
        model.onAction(OnboardingAction.DeviceLock(secure = true))

        model.onAction(OnboardingAction.Finish)
        model.onAction(OnboardingAction.LockChecked(AuthOutcome.Canceled))
        model.onAction(OnboardingAction.Finish)
        model.onAction(OnboardingAction.LockChecked(AuthOutcome.Failed("Demasiados intentos")))
        advanceUntilIdle()

        assertFalse(model.uiState.value.done)
        assertFalse(model.uiState.value.lockCheck)
        assertEquals(0, locks)
        assertEquals(0, completes)
    }

    @Test
    fun `a phone that lost its screen lock stops offering it and goes on`() = runTest(dispatcher) {
        val model = model()
        model.onAction(OnboardingAction.DeviceLock(secure = true))

        model.onAction(OnboardingAction.Finish)
        model.onAction(OnboardingAction.LockChecked(AuthOutcome.NoDeviceCredential))
        advanceUntilIdle()
        assertFalse(model.uiState.value.lockOffered)
        assertFalse(model.uiState.value.done)

        model.onAction(OnboardingAction.Finish)
        advanceUntilIdle()
        assertEquals(0, locks)
        assertTrue(model.uiState.value.done)
    }

    @Test
    fun `if the lock cannot be saved the onboarding does not finish`() = runTest(dispatcher) {
        lockResult = ChangePreferenceResult.Failed(StorageFailure.DISK_FULL)
        val model = model()
        model.onAction(OnboardingAction.DeviceLock(secure = true))

        model.onAction(OnboardingAction.Finish)
        model.onAction(OnboardingAction.LockChecked(AuthOutcome.Success))
        advanceUntilIdle()

        assertTrue(model.uiState.value.saveFailed)
        assertFalse(model.uiState.value.done)
        assertEquals(0, completes)
    }

    @Test
    fun `the choice survives the process`() = runTest(dispatcher) {
        model().onAction(OnboardingAction.LockChoice(enabled = false))

        assertFalse(model().uiState.value.lockChosen)
    }

    @Test
    fun `sample data is prepared once and then the app restarts into it`() = runTest(dispatcher) {
        resume = OnboardingResume.WELCOME
        val model = model()

        model.onAction(OnboardingAction.SeeSampleData)
        assertEquals(DemoStartUi.WORKING, model.uiState.value.demo)
        // Un segundo toque mientras se prepara no la prepara otra vez.
        model.onAction(OnboardingAction.SeeSampleData)
        advanceUntilIdle()

        assertEquals(1, demoStarts)
        assertEquals(DemoStartUi.READY, model.uiState.value.demo)
        // El primer uso del negocio sigue sin terminar: la demostración no lo toca.
        assertEquals(OnboardingStepId.WELCOME, model.uiState.value.step)
        assertEquals(0, completes)
        assertFalse(model.uiState.value.done)
        model.onAction(OnboardingAction.SeeSampleData)
        advanceUntilIdle()
        assertEquals(1, demoStarts)
    }

    @Test
    fun `sample data that cannot be prepared can be asked again`() = runTest(dispatcher) {
        resume = OnboardingResume.WELCOME
        demoReady = { false }
        val model = model()

        model.onAction(OnboardingAction.SeeSampleData)
        advanceUntilIdle()
        assertEquals(DemoStartUi.FAILED, model.uiState.value.demo)
        assertEquals(OnboardingStepId.WELCOME, model.uiState.value.step)

        demoReady = { error("La base de ejemplo falló") }
        model.onAction(OnboardingAction.SeeSampleData)
        advanceUntilIdle()
        assertEquals(DemoStartUi.FAILED, model.uiState.value.demo)

        demoReady = { true }
        model.onAction(OnboardingAction.SeeSampleData)
        advanceUntilIdle()
        assertEquals(DemoStartUi.READY, model.uiState.value.demo)
        assertEquals(3, demoStarts)
        assertEquals(0, completes)
    }
}
