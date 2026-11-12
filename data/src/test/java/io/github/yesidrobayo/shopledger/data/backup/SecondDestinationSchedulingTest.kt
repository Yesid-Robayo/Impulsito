package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuándo se encola el segundo destino: a diario desde `reconcile()`, con cualquier `link_state` y
 * aunque falte aceptar la política, y con `Mantener al día esta carpeta`, al vencer la espera sin
 * destino conectado o con la copia principal esperando red, y tras una racha de diez minutos. Con
 * una restauración en curso, nunca.
 */
class SecondDestinationSchedulingTest {
    private var now = Instant.parse("2026-11-12T15:00:00Z")
    private val queue = FakeWorkQueue { now }
    private var restore = false
    private var accepted = true
    private var waiting = false
    private var facts: ScheduleFacts? =
        ScheduleFacts(
            connectedKind = BackupDestinationKind.WEBDAV,
            hasPending = true,
            firstUnsyncedAt = now - 1.minutes,
            lastUploadFinishedAt = now - 20.minutes,
            failureCode = null,
            policy = MeteredBackupPolicy.EVERY_60_MIN,
        )
    private var second: SecondDestinationFacts? =
        SecondDestinationFacts(
            followMainBackup = false,
            lastCopyAt = now - 25.hours,
            lastCopySeq = 4,
            changeSeq = 7,
        )
    private val scheduler =
        BackupScheduler(
            { facts },
            { restore },
            { accepted },
            queue,
            Clock { now },
            secondDestination = { second },
            mainWaitingForNetwork = { waiting },
        )

    private val keep = "keep second destination"

    @Test
    fun theDailyCopyIsQueuedWithoutADestinationAndWithoutThePolicy() = runBlocking {
        facts = facts?.copy(connectedKind = null)
        accepted = false

        scheduler.reconcile()

        assertTrue(keep in queue.calls)
    }

    @Test
    fun theDailyCopyWaitsADayAndNeedsChanges() = runBlocking {
        second = second?.copy(lastCopyAt = now - 23.hours)
        scheduler.reconcile()
        assertFalse(keep in queue.calls)

        second = second?.copy(lastCopyAt = now - 25.hours, lastCopySeq = 7)
        scheduler.reconcile()
        assertFalse(keep in queue.calls)

        second = null
        scheduler.reconcile()
        assertFalse(keep in queue.calls)
    }

    @Test
    fun aRestoreInProgressQueuesNothing() = runBlocking {
        restore = true
        second = second?.copy(followMainBackup = true, lastCopyAt = now - 2.hours)

        scheduler.reconcile()
        scheduler.onDataChanged()
        scheduler.onDebounceElapsed()
        scheduler.onSecondDestinationFinished()

        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun followingWithoutADestinationTheWaitWritesTheFolder() = runBlocking {
        facts = facts?.copy(connectedKind = null)
        second = second?.copy(followMainBackup = true, lastCopyAt = now - 3.minutes)

        scheduler.onDebounceElapsed()

        assertEquals(listOf(keep), queue.calls)
    }

    @Test
    fun followingWithTheMainCopyWaitingForNetworkTheFolderDoesNotWait() = runBlocking {
        second = second?.copy(followMainBackup = true, lastCopyAt = now - 3.minutes)
        waiting = true

        scheduler.onDebounceElapsed()

        assertTrue(keep in queue.calls)
    }

    @Test
    fun followingWithNetworkTheStepNineBWritesItInstead() = runBlocking {
        second = second?.copy(followMainBackup = true, lastCopyAt = now - 3.minutes)

        scheduler.onDebounceElapsed()

        assertFalse(keep in queue.calls)
    }

    @Test
    fun withoutFollowingTheWaitDoesNotWriteTheFolder() = runBlocking {
        facts = facts?.copy(connectedKind = null)

        scheduler.onDebounceElapsed()

        assertFalse(keep in queue.calls)
    }

    @Test
    fun followingWithoutChangesTheWaitWritesNothing() = runBlocking {
        facts = facts?.copy(connectedKind = null)
        second = second?.copy(followMainBackup = true, lastCopySeq = 7)

        scheduler.onDebounceElapsed()

        assertFalse(keep in queue.calls)
    }

    @Test
    fun followingALongStreakWritesTheFolderAfterTenMinutes() = runBlocking {
        second = second?.copy(followMainBackup = true, lastCopyAt = now - 9.minutes)
        scheduler.onDataChanged()
        assertFalse(keep in queue.calls)

        second = second?.copy(lastCopyAt = now - 10.minutes)
        scheduler.onDataChanged()
        assertTrue(keep in queue.calls)
    }

    @Test
    fun changesLeftAfterWritingRestartTheWaitOnlyWhenFollowing() = runBlocking {
        scheduler.onSecondDestinationFinished()
        assertEquals(emptyList<String>(), queue.calls)

        second = second?.copy(followMainBackup = true)
        scheduler.onSecondDestinationFinished()
        assertEquals(listOf("debounce 1m 30s"), queue.calls)

        queue.calls.clear()
        second = second?.copy(lastCopySeq = 7)
        scheduler.onSecondDestinationFinished()
        assertEquals(emptyList<String>(), queue.calls)
    }

    @Test
    fun disconnectingTheMainDestinationKeepsTheLocalFolderGoing() = runBlocking {
        scheduler.cancelForDisconnect()
        assertFalse("cancel second-destination" in queue.calls)

        // Una restauración sí la detiene: también lee la base.
        scheduler.cancelForRestore()
        assertTrue("cancel second-destination" in queue.calls)
    }
}
