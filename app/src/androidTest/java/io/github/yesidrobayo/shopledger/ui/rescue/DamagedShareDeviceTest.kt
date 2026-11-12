package io.github.yesidrobayo.shopledger.ui.rescue

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compartir la base dañada por el proveedor de archivos, en el teléfono: solo sale lo que está en
 * `cache/exports/`, con permiso solo de lectura, y esa carpeta no conserva nada. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class DamagedShareDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = ExportFolder.authority(context)

    @Before
    fun clean() = ExportFolder.clear(context)

    @After
    fun cleanUp() = ExportFolder.clear(context)

    @Test
    fun thePackageIsSharedReadOnlyAndTheFolderIsEmptiedAfterwards() = runBlocking {
        val uri =
            exportDamaged(context) { target ->
                target.writeBytes("paquete de prueba".toByteArray())
                true
            }

        assertNotNull(uri)
        val shared = requireNotNull(uri)
        assertEquals(authority, shared.authority)
        assertEquals(
            "paquete de prueba",
            context.contentResolver.openInputStream(shared)?.use { String(it.readBytes()) },
        )
        val chooser = damagedShareIntent(shared)
        val send =
            requireNotNull(chooser.getParcelableExtraCompat()) { "El selector lleva el envío" }
        assertEquals(Intent.ACTION_SEND, send.action)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        // Al volver del selector, la carpeta se vacía: no queda ninguna copia de la base.
        ExportFolder.clear(context)
        assertTrue(ExportFolder.of(context).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFailedPackLeavesNothingInTheFolder() = runBlocking {
        val uri =
            exportDamaged(context) { target ->
                target.writeBytes("a medias".toByteArray())
                throw IOException("disco de prueba lleno")
            }

        assertNull(uri)
        assertTrue(ExportFolder.of(context).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun withoutADamagedOriginalNothingIsShared() = runBlocking {
        assertNull(exportDamaged(context) { false })
        assertTrue(ExportFolder.of(context).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theProviderSharesNothingOutsideTheExportsFolder() {
        val outside =
            listOf(
                File(context.filesDir, "media/foto.webp"),
                File(context.noBackupFilesDir, "link.json"),
                context.getDatabasePath("shopledger.db"),
                File(context.cacheDir, "otra/archivo.zip"),
                File(context.cacheDir, "exports-falsa/archivo.zip"),
            )
        for (file in outside) {
            try {
                FileProvider.getUriForFile(context, authority, file)
                fail("El proveedor compartió un archivo fuera de cache/exports/")
            } catch (_: IllegalArgumentException) {
                // Esperado: ese camino no está en el proveedor.
            }
        }
        val inside = File(ExportFolder.of(context), ExportFolder.DAMAGED_FILE_NAME)
        assertEquals(authority, FileProvider.getUriForFile(context, authority, inside).authority)
    }

    @Test
    fun aDirectionThatClimbsOutOfTheFolderIsNotServed() {
        val climbing = Uri.parse("content://$authority/exports/../../databases/shopledger.db")
        try {
            context.contentResolver.openInputStream(climbing)?.close()
            fail("El proveedor sirvió un archivo fuera de cache/exports/")
        } catch (_: IllegalArgumentException) {
            // Esperado: FileProvider rechaza un camino fuera de su raíz.
        } catch (_: SecurityException) {
            // Esperado también, según la versión de la biblioteca.
        } catch (_: java.io.FileNotFoundException) {
            // Esperado: no hay archivo que servir.
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(): Intent? = getParcelableExtra(Intent.EXTRA_INTENT)
}
