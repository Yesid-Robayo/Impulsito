package io.github.yesidrobayo.shopledger.data.crypto

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.RecoveryCodeView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `Ver mi código` con Keystore real y alias propios: muestra el código sellado tal como se imprime,
 * no escribe nada al mostrarlo, y sin sobre o con la clave borrada no hay nada que mostrar. Los
 * códigos salen del generador de la app y no se guardan en ningún otro lado.
 */
class RecoveryCodeViewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "recovery-code-view-test")
    private val aliases = "test-code-view-a" to "test-code-view-b"
    private val now = 1_793_491_200_000L

    private fun custody() = RecoveryCustody(
        RecoveryKeyStore({ directory }, Dispatchers.IO, aliases),
        RecoveryStateStore({ directory }, Dispatchers.IO),
    ) { now }

    private fun service(custody: RecoveryCustody = custody()) = EncryptionService(
        custody,
        EncryptionActivation(
            custody = custody,
            backupMutex = Mutex(),
            discardPendingPlainUpload = {},
            forceUpload = {},
            nowUtcMillis = { now },
            ioContext = Dispatchers.IO,
        ),
    )

    @Before
    fun cleanFiles() {
        directory.deleteRecursively()
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }

    @After
    fun cleanUp() = cleanFiles()

    @Test
    fun theSealedCodeIsShownAsPrinted() = runBlocking {
        val custody = custody()
        val activation =
            EncryptionActivation(custody, Mutex(), {}, {}, { now }, Dispatchers.IO)
        val code = activation.newCode()
        activation.activate(code)

        val view = service(custody).viewCode()

        assertTrue(view is RecoveryCodeView.Shown)
        val groups = (view as RecoveryCodeView.Shown).code.groups
        assertEquals(RecoveryCodec.printable(code), groups.joinToString("-"))
    }

    @Test
    fun showingTheCodeWritesNothing() = runBlocking {
        val custody = custody()
        val activation =
            EncryptionActivation(custody, Mutex(), {}, {}, { now }, Dispatchers.IO)
        activation.activate(activation.newCode())
        val before = snapshot()

        repeat(3) { service(custody).viewCode() }

        val after = snapshot()
        assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after.getValue(name)) }
    }

    @Test
    fun withoutASealedCodeThereIsNothingToShow() = runBlocking {
        assertEquals(RecoveryCodeView.Unavailable, service().viewCode())
    }

    @Test
    fun withTheKeyGoneThereIsNothingToShowAndNothingIsDeleted() = runBlocking {
        val custody = custody()
        val activation =
            EncryptionActivation(custody, Mutex(), {}, {}, { now }, Dispatchers.IO)
        activation.activate(activation.newCode())
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
        val before = snapshot()

        assertEquals(RecoveryCodeView.Unavailable, service(custody).viewCode())

        assertEquals(before.keys, snapshot().keys)
    }

    /** Cada archivo de la carpeta del código con sus bytes. */
    private fun snapshot(): Map<String, ByteArray> = directory
        .listFiles()
        .orEmpty()
        .filter { it.isFile }
        .associate { it.name to it.readBytes() }
}
