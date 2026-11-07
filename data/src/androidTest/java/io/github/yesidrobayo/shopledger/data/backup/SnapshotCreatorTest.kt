package io.github.yesidrobayo.shopledger.data.backup

import android.util.Log
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.seed.SPANISH_ROW
import io.github.yesidrobayo.shopledger.data.seed.SeedDataGenerator
import io.github.yesidrobayo.shopledger.data.seed.SeedInvariants
import io.github.yesidrobayo.shopledger.data.seed.SeedProfile
import io.github.yesidrobayo.shopledger.data.seed.SeedSaleWriter
import io.github.yesidrobayo.shopledger.data.seed.assertInvariantsHold
import io.github.yesidrobayo.shopledger.data.seed.seedConfigOf
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La instantánea de la base en el dispositivo, con el SQLite empaquetado de la app: consistente
 * mientras se registran ventas, sin crear nunca una base, y sin dejar nada a medias si falla.
 * Todos los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotCreatorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = testDatabaseFile(context, "snapshot-source.db")
    private val staging =
        BackupStaging(File(context.noBackupFilesDir, "snapshot-test"), Dispatchers.IO)

    @Before
    fun clean() {
        deleteDatabaseFiles(database)
        staging.directory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        clean()
    }

    @Test
    fun snapshotIsConsistentWhileSalesAreWritten() = runBlocking {
        val config = requireNotNull(seedConfigOf(SPANISH_ROW))
        val report =
            SeedDataGenerator(SeedDataGenerator.DEFAULT_SEED, SeedProfile.TYPICAL, config)
                .generate(context, database)
        assertInvariantsHold("base generada", report)
        val creator = SnapshotCreator(database, Dispatchers.IO)
        val live = AppDatabase.open(context, database, Dispatchers.IO)
        try {
            val ports = DataPorts(live, database, Dispatchers.IO, SystemClock)
            val sales = SeedSaleWriter.on(live, ports, config)
            val copying = AtomicBoolean(false)
            val writing = AtomicBoolean(true)
            val salesDuringCopies = AtomicInteger()
            // Las ventas se escriben sin pausa, en otro hilo, mientras se hacen las copias.
            val writer =
                async(Dispatchers.IO) {
                    while (isActive && writing.get()) {
                        val duringCopy = copying.get()
                        sales.write()
                        if (duringCopy && copying.get()) salesDuringCopies.incrementAndGet()
                    }
                }

            for (generation in 1L..COPY_ROUNDS) {
                val before = live.salesCount()
                copying.set(true)
                val result = creator.create(staging.snapshotFile(generation))
                copying.set(false)
                val after = live.salesCount()
                val case = "copia $generation"

                assertTrue("$case: $result", result is SnapshotResult.Created)
                val snapshot = (result as SnapshotResult.Created).file
                assertEquals(case, snapshot.length(), result.sizeBytes)
                assertEquals(case, IntegrityResult.Intact, creator.checkIntegrity(snapshot))
                // Un solo archivo, sin diario aparte, aunque la base esté en WAL.
                assertEquals(
                    case,
                    listOf("delete"),
                    inspectWithoutRoom(snapshot) { it.texts("PRAGMA journal_mode") },
                )
                // La foto es de un instante entre el comienzo y el final de la copia: ninguna
                // venta a medias, y las que llegaron durante la copia quedan para la siguiente.
                val copied = inspectWithoutRoom(snapshot) { it.longValue(SALES_COUNT) }
                assertTrue("$case: $copied ventas", copied in before..after)
                assertInvariants(case, snapshot, config.zone)
            }
            writing.set(false)
            writer.await()

            // Solo cifras: cuántas ventas cupieron enteras dentro de una copia.
            Log.i(TAG, "ventas durante las copias: ${salesDuringCopies.get()}")
            assertTrue(
                "Ninguna venta se escribió durante una copia: la prueba no prueba nada",
                salesDuringCopies.get() > 0,
            )
            assertTrue(
                "No se escribió ninguna venta",
                live.salesCount() > report.rowCounts.getValue("sales"),
            )
            assertEquals(
                "La base activa quedó con invariantes rotos",
                emptyMap<String, Long>(),
                SeedInvariants.check(live, config.zone).filterValues { it != 0L },
            )
        } finally {
            live.close()
        }
    }

    @Test
    fun missingDatabaseIsNeverCreated() = runBlocking {
        val target = staging.snapshotFile(1)

        val result = SnapshotCreator(database, Dispatchers.IO).create(target)

        assertEquals(SnapshotResult.DatabaseMissing, result)
        assertFalse("Se creó una base vacía", database.exists())
        assertFalse(target.exists())
    }

    @Test
    fun leftoverOutputOfAnInterruptedRunIsReplaced() = runBlocking {
        createPlainDatabase(database)
        val target = staging.snapshotFile(7)
        assertTrue(staging.directory.mkdirs())
        target.writeText("resto de una copia cortada")
        File(target.path + "-journal").writeText("diario de una copia cortada")
        val creator = SnapshotCreator(database, Dispatchers.IO)

        val result = creator.create(target)

        assertEquals(SnapshotResult.Created(target, target.length()), result)
        assertEquals(IntegrityResult.Intact, creator.checkIntegrity(target))
        assertEquals(ROWS, inspectWithoutRoom(target) { it.longValue("SELECT COUNT(*) FROM t") })
        assertEquals(listOf(target.name), staging.directory.list()?.toList())
    }

    @Test
    fun damagedDatabaseIsCorruptAndLeavesNothing() = runBlocking {
        createPlainDatabase(database)
        damagePages(database)
        val target = staging.snapshotFile(1)

        val result = SnapshotCreator(database, Dispatchers.IO).create(target)

        assertEquals(SnapshotResult.Corrupt, result)
        assertStagingEmpty()
    }

    @Test
    fun fileThatIsNotADatabaseIsCorrupt() = runBlocking {
        database.writeBytes(ByteArray(PAGE_SIZE) { 'x'.code.toByte() })
        val target = staging.snapshotFile(1)

        val result = SnapshotCreator(database, Dispatchers.IO).create(target)

        assertEquals(SnapshotResult.Corrupt, result)
        assertStagingEmpty()
    }

    @Test
    fun unwritableOutputFailsAndLeavesNothing() = runBlocking {
        createPlainDatabase(database)
        // La carpeta de la salida es un archivo: SQLite no puede abrir la salida.
        staging.directory.parentFile?.mkdirs()
        staging.directory.writeText("no es una carpeta")
        val target = staging.snapshotFile(1)

        val result = SnapshotCreator(database, Dispatchers.IO).create(target)

        assertTrue("$result", result is SnapshotResult.Failed)
        assertFalse(target.exists())
    }

    @Test
    fun integrityCheckFindsADamagedSnapshot() = runBlocking {
        createPlainDatabase(database)
        val creator = SnapshotCreator(database, Dispatchers.IO)
        val target = staging.snapshotFile(1)
        assertTrue(creator.create(target) is SnapshotResult.Created)

        damagePages(target)

        assertEquals(IntegrityResult.Damaged, creator.checkIntegrity(target))
    }

    @Test
    fun integrityCheckOfSomethingElseIsDamaged() = runBlocking {
        assertTrue(staging.directory.mkdirs())
        val target = staging.snapshotFile(1)
        target.writeBytes(ByteArray(PAGE_SIZE) { 'x'.code.toByte() })

        assertEquals(
            IntegrityResult.Damaged,
            SnapshotCreator(database, Dispatchers.IO).checkIntegrity(target),
        )
    }

    @Test
    fun integrityCheckOfAMissingFileFails() = runBlocking {
        val target = staging.snapshotFile(1)

        val result = SnapshotCreator(database, Dispatchers.IO).checkIntegrity(target)

        assertTrue("$result", result is IntegrityResult.Failed)
        assertFalse("Se creó un archivo al comprobar", target.exists())
    }

    private suspend fun AppDatabase.salesCount(): Long = useReaderConnection { connection ->
        connection.usePrepared(SALES_COUNT) { statement ->
            check(statement.step())
            statement.getLong(0)
        }
    }

    /** Corre sobre la instantánea las mismas comprobaciones que sobre la base generada. */
    private suspend fun assertInvariants(case: String, snapshot: File, zone: BusinessTimeZone) {
        val copy = AppDatabase.open(context, snapshot, Dispatchers.IO)
        try {
            assertEquals(
                "$case: invariantes rotos",
                emptyMap<String, Long>(),
                SeedInvariants.check(copy, zone).filterValues { it != 0L },
            )
        } finally {
            copy.close()
        }
    }

    private fun assertStagingEmpty() {
        assertEquals(emptyList<String>(), staging.directory.list()?.toList().orEmpty())
    }

    /** Una base sin Room, con una tabla de [ROWS] filas inventadas que ocupa varias páginas. */
    private fun createPlainDatabase(file: File) {
        BundledSQLiteDriver()
            .open(file.path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
            .use { connection ->
                connection.execSQL("PRAGMA page_size = $PAGE_SIZE")
                connection.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT NOT NULL)")
                connection.execSQL(
                    "WITH RECURSIVE n(x) AS " +
                        "(SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < $ROWS) " +
                        "INSERT INTO t SELECT x, hex(randomblob(100)) FROM n",
                )
            }
    }

    /** Pisa varias páginas de la tabla con bytes que no son una página de SQLite. */
    private fun damagePages(file: File) {
        RandomAccessFile(file, "rw").use { raw ->
            raw.seek(PAGE_SIZE.toLong() * 2)
            raw.write(ByteArray(PAGE_SIZE * DAMAGED_PAGES) { 0xFF.toByte() })
            raw.fd.sync()
        }
    }

    private companion object {
        const val TAG = "SnapshotTest"
        const val COPY_ROUNDS = 5L
        const val ROWS = 2_000L
        const val PAGE_SIZE = 4_096
        const val DAMAGED_PAGES = 4
        const val SALES_COUNT = "SELECT COUNT(*) FROM sales"
    }
}
