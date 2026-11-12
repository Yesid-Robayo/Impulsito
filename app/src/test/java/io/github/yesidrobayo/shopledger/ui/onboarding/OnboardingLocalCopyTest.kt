package io.github.yesidrobayo.shopledger.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.ConnectFolderResult
import io.github.yesidrobayo.shopledger.domain.model.backup.FoundLocalCopies
import io.github.yesidrobayo.shopledger.domain.model.backup.FoundLocalCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import io.github.yesidrobayo.shopledger.domain.port.SecondDestinationPort
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.SecondDestinationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.CompleteOnboardingResult
import io.github.yesidrobayo.shopledger.domain.usecase.onboarding.OnboardingResume
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi
import kotlin.time.Duration.Companion.hours
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
import org.junit.Before
import org.junit.Test

/**
 * `Buscar mi copia en este teléfono`: lee la carpeta sin guardar nada, propone la más reciente,
 * antes de restaurarla deja la carpeta conectada con sus copias adoptadas, restaura por el camino de
 * un archivo elegido, y si esa no sirve, propone la anterior. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingLocalCopyTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-12T15:00:00Z")
    private val newest = FoundLocalCopy("content://arbol/nueva", now, 9, encrypted = false)
    private val older =
        FoundLocalCopy("content://arbol/vieja", now - 24.hours, 8, encrypted = false)

    private var found: FoundLocalCopies = FoundLocalCopies.Found(listOf(newest, older))
    private val finds = mutableListOf<Pair<String, String>>()
    private val adopted = mutableListOf<String>()
    private val restored = mutableListOf<String>()
    private var restoreResult: RestoreProgress = RestoreProgress.ReadyToRestart(0)

    private val second =
        object : SecondDestinationPort {
            override suspend fun view() = SecondDestinationView.None

            override suspend fun connect(treeUri: String, appName: String) =
                ConnectFolderResult.CONNECTED

            override suspend fun setFollowMainBackup(follow: Boolean) = Unit

            override suspend fun remove() = Unit

            override suspend fun find(treeUri: String, appName: String): FoundLocalCopies {
                finds += treeUri to appName
                return found
            }

            override suspend fun reconnectAndAdopt(
                treeUri: String,
                appName: String,
            ): ConnectFolderResult {
                adopted += treeUri
                return ConnectFolderResult.CONNECTED
            }
        }

    private val restore =
        object : BackupRestorePort {
            override suspend fun restoreCopy(copyKey: String) = RestoreProgress.Failed

            override suspend fun restoreFile(uri: String): RestoreProgress {
                restored += uri
                return restoreResult
            }

            override suspend fun beforeUpdateCopy(): BeforeUpdateCopy? = null

            override suspend fun restoreBeforeUpdate() = RestoreProgress.Failed

            override suspend fun answer(answer: RestoreAnswer) = RestoreProgress.ReadyToRestart(0)

            override suspend fun cancel() = true
        }

    private val destination =
        object : BackupDestinationSource {
            override suspend fun describe(): BackupDestinationInfo? = null

            override suspend fun listCopies() = BackupCopyList.NotConnected

            override suspend fun latestHandoff(): HandoffCopy? = null

            override fun hasNetwork() = false
        }

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): OnboardingViewModel {
        val model =
            OnboardingViewModel(
                resume = { OnboardingResume.WELCOME },
                complete = { CompleteOnboardingResult.Completed },
                enableLock = { ChangePreferenceResult.Saved },
                destination = BackupDestinationReader(destination),
                slotsLabel = { null },
                calendar = MutableStateFlow(BusinessCalendar.of("Etc/GMT+5", DayOfWeek.MONDAY)),
                restore = RestoreBackupUseCase(restore),
                savedState = SavedStateHandle(),
                computation = dispatcher,
                localCopies = SecondDestinationUseCase(second),
                appName = "Tienda",
            )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `the folder is read and the newest copy is proposed, saving nothing`() =
        runTest(dispatcher) {
            val model = model()

            model.onAction(OnboardingAction.LocalFolderPicked("content://arbol"))
            advanceUntilIdle()

            assertEquals(listOf("content://arbol" to "Tienda"), finds)
            assertEquals(newest, (model.uiState.value.localCopy as LocalCopySearch.Found).proposed)
            assertEquals(emptyList<String>(), adopted)
        }

    @Test
    fun `restoring reconnects the folder first and goes the way of a picked file`() =
        runTest(dispatcher) {
            val model = model()
            model.onAction(OnboardingAction.LocalFolderPicked("content://arbol"))
            advanceUntilIdle()

            model.onAction(OnboardingAction.RestoreLocalCopy)
            advanceUntilIdle()
            assertEquals(listOf("content://arbol"), adopted)
            assertEquals(RestoreUi.ConfirmFile(newest.documentUri), model.uiState.value.restore)

            model.onAction(OnboardingAction.Restore(BackupAction.StartRestore))
            advanceUntilIdle()
            assertEquals(listOf(newest.documentUri), restored)
            assertEquals(RestoreUi.Ready, model.uiState.value.restore)
        }

    @Test
    fun `a copy that does not pass proposes the previous one of that folder`() =
        runTest(dispatcher) {
            restoreResult = RestoreProgress.Refused(RestoreProblem.DAMAGED)
            val model = model()
            model.onAction(OnboardingAction.LocalFolderPicked("content://arbol"))
            advanceUntilIdle()

            model.onAction(OnboardingAction.RestoreLocalCopy)
            advanceUntilIdle()
            model.onAction(OnboardingAction.Restore(BackupAction.StartRestore))
            advanceUntilIdle()

            assertEquals(older, (model.uiState.value.localCopy as LocalCopySearch.Found).proposed)
        }

    @Test
    fun `an empty folder, another storage and a failure are said apart`() = runTest(dispatcher) {
        val model = model()
        for ((outcome, shown) in listOf(
            FoundLocalCopies.NoCopies to LocalCopySearch.NoCopies,
            FoundLocalCopies.NotLocal to LocalCopySearch.NotLocal,
            FoundLocalCopies.Failed to LocalCopySearch.Failed,
        )) {
            found = outcome
            model.onAction(OnboardingAction.LocalFolderPicked("content://arbol"))
            advanceUntilIdle()
            assertEquals(shown, model.uiState.value.localCopy)
        }
    }
}
