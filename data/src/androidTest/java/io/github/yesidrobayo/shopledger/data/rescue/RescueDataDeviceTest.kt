package io.github.yesidrobayo.shopledger.data.rescue

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.OpeningStep
import io.github.yesidrobayo.shopledger.data.RescueCause
import io.github.yesidrobayo.shopledger.data.WorkAccess
import io.github.yesidrobayo.shopledger.data.backup.RestoreFiles
import io.github.yesidrobayo.shopledger.data.destination.DestinationLink
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.PreMigrationCopy
import io.github.yesidrobayo.shopledger.data.local.PreMigrationResult
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.transaction.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El rescate en el teléfono, sin abrir la base: el destino sale de `link.json`, restaurar sin
 * destino no toca nada, la base dañada apartada se empaqueta sin cambiarla, y la pantalla sabe
 * cuándo la compuerta copia y migra. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class RescueDataDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "rescue-data-test.db")
    private val noBackup = File(context.noBackupFilesDir, "rescue-data-test")
    private val files = DatabaseFiles(file, noBackup)
    private val restoreFiles = RestoreFiles(files)
    private val exported = File(context.cacheDir, "rescue-data-test.zip")

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
        noBackup.deleteRecursively()
        exported.delete()
    }

    @After
    fun cleanUp() = clean()

    @Test
    fun theSavedDestinationComesFromTheLinkWithoutOpeningTheDamagedDatabase() = runBlocking {
        val damaged = damageDatabase()
        DestinationLinkStore({ noBackup }, Dispatchers.IO).write(
            DestinationLink(
                kind = BackupDestinationKind.WEBDAV,
                account = "cuenta-de-prueba",
                destinationId = "a".repeat(64),
                folderUrl = "https://dav.example.com/copias/",
                connectedAtMillis = FIXED_CLOCK.nowEpochMillis(),
            ),
        )
        val graph = graph()

        val rescue = graph.rescue
        assertEquals(BackupDestinationKind.WEBDAV, rescue.savedDestinationKind())
        assertEquals(
            BackupDestinationInfo.WebDav("cuenta-de-prueba", "dav.example.com"),
            rescue.destination.describe(),
        )

        assertArrayEquals(damaged, file.readBytes())
        assertDatabaseUntouched()
    }

    @Test
    fun withoutALinkThereIsNoDestinationAndRestoringACopyTouchesNothing() = runBlocking {
        val damaged = damageDatabase()
        val rescue = graph().rescue

        assertEquals(null, rescue.savedDestinationKind())
        assertEquals(null, rescue.destination.describe())
        assertEquals(
            RestoreProgress.Refused(RestoreProblem.NOT_CONNECTED),
            rescue.restore.restoreCopy("copia-que-no-existe"),
        )

        assertArrayEquals(damaged, file.readBytes())
        assertDatabaseUntouched()
        assertFalse(restoreFiles.lock.exists())
    }

    @Test
    fun theNewestDamagedOriginalIsPackedWithItsSideFilesAndLeftAsItWas() = runBlocking {
        val older = File(restoreFiles.damagedDirectory, "1000").apply { mkdirs() }
        File(older, "shopledger.db").writeBytes("base vieja de prueba".toByteArray())
        val newer = File(restoreFiles.damagedDirectory, "2000").apply { mkdirs() }
        val database = "base dañada de prueba ".repeat(500).toByteArray()
        val wal = "diario de prueba".toByteArray()
        File(newer, "shopledger.db").writeBytes(database)
        File(newer, "shopledger.db-wal").writeBytes(wal)
        val rescue = graph().rescue

        assertTrue(rescue.hasDamagedOriginal())
        assertTrue(rescue.exportDamagedOriginal(exported))

        val entries = unzip(exported)
        assertEquals(listOf("shopledger.db", "shopledger.db-wal"), entries.keys.toList())
        assertArrayEquals(database, entries.getValue("shopledger.db"))
        assertArrayEquals(wal, entries.getValue("shopledger.db-wal"))
        // Solo se leyó: las dos carpetas siguen como estaban, y no quedó ningún temporal.
        assertArrayEquals(database, File(newer, "shopledger.db").readBytes())
        assertTrue(File(older, "shopledger.db").isFile)
        assertFalse(File(exported.path + ".tmp").exists())
    }

    @Test
    fun withoutADamagedOriginalNothingIsPackedAndNothingIsLeft() = runBlocking {
        val rescue = graph().rescue

        assertFalse(rescue.hasDamagedOriginal())
        assertFalse(rescue.exportDamagedOriginal(exported))
        assertFalse(exported.exists())
        assertFalse(File(exported.path + ".tmp").exists())
    }

    @Test
    fun theScreenKnowsWhenTheAppCopiesAndMigratesAndAWorkerNeverShowsIt() = runBlocking {
        AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK).close()
        files.markInitialized()
        val copyStarted = CompletableDeferred<Unit>()
        val releaseCopy = CompletableDeferred<Unit>()
        // La app de la prueba compila una versión más: la base quedó en la anterior.
        val graph =
            DataGraph(
                context,
                Dispatchers.IO,
                FIXED_CLOCK,
                { files },
                SilentLog,
                schemaVersion = AppDatabase.SCHEMA_VERSION + 1,
                preMigrationCopy = {
                    PreMigrationCopy {
                        copyStarted.complete(Unit)
                        releaseCopy.await()
                        PreMigrationResult.Ready
                    }
                },
            )

        assertEquals(WorkAccess.MigrationPending, graph.openForWork())
        assertEquals(OpeningStep.OPENING, graph.opening.value)

        val app = async(Dispatchers.Default) { graph.open() }
        withTimeout(WAIT_MILLIS) { copyStarted.await() }
        assertEquals(OpeningStep.MIGRATING, graph.opening.value)

        releaseCopy.complete(Unit)
        assertTrue(withTimeout(WAIT_MILLIS) { app.await() } is DataAccess.Ready)
        assertEquals(OpeningStep.OPENING, graph.opening.value)
    }

    @Test
    fun aDamagedDatabaseLeavesTheOpeningStepAtRest() = runBlocking {
        damageDatabase()
        val graph = graph()

        assertEquals(DataAccess.Rescue(RescueCause.OpenFailed), graph.open())
        assertEquals(OpeningStep.OPENING, graph.opening.value)
    }

    private fun graph() = DataGraph(context, Dispatchers.IO, FIXED_CLOCK, { files }, SilentLog)

    /** Una base que ya existió y hoy no es una base: la que lleva al rescate. */
    private fun damageDatabase(): ByteArray {
        val damaged = "esto no es una base de datos de prueba ".repeat(200).toByteArray()
        file.parentFile?.mkdirs()
        file.writeBytes(damaged)
        files.markInitialized()
        return damaged
    }

    /** Nada abrió la base: SQLite no dejó a su lado ningún archivo. */
    private fun assertDatabaseUntouched() {
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            assertFalse(File(file.path + suffix).exists())
        }
    }

    private fun unzip(zip: File): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                entries[entry.name] = stream.readBytes()
            }
        }
        return entries
    }

    private object SilentLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
