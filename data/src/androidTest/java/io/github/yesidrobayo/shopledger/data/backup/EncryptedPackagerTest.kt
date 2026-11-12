package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedReadResult
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La copia cifrada en el teléfono: el archivo sale en el nombre `.slbk`, se descifra con la clave
 * del código y da la instantánea; sin clave, sin los datos de la cabecera o pasado el tope, no
 * queda ningún archivo. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedPackagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "encrypted-packager-test").absoluteFile
    private val code = RecoveryCode("0123456789ABCDEFGHJKMNPQRS")
    private val facts = CopyFacts(9, 1, 1_793_491_200_000L, "6b1f3c2a-9d4e-4f0a-8c7b-5e6d4c3b2a19")
    private lateinit var snapshot: File
    private lateinit var content: ByteArray

    @Before
    fun prepare() {
        directory.deleteRecursively()
        directory.mkdirs()
        content = "SQLite format 3\u0000".toByteArray() + Random(29).nextBytes(2 * (1 shl 20) + 777)
        snapshot = File(directory, "snapshot.db").also { it.writeBytes(content) }
    }

    @After
    fun clean() {
        directory.deleteRecursively()
    }

    private fun target(encrypted: Boolean) =
        File(directory, if (encrypted) "copy.slbk" else "copy.db.gz")

    @Test
    fun anEncryptedCopyOpensWithTheCodeAndGivesTheSnapshot() = runBlocking {
        val packager = BackupPackager(Dispatchers.IO, encryption = { PackEncryption.Encrypt(code) })

        val result = packager.compress(snapshot, ::target, facts)

        assertTrue("$result", result is PackageResult.Packed)
        result as PackageResult.Packed
        assertEquals(target(true), result.file)
        assertEquals(BackupKey.from(code).keyIdHex, result.keyIdHex)
        assertFalse(target(false).exists())
        assertFalse(snapshot.exists())
        val bytes = result.file.readBytes()
        assertEquals(newSha256().digest(bytes).toLowerHex(), result.fileSha256)
        val read = EncryptedCopy.read(bytes.inputStream(), BackupKey.from(code))
        read as EncryptedReadResult.Read
        assertEquals(content.size.toLong(), read.content.size)
        assertEquals(newSha256().digest(content).toLowerHex(), read.content.sha256)
        assertEquals(9L, read.header.generation)
    }

    @Test
    fun withoutTheKeyNothingIsWrittenAndTheSnapshotStays() = runBlocking {
        val packager =
            BackupPackager(Dispatchers.IO, encryption = { PackEncryption.KeyUnavailable })

        assertEquals(
            PackageResult.EncryptionUnavailable,
            packager.compress(snapshot, ::target, facts),
        )
        assertEquals(
            PackageResult.KeyRetry,
            BackupPackager(Dispatchers.IO, encryption = {
                PackEncryption.RetryLater
            }).compress(snapshot, ::target, facts),
        )
        assertFalse(target(true).exists())
        assertFalse(target(false).exists())
        assertTrue(snapshot.exists())
    }

    @Test
    fun withoutHeaderFactsAnEncryptedCopyIsNotMadeAndNeverPlain() = runBlocking {
        val packager = BackupPackager(Dispatchers.IO, encryption = { PackEncryption.Encrypt(code) })

        assertEquals(
            PackageResult.EncryptionUnavailable,
            packager.compress(snapshot, target(false)),
        )
        assertFalse(target(false).exists())
    }

    @Test
    fun anEncryptedCopyOverTheLimitLeavesNoFile() = runBlocking {
        val packager =
            BackupPackager(Dispatchers.IO, maxFileBytes = 4096, encryption = {
                PackEncryption.Encrypt(code)
            })

        assertEquals(PackageResult.TooLarge, packager.compress(snapshot, ::target, facts))
        assertFalse(target(true).exists())
        assertTrue(snapshot.exists())
    }

    @Test
    fun withEncryptionOffTheCopyStaysPlain() = runBlocking {
        val result = BackupPackager(
            Dispatchers.IO,
        ).compress(snapshot, ::target, facts) as PackageResult.Packed

        assertEquals(target(false), result.file)
        assertEquals(null, result.keyIdHex)
        assertFalse(result.encrypted)
    }
}
