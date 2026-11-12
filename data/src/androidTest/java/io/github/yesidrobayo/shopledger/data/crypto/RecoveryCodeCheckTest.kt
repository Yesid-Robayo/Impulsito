package io.github.yesidrobayo.shopledger.data.crypto

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.CodeCheckOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.EncryptionStatus
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La prueba periódica del código con Keystore real y alias propios: escribir bien el código anota la
 * fecha en `state.json` y nada más; diez errores seguidos, un error de escritura, otro código válido
 * o un código guardado que no abre no cambian ningún byte de `recovery/`; y un corte a mitad de la
 * escritura deja el estado de antes o el nuevo. Los códigos salen del generador de la app.
 */
class RecoveryCodeCheckTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "recovery-code-check-test")
    private val aliases = "test-code-check-a" to "test-code-check-b"
    private val activatedAt = 1_793_491_200_000L
    private var now = activatedAt
    private val day = 86_400_000L

    private fun custody() = RecoveryCustody(
        RecoveryKeyStore({ directory }, Dispatchers.IO, aliases),
        RecoveryStateStore({ directory }, Dispatchers.IO),
    ) { now }

    private fun activation(custody: RecoveryCustody) =
        EncryptionActivation(custody, Mutex(), {}, {}, { now }, Dispatchers.IO)

    private fun service(custody: RecoveryCustody) = EncryptionService(custody, activation(custody))

    /** Activa con un código nuevo y lo devuelve impreso, como lo escribiría la persona. */
    private fun activate(custody: RecoveryCustody): String = runBlocking {
        val code = RecoveryCodec.generate(SecureRandom())
        activation(custody).activate(code)
        RecoveryCodec.printable(code)
    }

    @Before
    fun cleanFiles() {
        directory.deleteRecursively()
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }

    @After
    fun cleanUp() = cleanFiles()

    @Test
    fun theRightCodeRecordsTheDateAndNothingElse() = runBlocking {
        val custody = custody()
        val printed = activate(custody)
        val sealedBefore = File(directory, RecoveryKeyStore.SEALED_FILE).readBytes()
        now = activatedAt + 7 * day

        assertEquals(CodeCheckOutcome.CORRECT, service(custody).checkCode(printed.lowercase()))

        val state = custody.state.read()
        assertEquals(now, state.lastCodeCheckAtUtcMillis)
        assertEquals(activatedAt, state.enabledAtUtcMillis)
        assertTrue(state.enabled)
        assertArrayEquals(sealedBefore, File(directory, RecoveryKeyStore.SEALED_FILE).readBytes())
        assertEquals(
            EncryptionStatus.On(activatedAt, now),
            service(custody).status(),
        )
        assertFalse(custody.checkFacts().due)
    }

    @Test
    fun tenWrongAttemptsChangeNoByte() = runBlocking {
        val custody = custody()
        val printed = activate(custody)
        val other = RecoveryCodec.printable(RecoveryCodec.generate(SecureRandom()))
        val typo = printed.dropLast(1) + if (printed.last() == '0') '1' else '0'
        val before = snapshot()

        repeat(10) { attempt ->
            val typed = if (attempt % 2 == 0) other else typo
            assertEquals(CodeCheckOutcome.MISMATCH, service(custody).checkCode(typed))
        }
        assertEquals(CodeCheckOutcome.MISMATCH, service(custody).checkCode(""))

        assertSame(before, snapshot())
        // Sin límite de intentos: el código correcto sigue sirviendo.
        assertEquals(CodeCheckOutcome.CORRECT, service(custody).checkCode(printed))
    }

    @Test
    fun aCodeThatDoesNotOpenChangesNothing() = runBlocking {
        val custody = custody()
        val printed = activate(custody)
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
        val before = snapshot()

        assertEquals(CodeCheckOutcome.UNAVAILABLE, service(custody).checkCode(printed))

        assertSame(before, snapshot())
    }

    @Test
    fun withoutACodeThereIsNothingToCompare() = runBlocking {
        assertEquals(CodeCheckOutcome.UNAVAILABLE, service(custody()).checkCode("G7K2"))
        assertFalse(directory.exists())
    }

    @Test
    fun aCutBeforeTheRenameKeepsTheStateBeforeAndTheNextCheckWorks() = runBlocking {
        val custody = custody()
        val printed = activate(custody)
        val stateFile = File(directory, RecoveryStateStore.FILE_NAME)
        val before = stateFile.readBytes()
        // Lo que deja un corte entre escribir el temporal y renombrarlo, dos veces seguidas.
        repeat(2) { DurableFile.temporaryOf(stateFile).writeText("{\"version\":") }

        assertArrayEquals(before, stateFile.readBytes())
        assertNull(custody.state.read().lastCodeCheckAtUtcMillis)
        assertTrue(custody.checkFacts().active)

        now = activatedAt + 8 * day
        assertEquals(CodeCheckOutcome.CORRECT, service(custody).checkCode(printed))
        assertEquals(now, custody().state.read().lastCodeCheckAtUtcMillis)
        assertFalse(DurableFile.temporaryOf(stateFile).exists())
    }

    @Test
    fun aCheckWithAnOldCodeAfterChangingItRecordsNothing() = runBlocking {
        val custody = custody()
        activate(custody)
        val state = custody.state.read()

        assertFalse(custody.recordCodeCheck("0000000000000000"))
        assertEquals(state, custody.state.read())
    }

    @Test
    fun theCheckIsDueOnDaysSevenAndThirtyThenNinetyAfterTheLastOne() = runBlocking {
        val custody = custody()
        val printed = activate(custody)

        now = activatedAt + 6 * day
        assertFalse(custody.checkFacts().due)
        now = activatedAt + 7 * day
        assertTrue(custody.checkFacts().due)
        service(custody).checkCode(printed)
        assertFalse(custody.checkFacts().due)
        now = activatedAt + 30 * day
        assertTrue(custody.checkFacts().due)
        service(custody).checkCode(printed)
        now = activatedAt + 119 * day
        assertFalse(custody.checkFacts().due)
        now = activatedAt + 120 * day
        assertTrue(custody.checkFacts().due)
    }

    /** Cada archivo de la carpeta del código con sus bytes. */
    private fun snapshot(): Map<String, List<Byte>> = directory
        .listFiles()
        .orEmpty()
        .filter { it.isFile }
        .associate { it.name to it.readBytes().toList() }

    private fun assertSame(before: Map<String, List<Byte>>, after: Map<String, List<Byte>>) =
        assertEquals(before, after)
}
