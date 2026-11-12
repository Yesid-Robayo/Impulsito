package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.data.backup.BackupPackager
import io.github.yesidrobayo.shopledger.data.backup.BackupStaging
import io.github.yesidrobayo.shopledger.data.backup.PackEncryption
import io.github.yesidrobayo.shopledger.data.backup.PackageResult
import io.github.yesidrobayo.shopledger.data.backup.PendingPlainUpload
import io.github.yesidrobayo.shopledger.data.backup.RejectedGenerationStore
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La activación del cifrado con Keystore real, la base de prueba y carpetas y alias propios: la
 * prueba de ida y vuelta, el sello, la subida pendiente de una copia en claro que se descarta, y la
 * muerte del proceso en cada paso, también dos seguidas. Al final, nunca queda una copia en claro
 * que pueda salir con el cifrado activo. Los datos son inventados.
 */
internal class EncryptionActivationTest : CatalogDatabaseTest() {
    private val root = File(context.noBackupFilesDir, "encryption-activation-test")
    private val recovery = File(root, RecoveryKeyStore.DIRECTORY)
    private val aliases = "test-activation-a" to "test-activation-b"
    private val stagingDir = File(root, BackupStaging.DIRECTORY_NAME)
    private val rejectedFile = File(root, RejectedGenerationStore.FILE_NAME)
    private var now = 1_793_491_200_000L
    private var forced = 0

    private fun custody(faults: RecoveryKeyFaults = RecoveryKeyFaults.None) = RecoveryCustody(
        RecoveryKeyStore({ recovery }, Dispatchers.IO, aliases, faults),
        RecoveryStateStore({ recovery }, Dispatchers.IO),
    ) { now }

    private fun activation(
        custody: RecoveryCustody = custody(),
        faults: (ActivationStep) -> Unit = {},
    ) = EncryptionActivation(
        custody = custody,
        backupMutex = Mutex(),
        discardPendingPlainUpload = {
            PendingPlainUpload(
                state = { database.backupStateDao() },
                staging = { BackupStaging(stagingDir, Dispatchers.IO) },
                rejected = { RejectedGenerationStore(rejectedFile, Dispatchers.IO) },
                ioContext = Dispatchers.IO,
            ).discard()
        },
        forceUpload = { forced++ },
        nowUtcMillis = { now },
        ioContext = Dispatchers.IO,
        faults = faults,
    )

    @Before
    fun cleanFiles() {
        root.deleteRecursively()
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }

    @After
    fun cleanUp() = cleanFiles()

    @Test
    fun theRoundTripPassesWithTheCodeAndTouchesNothing() = runBlocking {
        val activation = activation()
        assertTrue(activation.roundTrip(activation.newCode()))
        assertFalse(recovery.exists())
    }

    @Test
    fun activatingSealsTurnsOnAndForcesACopy() = runBlocking {
        val activation = activation()
        val code = activation.newCode()
        val result = activation.activate(code)
        val keyId = BackupKey.from(code).keyIdHex
        assertEquals(ActivationResult.Activated(keyId, stateWritten = true), result)
        val custody = custody()
        assertEquals(code, (custody.keys.open() as RecoveryKeyRead.Available).code)
        assertEquals(RecoveryState(true, keyId, now, null, null), custody.state.read())
        assertEquals(1, forced)
    }

    @Test
    fun activatingDiscardsAPendingPlainUploadAndMarksItRejected() = runBlocking {
        val generation = 7L
        val staging = BackupStaging(stagingDir, Dispatchers.IO)
        stagingDir.mkdirs()
        staging.compressedFile(generation).writeBytes(byteArrayOf(0x1F, 0x8B.toByte(), 1, 2))
        staging.metaFile(generation).writeText("{}")
        database.backupStateDao().saveIntent(generation, 3, "a".repeat(64), 4)

        val activation = activation()
        activation.activate(activation.newCode())

        assertNull(database.backupStateDao().get()?.pendingGeneration)
        assertFalse(staging.compressedFile(generation).exists())
        assertFalse(staging.metaFile(generation).exists())
        assertEquals(
            setOf(generation),
            RejectedGenerationStore(rejectedFile, Dispatchers.IO).load(),
        )
    }

    @Test
    fun withEncryptionOnTheBackupNeverPacksAPlainCopy() = runBlocking {
        val snapshot = File(root, "snapshot.db").absoluteFile.also { root.mkdirs() }
        snapshot.writeBytes("SQLite format 3\u0000".toByteArray() + ByteArray(4096))
        val target = File(root, "snapshot.db.gz").absoluteFile
        val packager =
            BackupPackager(Dispatchers.IO, encryption = { PackEncryption.KeyUnavailable })
        assertEquals(PackageResult.EncryptionUnavailable, packager.compress(snapshot, target))
        assertFalse(target.exists())
        assertTrue(snapshot.exists())
    }

    @Test
    fun aDeathAtEveryStepLeavesItOffOrOnButNeverHalfway() = runBlocking {
        for (step in ActivationStep.entries) {
            cleanFiles()
            val dying = activation { if (it == step) throw ProcessDeath() }
            val code = dying.newCode()
            try {
                dying.activate(code)
            } catch (_: ProcessDeath) {
                // Así termina un proceso muerto.
            }
            assertConsistent(step.name, code)
        }
    }

    @Test
    fun twoDeathsInARowStillEndOffOrOn() = runBlocking {
        for (first in ActivationStep.entries) {
            for (second in ActivationStep.entries) {
                cleanFiles()
                val code = activation().newCode()
                listOf(first, second).forEach { step ->
                    try {
                        activation { if (it == step) throw ProcessDeath() }.activate(code)
                    } catch (_: ProcessDeath) {
                        Unit
                    }
                }
                assertConsistent("$first,$second", code)
            }
        }
    }

    @Test
    fun aDeathInsideTheSealLeavesItOff() = runBlocking {
        for (step in RecoveryKeyStep.entries.filter { it != RecoveryKeyStep.PROMOTE_RENAME }) {
            cleanFiles()
            val code = activation().newCode()
            val dyingKeys = custody { if (it == step) throw ProcessDeath() }
            try {
                activation(dyingKeys).activate(code)
            } catch (_: ProcessDeath) {
                Unit
            }
            assertConsistent(step.name, code)
        }
    }

    @Test
    fun changingTheCodeSealsTheNewOneAndForgetsTheCheckDate() = runBlocking {
        val activation = activation()
        val first = activation.newCode()
        activation.activate(first)
        val custody = custody()
        custody.state.write(custody.state.read().copy(lastCodeCheckAtUtcMillis = now))
        now += 1000
        val second = activation.newCode()

        activation.activate(second)

        assertEquals(second, (custody.keys.open() as RecoveryKeyRead.Available).code)
        val state = custody.state.read()
        assertEquals(BackupKey.from(second).keyIdHex, state.keyIdHex)
        assertEquals(now, state.enabledAtUtcMillis)
        assertNull(state.lastCodeCheckAtUtcMillis)
    }

    @Test
    fun aDeathAtEveryStepOfChangingTheCodeLeavesTheOldOrTheNewOneWorking() = runBlocking {
        for (step in ActivationStep.entries) {
            cleanFiles()
            val first = activation().newCode()
            activation().activate(first)
            val second = activation().newCode()
            try {
                activation { if (it == step) throw ProcessDeath() }.activate(second)
            } catch (_: ProcessDeath) {
                Unit
            }
            val custody = custody()
            val state = custody.alignAtStartup()
            val sealed = (custody.keys.open() as RecoveryKeyRead.Available).code
            assertTrue(step.name, sealed == first || sealed == second)
            assertTrue(step.name, state.enabled)
            assertEquals(step.name, BackupKey.from(sealed).keyIdHex, state.keyIdHex)
        }
    }

    @Test
    fun theCurrentCodeTypedAgainIsResealedAndAnotherOneIsNot() = runBlocking {
        val activation = activation()
        val code = activation.newCode()
        activation.activate(code)
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
        val custody = custody()
        assertTrue(custody.keys.open() is RecoveryKeyRead.Unavailable)
        val before = custody.state.read()

        assertEquals(ResealResult.OtherCode, activation.reseal(activation.newCode()))
        assertTrue(custody.keys.open() is RecoveryKeyRead.Unavailable)

        assertEquals(ResealResult.Sealed, activation.reseal(code))
        assertEquals(code, (custody.keys.open() as RecoveryKeyRead.Available).code)
        assertEquals(before, custody.state.read())
    }

    @Test
    fun withEncryptionOffNothingIsResealed() = runBlocking {
        val activation = activation()
        assertEquals(ResealResult.NotNeeded, activation.reseal(activation.newCode()))
        assertFalse(recovery.exists() && File(recovery, RecoveryKeyStore.SEALED_FILE).exists())
    }

    /**
     * Tras arrancar de nuevo, el cifrado está apagado y sin sobre, o activo con el sobre de [code]
     * que abre: nunca activo sin un código que abra, ni un sobre que el arranque no reconozca.
     */
    private suspend fun assertConsistent(case: String, code: RecoveryCode) {
        val custody = custody()
        val state = custody.alignAtStartup()
        when (val read = custody.keys.open()) {
            is RecoveryKeyRead.Available -> {
                assertEquals(case, code, read.code)
                assertTrue(case, state.enabled)
                assertEquals(case, BackupKey.from(code).keyIdHex, state.keyIdHex)
            }

            RecoveryKeyRead.Absent -> assertFalse(case, state.enabled)

            is RecoveryKeyRead.Unavailable, is RecoveryKeyRead.RetryLater ->
                throw AssertionError("$case: el sobre no abre: $read")
        }
    }

    private class ProcessDeath : RuntimeException()
}
