package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.crypto.BackupHeader
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedReadResult
import io.github.yesidrobayo.shopledger.data.crypto.RecoveryCode
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import java.io.ByteArrayOutputStream
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
import org.junit.runner.RunWith

/**
 * El respaldo con el cifrado activo, con el destino falso y la base real: la copia sale cifrada y
 * comprobada, con su formato y su clave en los metadatos; sin clave no sale nada; un fallo pasajero
 * de Keystore se reintenta; y una subida en claro que quedó en camino nunca se retoma. En ningún
 * caso llega al destino un archivo en claro. El código es inventado.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedBackupRunnerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "encrypted-backup-runner-test")
    private val control = TestControl()
    private val code = RecoveryCode("0123456789ABCDEFGHJKMNPQRS")
    private val key = BackupKey.from(code)
    private val on =
        LocalEncryption(
            active = true,
            keyAvailable = true,
            hasRecoveryCode = true,
            offConfirmed = false,
        )

    private fun packager(mode: () -> PackEncryption) = BackupPackager(Dispatchers.IO, encryption = {
        mode()
    })

    @Before
    fun open() = runBlocking {
        fixture.open()
        fixture.connect()
    }

    @After
    fun close() {
        fixture.close()
    }

    @Test
    fun withEncryptionOnTheCopyGoesOutEncryptedAndChecked() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()

        val run =
            fixture
                .runner(
                    destination,
                    packager = packager {
                        PackEncryption.Encrypt(code)
                    },
                    encryption = { on },
                )
                .run(control)

        assertEquals(BackupRun.Verified(1), run)
        val name = destination.fileNames.single { !it.endsWith(BackupFileName.META_SUFFIX) }
        assertTrue(name, name.endsWith(".slbk"))
        val bytes = requireNotNull(destination.file(name))
        assertArrayEquals(BackupHeader.MAGIC, bytes.copyOf(BackupHeader.MAGIC.size))
        val plain = ByteArrayOutputStream()
        val read = EncryptedCopy.read(bytes.inputStream(), key, plain)
        assertTrue(read is EncryptedReadResult.Read)
        read as EncryptedReadResult.Read
        assertEquals(1L, read.header.generation)
        // Lo descifrado es la base: empieza con el encabezado de SQLite.
        assertEquals("SQLite format 3", String(plain.toByteArray(), 0, 15, Charsets.US_ASCII))
        val meta =
            SlMetaCodec.decodeUnnamed(
                requireNotNull(destination.file(name + BackupFileName.META_SUFFIX)),
            )
        meta as CopyMetadata.Valid
        assertEquals(2, meta.meta.format)
        assertEquals(key.keyIdHex, meta.meta.keyId)
        assertEquals(null, meta.meta.deviceLabel)
        assertEquals(null, meta.meta.appVersion)
        assertNothingPlainReachedTheDestination(destination)
        assertTrue(fixture.stagingFiles().none { it.endsWith(".db.gz") || it.endsWith(".db") })
    }

    @Test
    fun withoutTheKeyNothingGoesOutAndTheFailureIsShown() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()

        val run =
            fixture
                .runner(
                    destination,
                    packager = packager {
                        PackEncryption.KeyUnavailable
                    },
                    encryption = { on },
                )
                .run(control)

        assertEquals(
            BackupRun.Stopped(StopCause.Failure(BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE)),
            run,
        )
        assertEquals(BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE.name, fixture.state().failureCode)
        assertTrue(destination.fileNames.isEmpty())
        assertFalse(destination.calls.contains(Operation.UPLOAD))
        assertTrue(fixture.stagingFiles().isEmpty())
    }

    @Test
    fun aPassingKeystoreFailureRetriesWithoutChangingTheState() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()

        val run =
            fixture
                .runner(
                    destination,
                    packager = packager {
                        PackEncryption.RetryLater
                    },
                    encryption = { on },
                )
                .run(control)

        assertEquals(BackupRun.Retry(RetryReason.LOCAL_FAILURE), run)
        assertEquals(null, fixture.state().failureCode)
        assertTrue(destination.fileNames.isEmpty())
    }

    @Test
    fun aPlainUploadLeftOnTheWayIsNeverResumedOnceEncryptionIsOn() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        destination.failNext(Operation.UPLOAD, DestinationFailure.Transient(TransientKind.NETWORK))
        val plainRun = fixture.runner(destination).run(control)
        assertEquals(
            BackupRun.Retry(RetryReason.TRANSIENT, transientKind = TransientKind.NETWORK),
            plainRun,
        )
        val pending = requireNotNull(fixture.state().pendingGeneration)

        // Se activa el cifrado sin pasar por el descarte, como tras una muerte a mitad.
        val run =
            fixture
                .runner(
                    destination,
                    packager = packager {
                        PackEncryption.Encrypt(code)
                    },
                    encryption = { on },
                )
                .run(control)

        assertTrue("$run", run is BackupRun.Verified)
        // La subida en claro no se retomó: su generación no está en el destino, y la nueva la
        // salta. Como nada suyo llegó al destino, la marca de rechazada ya no hace falta.
        assertTrue((run as BackupRun.Verified).generation > pending)
        assertTrue(destination.fileNames.none { BackupFileName.parse(it)?.generation == pending })
        assertNothingPlainReachedTheDestination(destination)
        assertTrue(destination.fileNames.any { it.endsWith(".slbk") })
    }

    @Test
    fun anEncryptedUploadLeftOnTheWayIsResumedWithTheSameFile() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        val encrypted = packager { PackEncryption.Encrypt(code) }
        destination.failNext(Operation.UPLOAD, DestinationFailure.Transient(TransientKind.NETWORK))
        fixture.runner(destination, packager = encrypted, encryption = { on }).run(control)
        val pending = requireNotNull(fixture.state().pendingGeneration)
        val staged =
            File(fixture.stagingDir, "snapshot-g${BackupStaging.generationText(pending)}.slbk")
        assertTrue(staged.isFile)
        val stagedBytes = staged.readBytes()

        val run = fixture.runner(destination, packager = encrypted, encryption = {
            on
        }).run(control)

        assertEquals(BackupRun.Verified(pending), run)
        val name = destination.fileNames.single { !it.endsWith(BackupFileName.META_SUFFIX) }
        assertArrayEquals(stagedBytes, destination.file(name))
        assertNothingPlainReachedTheDestination(destination)
    }

    @Test
    fun aDeathAtEveryUploadStepOfAnEncryptedCopyEndsVerifiedAndNeverPlain() = runBlocking {
        val encrypted = packager { PackEncryption.Encrypt(code) }
        for (step in FakeBackupDestination.UploadStep.entries) {
            // Una base propia por paso: la de la prueba sigue abierta para las demás.
            val own = BackupFixture(context, "encrypted-death-${step.ordinal}")
            try {
                own.open()
                own.connect()
                own.addProduct("Pan de prueba")
                val destination = FakeBackupDestination()
                destination.crashUploadAfter = step
                try {
                    own.runner(destination, packager = encrypted, encryption = { on }).run(control)
                } catch (_: Exception) {
                    // Así termina un proceso muerto a mitad de la subida.
                }
                destination.crashUploadAfter = null

                val runner = own.runner(destination, packager = encrypted, encryption = { on })
                val run = runner.run(control)

                assertTrue("$step: $run", run is BackupRun.Verified)
                assertNothingPlainReachedTheDestination(destination)
            } finally {
                own.close()
            }
        }
    }

    private fun assertNothingPlainReachedTheDestination(destination: FakeBackupDestination) {
        for (name in destination.fileNames.filter { !it.endsWith(BackupFileName.META_SUFFIX) }) {
            val bytes = requireNotNull(destination.file(name))
            assertFalse(
                "$name va en claro",
                bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte(),
            )
        }
    }
}
