package io.github.yesidrobayo.shopledger.data.crypto

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La custodia del código con Keystore real, en una carpeta y con alias propios de la prueba, que se
 * borran al terminar: abrir, clave ausente, sobre dañado, clave regenerada, cambiar el código, el
 * sobre de una restauración, el estado al arrancar y la muerte del proceso en cada paso, también dos
 * seguidas. Los códigos son inventados.
 */
class RecoveryCustodyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "recovery-custody-test")
    private val aliases = "test-recovery-code-a" to "test-recovery-code-b"
    private val sealed = File(directory, RecoveryKeyStore.SEALED_FILE)
    private val pending = File(directory, RecoveryKeyStore.PENDING_FILE)
    private val first = RecoveryCode("0123456789ABCDEFGHJKMNPQRS")
    private val second = RecoveryCode("G7K2M9PQR4STV6WXY8Z3ABCDEF")
    private var now = 1_793_491_200_000L

    private fun store(faults: RecoveryKeyFaults = RecoveryKeyFaults.None) =
        RecoveryKeyStore({ directory }, Dispatchers.IO, aliases, faults)

    private fun custody(keys: RecoveryKeyStore = store()) =
        RecoveryCustody(keys, RecoveryStateStore({ directory }, Dispatchers.IO), { now })

    @Before
    fun clean() {
        directory.deleteRecursively()
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }

    @After
    fun cleanUp() = clean()

    @Test
    fun whatIsSealedOpensWithItsKeyIdAndTheFileDoesNotShowTheCode() = runBlocking {
        val saved = store().seal(first)
        assertEquals(RecoveryKeySave.Sealed(BackupKey.from(first).keyIdHex), saved)
        val read = store().open()
        assertTrue(read is RecoveryKeyRead.Available)
        assertEquals(first, (read as RecoveryKeyRead.Available).code)
        assertFalse(String(sealed.readBytes(), Charsets.ISO_8859_1).contains(first.canonical))
        assertFalse(DurableFile.temporaryOf(sealed).exists())
    }

    @Test
    fun noEnvelopeIsAbsentAndOpeningCreatesNothing() = runBlocking {
        assertEquals(RecoveryKeyRead.Absent, store().open())
        assertFalse(KeystoreSealer(aliases.first).keyExists())
        assertFalse(KeystoreSealer(aliases.second).keyExists())
        assertFalse(directory.exists() && directory.list().orEmpty().isNotEmpty())
    }

    @Test
    fun aMissingKeyIsUnavailableAndNothingIsRecreatedOrTouched() = runBlocking {
        store().seal(first)
        val before = sealed.readBytes()
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
        val read = store().open()
        assertEquals(RecoveryKeyRead.Unavailable(RecoveryKeyProblem.KEY_MISSING), read)
        assertFalse(KeystoreSealer(aliases.first).keyExists())
        assertFalse(KeystoreSealer(aliases.second).keyExists())
        assertArrayEquals(before, sealed.readBytes())
    }

    @Test
    fun aChangedBitOrARegeneratedKeyIsADamagedEnvelopeAndNothingIsTouched() = runBlocking {
        store().seal(first)
        val good = sealed.readBytes()
        val flipped = good.copyOf().also {
            it[it.size - 1] =
                (it[it.size - 1].toInt() xor 1).toByte()
        }
        sealed.writeBytes(flipped)
        assertProblem(RecoveryKeyProblem.ENVELOPE_DAMAGED, store().open())
        assertArrayEquals(flipped, sealed.readBytes())

        sealed.writeBytes(good)
        val alias = requireNotNull(SealedEnvelope.aliasOf(good))
        KeystoreSealer(alias).replaceKey()
        assertProblem(RecoveryKeyProblem.ENVELOPE_DAMAGED, store().open())
        assertArrayEquals(good, sealed.readBytes())

        sealed.writeBytes(byteArrayOf(1, 2, 3))
        assertProblem(RecoveryKeyProblem.ENVELOPE_DAMAGED, store().open())
    }

    @Test
    fun changingTheCodeSwitchesKeyAndDeletesThePreviousOne() = runBlocking {
        store().seal(first)
        val firstAlias = requireNotNull(SealedEnvelope.aliasOf(sealed.readBytes()))
        store().seal(second)
        val secondAlias = requireNotNull(SealedEnvelope.aliasOf(sealed.readBytes()))
        assertTrue(firstAlias != secondAlias)
        assertFalse(KeystoreSealer(firstAlias).keyExists())
        assertEquals(second, (store().open() as RecoveryKeyRead.Available).code)
    }

    @Test
    fun aRestoredCodeWaitsInPendingUntilPromotedAndDeletingItLeavesTheSealedOne() = runBlocking {
        store().seal(first)
        store().sealPending(second)
        assertEquals(first, (store().open() as RecoveryKeyRead.Available).code)
        assertEquals(second, (store().openPending() as RecoveryKeyRead.Available).code)

        assertTrue(store().deletePending())
        assertFalse(pending.exists())
        assertEquals(first, (store().open() as RecoveryKeyRead.Available).code)

        store().sealPending(second)
        assertTrue(store().promotePending())
        assertFalse(pending.exists())
        assertEquals(second, (store().open() as RecoveryKeyRead.Available).code)
        assertFalse(store().promotePending())
    }

    @Test
    fun theEnvelopeRulesTheStateAtStartupUnlessItsKeyWasTurnedOff() = runBlocking {
        val custody = custody()
        assertEquals(RecoveryState.NEVER_ENABLED, custody.alignAtStartup())

        // Se murió entre sellar y escribir el estado: el sobre manda.
        custody.keys.seal(first)
        val keyId = BackupKey.from(first).keyIdHex
        assertEquals(RecoveryState(true, keyId, now, null, null), custody.alignAtStartup())

        // Apagado a propósito con ese mismo código: reiniciar no lo enciende.
        // Con el código anterior comprobado: la comprobación no pasa a un código nuevo.
        val off = RecoveryState(false, keyId, now, now + 1, keyId, lastCodeCheckAtUtcMillis = now)
        custody.state.write(off)
        now += 1000
        assertEquals(off, custody.alignAtStartup())

        // Un sobre de una activación nueva sí manda.
        custody.keys.seal(second)
        val aligned = custody.alignAtStartup()
        assertEquals(
            RecoveryState(true, BackupKey.from(second).keyIdHex, now, null, keyId),
            aligned,
        )
        assertEquals(aligned, custody.state.read())
    }

    @Test
    fun anUnreadableStateIsNeverEnabledAndReadingDoesNotOverwriteIt() = runBlocking {
        directory.mkdirs()
        val file = File(directory, RecoveryStateStore.FILE_NAME)
        file.writeText("{roto")
        val state = RecoveryStateStore({ directory }, Dispatchers.IO)
        assertEquals(RecoveryState.NEVER_ENABLED, state.read())
        assertEquals("{roto", file.readText())
    }

    @Test
    fun aDeathAtEveryStepOfChangingTheCodeLeavesTheOldOrTheNewOneOpening() = runBlocking {
        for (step in RecoveryKeyStep.entries.filter { it != RecoveryKeyStep.PROMOTE_RENAME }) {
            clean()
            store().seal(first)
            dieAt(step) { it.seal(second) }
            assertOneOf(store().open(), first, second)
            // Y la siguiente vez termina.
            store().seal(second)
            assertEquals(second, (store().open() as RecoveryKeyRead.Available).code)
        }
    }

    @Test
    fun twoDeathsInARowWhileChangingTheCodeStillLeaveOneThatOpens() = runBlocking {
        val steps = RecoveryKeyStep.entries.filter { it != RecoveryKeyStep.PROMOTE_RENAME }
        for (firstStep in steps) {
            for (secondStep in steps) {
                clean()
                store().seal(first)
                dieAt(firstStep) { it.seal(second) }
                dieAt(secondStep) { it.seal(second) }
                assertOneOf(store().open(), first, second)
            }
        }
    }

    @Test
    fun aDeathWhilePromotingLeavesTheSealedOrThePendingCode() = runBlocking {
        for (step in listOf(RecoveryKeyStep.PROMOTE_RENAME, RecoveryKeyStep.DELETE_PREVIOUS_KEY)) {
            clean()
            store().seal(first)
            store().sealPending(second)
            dieAt(step) { it.promotePending() }
            assertOneOf(store().open(), first, second)
            if (pending.exists()) assertTrue(store().promotePending())
            assertEquals(second, (store().open() as RecoveryKeyRead.Available).code)
        }
    }

    @Test
    fun aLeftoverTemporaryIsNeverAdopted() = runBlocking {
        store().seal(first)
        DurableFile.temporaryOf(sealed).writeBytes(byteArrayOf(9, 9, 9))
        assertEquals(first, (store().open() as RecoveryKeyRead.Available).code)
        store().seal(second)
        assertFalse(DurableFile.temporaryOf(sealed).exists())
        assertEquals(second, (store().open() as RecoveryKeyRead.Available).code)
    }

    @Test
    fun theCodeNeverReachesTheStateFileOrTheLog() = runBlocking {
        val custody = custody()
        custody.keys.seal(first)
        custody.alignAtStartup()
        custody.keys.sealPending(second)
        custody.keys.promotePending()
        val state = File(directory, RecoveryStateStore.FILE_NAME).readText()
        for (code in listOf(first, second)) {
            assertFalse(state.contains(code.canonical))
            directory.listFiles().orEmpty().forEach {
                assertFalse(
                    it.name,
                    String(it.readBytes(), Charsets.ISO_8859_1).contains(code.canonical),
                )
            }
        }
        val log =
            Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.bufferedReader().use {
                it.readText()
            }
        assertFalse(log.contains(first.canonical))
        assertFalse(log.contains(second.canonical))
    }

    /** Corre [block] con un almacén que muere en [step], como un proceso que el sistema mata. */
    private suspend fun dieAt(step: RecoveryKeyStep, block: suspend (RecoveryKeyStore) -> Unit) {
        val dying = store { if (it == step) throw ProcessDeath() }
        try {
            block(dying)
        } catch (_: ProcessDeath) {
            // Así termina un proceso muerto: sin limpiar nada.
        }
    }

    private fun assertOneOf(read: RecoveryKeyRead, vararg codes: RecoveryCode) {
        assertTrue("No abre: $read", read is RecoveryKeyRead.Available)
        assertTrue(codes.contains((read as RecoveryKeyRead.Available).code))
    }

    private fun assertProblem(expected: RecoveryKeyProblem, read: RecoveryKeyRead) {
        assertTrue("Debía no abrir: $read", read is RecoveryKeyRead.Unavailable)
        assertEquals(expected, (read as RecoveryKeyRead.Unavailable).problem)
    }

    private class ProcessDeath : RuntimeException()
}
