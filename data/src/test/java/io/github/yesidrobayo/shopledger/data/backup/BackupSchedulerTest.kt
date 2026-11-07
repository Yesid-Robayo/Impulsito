package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.work.BackupWorkNames
import io.github.yesidrobayo.shopledger.domain.model.backup.AppOpenUpload
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué encola cada evento del respaldo: la venta confirmada, la espera que vence, la app que se abre
 * o pasa a segundo plano, `Respaldar ahora`, el cambio de política y el final de cada subida. Con
 * una restauración en curso, o sin aceptar la versión vigente de la política y los términos, ningún
 * evento encola nada.
 */
class BackupSchedulerTest {
    private var now = Instant.parse("2026-11-07T15:00:00Z")
    private val queue = FakeWorkQueue { now }
    private var restore = false
    private var accepted = true
    private var facts: ScheduleFacts? =
        ScheduleFacts(
            connectedKind = BackupDestinationKind.WEBDAV,
            hasPending = true,
            firstUnsyncedAt = now - 1.minutes,
            lastUploadFinishedAt = now - 20.minutes,
            failureCode = null,
            policy = MeteredBackupPolicy.EVERY_60_MIN,
        )
    private val scheduler =
        BackupScheduler({ facts }, { restore }, { accepted }, queue, Clock { now })

    @Test
    fun aChangeAlwaysRestartsTheWaitAndNothingElseWhileTheStreakIsShort() = runBlocking {
        scheduler.onDataChanged()

        assertEquals(listOf("debounce 1m 30s"), queue.calls)
    }

    @Test
    fun aStreakOfTenMinutesQueuesTheUploadWithoutWaiting() = runBlocking {
        facts = facts?.copy(firstUnsyncedAt = now - 10.minutes)

        scheduler.onDataChanged()

        assertEquals(
            listOf("debounce 1m 30s", "keep unmetered", "keep upload 40m con red"),
            queue.calls,
        )
    }

    @Test
    fun anUnrestorableCopyDoesNotQueueAnUploadWithEachChange() = runBlocking {
        facts =
            facts?.copy(
                firstUnsyncedAt = now - 1.minutes - 10.minutes,
                failureCode = BackupFailureCode.LOCAL_INVARIANT_FATAL.name,
            )

        scheduler.onDataChanged()

        assertEquals(listOf("debounce 1m 30s"), queue.calls)
    }

    @Test
    fun withoutADestinationAChangeOnlyRestartsTheWait() = runBlocking {
        facts = facts?.copy(connectedKind = null, firstUnsyncedAt = now - 30.minutes)

        scheduler.onDataChanged()

        assertEquals(listOf("debounce 1m 30s"), queue.calls)
    }

    @Test
    fun theWaitThatEndsQueuesBothUploadsWithTheRestOfTheInterval() = runBlocking {
        assertTrue(scheduler.onDebounceElapsed())

        assertEquals(listOf("keep unmetered", "keep upload 40m con red"), queue.calls)
    }

    @Test
    fun theWaitWithNothingPendingQueuesNothing() = runBlocking {
        facts = facts?.copy(hasPending = false)

        assertTrue(scheduler.onDebounceElapsed())

        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun theWaitIsRetriedWhenTheDatabaseCannotBeReadYet() = runBlocking {
        facts = null

        assertFalse(scheduler.onDebounceElapsed())
        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun withWiFiOnlyOnlyTheUploadWithoutMobileDataIsQueued() = runBlocking {
        facts = facts?.copy(policy = MeteredBackupPolicy.WIFI_ONLY)

        scheduler.onDebounceElapsed()
        scheduler.onAppOpened(AppOpenUpload.PAST_INTERVAL)

        assertEquals(listOf("keep unmetered", "keep unmetered"), queue.calls)
        assertFalse(BackupWorkNames.UPLOAD in queue.queued)
    }

    @Test
    fun withThePhoneCloudOnlyOneUploadWithoutNetworkAndItsOwnInterval() = runBlocking {
        facts = facts?.copy(connectedKind = BackupDestinationKind.DOCUMENT_SLOTS)

        scheduler.onDebounceElapsed()

        assertEquals(listOf("keep upload 40m sin red"), queue.calls)
    }

    @Test
    fun goingToTheBackgroundDropsTheWaitAndQueuesTheUpload() = runBlocking {
        scheduler.onAppBackgrounded()

        assertEquals(
            listOf("cancel backup-debounce", "keep unmetered", "keep upload 40m con red"),
            queue.calls,
        )
    }

    @Test
    fun goingToTheBackgroundWithNothingPendingTouchesNothing() = runBlocking {
        facts = facts?.copy(hasPending = false)

        scheduler.onAppBackgrounded()

        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun openingTheAppQueuesWhatTheStatusDecided() = runBlocking {
        scheduler.onAppOpened(AppOpenUpload.NONE)
        assertEquals(emptyList<String>(), queue.calls)

        scheduler.onAppOpened(AppOpenUpload.RESPECTING_INTERVAL)
        assertEquals(listOf("keep unmetered", "keep upload 40m con red"), queue.calls)

        queue.calls.clear()
        scheduler.onAppOpened(AppOpenUpload.PAST_INTERVAL)
        // La subida que ya esperaba el intervalo se reemplaza por una que sale ya.
        assertEquals(listOf("keep unmetered", "replace upload con red PAST_INTERVAL"), queue.calls)
        assertEquals(now, queue.queued.getValue(BackupWorkNames.UPLOAD).notBefore)
    }

    @Test
    fun aDayLateNeverReplacesAnUploadThatRuns() = runBlocking {
        queue.running += BackupWorkNames.UPLOAD

        scheduler.onAppOpened(AppOpenUpload.PAST_INTERVAL)

        assertEquals(listOf("keep unmetered"), queue.calls)
    }

    @Test
    fun backupNowReplacesTheUploadWithAManualOneThatLeavesAtOnce() = runBlocking {
        assertEquals(BackupNowRequest.QUEUED, scheduler.backupNow())
        assertEquals(listOf("replace upload con red BACKUP_NOW"), queue.calls)

        facts = facts?.copy(connectedKind = BackupDestinationKind.DOCUMENT_SLOTS)
        queue.calls.clear()
        assertEquals(BackupNowRequest.QUEUED, scheduler.backupNow())
        assertEquals(listOf("replace upload sin red BACKUP_NOW"), queue.calls)
    }

    @Test
    fun backupNowSaysWhyItQueuedNothing() = runBlocking {
        queue.running += BackupWorkNames.UPLOAD_UNMETERED
        assertEquals(BackupNowRequest.ALREADY_RUNNING, scheduler.backupNow())

        facts = facts?.copy(connectedKind = null)
        assertEquals(BackupNowRequest.NOT_CONNECTED, scheduler.backupNow())

        facts = null
        assertEquals(BackupNowRequest.UNAVAILABLE, scheduler.backupNow())

        restore = true
        assertEquals(BackupNowRequest.RESTORE_IN_PROGRESS, scheduler.backupNow())
        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun changingThePolicyQueuesTheUploadAgainWithTheNewInterval() = runBlocking {
        facts = facts?.copy(policy = MeteredBackupPolicy.EVERY_30_MIN)

        scheduler.onMeteredPolicyChanged()

        assertEquals(
            listOf("cancel backup-upload", "keep unmetered", "keep upload 10m con red"),
            queue.calls,
        )
    }

    @Test
    fun aVerifiedCopyCancelsTheOtherUploadAndWaitsAgainForWhatIsLeft() = runBlocking {
        scheduler.onRunFinished(BackupRun.Verified(4), BackupWorkNames.UPLOAD_UNMETERED)
        assertEquals(listOf("cancel backup-upload", "debounce 1m 30s"), queue.calls)

        queue.calls.clear()
        facts = facts?.copy(hasPending = false)
        scheduler.onRunFinished(BackupRun.Verified(5), BackupWorkNames.UPLOAD)
        assertEquals(listOf("cancel backup-upload-unmetered"), queue.calls)
    }

    @Test
    fun anUnrestorableCopyNeverQueuesTheWaitAgain() = runBlocking {
        scheduler.onRunFinished(
            BackupRun.Verified(4, unrestorable = true),
            BackupWorkNames.UPLOAD,
        )
        scheduler.onRunFinished(
            BackupRun.Skipped(SkipReason.UNRESTORABLE_NOT_WRITTEN),
            BackupWorkNames.UPLOAD,
        )

        assertEquals(
            listOf("cancel backup-upload-unmetered", "cancel backup-upload-unmetered"),
            queue.calls,
        )
    }

    @Test
    fun theGateWithTheIntervalRunningWaitsAgainToComputeTheDelay() = runBlocking {
        scheduler.onRunFinished(
            BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL),
            BackupWorkNames.UPLOAD,
        )

        assertEquals(listOf("debounce 1m 30s"), queue.calls)
    }

    @Test
    fun otherEndsOfARunQueueNothing() = runBlocking {
        val runs =
            listOf(
                BackupRun.Skipped(SkipReason.WAITING_FOR_UNMETERED),
                BackupRun.Skipped(SkipReason.NOTHING_PENDING),
                BackupRun.Skipped(SkipReason.NOT_CONNECTED),
                BackupRun.Skipped(SkipReason.POLICY_PENDING),
                BackupRun.Skipped(SkipReason.EMPTY_DATABASE),
                BackupRun.Skipped(SkipReason.DESTINATION_CHANGED),
                BackupRun.Retry(RetryReason.TRANSIENT),
                BackupRun.Stopped(StopCause.Failure(BackupFailureCode.QUOTA_EXCEEDED)),
            )
        for (run in runs) scheduler.onRunFinished(run, BackupWorkNames.UPLOAD)

        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun withARestoreInProgressNoEventQueuesAnything() = runBlocking {
        restore = true
        facts = facts?.copy(firstUnsyncedAt = now - 1.minutes - 10.minutes)

        scheduler.onDataChanged()
        assertTrue(scheduler.onDebounceElapsed())
        scheduler.onAppBackgrounded()
        scheduler.onAppOpened(AppOpenUpload.PAST_INTERVAL)
        scheduler.onMeteredPolicyChanged()
        scheduler.onRunFinished(BackupRun.Verified(2), null)
        scheduler.onRunFinished(BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL), null)
        scheduler.resumeAfterRestore()

        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun theRestorePauseCancelsTheThreeJobsAndTheResumeQueuesWhatIsPending() = runBlocking {
        scheduler.cancelForRestore()
        scheduler.awaitNoneRunning()
        scheduler.resumeAfterRestore()

        assertEquals(
            listOf(
                "cancel backup-debounce",
                "cancel backup-upload-unmetered",
                "cancel backup-upload",
                "await none running",
                "keep unmetered",
                "keep upload 40m con red",
            ),
            queue.calls,
        )
    }

    @Test
    fun withoutThePolicyAcceptedNoEventQueuesAnything() = runBlocking {
        accepted = false
        facts = facts?.copy(firstUnsyncedAt = now - 1.minutes - 10.minutes)

        scheduler.onDataChanged()
        assertTrue(scheduler.onDebounceElapsed())
        scheduler.onAppBackgrounded()
        scheduler.onAppOpened(AppOpenUpload.PAST_INTERVAL)
        scheduler.onAppOpened(AppOpenUpload.RESPECTING_INTERVAL)
        scheduler.onPolicyAccepted()
        scheduler.onRunFinished(BackupRun.Verified(2), null)
        scheduler.onRunFinished(BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL), null)
        scheduler.onRunFinished(BackupRun.Skipped(SkipReason.POLICY_PENDING), null)
        scheduler.resumeAfterRestore()

        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun withoutThePolicyAcceptedChangingTheMobilePolicyOnlyDropsTheOldUpload() = runBlocking {
        accepted = false

        scheduler.onMeteredPolicyChanged()

        assertEquals(listOf("cancel backup-upload"), queue.calls)
    }

    @Test
    fun backupNowAsksToAcceptThePolicyOnlyWithADestination() = runBlocking {
        accepted = false
        assertEquals(BackupNowRequest.POLICY_PENDING, scheduler.backupNow())

        // Sin destino, lo primero es conectarlo.
        facts = facts?.copy(connectedKind = null)
        assertEquals(BackupNowRequest.NOT_CONNECTED, scheduler.backupNow())
        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun acceptingThePolicyQueuesWhatWasPendingWithTheRestOfTheInterval() = runBlocking {
        accepted = false
        scheduler.onDataChanged()
        assertEquals(emptyList<String>(), queue.calls)

        accepted = true
        scheduler.onPolicyAccepted()

        assertEquals(listOf("keep unmetered", "keep upload 40m con red"), queue.calls)
    }

    @Test
    fun acceptingThePolicyWithNothingToUploadQueuesNothing() = runBlocking {
        facts = facts?.copy(hasPending = false)
        scheduler.onPolicyAccepted()

        facts = facts?.copy(hasPending = true, connectedKind = null)
        scheduler.onPolicyAccepted()

        restore = true
        facts = facts?.copy(connectedKind = BackupDestinationKind.WEBDAV)
        scheduler.onPolicyAccepted()

        assertEquals(emptyList<String>(), queue.calls)
    }
}
