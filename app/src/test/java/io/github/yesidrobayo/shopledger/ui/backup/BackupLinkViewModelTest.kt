package io.github.yesidrobayo.shopledger.ui.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDrillResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictLookup
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictSides
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.backup.DisconnectResult
import io.github.yesidrobayo.shopledger.domain.model.backup.DrillOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.FullVerifyOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthSignal
import io.github.yesidrobayo.shopledger.domain.model.backup.HealthState
import io.github.yesidrobayo.shopledger.domain.model.backup.KeepPhoneResult
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreAnswer
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import io.github.yesidrobayo.shopledger.domain.port.BackupLinkPort
import io.github.yesidrobayo.shopledger.domain.port.BackupRestorePort
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupLinkUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.ReadDataHealthUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RestoreBackupUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RunFullVerifyUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.backup.RunRestoreDrillUseCase
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
 * El enlace con el destino desde la pantalla del respaldo, sin base ni red: desconectar con su
 * confirmación, volver tras iniciar sesión en la nube del teléfono y la hoja del conflicto con sus
 * tres opciones. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupLinkViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-07T15:00:00Z")

    private fun overview(
        status: BackupStatus,
        kind: BackupDestinationKind = BackupDestinationKind.WEBDAV,
    ) = BackupOverview(
        report = BackupStatusReport(status, pendingChanges = 3, savingSoon = false),
        destinationKind = kind,
        lastSuccessAt = now - 1.hours,
        failureCode = null,
        staleReason = null,
        meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
        nextMeteredUploadAt = null,
        lastFullReadAt = null,
        computedAt = now,
    )

    private val overviews = MutableStateFlow(overview(BackupStatus.CONFLICT))

    private val copy = BackupCopySummary(
        key = "copia-del-otro",
        createdAt = now - 1.hours,
        sizeBytes = 2_400_000,
        origin = CopyOrigin.OTHER_PHONE,
        slot = null,
        records = 4_812,
        newest = true,
        pinned = false,
        withDifferences = false,
        restorable = true,
    )

    private val sides = ConflictSides(
        kind = BackupConflictKind.OTHER_DEVICE,
        destinationKind = BackupDestinationKind.WEBDAV,
        localRecords = 4_650,
        localCatalogCount = 120,
        pendingChanges = 3,
        cloudCopyKey = copy.key,
        cloudCreatedAt = now - 1.hours,
        cloudDeviceLabel = null,
        cloudFromThisPhone = false,
        cloudRecords = 4_812,
        cloudCatalogCount = 120,
    )

    private class FakeDestination : BackupDestinationSource {
        var info: BackupDestinationInfo? =
            BackupDestinationInfo.WebDav("negocio", "dav.example.com")
        var copies: BackupCopyList = BackupCopyList.Listed(emptyList())
        var network = true
        var describes = 0

        override suspend fun describe(): BackupDestinationInfo? {
            describes += 1
            return info
        }

        override suspend fun listCopies(): BackupCopyList = copies

        override suspend fun latestHandoff(): HandoffCopy? = null

        override fun hasNetwork(): Boolean = network
    }

    private class FakeLink : BackupLinkPort {
        var disconnectResult = DisconnectResult.DISCONNECTED
        var disconnects = 0
        var resumeResult = false
        var resumes = 0
        var lookup: ConflictLookup = ConflictLookup.NotInConflict
        val keepResults = ArrayDeque<KeepPhoneResult>()
        val keeps = mutableListOf<Boolean>()

        override suspend fun disconnect(): DisconnectResult {
            disconnects += 1
            return disconnectResult
        }

        override suspend fun resumeAfterSignIn(): Boolean {
            resumes += 1
            return resumeResult
        }

        override suspend fun describeConflict(): ConflictLookup = lookup

        override suspend fun keepThisPhone(fewerRecordsConfirmed: Boolean): KeepPhoneResult {
            keeps += fewerRecordsConfirmed
            return keepResults.removeFirst()
        }
    }

    private object NoRestore : BackupRestorePort {
        override suspend fun restoreCopy(copyKey: String, code: String?): RestoreProgress =
            error(NOT_HERE)

        override suspend fun restoreFile(uri: String, code: String?): RestoreProgress =
            error(NOT_HERE)

        override suspend fun beforeUpdateCopy(): BeforeUpdateCopy? = null

        override suspend fun restoreBeforeUpdate(): RestoreProgress = error(NOT_HERE)

        override suspend fun answer(answer: RestoreAnswer): RestoreProgress = error(NOT_HERE)

        override suspend fun cancel(): Boolean = false

        const val NOT_HERE = "No se restaura en esta prueba"
    }

    private val destination = FakeDestination()
    private val link = FakeLink()
    private val drillOutcomes = ArrayDeque<DrillOutcome>()
    private var healthReads = 0
    private val verifyOutcomes = ArrayDeque<FullVerifyOutcome>()
    private var healthFacts: HealthFacts? =
        HealthFacts(
            lastHumanCheckAt = null,
            lastFullVerifyAt = now - 1.hours,
            lastDrillAt = now - 1.hours,
            lastDrillResult = BackupDrillResult.PASSED,
            deviceLocked = true,
            serviceSpaceKnown = true,
        )

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): BackupViewModel {
        val model = BackupViewModel(
            overview = { refreshes -> refreshes.map { overviews.value } },
            destination = BackupDestinationReader(destination),
            calendar = MutableStateFlow(BusinessCalendar.of("Etc/GMT+5", DayOfWeek.MONDAY)),
            requestBackup = {},
            recordCheck = { true },
            computation = dispatcher,
            restore = RestoreBackupUseCase(NoRestore),
            link = BackupLinkUseCase(link),
            drill = RunRestoreDrillUseCase { drillOutcomes.removeFirst() },
            health = ReadDataHealthUseCase {
                healthReads++
                healthFacts
            },
            fullVerify = RunFullVerifyUseCase { verifyOutcomes.removeFirst() },
        )
        advanceUntilIdle()
        return model
    }

    // Desconectar

    @Test
    fun `disconnecting asks first and only the confirmation disconnects`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(BackupAction.Disconnect)
        advanceUntilIdle()
        assertEquals(DisconnectUi.ASKING, model.uiState.value.disconnect)
        assertEquals(0, link.disconnects)

        model.onAction(BackupAction.DismissDisconnect)
        assertEquals(DisconnectUi.HIDDEN, model.uiState.value.disconnect)

        model.onAction(BackupAction.Disconnect)
        model.onAction(BackupAction.ConfirmDisconnect)
        // Un segundo toque mientras desconecta no repite nada.
        model.onAction(BackupAction.ConfirmDisconnect)
        advanceUntilIdle()

        assertEquals(1, link.disconnects)
        assertEquals(DisconnectUi.HIDDEN, model.uiState.value.disconnect)
        assertEquals(BackupMessage.DISCONNECTED, model.uiState.value.message)
    }

    @Test
    fun `a failed disconnection says that nothing changed`() = runTest(dispatcher) {
        link.disconnectResult = DisconnectResult.FAILED
        val model = viewModel()

        model.onAction(BackupAction.Disconnect)
        model.onAction(BackupAction.ConfirmDisconnect)
        advanceUntilIdle()

        assertEquals(BackupMessage.DISCONNECT_FAILED, model.uiState.value.message)
    }

    // Sesión cerrada

    @Test
    fun `returning with the cloud session closed checks the files and reloads`() =
        runTest(dispatcher) {
            overviews.value =
                overview(BackupStatus.PERMISSION_LOST, BackupDestinationKind.DOCUMENT_SLOTS)
            destination.info = BackupDestinationInfo.Slots("Drive", sessionRequired = true)
            val model = viewModel()
            assertEquals(true, model.uiState.value.wording?.sessionRequired)
            val before = destination.describes

            link.resumeResult = true
            destination.info = BackupDestinationInfo.Slots("Drive")
            model.onReturnToForeground()
            advanceUntilIdle()

            assertEquals(1, link.resumes)
            assertTrue(destination.describes > before)
            assertEquals(false, model.uiState.value.wording?.sessionRequired)
        }

    @Test
    fun `without a closed session returning does not touch the cloud`() = runTest(dispatcher) {
        destination.info = BackupDestinationInfo.Slots("Drive")
        val model = viewModel()

        model.onReturnToForeground()
        advanceUntilIdle()

        assertEquals(0, link.resumes)
    }

    // Conflicto

    @Test
    fun `reviewing shows both sides and deciding later changes nothing`() = runTest(dispatcher) {
        link.lookup = ConflictLookup.Shown(sides)
        val model = viewModel()

        model.onAction(BackupAction.ReviewConflict)
        advanceUntilIdle()
        assertEquals(ConflictLookup.Shown(sides), model.uiState.value.conflict?.lookup)

        model.onAction(BackupAction.DecideLater)

        assertNull(model.uiState.value.conflict)
        assertTrue(link.keeps.isEmpty())
    }

    @Test
    fun `keeping a phone with fewer records needs the reinforced confirmation`() =
        runTest(dispatcher) {
            link.lookup = ConflictLookup.Shown(sides)
            link.keepResults += KeepPhoneResult.Kept
            val model = viewModel()
            model.onAction(BackupAction.ReviewConflict)
            advanceUntilIdle()

            model.onAction(BackupAction.KeepPhone)
            val confirm = model.uiState.value.conflict?.confirm as KeepConfirm.Sides
            assertTrue("Este teléfono tiene menos registros", confirm.reinforced)
            assertTrue("Nada se decide antes de confirmar", link.keeps.isEmpty())

            model.onAction(BackupAction.ConfirmKeep)
            advanceUntilIdle()

            assertEquals(listOf(true), link.keeps)
            assertNull(model.uiState.value.conflict)
        }

    @Test
    fun `with more records keeping asks a plain confirmation`() = runTest(dispatcher) {
        val more = sides.copy(localRecords = 5_000)
        link.lookup = ConflictLookup.Shown(more)
        link.keepResults += KeepPhoneResult.NeedsConfirmation(sides)
        link.keepResults += KeepPhoneResult.Kept
        val model = viewModel()
        model.onAction(BackupAction.ReviewConflict)
        advanceUntilIdle()

        model.onAction(BackupAction.KeepPhone)
        assertEquals(KeepConfirm.Sides(more, false), model.uiState.value.conflict?.confirm)
        model.onAction(BackupAction.ConfirmKeep)
        advanceUntilIdle()

        // La nube cambió a algo con más registros: ahora sí, la confirmación reforzada.
        assertEquals(KeepConfirm.Sides(sides, true), model.uiState.value.conflict?.confirm)
        model.onAction(BackupAction.ConfirmKeep)
        advanceUntilIdle()
        assertEquals(listOf(false, true), link.keeps)
    }

    @Test
    fun `each failure to keep leaves the conflict and says why`() = runTest(dispatcher) {
        link.lookup = ConflictLookup.Shown(sides.copy(localRecords = 5_000))
        link.keepResults += KeepPhoneResult.TooManyPinned
        link.keepResults += KeepPhoneResult.Unreachable
        link.keepResults += KeepPhoneResult.Unreachable
        link.keepResults += KeepPhoneResult.CloudChanged(sides)
        link.keepResults += KeepPhoneResult.EmptyDatabase
        val model = viewModel()
        model.onAction(BackupAction.ReviewConflict)
        advanceUntilIdle()

        fun keep(): BackupMessage? {
            model.onAction(BackupAction.MessageShown)
            model.onAction(BackupAction.KeepPhone)
            model.onAction(BackupAction.ConfirmKeep)
            advanceUntilIdle()
            return model.uiState.value.message
        }

        assertEquals(BackupMessage.PINNED_MAX, keep())
        assertEquals(BackupMessage.CONFLICT_UNREACHABLE, keep())
        destination.network = false
        assertEquals(BackupMessage.CONFLICT_NO_INTERNET, keep())
        keep()
        assertEquals(ConflictLookup.Shown(sides), model.uiState.value.conflict?.lookup)
        assertNull(model.uiState.value.conflict?.confirm)
        keep()
        assertEquals(ConflictLookup.EmptyDatabase, model.uiState.value.conflict?.lookup)
    }

    @Test
    fun `a slot that needs a new file confirms and then leaves to create it`() =
        runTest(dispatcher) {
            val need = ConflictLookup.NewSlotFileRequired(BackupSlot.B, null, false, 4_650, 0)
            link.lookup = need
            val model = viewModel()
            model.onAction(BackupAction.ReviewConflict)
            advanceUntilIdle()

            model.onAction(BackupAction.KeepPhone)
            assertEquals(KeepConfirm.NewFile(need), model.uiState.value.conflict?.confirm)
            // Confirmar con los dos lados no aplica: lo resuelve la navegación.
            model.onAction(BackupAction.ConfirmKeep)
            advanceUntilIdle()
            assertTrue(link.keeps.isEmpty())

            model.onAction(BackupAction.CreateConflictFile)
            assertNull(model.uiState.value.conflict)
        }

    @Test
    fun `an unreachable destination offers to try again, offline or not`() = runTest(dispatcher) {
        link.lookup = ConflictLookup.Unreachable
        destination.network = false
        val model = viewModel()

        model.onAction(BackupAction.ReviewConflict)
        advanceUntilIdle()
        assertEquals(true, model.uiState.value.conflict?.offline)
        // Sin los dos lados, conservar este teléfono no se ofrece.
        model.onAction(BackupAction.KeepPhone)
        assertNull(model.uiState.value.conflict?.confirm)

        destination.network = true
        link.lookup = ConflictLookup.Shown(sides)
        model.onAction(BackupAction.RetryConflict)
        advanceUntilIdle()
        assertEquals(ConflictLookup.Shown(sides), model.uiState.value.conflict?.lookup)
    }

    @Test
    fun `without a conflict anymore the sheet does not open`() = runTest(dispatcher) {
        val model = viewModel()

        model.onAction(BackupAction.ReviewConflict)
        advanceUntilIdle()

        assertNull(model.uiState.value.conflict)
    }

    @Test
    fun `restoring from the conflict opens that copy, or the newest one`() = runTest(dispatcher) {
        destination.copies = BackupCopyList.Listed(listOf(copy))
        link.lookup = ConflictLookup.Shown(sides)
        val model = viewModel()
        model.onAction(BackupAction.ReviewConflict)
        advanceUntilIdle()

        model.onAction(BackupAction.RestoreCloud(copy.key))
        assertNull(model.uiState.value.conflict)
        assertEquals(RestoreUi.CopySheet(copy), model.uiState.value.restore)

        model.onAction(BackupAction.CloseRestore)
        model.onAction(BackupAction.RestoreCloud(null))
        assertEquals(RestoreUi.CopySheet(copy), model.uiState.value.restore)
    }
    // Probar restauración

    @Test
    fun `each drill outcome is said once and the state is read again`() = runTest(dispatcher) {
        val model = viewModel()
        val expected =
            listOf(
                DrillOutcome.PASSED to BackupMessage.DRILL_PASSED,
                DrillOutcome.FAILED to BackupMessage.DRILL_FAILED,
                DrillOutcome.SKIPPED to BackupMessage.DRILL_SKIPPED,
                DrillOutcome.NO_COPY to BackupMessage.DRILL_NO_COPY,
                DrillOutcome.UNREACHABLE to BackupMessage.CONFLICT_UNREACHABLE,
            )
        for ((outcome, message) in expected) {
            drillOutcomes += outcome
            model.onAction(BackupAction.RunDrill)
            assertTrue(model.uiState.value.drilling)
            // Un segundo toque mientras corre no repite el simulacro.
            model.onAction(BackupAction.RunDrill)
            advanceUntilIdle()

            assertEquals(message, model.uiState.value.message)
            assertEquals(false, model.uiState.value.drilling)
            model.onAction(BackupAction.MessageShown)
        }
        destination.network = false
        drillOutcomes += DrillOutcome.UNREACHABLE
        model.onAction(BackupAction.RunDrill)
        advanceUntilIdle()
        assertEquals(BackupMessage.CONFLICT_NO_INTERNET, model.uiState.value.message)
        assertTrue(drillOutcomes.isEmpty())
    }
    // Salud de mis datos

    @Test
    fun `the health panel reads its rows and only opening it changes what is seen`() =
        runTest(dispatcher) {
            overviews.value = overview(BackupStatus.UP_TO_DATE)
            destination.copies = BackupCopyList.Listed(listOf(copy.copy(pinned = true)))
            val model = viewModel()

            val rows = requireNotNull(model.uiState.value.health)
            assertEquals(HealthState.OK, rows.first { it.signal == HealthSignal.LAST_DRILL }.state)
            assertEquals(1, rows.first { it.signal == HealthSignal.PINNED }.count)
            // Con todo bien va plegado hasta que la persona lo abra.
            assertTrue(healthFolded(rows))
            assertNull(model.uiState.value.healthOpen)
            model.onAction(BackupAction.ToggleHealth)
            assertEquals(true, model.uiState.value.healthOpen)
            model.onAction(BackupAction.ToggleHealth)
            assertEquals(false, model.uiState.value.healthOpen)
            assertTrue("Solo lee", healthReads > 0)
        }

    @Test
    fun `without the base the panel does not appear`() = runTest(dispatcher) {
        healthFacts = null

        val model = viewModel()

        assertNull(model.uiState.value.health)
    }
    // Comprobar ahora

    @Test
    fun `checking the full copy only runs when tapped and says how it ended`() =
        runTest(dispatcher) {
            val model = viewModel()
            assertTrue("Nada corre solo", verifyOutcomes.isEmpty())
            val expected =
                listOf(
                    FullVerifyOutcome.MATCHED to BackupMessage.VERIFY_MATCHED,
                    FullVerifyOutcome.MISMATCH to BackupMessage.VERIFY_MISMATCH,
                    FullVerifyOutcome.NO_SPACE to BackupMessage.VERIFY_NO_SPACE,
                    FullVerifyOutcome.NO_COPY to BackupMessage.DRILL_NO_COPY,
                    FullVerifyOutcome.UNREACHABLE to BackupMessage.CONFLICT_UNREACHABLE,
                )
            for ((outcome, message) in expected) {
                verifyOutcomes += outcome
                model.onAction(BackupAction.CheckFullCopy)
                assertTrue(model.uiState.value.verifying)
                model.onAction(BackupAction.CheckFullCopy)
                advanceUntilIdle()
                assertEquals(message, model.uiState.value.message)
                assertEquals(false, model.uiState.value.verifying)
                model.onAction(BackupAction.MessageShown)
            }
            destination.network = false
            verifyOutcomes += FullVerifyOutcome.UNREACHABLE
            model.onAction(BackupAction.CheckFullCopy)
            advanceUntilIdle()
            assertEquals(BackupMessage.CONFLICT_NO_INTERNET, model.uiState.value.message)
        }

    @Test
    fun `the offer appears only with the full read overdue, with its days and size`() =
        runTest(dispatcher) {
            val overdue =
                overview(BackupStatus.UP_TO_DATE).let {
                    it.copy(
                        report = it.report.copy(notices = setOf(BackupNotice.FULL_READ_OVERDUE)),
                        lastFullReadAt = now - 9.days,
                    )
                }
            overviews.value = overdue
            destination.copies = BackupCopyList.Listed(listOf(copy))
            val model = viewModel()

            val offer = fullVerifyOffer(model.uiState.value)
            assertEquals(FullVerifyOffer(9, copy.sizeBytes), offer)

            overviews.value = overview(BackupStatus.UP_TO_DATE)
            model.onReturnToForeground()
            advanceUntilIdle()
            assertNull(fullVerifyOffer(model.uiState.value))
        }
}
