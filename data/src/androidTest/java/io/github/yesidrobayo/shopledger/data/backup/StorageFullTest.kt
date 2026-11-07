package io.github.yesidrobayo.shopledger.data.backup

import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un disco que se llena de verdad mientras se arma la copia: SQLite da `SQLITE_FULL` al escribir la
 * instantánea, gzip da `ENOSPC` al escribir el archivo, y el motor lo deja a la vista como falta de
 * espacio, sin subir nada y sin dejar restos.
 *
 * El disco del emulador lo comparten otros procesos, así que no se llena: se llena una tarjeta SD
 * de 512 MB, con la carpeta propia de la app que el sistema da en ella. En el emulador se agrega con
 * `sm set-virtual-disk true` y `sm partition <disco> public`, y se quita al terminar con
 * `sm set-virtual-disk false`. Sin tarjeta, o si la tarjeta no informa que se llenó, la prueba no
 * corre. Tampoco desde Android 11, donde la tarjeta pasa por FUSE.
 */
@RunWith(AndroidJUnit4::class)
class StorageFullTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "storage-full-test")
    private var card: File? = null

    @Before
    fun open() = runBlocking {
        val removable =
            context.getExternalFilesDirs(null).drop(1).filterNotNull().firstOrNull {
                Environment.isExternalStorageRemovable(it) &&
                    Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
            }
        assumeTrue("Sin tarjeta SD emulada", removable != null)
        // Desde Android 11 la tarjeta pasa por FUSE, y un disco lleno llega a SQLite como un error
        // de entrada y salida, no como disco lleno: no es el disco de la app, que no pasa por FUSE.
        assumeTrue(
            "La tarjeta pasa por FUSE",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
        )
        val folder = File(requireNotNull(removable), "storage-full-test")
        folder.deleteRecursively()
        folder.mkdirs()
        card = folder
        fixture.open()
        fixture.connect()
        fixture.addProduct("Pan de prueba")
        // La carpeta de preparación ya existe, como en la app: en un disco lleno no se podría crear.
        File(folder, "staging").mkdirs()
        // La base debe pesar más que lo que queda libre en la tarjeta.
        fill(File(folder, "relleno.bin"))
    }

    @After
    fun close() {
        card?.deleteRecursively()
        fixture.close()
    }

    @Test
    fun aFullDiskWhileWritingTheSnapshotIsLowStorage() = runBlocking {
        val target = File(requireNotNull(card), "snapshot.db")

        val result = SnapshotCreator(fixture.file, Dispatchers.IO).create(target)

        assertEquals(SnapshotResult.StorageFull, result)
        assertFalse(target.exists())
    }

    @Test
    fun aFullDiskWhileWritingTheArchiveIsLowStorage() = runBlocking {
        val snapshot = File(fixture.stagingDir, "snapshot.db")
        fixture.stagingDir.mkdirs()
        assertTrue(
            SnapshotCreator(
                fixture.file,
                Dispatchers.IO,
            ).create(snapshot) is SnapshotResult.Created,
        )
        // Sin compresión, el archivo pesa lo mismo que la base: no cabe en lo que queda libre.
        val target = File(requireNotNull(card), "snapshot.db.gz")
        val packager =
            BackupPackager(Dispatchers.IO) {
                object : GZIPOutputStream(it, 64 * 1024) {
                    init {
                        def.setLevel(Deflater.NO_COMPRESSION)
                    }
                }
            }

        val result = packager.compress(snapshot, target)

        assertEquals(PackageResult.StorageFull, result)
        assertFalse(target.exists())
        // La instantánea queda: la limpieza de la carpeta de preparación la borra.
        assertEquals(true, snapshot.isFile)
    }

    @Test
    fun theEngineShowsLowStorageAndUploadsNothing() = runBlocking {
        val destination = FakeBackupDestination()
        // El aviso de espacio del paso 1 se salta a propósito: así la falta de espacio llega a
        // SQLite, como cuando el disco se llena entre el aviso y la copia.
        val runner =
            fixture.runner(
                destination,
                freeSpace = { Long.MAX_VALUE },
                staging = BackupStaging(File(requireNotNull(card), "staging"), Dispatchers.IO),
            )

        val run = runner.run(TestControl())

        assertEquals(BackupRun.Stopped(StopCause.Failure(BackupFailureCode.LOCAL_STORAGE_LOW)), run)
        assertEquals("LOCAL_STORAGE_LOW", fixture.state().failureCode)
        assertFalse(destination.calls.contains(Operation.UPLOAD))
        assertEquals(
            emptyList<String>(),
            File(requireNotNull(card), "staging").list()?.toList().orEmpty(),
        )
    }

    /**
     * Escribe en [filler] hasta que el disco se llena, y deja libres menos de 16 KiB. Nunca escribe
     * más que la capacidad de la tarjeta: en API 37 la tarjeta virtual pasa por FUSE y una escritura
     * de más puede no fallar. Si al final el sistema no dice que está llena, la prueba no corre.
     */
    private fun fill(filler: File) {
        val folder = requireNotNull(filler.parentFile)
        val limit = StatFs(folder.path).totalBytes + EXTRA_BYTES
        var written = 0L
        FileOutputStream(filler).use { out ->
            for (size in listOf(1024 * 1024, 4096)) {
                val chunk = ByteArray(size)
                try {
                    while (written < limit) {
                        out.write(chunk)
                        written += size
                    }
                } catch (_: IOException) {
                    // Lleno con este tamaño: sigue con uno menor, hasta el último bloque.
                }
            }
            try {
                out.fd.sync()
            } catch (_: IOException) {
                // Lo que no cupo no llega al disco: la tarjeta quedó llena igual.
            }
        }
        assumeTrue(
            "La tarjeta no informa que está llena",
            StatFs(folder.path).availableBytes < FULL_BELOW_BYTES,
        )
    }

    private companion object {
        const val EXTRA_BYTES = 64L * 1024 * 1024
        const val FULL_BELOW_BYTES = 1024L * 1024
    }
}
