package io.github.yesidrobayo.shopledger.data.work

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.backup.BackupEngine
import io.github.yesidrobayo.shopledger.data.backup.BackupRun
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.backup.RestoreMarkers
import io.github.yesidrobayo.shopledger.data.backup.RetryReason
import io.github.yesidrobayo.shopledger.data.backup.SkipReason
import io.github.yesidrobayo.shopledger.data.backup.StopCause
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.fileUserVersion
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.log.LogSentinels
import io.github.yesidrobayo.shopledger.data.transaction.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El trabajo de respaldo, corrido por la biblioteca de trabajos con su fábrica: cómo traduce cada
 * final de una ejecución, que nunca abre la base mientras una migración o una restauración esperan,
 * y que ninguna excepción sale con su mensaje.
 */
@RunWith(AndroidJUnit4::class)
class BackupWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "backup-worker-test.db")
    private val noBackup = File(context.noBackupFilesDir, "backup-worker-test")
    private val log = MemoryLog()

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
        noBackup.deleteRecursively()
    }

    @After
    fun cleanUp() {
        deleteDatabaseFiles(file)
        noBackup.deleteRecursively()
    }

    @Test
    fun aDatabaseThatMustMigrateIsRetriedWithoutOpeningRoom() = runBlocking {
        writeDatabaseOfVersion(AppDatabase.SCHEMA_VERSION)
        // La app ya está en la versión siguiente: la base debe migrar, y solo la app migra.
        val graph = graph(schemaVersion = AppDatabase.SCHEMA_VERSION + 1)

        val result = worker(graph.workerFactory).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        // Room no la abrió: sigue en su versión, sin tablas, sin marca y sin copia previa.
        assertEquals(AppDatabase.SCHEMA_VERSION, fileUserVersion(file))
        assertFalse(File(noBackup, DatabaseFiles.INITIALIZED_MARKER_NAME).exists())
        assertFalse(File(noBackup, DatabaseFiles.PRE_MIGRATION_DIRECTORY_NAME).exists())
        assertTrue(log.lines.any { it.startsWith("Migración pendiente, que espera a la app") })
        assertTrue(log.lines.any { it == "Respaldo para más tarde: motivo=MIGRATION_PENDING" })
    }

    @Test
    fun aRestoreInProgressIsRetriedWithoutOpeningTheDatabase() = runBlocking {
        val markers =
            listOf(RestoreMarkers.IN_PROGRESS, RestoreMarkers.JOURNAL).map {
                File(File(noBackup, RestoreMarkers.RESTORE_DIRECTORY), it)
            }
        for (marker in markers) {
            noBackup.deleteRecursively()
            requireNotNull(marker.parentFile).mkdirs()
            marker.writeText("")
            val graph = graph(markers = noBackup)

            val result = worker(graph.workerFactory).doWork()

            assertEquals(marker.name, ListenableWorker.Result.retry(), result)
            assertFalse(marker.name, file.exists())
        }
    }

    @Test
    fun eachEndOfARunBecomesItsResult() = runBlocking {
        val cases =
            listOf(
                BackupRun.Verified(3) to ListenableWorker.Result.success(),
                BackupRun.Skipped(SkipReason.NOTHING_PENDING) to ListenableWorker.Result.success(),
                BackupRun.Retry(RetryReason.INCOMPLETE_LISTING) to ListenableWorker.Result.retry(),
                BackupRun.Stopped(StopCause.PermissionLost) to ListenableWorker.Result.failure(),
                BackupRun.Stopped(StopCause.Failure(BackupFailureCode.QUOTA_EXCEEDED)) to
                    ListenableWorker.Result.failure(),
            )
        for ((run, expected) in cases) {
            assertEquals(run.toString(), expected, worker(factory(FixedEngine { run })).doWork())
        }
    }

    @Test
    fun anUnexpectedFailureStopsTheWorkWithItsClassOnly() = runBlocking {
        val failures =
            listOf(
                IllegalStateException(
                    LogSentinels.leakyMessage,
                    RuntimeException(LogSentinels.leakyMessage),
                ),
                StackOverflowError(LogSentinels.leakyMessage),
            )
        for (failure in failures) {
            val engine = FixedEngine { throw failure }

            assertEquals(ListenableWorker.Result.failure(), worker(factory(engine)).doWork())
            assertTrue(engine.recordedUnexpected)
        }
        assertEquals(
            listOf(
                "Falla no prevista del trabajo de respaldo: error=" +
                    IllegalStateException::class.java.name,
                "Falla no prevista del trabajo de respaldo: error=" +
                    StackOverflowError::class.java.name,
            ),
            log.lines,
        )
        for (line in log.lines) {
            LogSentinels.all.forEach { assertFalse(line, line.contains(it)) }
        }
    }

    @Test
    fun aCancellationIsNotSwallowed() = runBlocking {
        val worker = worker(factory(FixedEngine { throw CancellationException("parar") }))
        try {
            worker.doWork()
            fail("La cancelación debía seguir su camino")
        } catch (expected: CancellationException) {
            assertEquals(emptyList<String>(), log.lines)
        }
    }

    @Test
    fun theRunGetsTheStopRequestAndTheTimeLeftFromTheWorker() = runBlocking {
        var seen: TransferControl? = null
        val engine = FixedEngine { control ->
            seen = control
            BackupRun.Skipped(SkipReason.NOTHING_PENDING)
        }
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
                            engine,
                            log,
                            { true },
                            budget = 1.milliseconds,
                        )
                    },
                ).build()

        worker.doWork()

        val control = requireNotNull(seen)
        assertFalse(control.isStopped())
        assertTrue(control.isMetered())
        assertFalse(control.meteredStop())
        Thread.sleep(5)
        assertEquals(0L, control.remainingMillis())
        assertEquals(8 * 60 * 1000L, BackupWorker.BACKUP_TIME_BUDGET.inWholeMilliseconds)
    }

    @Test
    fun theFactoryLeavesOtherWorkersToTheDefaultOne() {
        val factory = DataWorkerFactory(FixedEngine { BackupRun.Verified(1) }, log)
        var asked = false
        var other: ListenableWorker? = null
        val worker =
            TestListenableWorkerBuilder<BackupWorker>(context)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? {
                            asked = true
                            other =
                                factory.createWorker(appContext, "otro.Trabajo", workerParameters)
                            return factory.createWorker(
                                appContext,
                                workerClassName,
                                workerParameters,
                            )
                        }
                    },
                ).build()

        assertTrue(worker is BackupWorker)
        assertTrue(asked)
        assertEquals(null, other)
    }

    private fun worker(factory: WorkerFactory): BackupWorker =
        TestListenableWorkerBuilder<BackupWorker>(context).setWorkerFactory(factory).build()

    private fun factory(engine: BackupEngine) = DataWorkerFactory(engine, log)

    private fun graph(
        schemaVersion: Int = AppDatabase.SCHEMA_VERSION,
        markers: File = noBackup,
    ): DataGraph = DataGraph(
        context = context,
        ioContext = Dispatchers.IO,
        clock = FIXED_CLOCK,
        files = { DatabaseFiles(file, markers) },
        log = log,
        schemaVersion = schemaVersion,
    )

    /** Una base de la app en [version], sin Room, como la dejó una versión anterior. */
    private fun writeDatabaseOfVersion(version: Int) {
        BundledSQLiteDriver().open(file.path).use { connection ->
            connection.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY)")
            connection.execSQL("PRAGMA user_version = $version")
        }
    }

    /** Un motor que hace lo que la prueba le pide. */
    private class FixedEngine(private val block: suspend (TransferControl) -> BackupRun) :
        BackupEngine {
        var recordedUnexpected = false
            private set

        override suspend fun run(control: TransferControl): BackupRun = block(control)

        override suspend fun recordUnexpected() {
            recordedUnexpected = true
        }
    }
}
