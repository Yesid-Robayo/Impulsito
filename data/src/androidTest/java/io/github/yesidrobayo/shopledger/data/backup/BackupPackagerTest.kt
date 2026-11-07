package io.github.yesidrobayo.shopledger.data.backup

import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import io.github.yesidrobayo.shopledger.domain.model.backup.MetaValueForm
import io.github.yesidrobayo.shopledger.domain.port.SystemClock
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La copia comprimida en el dispositivo, con el gzip del sistema: en flujo, comprobada antes de
 * borrar la instantánea, con tope de tamaño, y sin dejar temporales salvo la copia de una subida
 * pendiente. Todos los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class BackupPackagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val staging =
        BackupStaging(File(context.noBackupFilesDir, "packager-test"), Dispatchers.IO)
    private val snapshot = staging.snapshotFile(GENERATION)
    private val target = staging.compressedFile(GENERATION)

    @Before
    fun clean() {
        staging.directory.deleteRecursively()
        assertTrue(staging.directory.mkdirs())
    }

    @After
    fun cleanUp() {
        staging.directory.deleteRecursively()
    }

    @Test
    fun theCopyDecompressesIntoTheSnapshotExactly() = runBlocking {
        createSnapshot()
        val original = snapshot.readBytes()

        val result = BackupPackager(Dispatchers.IO).compress(snapshot, target)

        assertTrue("$result", result is PackageResult.Packed)
        val packed = result as PackageResult.Packed
        assertEquals(target, packed.file)
        assertEquals(target.length(), packed.fileSizeBytes)
        assertEquals(sha256(target.readBytes()), packed.fileSha256)
        assertTrue(MetaValueForm.LowerHex(SHA256_HEX).accepts(packed.fileSha256))
        assertEquals(original.size.toLong(), packed.rawSizeBytes)
        // Es gzip, y se abre con cualquier gzip, sin la app.
        assertEquals(GZIP_MAGIC, target.readBytes().take(2).map { it.toInt() and 0xFF })
        assertArrayEquals(original, GZIPInputStream(target.inputStream()).use { it.readBytes() })
        assertTrue("Comprimir no redujo la base", packed.fileSizeBytes < packed.rawSizeBytes)
        // La instantánea se borra solo después de comprobar la copia.
        assertFalse(snapshot.exists())
        assertEquals(listOf(target.name), staging.directory.list()?.toList())
    }

    @Test
    fun gzipWithoutItsEndFailsBeforeUpload() = runBlocking {
        createSnapshot()
        val packager = BackupPackager(Dispatchers.IO, compressor = ::UnfinishedGzip)

        val result = packager.compress(snapshot, target)

        assertEquals(PackageResult.Mismatch, result)
        assertFalse("Quedó el gzip sin cierre", target.exists())
        assertTrue("La instantánea se borró sin una copia buena", snapshot.exists())
        assertLeavesNothingAfterCleanUp()
    }

    @Test
    fun aCopyOverTheLimitIsRefused() = runBlocking {
        snapshot.writeBytes(Random(SEED).nextBytes(INCOMPRESSIBLE_BYTES))
        val packager = BackupPackager(Dispatchers.IO, maxFileBytes = SMALL_LIMIT)

        val result = packager.compress(snapshot, target)

        assertEquals(PackageResult.TooLarge, result)
        assertFalse(target.exists())
        assertTrue(snapshot.exists())
        assertLeavesNothingAfterCleanUp()
    }

    @Test
    fun aCopyJustUnderTheLimitIsPacked() = runBlocking {
        snapshot.writeBytes(Random(SEED).nextBytes(INCOMPRESSIBLE_BYTES))
        val size = gzipSize(snapshot)

        assertEquals(
            PackageResult.TooLarge,
            BackupPackager(Dispatchers.IO, maxFileBytes = size - 1).compress(snapshot, target),
        )
        val result = BackupPackager(Dispatchers.IO, maxFileBytes = size).compress(snapshot, target)

        assertEquals(size, (result as PackageResult.Packed).fileSizeBytes)
    }

    @Test
    fun theLimitIsTheOneTheRestoreAccepts() {
        // 512 MiB: el mismo tope con que la restauración deja de leer.
        assertEquals(536_870_912L, BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES)
    }

    @Test
    fun aLeftoverCopyOfAnInterruptedRunIsReplaced() = runBlocking {
        createSnapshot()
        target.writeText("resto de una copia cortada")

        val result = BackupPackager(Dispatchers.IO).compress(snapshot, target)

        assertEquals(sha256(target.readBytes()), (result as PackageResult.Packed).fileSha256)
    }

    @Test
    fun aMissingSnapshotFailsAndLeavesNothing() = runBlocking {
        val result = BackupPackager(Dispatchers.IO).compress(snapshot, target)

        assertTrue("$result", result is PackageResult.Failed)
        assertEquals(emptyList<String>(), staging.directory.list()?.toList())
    }

    @Test
    fun cancellingLeavesNoCopy() = runBlocking {
        writeLargeSnapshot()

        val job =
            async(Dispatchers.Default) { BackupPackager(Dispatchers.IO).compress(snapshot, target) }
        delay(CANCEL_AFTER_MILLIS)
        job.cancel()
        try {
            job.await()
            fail("La compresión terminó antes de cancelarla: la prueba no prueba nada")
        } catch (_: CancellationException) {
            // Lo esperado.
        }

        assertFalse("Quedó una copia a medias", target.exists())
        assertTrue(snapshot.exists())
    }

    @Test
    fun aLargeBaseIsPackedWithoutLoadingItInMemory() = runBlocking {
        writeLargeSnapshot()
        val runtime = Runtime.getRuntime()
        System.gc()
        val baseline = runtime.totalMemory() - runtime.freeMemory()
        val peak = AtomicLong(baseline)
        val sampler =
            async(Dispatchers.Default) {
                while (true) {
                    peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                    delay(SAMPLE_MILLIS)
                }
            }

        val result = BackupPackager(Dispatchers.IO).compress(snapshot, target)
        sampler.cancel()

        assertEquals(LARGE_BYTES, (result as PackageResult.Packed).rawSizeBytes)
        val grown = peak.get() - baseline
        // Solo cifras: cuánto creció el montón.
        Log.i(TAG, "montón: creció $grown bytes con una base de $LARGE_BYTES")
        assertTrue(
            "El montón creció $grown bytes con una base de $LARGE_BYTES",
            grown < MAX_HEAP_GROWTH_BYTES,
        )
    }

    @Test
    fun aPackedCopyIsKeptForItsPendingUpload() = runBlocking {
        createSnapshot()
        val result = BackupPackager(Dispatchers.IO).compress(snapshot, target)
        assertTrue("$result", result is PackageResult.Packed)
        staging.snapshotFile(GENERATION + 1).writeText("instantánea de una ejecución cortada")

        staging.cleanUp(GENERATION, SystemClock.now())

        assertEquals(listOf(target.name), staging.directory.list()?.toList())
    }

    /** Una instantánea real: una base sin Room, con una tabla de filas inventadas. */
    private suspend fun createSnapshot() {
        val source = File(staging.directory, "source.db")
        BundledSQLiteDriver()
            .open(source.path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
            .use { connection ->
                connection.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT NOT NULL)")
                connection.execSQL(
                    "WITH RECURSIVE n(x) AS " +
                        "(SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < $ROWS) " +
                        "INSERT INTO t SELECT x, 'fila ' || x FROM n",
                )
            }
        val created = SnapshotCreator(source, Dispatchers.IO).create(snapshot)
        assertTrue("$created", created is SnapshotResult.Created)
        source.delete()
        assertEquals(ROWS, inspectWithoutRoom(snapshot) { it.longValue("SELECT COUNT(*) FROM t") })
    }

    /** Una base grande, que se comprime bien: bloques con una parte al azar y el resto en cero. */
    private fun writeLargeSnapshot() {
        val random = Random(SEED)
        snapshot.outputStream().buffered().use { output ->
            repeat((LARGE_BYTES / BLOCK_BYTES).toInt()) {
                val block = ByteArray(BLOCK_BYTES)
                random.nextBytes(block, 0, BLOCK_BYTES / 16)
                output.write(block)
            }
        }
        assertEquals(LARGE_BYTES, snapshot.length())
    }

    private fun gzipSize(file: File): Long {
        val counter = CountingOutput()
        GZIPOutputStream(counter, BUFFER_BYTES).use { gzip -> file.inputStream().copyTo(gzip) }
        return counter.bytes
    }

    private suspend fun assertLeavesNothingAfterCleanUp() {
        staging.cleanUp(pendingGeneration = null, now = SystemClock.now())
        assertEquals(emptyList<String>(), staging.directory.list()?.toList())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    /** Un gzip que nunca escribe su cierre: el que deja un proceso que murió al final. */
    private class UnfinishedGzip(output: OutputStream) : GZIPOutputStream(output, BUFFER_BYTES) {
        override fun finish() = Unit
    }

    private class CountingOutput : OutputStream() {
        var bytes = 0L

        override fun write(b: Int) {
            bytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bytes += len
        }
    }

    private companion object {
        const val TAG = "PackagerTest"
        const val GENERATION = 42L
        const val ROWS = 5_000L
        const val SEED = 20_261_107
        const val SHA256_HEX = 64
        val GZIP_MAGIC = listOf(0x1F, 0x8B)
        const val BUFFER_BYTES = 64 * 1024
        const val INCOMPRESSIBLE_BYTES = 256 * 1024
        const val SMALL_LIMIT = 64 * 1024L
        const val BLOCK_BYTES = 1024 * 1024
        const val LARGE_BYTES = 96L * 1024 * 1024
        const val MAX_HEAP_GROWTH_BYTES = 24L * 1024 * 1024
        const val SAMPLE_MILLIS = 5L
        const val CANCEL_AFTER_MILLIS = 50L
    }
}
