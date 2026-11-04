package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.MetaValueForm
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El identificador de la instalación, en el dispositivo: nace una sola vez, aleatorio y en su forma
 * canónica, y después siempre se lee el mismo, también desde otra instancia, que es lo que pasa al
 * reiniciar la app.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIdentityStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "device-identity-test")

    @Before
    fun clean() {
        directory.deleteRecursively()
        File(context.noBackupFilesDir, DeviceIdentityStore.FILE_NAME).delete()
    }

    @After
    fun cleanUp() {
        clean()
    }

    @Test
    fun itIsCreatedOnceAndNeverChanges() = runBlocking {
        val store = DeviceIdentityStore(directory, Dispatchers.IO)

        val created = store.deviceId()

        assertTrue("No es un UUID canónico: $created", MetaValueForm.Uuid.accepts(created))
        // La versión 4 es la aleatoria, y la variante es la del estándar.
        assertEquals('4', created[14])
        assertTrue(created[19] in "89ab")
        assertEquals(created, store.deviceId())
        // Otra instancia es la app después de reiniciar: lee el mismo archivo.
        assertEquals(created, DeviceIdentityStore(directory, Dispatchers.IO).deviceId())
        assertEquals(created, idFile().readText(Charsets.US_ASCII))
        assertEquals(listOf(DeviceIdentityStore.FILE_NAME), directory.list()?.toList())
    }

    @Test
    fun simultaneousFirstCallsGetTheSameIdentifier() = runBlocking {
        val store = DeviceIdentityStore(directory, Dispatchers.IO)

        val seen = List(CALLERS) { async(Dispatchers.IO) { store.deviceId() } }.awaitAll()

        assertEquals(1, seen.toSet().size)
        assertEquals(seen.first(), idFile().readText(Charsets.US_ASCII))
    }

    @Test
    fun anotherInstallationGetsAnotherIdentifier() = runBlocking {
        val other = File(directory, "other")

        val first = DeviceIdentityStore(directory, Dispatchers.IO).deviceId()
        val second = DeviceIdentityStore(other, Dispatchers.IO).deviceId()

        assertNotEquals(first, second)
    }

    @Test
    fun aDamagedFileOrALeftoverTemporaryIsReplacedByAValidIdentifier() = runBlocking {
        directory.mkdirs()
        // Un archivo que no trae un UUID canónico, y el temporal de un corte a mitad.
        idFile().writeText("NO-ES-UN-UUID")
        File(directory, "${DeviceIdentityStore.FILE_NAME}.tmp").writeText("a medias")

        val created = DeviceIdentityStore(directory, Dispatchers.IO).deviceId()

        assertTrue(MetaValueForm.Uuid.accepts(created))
        assertEquals(created, idFile().readText(Charsets.US_ASCII))
        assertFalse(File(directory, "${DeviceIdentityStore.FILE_NAME}.tmp").exists())
        // Un UUID en mayúsculas tampoco es canónico: las copias lo comparan en minúsculas.
        idFile().writeText(created.uppercase())
        val replaced = DeviceIdentityStore(directory, Dispatchers.IO).deviceId()
        assertNotEquals(created, replaced)
        assertTrue(MetaValueForm.Uuid.accepts(replaced))
    }

    @Test
    fun theAppKeepsItInTheFolderTheSystemNeverBacksUp() = runBlocking {
        val created = DeviceIdentityStore.inNoBackupDir(context, Dispatchers.IO).deviceId()

        val file = File(context.noBackupFilesDir, DeviceIdentityStore.FILE_NAME)
        assertEquals(created, file.readText(Charsets.US_ASCII))
        assertEquals(created, DeviceIdentityStore.inNoBackupDir(context, Dispatchers.IO).deviceId())
    }

    private fun idFile(): File = File(directory, DeviceIdentityStore.FILE_NAME)

    private companion object {
        const val CALLERS = 16
    }
}
