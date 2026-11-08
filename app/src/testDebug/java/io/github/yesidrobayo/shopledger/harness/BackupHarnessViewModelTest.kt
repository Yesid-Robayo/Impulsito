package io.github.yesidrobayo.shopledger.harness

import io.github.yesidrobayo.shopledger.data.backup.BackupNowRequest
import io.github.yesidrobayo.shopledger.data.harness.HarnessRestore
import io.github.yesidrobayo.shopledger.data.harness.HarnessResult
import io.github.yesidrobayo.shopledger.data.harness.HarnessState
import io.github.yesidrobayo.shopledger.data.harness.RestoreAnswer
import io.github.yesidrobayo.shopledger.data.harness.RestoreQuestion
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * El estado del arnés del respaldo con un doble de la capa de datos: una acción a la vez, la
 * contraseña que solo pasa por la llamada, y las preguntas de una restauración hasta reiniciar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupHarnessViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val port = FakePort()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScopeHolder.ready(): HarnessUiState.Ready =
        viewModel.uiState.value as HarnessUiState.Ready

    /** El modelo, con el estado de la pantalla recogido mientras dura la prueba. */
    private class TestScopeHolder(val viewModel: BackupHarnessViewModel)

    private fun runHarness(block: suspend TestScopeHolder.() -> Unit) = runTest(dispatcher) {
        val holder = TestScopeHolder(BackupHarnessViewModel(port))
        val collecting = launch { holder.viewModel.uiState.collect {} }
        try {
            holder.block()
        } finally {
            collecting.cancel()
        }
    }

    @Test
    fun aDatabaseThatDoesNotOpenShowsThatTheHarnessTouchesNothing() = runHarness {
        port.reports.value = null
        assertEquals(HarnessUiState.Unavailable, viewModel.uiState.value)
    }

    @Test
    fun theStatusTheSavedCountersAndThePolicyAreShown() = runHarness {
        val ready = ready()
        assertEquals(REPORT, ready.report)
        assertEquals(SAVED, ready.saved)
        assertEquals(PolicyConsentStatus.NEVER_ACCEPTED, ready.policy)
        assertNull(ready.working)
        assertNull(ready.outcome)
        assertEquals(RestorePhase.Idle, ready.restore)

        viewModel.acceptPolicy()

        assertEquals(PolicyConsentStatus.ACCEPTED, ready().policy)
        assertEquals(HarnessOutcome(HarnessAction.POLICY, true, "SAVED"), ready().outcome)
    }

    @Test
    fun connectingTrimsTheAddressAndPassesThePasswordAsWritten() = runHarness {
        viewModel.connectWebDav("  https://dav.example.com/  ", " persona ", " clave ")

        assertEquals(Triple("https://dav.example.com/", "persona", " clave "), port.connected)
        assertEquals(
            HarnessOutcome(HarnessAction.WEBDAV, false, "CredentialRejected"),
            ready().outcome,
        )
    }

    @Test
    fun onlyOneActionRunsAtATime() = runHarness {
        val release = CompletableDeferred<Unit>()
        port.changesGate = release
        viewModel.recordInventedChanges()
        assertEquals(HarnessAction.CHANGES, ready().working)

        // Mientras corre, otra acción no empieza.
        viewModel.backupNow()
        assertEquals(0, port.backupNowCalls)

        release.complete(Unit)
        assertNull(ready().working)
        assertEquals(
            HarnessOutcome(
                HarnessAction.CHANGES,
                true,
                "RECORDED_${BackupHarnessViewModel.CHANGES_PER_TAP}",
            ),
            ready().outcome,
        )
        viewModel.backupNow()
        assertEquals(1, port.backupNowCalls)
        assertEquals(HarnessOutcome(HarnessAction.BACKUP_NOW, true, "QUEUED"), ready().outcome)
    }

    @Test
    fun anUnexpectedFailureBecomesItsClassName() = runHarness {
        port.slotsFailure = IllegalStateException("texto que no se muestra")

        viewModel.prepareSlots(listOf("a", "b", "c"))

        assertEquals(
            HarnessOutcome(HarnessAction.SLOTS, false, "IllegalStateException"),
            ready().outcome,
        )
        assertNull(ready().working)
    }

    @Test
    fun aRestoreAsksUntilItIsReadyToRestart() = runHarness {
        port.restoreStages += HarnessRestore.Question(RestoreQuestion.EMPTY_COPY, 0)
        viewModel.restoreFromFile("content://example.documents/document/copia")
        assertEquals(RestorePhase.Asking(RestoreQuestion.EMPTY_COPY, 0), ready().restore)

        port.restoreStages += HarnessRestore.ReadyToRestart(2)
        viewModel.answer(RestoreAnswer.ReplaceWithEmptyCopy)
        assertEquals(RestoreAnswer.ReplaceWithEmptyCopy, port.answers.single())
        assertEquals(RestorePhase.ReadyToRestart(2), ready().restore)
    }

    @Test
    fun aStoppedOrCancelledRestoreGoesBackToTheStart() = runHarness {
        port.restoreStages += HarnessRestore.Question(RestoreQuestion.REPAIR, 3)
        viewModel.restoreFromFile("content://example.documents/document/copia")
        viewModel.cancelRestore()
        assertEquals(RestorePhase.Idle, ready().restore)
        assertEquals(HarnessOutcome(HarnessAction.RESTORE, true, "CANCELLED"), ready().outcome)

        port.restoreStages += HarnessRestore.Stopped("REFUSED NOT_A_DATABASE")
        viewModel.restoreFromFile("content://example.documents/document/copia")
        assertEquals(RestorePhase.Idle, ready().restore)
        assertEquals(
            HarnessOutcome(HarnessAction.RESTORE, false, "REFUSED NOT_A_DATABASE"),
            ready().outcome,
        )
    }

    private class FakePort : HarnessPort {
        val reports = MutableStateFlow<BackupStatusReport?>(REPORT)
        var policy = PolicyConsentStatus.NEVER_ACCEPTED
        var connected: Triple<String, String, String>? = null
        var changesGate: CompletableDeferred<Unit>? = null
        var slotsFailure: Exception? = null
        var backupNowCalls = 0
        val restoreStages = ArrayDeque<HarnessRestore>()
        val answers = ArrayList<RestoreAnswer>()

        override fun statusReports(): Flow<BackupStatusReport?> = reports

        override fun savedState(): Flow<HarnessState?> = flowOf(SAVED)

        override suspend fun policyStatus(): PolicyConsentStatus = policy

        override suspend fun acceptPolicy(): HarnessResult {
            policy = PolicyConsentStatus.ACCEPTED
            return HarnessResult.Done("SAVED")
        }

        override suspend fun connectWebDav(
            url: String,
            username: String,
            password: String,
        ): HarnessResult {
            connected = Triple(url, username, password)
            return HarnessResult.Stopped("CredentialRejected")
        }

        override suspend fun prepareSlots(uris: List<String>): HarnessResult {
            slotsFailure?.let { throw it }
            return HarnessResult.Done("SLOTS_PREPARED")
        }

        override suspend fun recordInventedChanges(count: Int): HarnessResult {
            changesGate?.await()
            return HarnessResult.Done("RECORDED_$count")
        }

        override suspend fun backupNow(): BackupNowRequest {
            backupNowCalls++
            return BackupNowRequest.QUEUED
        }

        override suspend fun restoreFromFile(uri: String): HarnessRestore =
            restoreStages.removeFirst()

        override suspend fun answer(answer: RestoreAnswer): HarnessRestore {
            answers += answer
            return restoreStages.removeFirst()
        }

        override suspend fun cancelRestore(): Boolean = true
    }

    private companion object {
        val REPORT =
            BackupStatusReport(BackupStatus.NOT_CONNECTED, pendingChanges = 0, savingSoon = false)
        val SAVED = HarnessState("NOT_CONNECTED", null, 0, 0, 0, null, 0, null, null)
    }
}
