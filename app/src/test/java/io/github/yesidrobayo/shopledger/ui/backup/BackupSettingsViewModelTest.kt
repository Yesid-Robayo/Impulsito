package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.PinRelease
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
import org.junit.Before
import org.junit.Test

/**
 * Los ajustes de la pantalla del respaldo: `Copias con datos móviles`, que advierte al elegir
 * `Solo con Wi-Fi`; `Subido este mes`; y `Liberar copia`, solo con una acción explícita y solo
 * para una copia fijada. Con ranuras no aparece nada de esto. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-12T15:00:00Z")

    private fun overview(
        kind: BackupDestinationKind = BackupDestinationKind.WEBDAV,
        policy: MeteredBackupPolicy = MeteredBackupPolicy.EVERY_60_MIN,
    ) = BackupOverview(
        report = BackupStatusReport(
            BackupStatus.UP_TO_DATE,
            pendingChanges = 0,
            savingSoon = false,
        ),
        destinationKind = kind,
        lastSuccessAt = now - 1.hours,
        failureCode = null,
        staleReason = null,
        meteredPolicy = policy,
        nextMeteredUploadAt = null,
        lastFullReadAt = null,
        computedAt = now,
    )

    private val overviews = MutableStateFlow(overview())

    private fun copy(key: String, pinned: Boolean) = BackupCopySummary(
        key = key,
        createdAt = now - 1.hours,
        sizeBytes = 2_400_000,
        origin = CopyOrigin.THIS_PHONE,
        slot = null,
        records = 4_650,
        newest = false,
        pinned = pinned,
        withDifferences = false,
        restorable = true,
    )

    private val pinned = copy("copia-fijada", pinned = true)
    private val loose = copy("copia-suelta", pinned = false)

    private val destination =
        object : BackupDestinationSource {
            var info: BackupDestinationInfo? = BackupDestinationInfo.WebDav(
                "negocio",
                "dav.example.com",
            )
            var lists = 0

            override suspend fun describe(): BackupDestinationInfo? = info

            override suspend fun listCopies(): BackupCopyList {
                lists += 1
                return BackupCopyList.Listed(listOf(pinned, loose))
            }

            override suspend fun latestHandoff(): HandoffCopy? = null

            override fun hasNetwork(): Boolean = true
        }

    private val policies = mutableListOf<MeteredBackupPolicy>()
    private val releases = mutableListOf<String>()
    private var releaseResult = PinRelease.RELEASED
    private var uploaded: Long? = 84_000_000

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): BackupViewModel {
        val model = BackupViewModel(
            overview = { refreshes -> combine(refreshes, overviews) { _, current -> current } },
            destination = BackupDestinationReader(destination),
            calendar = MutableStateFlow(BusinessCalendar.of("Etc/GMT+5", DayOfWeek.MONDAY)),
            requestBackup = {},
            recordCheck = { true },
            computation = dispatcher,
            changePolicy = { policy ->
                policies += policy
                overviews.value = overview(policy = policy)
            },
            releaseCopy = { key ->
                releases += key
                releaseResult
            },
            uploadedThisMonth = { uploaded },
        )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `with WebDAV the month usage is read with the destination`() = runTest(dispatcher) {
        assertEquals(84_000_000L, viewModel().uiState.value.uploadedThisMonth)
    }

    @Test
    fun `returning to the foreground reads the usage again`() = runTest(dispatcher) {
        val model = viewModel()
        uploaded = 112_000_000

        model.onReturnToForeground()
        advanceUntilIdle()

        assertEquals(112_000_000L, model.uiState.value.uploadedThisMonth)
    }

    @Test
    fun `with the cloud of the phone there is no usage and no policy sheet`() =
        runTest(dispatcher) {
            overviews.value = overview(kind = BackupDestinationKind.DOCUMENT_SLOTS)
            destination.info = BackupDestinationInfo.Slots("Nube")
            val model = viewModel()

            model.onAction(BackupAction.OpenMeteredPolicy)
            advanceUntilIdle()

            assertNull(model.uiState.value.uploadedThisMonth)
            assertEquals(MeteredUi.Closed, model.uiState.value.metered)
        }

    @Test
    fun `every 30 minutes is saved at once and closes the sheet`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(BackupAction.OpenMeteredPolicy)
        assertEquals(MeteredUi.Choosing, model.uiState.value.metered)

        model.onAction(BackupAction.PickMeteredPolicy(MeteredBackupPolicy.EVERY_30_MIN))
        advanceUntilIdle()

        assertEquals(listOf(MeteredBackupPolicy.EVERY_30_MIN), policies)
        assertEquals(MeteredUi.Closed, model.uiState.value.metered)
        assertEquals(
            MeteredBackupPolicy.EVERY_30_MIN,
            model.uiState.value.wording?.overview?.meteredPolicy,
        )
    }

    @Test
    fun `wifi only warns first and is saved only when confirmed`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(BackupAction.OpenMeteredPolicy)

        model.onAction(BackupAction.PickMeteredPolicy(MeteredBackupPolicy.WIFI_ONLY))
        advanceUntilIdle()
        assertEquals(MeteredUi.ConfirmWifiOnly, model.uiState.value.metered)
        assertEquals(emptyList<MeteredBackupPolicy>(), policies)

        model.onAction(BackupAction.ConfirmWifiOnly)
        advanceUntilIdle()
        assertEquals(listOf(MeteredBackupPolicy.WIFI_ONLY), policies)
        assertEquals(MeteredUi.Closed, model.uiState.value.metered)
    }

    @Test
    fun `cancelling the warning keeps the policy`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(BackupAction.OpenMeteredPolicy)
        model.onAction(BackupAction.PickMeteredPolicy(MeteredBackupPolicy.WIFI_ONLY))

        model.onAction(BackupAction.CloseMeteredPolicy)
        advanceUntilIdle()

        assertEquals(emptyList<MeteredBackupPolicy>(), policies)
        assertEquals(MeteredUi.Closed, model.uiState.value.metered)
    }

    @Test
    fun `the current policy writes nothing, wifi only included`() = runTest(dispatcher) {
        overviews.value = overview(policy = MeteredBackupPolicy.WIFI_ONLY)
        val model = viewModel()
        model.onAction(BackupAction.OpenMeteredPolicy)

        model.onAction(BackupAction.PickMeteredPolicy(MeteredBackupPolicy.WIFI_ONLY))
        advanceUntilIdle()

        assertEquals(emptyList<MeteredBackupPolicy>(), policies)
        assertEquals(MeteredUi.Closed, model.uiState.value.metered)
    }

    @Test
    fun `a pinned copy is released only after confirming`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(BackupAction.OpenCopy(pinned.key))

        model.onAction(BackupAction.ReleaseCopy(pinned.key))
        advanceUntilIdle()
        assertEquals(ReleaseUi.Confirm(pinned), model.uiState.value.release)
        assertEquals(RestoreUi.Idle, model.uiState.value.restore)
        assertEquals(emptyList<String>(), releases)

        val listsBefore = destination.lists
        model.onAction(BackupAction.ConfirmRelease)
        advanceUntilIdle()

        assertEquals(listOf(pinned.key), releases)
        assertEquals(ReleaseUi.Idle, model.uiState.value.release)
        assertEquals(BackupMessage.RELEASED, model.uiState.value.message)
        // La lista se vuelve a leer, para ver la copia sin la marca.
        assertEquals(listsBefore + 1, destination.lists)
    }

    @Test
    fun `cancelling the question releases nothing`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(BackupAction.ReleaseCopy(pinned.key))

        model.onAction(BackupAction.CloseRelease)
        advanceUntilIdle()

        assertEquals(ReleaseUi.Idle, model.uiState.value.release)
        assertEquals(emptyList<String>(), releases)
    }

    @Test
    fun `a copy that is not pinned has nothing to release`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(BackupAction.ReleaseCopy(loose.key))
        model.onAction(BackupAction.ConfirmRelease)
        advanceUntilIdle()

        assertEquals(ReleaseUi.Idle, model.uiState.value.release)
        assertEquals(emptyList<String>(), releases)
    }

    @Test
    fun `a failed release keeps the copy pinned and offers to retry it`() = runTest(dispatcher) {
        releaseResult = PinRelease.FAILED
        val model = viewModel()
        model.onAction(BackupAction.ReleaseCopy(pinned.key))
        model.onAction(BackupAction.ConfirmRelease)
        advanceUntilIdle()

        assertEquals(ReleaseUi.Failed(pinned), model.uiState.value.release)
        assertEquals(BackupMessage.RELEASE_FAILED, model.uiState.value.message)

        // Reintentar vuelve a preguntar por la misma copia.
        releaseResult = PinRelease.RELEASED
        model.onAction(BackupAction.ReleaseCopy(pinned.key))
        assertEquals(ReleaseUi.Confirm(pinned), model.uiState.value.release)
        model.onAction(BackupAction.ConfirmRelease)
        advanceUntilIdle()

        assertEquals(listOf(pinned.key, pinned.key), releases)
        assertEquals(ReleaseUi.Idle, model.uiState.value.release)
    }

    @Test
    fun `a release that throws counts as failed`() = runTest(dispatcher) {
        val model =
            BackupViewModel(
                overview = { refreshes -> combine(refreshes, overviews) { _, current -> current } },
                destination = BackupDestinationReader(destination),
                calendar = MutableStateFlow(BusinessCalendar.of("Etc/GMT+5", DayOfWeek.MONDAY)),
                requestBackup = {},
                recordCheck = { true },
                computation = dispatcher,
                releaseCopy = { error("Falla inventada") },
            )
        advanceUntilIdle()
        model.onAction(BackupAction.ReleaseCopy(pinned.key))
        model.onAction(BackupAction.ConfirmRelease)
        advanceUntilIdle()

        assertEquals(ReleaseUi.Failed(pinned), model.uiState.value.release)
        assertEquals(BackupMessage.RELEASE_FAILED, model.uiState.value.message)
    }
}
