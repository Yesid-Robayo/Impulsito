package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.yesidrobayo.shopledger.data.BackupNotifier
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.backup.BackupRun
import io.github.yesidrobayo.shopledger.data.backup.DailyMaintenance
import io.github.yesidrobayo.shopledger.data.backup.MaintenanceAccess
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.backup.MutexBackupPause
import io.github.yesidrobayo.shopledger.data.backup.RunKind
import io.github.yesidrobayo.shopledger.data.backup.ScheduledBackupWork
import io.github.yesidrobayo.shopledger.data.backup.SkipReason
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los trabajos del respaldo en la biblioteca de trabajos de prueba: nombres, etiquetas, red,
 * retraso y entrada de cada uno; reemplazar y conservar; que conservar admite un trabajo nuevo
 * cuando el anterior terminó; la pausa de una restauración; que una venta confirmada en la base
 * real deja la espera encolada; que sin aceptar la política no se encola nada hasta aceptarla; y
 * el mantenimiento diario, periódico y sin condiciones.
 */
@RunWith(AndroidJUnit4::class)
class BackupSchedulingDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val queue = WorkManagerBackupQueue(context)
    private lateinit var manager: WorkManager

    @Before
    fun initialize() {
        val configuration =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.ERROR)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(DoneFactory)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        manager = WorkManager.getInstance(context)
    }

    @After
    fun clean() {
        manager.cancelAllWork().result.get()
        manager.pruneWork().result.get()
    }

    @Test
    fun theWaitIsReplacedWithEachChangeAndRunsTheTriggerWithoutNetwork() = runBlocking {
        queue.restartDebounce(90.seconds)
        val first = single(BackupWorkNames.DEBOUNCE)
        queue.restartDebounce(90.seconds)
        val second = single(BackupWorkNames.DEBOUNCE)

        assertNotEquals(first.id, second.id)
        // Reemplazar deja una sola espera: la anterior ya no está encolada.
        assertEquals(
            listOf(second.id),
            manager.getWorkInfosForUniqueWork(BackupWorkNames.DEBOUNCE).get().map { it.id },
        )
        assertEquals(90_000L, second.initialDelayMillis)
        assertEquals(NetworkType.NOT_REQUIRED, second.constraints.requiredNetworkType)
        assertTrue(BackupWorkNames.DEBOUNCE in second.tags)
        assertTrue(BackupTriggerWorker::class.java.name in second.tags)
    }

    @Test
    fun theUploadWithoutMobileDataNeedsAnUnmeteredNetworkAndNoDelay() = runBlocking {
        queue.keepUnmetered()
        val work = single(BackupWorkNames.UPLOAD_UNMETERED)

        assertEquals(NetworkType.UNMETERED, work.constraints.requiredNetworkType)
        assertEquals(0L, work.initialDelayMillis)
        assertTrue(BackupWorkNames.UPLOAD_UNMETERED in work.tags)
        assertEquals(emptyMap<String, Any?>(), inputAfterRunning(work))
    }

    @Test
    fun keepLeavesTheUploadThatWaitsWithItsDelay() = runBlocking {
        queue.keepUpload(40.minutes, needsNetwork = true)
        val first = single(BackupWorkNames.UPLOAD)
        queue.keepUpload(Duration.ZERO, needsNetwork = true)

        val kept = single(BackupWorkNames.UPLOAD)
        assertEquals(first.id, kept.id)
        assertEquals(40.minutes.inWholeMilliseconds, kept.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, kept.constraints.requiredNetworkType)
        assertEquals(BackupUploadWork.ENQUEUED, queue.uploadWork())
    }

    @Test
    fun keepAdmitsANewUploadOnceThePreviousOneFinished() = runBlocking {
        queue.keepUpload(Duration.ZERO, needsNetwork = true)
        val first = single(BackupWorkNames.UPLOAD)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        driver.setAllConstraintsMet(first.id)
        assertEquals(WorkInfo.State.SUCCEEDED, latest(BackupWorkNames.UPLOAD).state)
        assertEquals(BackupUploadWork.NONE, queue.uploadWork())

        queue.keepUpload(10.minutes, needsNetwork = true)

        val second = manager.getWorkInfosForUniqueWork(BackupWorkNames.UPLOAD).get()
        assertEquals(1, second.size)
        assertNotEquals(first.id, second.single().id)
        assertEquals(WorkInfo.State.ENQUEUED, second.single().state)
    }

    @Test
    fun backupNowAndADayLateReplaceTheUploadAndSayWhyTheyRun() = runBlocking {
        queue.keepUpload(40.minutes, needsNetwork = true)
        queue.replaceUpload(needsNetwork = true, kind = RunKind.BACKUP_NOW)
        val manual = single(BackupWorkNames.UPLOAD)

        assertEquals(0L, manual.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, manual.constraints.requiredNetworkType)
        assertEquals(
            mapOf<String, Any?>(BackupWorker.KEY_BACKUP_NOW to true),
            inputAfterRunning(manual),
        )

        queue.keepUpload(40.minutes, needsNetwork = true)
        queue.replaceUpload(needsNetwork = false, kind = RunKind.PAST_INTERVAL)
        // Sin red ni retraso, la biblioteca de prueba la corre al encolarla.
        val late = latest(BackupWorkNames.UPLOAD)
        // Con la nube del teléfono, la subida no espera a ninguna red.
        assertEquals(NetworkType.NOT_REQUIRED, late.constraints.requiredNetworkType)
        assertEquals(
            mapOf<String, Any?>(BackupWorker.KEY_PAST_INTERVAL to true),
            inputAfterRunning(late),
        )
    }

    @Test
    fun theRestorePauseCancelsTheThreeJobsAndNoneIsLeftRunning() = runBlocking {
        queue.restartDebounce(90.seconds)
        queue.keepUnmetered()
        queue.keepUpload(40.minutes, needsNetwork = true)
        val mutex = Mutex()
        var resumed = false
        val pause =
            MutexBackupPause(
                mutex,
                object : ScheduledBackupWork {
                    override suspend fun cancel() {
                        BackupWorkNames.ALL.forEach { queue.cancel(it) }
                    }

                    override suspend fun awaitNoneRunning() = queue.awaitNoneRunning(
                        BackupWorkNames.ALL,
                    )

                    override fun resume() {
                        resumed = true
                    }
                },
            )

        pause.pause()

        val states =
            manager
                .getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(BackupWorkNames.ALL))
                .first()
                .map { it.state }
        assertEquals(List(3) { WorkInfo.State.CANCELLED }, states)
        assertTrue(mutex.isLocked)
        assertEquals(BackupUploadWork.NONE, queue.uploadWork())

        pause.resume()
        assertFalse(mutex.isLocked)
        assertTrue(resumed)
    }

    @Test
    fun aConfirmedSaleOnTheRealDatabaseLeavesTheWaitQueued() = runBlocking {
        val fixture = BackupFixture(context, "backup-scheduling-test", ::WorkManagerBackupQueue)
        try {
            fixture.open()
            fixture.connect()
            fixture.policyConsent.acceptCurrent()

            fixture.addProduct("Pan de prueba")

            // La programación corre aparte de la escritura: se espera a que llegue.
            val work =
                manager
                    .getWorkInfosForUniqueWorkFlow(BackupWorkNames.DEBOUNCE)
                    .first { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }
                    .single()
            assertEquals(90_000L, work.initialDelayMillis)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun withoutThePolicyASaleQueuesNothingAndAcceptingQueuesTheUploads() = runBlocking {
        val fixture =
            BackupFixture(context, "backup-policy-scheduling-test", ::WorkManagerBackupQueue)
        try {
            fixture.open()
            fixture.connect()
            fixture.addProduct("Pan de prueba")
            // Directo, sin esperar a la programación que sigue a la venta, que tampoco encola.
            fixture.scheduler.onDataChanged()
            assertTrue(fixture.scheduler.onDebounceElapsed())
            fixture.scheduler.onAppBackgrounded()
            val query = WorkQuery.fromUniqueWorkNames(BackupWorkNames.ALL)
            assertEquals(emptyList<WorkInfo>(), manager.getWorkInfos(query).get())

            fixture.policyConsent.acceptCurrent()

            // Lo pendiente sale al aceptar, aparte de la escritura del archivo.
            for (name in BackupWorkNames.UPLOADS) {
                manager
                    .getWorkInfosForUniqueWorkFlow(name)
                    .first { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun theUploadWorkerHandsItsEndAndItsOwnNameToTheScheduler() = runBlocking {
        val seen = mutableListOf<Pair<BackupRun, String?>>()
        val worker =
            TestListenableWorkerBuilder<BackupWorker>(context)
                .setTags(listOf(BackupWorkNames.UPLOAD_UNMETERED, "otra"))
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) = BackupWorker(
                            appContext,
                            workerParameters,
                            engine(BackupRun.Verified(7)),
                            MemoryLog(),
                            { false },
                            afterRun = { run, name -> seen += run to name },
                        )
                    },
                ).build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf(BackupRun.Verified(7) to BackupWorkNames.UPLOAD_UNMETERED), seen)
    }

    @Test
    fun aSchedulingFailureAfterTheRunDoesNotChangeItsResult() = runBlocking {
        val log = MemoryLog()
        val worker =
            TestListenableWorkerBuilder<BackupWorker>(context)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ) = BackupWorker(
                            appContext,
                            workerParameters,
                            engine(BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL)),
                            log,
                            { true },
                            afterRun = { _, _ -> error("falla de prueba") },
                        )
                    },
                ).build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(
            log.lines.contains(
                "Falla no prevista al programar el respaldo: error=" +
                    IllegalStateException::class.java.name,
            ),
        )
    }

    @Test
    fun theTriggerSucceedsRetriesWithoutTheDatabaseAndFailsWithItsClassOnly() = runBlocking {
        val log = MemoryLog()
        val cases =
            listOf<Pair<suspend () -> Boolean, ListenableWorker.Result>>(
                suspendingAnswer { true } to ListenableWorker.Result.success(),
                suspendingAnswer { false } to ListenableWorker.Result.retry(),
                suspendingAnswer { error("falla de prueba") } to ListenableWorker.Result.failure(),
            )
        for ((elapsed, expected) in cases) {
            val worker =
                TestListenableWorkerBuilder<BackupTriggerWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ) = BackupTriggerWorker(appContext, workerParameters, elapsed, log)
                        },
                    ).build()
            assertEquals(expected, worker.doWork())
        }
        assertEquals(
            listOf(
                "Falla no prevista al programar el respaldo: error=" +
                    IllegalStateException::class.java.name,
            ),
            log.lines,
        )
    }

    @Test
    fun theDailyMaintenanceRunsEveryDayWithoutConditionsAndKeepsItsSchedule() = runBlocking {
        queue.keepDailyMaintenance()
        val first = single(BackupWorkNames.DAILY_MAINTENANCE)
        queue.keepDailyMaintenance()

        val kept = single(BackupWorkNames.DAILY_MAINTENANCE)
        assertEquals(first.id, kept.id)
        assertEquals(24.hours.inWholeMilliseconds, kept.periodicityInfo?.repeatIntervalMillis)
        // Nunca usa la red, y corre también sin cargador.
        assertEquals(NetworkType.NOT_REQUIRED, kept.constraints.requiredNetworkType)
        assertFalse(kept.constraints.requiresCharging())
        assertFalse(kept.constraints.requiresBatteryNotLow())
        assertFalse(kept.constraints.requiresDeviceIdle())
        assertTrue(BackupWorkNames.DAILY_MAINTENANCE in kept.tags)
        // Una restauración no lo detiene: no está entre los trabajos que cancela.
        assertFalse(BackupWorkNames.DAILY_MAINTENANCE in BackupWorkNames.ALL)
    }

    @Test
    fun theMaintenanceWorkerRetriesOnlyWhileTheDatabaseWaitsAndHidesFailures() = runBlocking {
        val log = MemoryLog()
        val cases =
            listOf(
                maintenance(open = { MaintenanceAccess.Unavailable }, log = log) to
                    ListenableWorker.Result.success(),
                maintenance(open = { MaintenanceAccess.Waiting }, log = log) to
                    ListenableWorker.Result.retry(),
                maintenance(open = { error("falla de prueba") }, log = log) to
                    ListenableWorker.Result.success(),
            )
        for ((maintenance, expected) in cases) {
            val worker =
                TestListenableWorkerBuilder<DailyMaintenanceWorker>(context)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ) = DailyMaintenanceWorker(
                                appContext,
                                workerParameters,
                                maintenance,
                                log,
                            )
                        },
                    ).build()
            assertEquals(expected, worker.doWork())
        }
        assertEquals(
            listOf(
                "Falla no prevista del mantenimiento diario: error=" +
                    IllegalStateException::class.java.name,
            ),
            log.lines,
        )
    }

    private fun maintenance(open: suspend () -> MaintenanceAccess, log: MemoryLog) =
        DailyMaintenance(
            clock = Clock { Instant.parse("2026-11-07T15:00:00Z") },
            log = log,
            mutex = Mutex(),
            restorePending = { false },
            open = open,
            reconcile = {},
            notifier = { BackupNotifier.None },
            cleanUp = {},
        )

    private fun suspendingAnswer(answer: () -> Boolean): suspend () -> Boolean = { answer() }

    private suspend fun single(name: String): WorkInfo = manager
        .getWorkInfosForUniqueWorkFlow(name)
        .first()
        .single { !it.state.isFinished }

    /** El único trabajo con ese nombre, en cualquier estado. */
    private fun latest(name: String): WorkInfo =
        manager.getWorkInfosForUniqueWork(name).get().single()

    /** La entrada con que corre [work], tras cumplir su red y su retraso con el controlador. */
    private fun inputAfterRunning(work: WorkInfo): Map<String, Any?> {
        if (!work.state.isFinished) {
            val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            driver.setAllConstraintsMet(work.id)
            driver.setInitialDelayMet(work.id)
        }
        return requireNotNull(DoneFactory.inputs[work.id]).keyValueMap
    }

    private fun engine(run: BackupRun) = object : BackupEngine {
        override suspend fun run(control: TransferControl) = run

        override suspend fun recordUnexpected() = Unit
    }

    /** Un trabajo que termina bien al instante, para lo que la biblioteca de prueba corre. */
    private class Done(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.success()
    }

    /** Crea trabajos que terminan al instante, y anota la entrada de cada uno. */
    private object DoneFactory : WorkerFactory() {
        val inputs = ConcurrentHashMap<UUID, Data>()

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            inputs[workerParameters.id] = workerParameters.inputData
            return Done(appContext, workerParameters)
        }
    }
}
