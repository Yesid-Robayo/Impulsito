package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNetwork
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.BackupStatusSource
import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El estado se calcula con el reloj del momento en que se pide: al abrir la app, un cambio que lleva
 * un día sin copia se ve aunque ningún trabajo en segundo plano haya corrido desde entonces.
 */
class ObserveBackupStatusUseCaseTest {
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

    private val useCase = ObserveBackupStatusUseCase(source, Clock { now })

    @Test
    fun `opening the app a day later shows the copy as stale without any worker`() = runTest {
        assertEquals(BackupStatus.PENDING, useCase.current().status)

        now = start + 24.hours

        assertEquals(BackupStatus.STALE, useCase.current().status)
        assertEquals(BackupStatus.STALE, useCase().first().status)
    }

    @Test
    fun `opening the app with the clock set back shows the copy as stale`() = runTest {
        now = start - 3.hours

        assertEquals(BackupStatus.STALE, useCase.current().status)
    }

    @Test
    fun `the status is emitted again only when it changes`() = runTest {
        val seen = mutableListOf<BackupStatusReport>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { useCase().toList(seen) }

        // Un dato guardado que no cambia lo que se ve no vuelve a emitir.
        stored.value = facts.copy(lastSuccessAt = start - 3.hours)
        stored.value = facts.copy(changeSeq = 10, firstUnsyncedAt = null)

        assertEquals(
            listOf(
                BackupStatusReport(BackupStatus.PENDING, pendingChanges = 2, savingSoon = true),
                BackupStatusReport(BackupStatus.UP_TO_DATE, pendingChanges = 0, savingSoon = false),
            ),
            seen,
        )
    }

    @Test
    fun `opening the app with pending changes uploads, and a stale copy skips the interval`() =
        runTest {
            assertEquals(AppOpenUpload.RESPECTING_INTERVAL, useCase.onAppOpen())

            now = start + 25.hours
            assertEquals(AppOpenUpload.PAST_INTERVAL, useCase.onAppOpen())

            stored.value = facts.copy(uploadWork = BackupUploadWork.RUNNING)
            assertEquals(AppOpenUpload.NONE, useCase.onAppOpen())
        }
}
