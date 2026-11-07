package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.BackupNotification
import io.github.yesidrobayo.shopledger.data.BackupNotifier
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El mantenimiento diario con la base, el programador y las notificaciones de mentira: verifica la
 * base y deja el fallo solo si algo no coincide, limpia, reconcilia y avisa un día sin copia como
 * mucho una vez cada 24 horas. Ninguna parte usa la red, y una falla en un paso no detiene a los
 * demás.
 */
class DailyMaintenanceTest {
    private val now = Instant.parse("2026-11-07T15:00:00Z")
    private val database = FakeMaintenanceDatabase()
    private val mutex = Mutex()
    private val log = Lines()
    private var restore = false
    private var access: MaintenanceAccess = MaintenanceAccess.Ready(database)
    private val steps = mutableListOf<String>()
    private val shown = mutableListOf<BackupNotification>()
    private var notificationsAllowed = true
    private var cleanUpFails = false
    private val notifier = BackupNotifier {
        if (notificationsAllowed) shown += it
        notificationsAllowed
    }

    private val maintenance =
        DailyMaintenance(
            clock = Clock { now },
            log = log,
            mutex = mutex,
            restorePending = { restore },
            open = { access },
            reconcile = { steps += "reconcile" },
            notifier = { notifier },
            cleanUp = { at ->
                steps += "clean $at"
                if (cleanUpFails) throw IOException("sin permiso")
            },
        )

    @Test
    fun aHealthyBaseChangesNothingAndTheDayStillCleansAndReconciles() = runBlocking {
        assertEquals(MaintenanceRun.DONE, maintenance.run())

        assertEquals(emptyList<Long>(), database.recorded)
        assertEquals(listOf("clean $now", "reconcile"), steps)
        assertEquals(emptyList<BackupNotification>(), shown)
    }

    @Test
    fun twoProductsOffTheirLedgerLeaveTheCountAndTheFailure() = runBlocking {
        database.invariants = ActiveInvariants.Checked(repairCases = 2)

        maintenance.run()

        assertEquals(listOf(2L), database.recorded)
        // Solo el número: nada del registro dice cuáles.
        assertTrue(log.lines.any { "casos=2" in it })
    }

    @Test
    fun aCheckThatFailsOrABaseThatIsMissingWriteNothingAndTheRestGoesOn() = runBlocking {
        database.invariants = ActiveInvariants.Failed(IOException("disco"))
        maintenance.run()
        database.invariants = ActiveInvariants.DatabaseMissing
        maintenance.run()

        assertEquals(emptyList<Long>(), database.recorded)
        assertEquals(listOf("clean $now", "reconcile", "clean $now", "reconcile"), steps)
        assertTrue(log.lines.none { "disco" in it })
    }

    @Test
    fun withACopyOrARestoreHoldingTheLockTheCheckAndTheCleanUpWaitForTomorrow() = runBlocking {
        database.invariants = ActiveInvariants.Checked(repairCases = 3)
        mutex.lock()

        assertEquals(MaintenanceRun.DONE, maintenance.run())

        assertEquals(0, database.checks)
        assertEquals(listOf("reconcile"), steps)
        assertFalse(mutex.tryLock())
    }

    @Test
    fun theLockIsReleasedEvenWhenACleanUpFails() = runBlocking {
        cleanUpFails = true

        assertEquals(MaintenanceRun.DONE, maintenance.run())

        assertTrue(mutex.tryLock())
        assertEquals(listOf("clean $now", "reconcile"), steps)
        assertTrue(log.lines.none { "sin permiso" in it })
    }

    @Test
    fun aRestoreInProgressOrABaseThatWaitsRetriesWithoutTouchingAnything() = runBlocking {
        restore = true
        assertEquals(MaintenanceRun.RETRY, maintenance.run())
        restore = false
        access = MaintenanceAccess.Waiting
        assertEquals(MaintenanceRun.RETRY, maintenance.run())

        assertEquals(0, database.checks)
        assertEquals(emptyList<String>(), steps)
    }

    @Test
    fun aBaseInRescueIsLeftToTheApp() = runBlocking {
        access = MaintenanceAccess.Unavailable

        assertEquals(MaintenanceRun.DONE, maintenance.run())

        assertEquals(emptyList<String>(), steps)
    }

    @Test
    fun aDayWithoutACopyNotifiesOnceAndWritesWhenItWasShown() = runBlocking {
        database.status = BackupStatus.STALE

        maintenance.run()
        // El día siguiente llega antes de las 24 horas: el trabajo periódico puede adelantarse.
        database.lastNotice = now - 23.hours
        maintenance.run()

        assertEquals(listOf(BackupNotification.STALE), shown)
        assertEquals(listOf(now), database.notices)
    }

    @Test
    fun aDayAfterTheLastNoticeItNotifiesAgain() = runBlocking {
        database.status = BackupStatus.STALE
        database.lastNotice = now - 24.hours

        maintenance.run()

        assertEquals(listOf(BackupNotification.STALE), shown)
    }

    @Test
    fun aNotificationThatCannotGoOutIsNotCountedAsShown() = runBlocking {
        database.status = BackupStatus.STALE
        notificationsAllowed = false

        maintenance.run()

        assertEquals(emptyList<Instant>(), database.notices)
    }

    @Test
    fun withoutADestinationOrWithAnyOtherStatusNothingIsNotified() = runBlocking {
        for (status in BackupStatus.entries - BackupStatus.STALE) {
            database.status = status
            maintenance.run()
        }

        assertEquals(emptyList<BackupNotification>(), shown)
    }

    @Test
    fun aFailingStepDoesNotStopTheNotification() = runBlocking {
        database.status = BackupStatus.STALE
        val failing =
            DailyMaintenance(
                clock = Clock { now },
                log = log,
                mutex = mutex,
                restorePending = { false },
                open = { access },
                reconcile = { throw IllegalStateException("cola") },
                notifier = { notifier },
                cleanUp = { },
            )

        assertEquals(MaintenanceRun.DONE, failing.run())

        assertEquals(listOf(BackupNotification.STALE), shown)
    }

    private class FakeMaintenanceDatabase : MaintenanceDatabase {
        var invariants: ActiveInvariants = ActiveInvariants.Checked(repairCases = 0)
        var checks = 0
        val recorded = mutableListOf<Long>()
        var status = BackupStatus.UP_TO_DATE
        var lastNotice: Instant? = null
        val notices = mutableListOf<Instant>()

        override suspend fun invariants(): ActiveInvariants {
            checks++
            return invariants
        }

        override suspend fun recordInvariantFailures(count: Long) {
            recorded += count
        }

        override suspend fun status(now: Instant): BackupStatus = status

        override suspend fun lastStaleNoticeAt(): Instant? = lastNotice

        override suspend fun markStaleNotice(now: Instant) {
            notices += now
            lastNotice = now
        }
    }

    private class Lines : EventLog {
        val lines = mutableListOf<String>()

        override fun write(level: LogLevel, line: LogLine) {
            lines += line.text
        }
    }
}
