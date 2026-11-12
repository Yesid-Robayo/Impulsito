package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNetwork
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.backup.StaleReason
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.BackupStatusSource
import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El estado con sus hechos: la última copia, el destino, el fallo y el motivo de un día sin copia,
 * que solo se afirma cuando se sabe. Volver a primer plano recalcula con la hora nueva.
 */
class ObserveBackupOverviewUseCaseTest {
    private val start = Instant.parse("2026-11-07T08:00:00Z")
    private var now = start

    private val facts =
        BackupStatusFacts(
            restoreInProgress = false,
            linkState = BackupLinkState.CONNECTED,
            destinationKind = BackupDestinationKind.WEBDAV,
            policyAccepted = true,
            databaseEmpty = false,
            uploadWork = BackupUploadWork.NONE,
            changeSeq = 12,
            verifiedSeq = 10,
            firstUnsyncedAt = start - 1.minutes,
            lastSuccessAt = start - 2.hours,
            lastUploadFinishedAt = start - 2.hours,
            failureCode = null,
            firstHandoffAt = null,
            lastHumanCheckAt = null,
            meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
            network = BackupNetwork(validated = true, metered = false, dataSaverRestricted = false),
        )

    private val stored = MutableStateFlow(facts)

    private val source =
        object : BackupStatusSource {
            override suspend fun current(): BackupStatusFacts = stored.value

            override fun observe(): Flow<BackupStatusFacts> = stored
        }

    private val useCase = ObserveBackupOverviewUseCase(source, Clock { now })

    private suspend fun overview(): BackupOverview = useCase(flowOf(Unit)).first()

    @Test
    fun `the overview carries the last copy, the destination and the time it was computed`() =
        runTest {
            val overview = overview()

            assertEquals(BackupStatus.PENDING, overview.report.status)
            assertEquals(2L, overview.report.pendingChanges)
            assertEquals(BackupDestinationKind.WEBDAV, overview.destinationKind)
            assertEquals(start - 2.hours, overview.lastSuccessAt)
            assertNull(overview.failureCode)
            assertNull(overview.staleReason)
            assertEquals(MeteredBackupPolicy.EVERY_60_MIN, overview.meteredPolicy)
            assertEquals(start - 1.hours, overview.nextMeteredUploadAt)
            assertEquals(start, overview.computedAt)
        }

    @Test
    fun `without an interval on mobile data or an upload there is no next upload`() = runTest {
        stored.value = facts.copy(meteredPolicy = MeteredBackupPolicy.WIFI_ONLY)
        assertNull(overview().nextMeteredUploadAt)

        stored.value = facts.copy(lastUploadFinishedAt = null)
        assertNull(overview().nextMeteredUploadAt)
    }

    @Test
    fun `a failure keeps its code`() = runTest {
        stored.value = facts.copy(failureCode = BackupFailureCode.QUOTA_EXCEEDED)

        val overview = overview()

        assertEquals(BackupStatus.FAILED, overview.report.status)
        assertEquals(BackupFailureCode.QUOTA_EXCEEDED, overview.failureCode)
    }

    @Test
    fun `a broken stock check carries how many products it found`() = runTest {
        stored.value =
            facts.copy(failureCode = BackupFailureCode.LOCAL_INVARIANT, invariantFailures = 4)

        val overview = overview()

        assertEquals(BackupFailureCode.LOCAL_INVARIANT, overview.failureCode)
        assertEquals(4L, overview.invariantFailures)
    }

    @Test
    fun `a clock set back is the reason before anything else`() = runTest {
        now = start - 3.hours
        stored.value = facts.copy(network = BackupNetwork.NONE)

        val overview = overview()

        assertEquals(BackupStatus.STALE, overview.report.status)
        assertEquals(StaleReason.CLOCK, overview.staleReason)
    }

    @Test
    fun `with backups only on wifi and no wifi, that is the reason`() = runTest {
        now = start + 24.hours
        stored.value = facts.copy(
            meteredPolicy = MeteredBackupPolicy.WIFI_ONLY,
            network = BackupNetwork(validated = true, metered = true, dataSaverRestricted = false),
        )

        assertEquals(StaleReason.NO_WIFI, overview().staleReason)
    }

    @Test
    fun `without a network the reason is the connection`() = runTest {
        now = start + 24.hours
        stored.value = facts.copy(network = BackupNetwork.NONE)

        assertEquals(StaleReason.NETWORK, overview().staleReason)
    }

    @Test
    fun `with a network and nothing known, no reason is invented`() = runTest {
        now = start + 24.hours

        val overview = overview()

        assertEquals(BackupStatus.STALE, overview.report.status)
        assertNull(overview.staleReason)
    }

    @Test
    fun `with the cloud of the phone the network is not a reason`() = runTest {
        now = start + 24.hours
        stored.value = facts.copy(
            destinationKind = BackupDestinationKind.DOCUMENT_SLOTS,
            network = BackupNetwork.NONE,
            meteredPolicy = MeteredBackupPolicy.WIFI_ONLY,
            firstHandoffAt = start - 1.hours,
            lastHumanCheckAt = start - 1.hours,
        )

        val overview = overview()

        assertEquals(BackupStatus.STALE, overview.report.status)
        assertNull(overview.staleReason)
    }

    @Test
    fun `returning to the foreground recomputes with the new time`() = runTest {
        val refreshes = MutableStateFlow(0)
        val seen = mutableListOf<BackupOverview>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase(refreshes.map {})
                .collect { seen += it }
        }

        now = start + 24.hours
        assertEquals(BackupStatus.PENDING, seen.last().report.status)
        refreshes.value = 1
        job.cancel()

        assertEquals(BackupStatus.STALE, seen.last().report.status)
    }

    @Test
    fun `the same state at another time does not emit again`() = runTest {
        val refreshes = MutableStateFlow(0)
        val seen = mutableListOf<BackupOverview>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase(refreshes.map {})
                .collect { seen += it }
        }

        now = start + 1.minutes
        refreshes.value = 1
        job.cancel()

        assertEquals(1, seen.size)
    }
}
