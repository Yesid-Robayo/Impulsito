package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.crypto.KeystoreSealer
import io.github.yesidrobayo.shopledger.data.crypto.SealedEnvelope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La credencial sellada con Keystore real, en una carpeta y con alias propios de la prueba, que se
 * borran al terminar. Todos los datos son inventados.
 */
class WebDavCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "webdav-credential-test")
    private val aliases = "test-webdav-credential-a" to "test-webdav-credential-b"
    private val store = WebDavCredentialStore({ directory }, Dispatchers.IO, aliases)
    private val file = File(directory, WebDavCredentialStore.FILE_NAME)
    private val credential = credential(SENTINEL_PASSWORD)

    private fun credential(password: String, destinationId: String = DESTINATION) =
        StoredWebDavCredential(
            "https://webdav.example.com/dav/",
            "persona-de-prueba",
            password,
            destinationId,
        )

    @Before
    fun clean() {
        directory.deleteRecursively()
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }

    @After
    fun cleanUp() = clean()

    @Test
    fun aSavedCredentialOpensWithEveryFieldAndNeverInTheClear() = runBlocking<Unit> {
        assertEquals(CredentialSave.Saved, store.save(credential))
        val read = store.read(DESTINATION) as CredentialRead.Usable
        assertEquals(credential.url, read.credential.url)
        assertEquals(credential.username, read.credential.username)
        assertEquals(SENTINEL_PASSWORD, read.credential.password)
        assertEquals(listOf(WebDavCredentialStore.FILE_NAME), directory.list()?.toList())
        val onDisk = file.readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains(SENTINEL_PASSWORD))
        assertFalse(String(onDisk, Charsets.UTF_8).contains("persona-de-prueba"))
    }

    @Test
    fun eachSaveUsesTheOtherKeyAndDropsThePreviousOne() = runBlocking<Unit> {
        store.save(credential)
        val first = SealedEnvelope.aliasOf(file.readBytes())
        assertEquals(aliases.first, first)
        store.save(credential("otra-clave-inventada"))
        assertEquals(aliases.second, SealedEnvelope.aliasOf(file.readBytes()))
        assertFalse(KeystoreSealer(aliases.first).keyExists())
        assertEquals(
            "otra-clave-inventada",
            (store.read(DESTINATION) as CredentialRead.Usable).credential.password,
        )
        store.save(credential("tercera-clave-inventada"))
        assertEquals(aliases.first, SealedEnvelope.aliasOf(file.readBytes()))
        assertFalse(KeystoreSealer(aliases.second).keyExists())
    }

    @Test
    fun aCredentialOfAnotherDestinationIsNeverUsed() = runBlocking<Unit> {
        store.save(credential)
        assertEquals(
            CredentialRead.PermissionLost(PermissionLostReason.OTHER_DESTINATION),
            store.read("ffffffffffffffffffffffffffffffff"),
        )
    }

    @Test
    fun withoutFileOrKeyOrWithADamagedEnvelopeThePermissionIsLost() = runBlocking<Unit> {
        assertEquals(
            CredentialRead.PermissionLost(PermissionLostReason.NO_FILE),
            store.read(DESTINATION),
        )
        store.save(credential)
        KeystoreSealer(aliases.first).deleteKey()
        assertEquals(
            CredentialRead.PermissionLost(PermissionLostReason.KEY_MISSING),
            store.read(DESTINATION),
        )
        store.save(credential)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        val damaged = store.read(DESTINATION)
        assertTrue("$damaged", damaged is CredentialRead.PermissionLost)
        assertEquals(
            PermissionLostReason.ENVELOPE_DAMAGED,
            (damaged as CredentialRead.PermissionLost).reason,
        )
        // Un alias cambiado en el encabezado tampoco abre: el encabezado está autenticado.
        store.save(credential)
        val other = file.readBytes()
        val alias = SealedEnvelope.decode(other)
        val swapped = SealedEnvelope(
            aliases.second,
            requireNotNull(alias).iv,
            alias.ciphertext,
        ).encode()
        file.writeBytes(swapped)
        assertTrue(store.read(DESTINATION) is CredentialRead.PermissionLost)
        // Leer nunca reescribe el archivo.
        assertTrue(file.readBytes().contentEquals(swapped))
    }

    @Test
    fun onlyHttpsIsSaved() = runBlocking<Unit> {
        val plain =
            StoredWebDavCredential("http://webdav.example.com/", "persona", "clave", DESTINATION)
        assertEquals(CredentialSave.Rejected(CredentialProblem.NOT_HTTPS), store.save(plain))
        assertFalse(file.exists())
    }

    @Test
    fun deletingRemovesTheFileAndBothKeys() = runBlocking<Unit> {
        store.save(credential)
        store.save(credential)
        assertEquals(CredentialDelete.Deleted, store.delete())
        assertFalse(file.exists())
        assertFalse(KeystoreSealer(aliases.first).keyExists())
        assertFalse(KeystoreSealer(aliases.second).keyExists())
    }

    @Test
    fun printedTextNeverCarriesTheCredential() {
        assertFalse(credential.toString().contains(SENTINEL_PASSWORD))
        assertFalse(CredentialRead.Usable(credential).toString().contains(SENTINEL_PASSWORD))
    }

    companion object {
        const val DESTINATION = "0123456789abcdef0123456789abcdef"

        /** Una contraseña falsa y marcada, que no debe aparecer fuera del sobre. */
        const val SENTINEL_PASSWORD = "SENTINEL_WEBDAV_PASS_4E1D-ñandú"
    }
}
