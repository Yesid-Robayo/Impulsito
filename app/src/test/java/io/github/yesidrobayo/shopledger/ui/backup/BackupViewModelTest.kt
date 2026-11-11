package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * La pantalla del respaldo sin base ni red: el estado con su zona, el destino y la lista, que se
 * vuelven a leer con una copia nueva, la lista que falla y no se muestra vacía, `Respaldar ahora` y
 * su aviso sin internet, y el estado que se recalcula al volver a primer plano. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-07T15:00:00Z")

    private fun overview(
        status: BackupStatus = BackupStatus.UP_TO_DATE,
        kind: BackupDestinationKind? = BackupDestinationKind.WEBDAV,
        lastSuccessAt: Instant? = now - 1.hours,
    ) = BackupOverview(
        report = BackupStatusReport(status, pendingChanges = 0, savingSoon = false),
        destinationKind = kind,
        lastSuccessAt = lastSuccessAt,
        failureCode = null,
        staleReason = null,
        meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
        nextMeteredUploadAt = null,
        lastFullReadAt = null,
        computedAt = now,
    )

    private val overviews = MutableStateFlow(overview())
    private var refreshCount = 0

    private val copy = BackupCopySummary(
        key = "copia-1",
        createdAt = now - 1.hours,
        sizeBytes = 2_400_000,
        origin = CopyOrigin.THIS_PHONE,
        slot = null,
        records = 4_812,
        newest = true,
        pinned = false,
        withDifferences = false,
        restorable = true,
    )

    private class FakeDestination : BackupDestinationSource {
        var info: BackupDestinationInfo? = BackupDestinationInfo.WebDav(
            "negocio",
            "dav.example.com",
        )
        var copies: BackupCopyList = BackupCopyList.Listed(emptyList())
        var failList = false
        var network = true
        var lists = 0

        override suspend fun describe(): BackupDestinationInfo? = info

        override suspend fun listCopies(): BackupCopyList {
            lists += 1
            if (failList) error("Falla inventada")
            return copies
        }

        override fun hasNetwork(): Boolean = network
    }

    private val destination = FakeDestination()
    private var requests = 0

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): BackupViewModel {
        val model = BackupViewModel(
            overview = { refreshes ->
                combine(refreshes, overviews) { _, current ->
                    refreshCount += 1
                    current
                }
            },
            destination = BackupDestinationReader(destination),
            calendar = MutableStateFlow(BusinessCalendar.of("Etc/GMT+5", DayOfWeek.MONDAY)),
            requestBackup = { requests += 1 },
            computation = dispatcher,
        )
        advanceUntilIdle()
        return model
    }

    @Test
    fun `the state comes with its zone, the destination and the copies`() = runTest(dispatcher) {
        destination.copies = BackupCopyList.Listed(listOf(copy))

        val state = viewModel().uiState.value

        assertEquals(BackupStatus.UP_TO_DATE, state.wording?.overview?.report?.status)
        assertEquals("Etc/GMT+5", state.wording?.zone?.id)
        assertEquals(BackupDestinationInfo.WebDav("negocio", "dav.example.com"), state.destination)
        assertEquals(BackupCopyList.Listed(listOf(copy)), state.copies)
    }

    @Test
    fun `a new copy reads the list again, another state does not`() = runTest(dispatcher) {
        val model = viewModel()
        assertEquals(1, destination.lists)

        overviews.value = overview(status = BackupStatus.UPLOADING)
        advanceUntilIdle()
        assertEquals(1, destination.lists)

        overviews.value = overview(lastSuccessAt = now)
        advanceUntilIdle()
        assertEquals(2, destination.lists)
        assertEquals(BackupStatus.UP_TO_DATE, model.uiState.value.wording?.overview?.report?.status)
    }

    @Test
    fun `with the cloud of the phone, the state carries its name`() = runTest(dispatcher) {
        overviews.value =
            overview(status = BackupStatus.HANDED_OFF, kind = BackupDestinationKind.DOCUMENT_SLOTS)
        destination.info = BackupDestinationInfo.Slots("Nube")

        val wording = viewModel().uiState.value.wording

        assertEquals("Nube", wording?.providerLabel)
        assertEquals(true, wording?.slots)
    }

    @Test
    fun `a list that fails is an error and can be retried`() = runTest(dispatcher) {
        destination.failList = true
        val model = viewModel()
        assertEquals(BackupCopyList.Failed, model.uiState.value.copies)

        destination.failList = false
        model.onAction(BackupAction.RetryCopies)
        advanceUntilIdle()

        assertEquals(BackupCopyList.Listed(emptyList()), model.uiState.value.copies)
    }

    @Test
    fun `back up now asks for the copy, and without internet says so`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(BackupAction.BackUpNow)
        advanceUntilIdle()
        assertEquals(1, requests)
        assertNull(model.uiState.value.message)
        assertFalse(model.uiState.value.requesting)

        destination.network = false
        model.onAction(BackupAction.BackUpNow)
        advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals(BackupMessage.NO_INTERNET, model.uiState.value.message)

        model.onAction(BackupAction.MessageShown)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `with the cloud of the phone there is no internet notice`() = runTest(dispatcher) {
        overviews.value = overview(kind = BackupDestinationKind.DOCUMENT_SLOTS)
        destination.network = false
        val model = viewModel()

        model.onAction(BackupAction.BackUpNow)
        advanceUntilIdle()

        assertNull(model.uiState.value.message)
    }

    @Test
    fun `returning to the foreground computes the state again`() = runTest(dispatcher) {
        val model = viewModel()
        val before = refreshCount

        model.onReturnToForeground()
        advanceUntilIdle()

        assertEquals(before + 1, refreshCount)
        // La navegación resuelve volver y elegir destino: no cambian el estado.
        val state = model.uiState.value
        model.onAction(BackupAction.Back)
        model.onAction(BackupAction.ChooseDestination)
        assertEquals(state, model.uiState.value)
    }
}
