package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.StoredCopy
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.destination.VerifiedCopy
import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La comprobación del paso 11 con WebDAV: cuándo se lee la copia entera, qué anota una lectura
 * completa, qué pasa con una copia que no coincide y qué decide la segunda lista.
 */
@RunWith(AndroidJUnit4::class)
class BackupVerificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "backup-verification-test")
    private var products = 0

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
    fun onlyAMatchingFullReadAdvancesTheLastFullVerification() = runBlocking {
        val destination = RecordingDestination(FakeBackupDestination())

        // Nunca hubo una lectura completa: la primera copia se lee entera.
        assertEquals(BackupRun.Verified(1), copy(destination, TestControl()))
        val first = fixture.now.toEpochMilliseconds()
        assertEquals(VerifyMode.FULL, destination.modes.last())
        assertEquals(first, fixture.state().lastFullVerifyAt)
        assertEquals(1L, fixture.fullVerify.load())

        // Dentro del plazo, con red no medida, solo una muestra: nada avanza.
        assertEquals(BackupRun.Verified(2), copy(destination, TestControl()))
        assertEquals(VerifyMode.SAMPLE, destination.modes.last())
        assertEquals(first, fixture.state().lastFullVerifyAt)
        assertEquals(1L, fixture.fullVerify.load())

        // Respaldar ahora la lee entera.
        assertEquals(BackupRun.Verified(3), copy(destination, TestControl(backupNow = true)))
        assertEquals(VerifyMode.FULL, destination.modes.last())
        assertEquals(3L, fixture.fullVerify.load())
        val third = fixture.now.toEpochMilliseconds()
        assertEquals(third, fixture.state().lastFullVerifyAt)

        // Con red medida, una muestra, aunque haya vencido el plazo.
        fixture.advance(25.hours)
        assertEquals(BackupRun.Verified(4), copy(destination, TestControl(metered = true)))
        assertEquals(VerifyMode.SAMPLE, destination.modes.last())
        assertEquals(third, fixture.state().lastFullVerifyAt)

        // Vencido y con red no medida, entera; pero si no alcanzó el tiempo y terminó en
        // muestra, no cuenta.
        destination.fullReadRunsOut = true
        assertEquals(BackupRun.Verified(5), copy(destination, TestControl()))
        assertEquals(VerifyMode.FULL, destination.modes.last())
        assertEquals(third, fixture.state().lastFullVerifyAt)
        assertEquals(3L, fixture.fullVerify.load())

        destination.fullReadRunsOut = false
        assertEquals(BackupRun.Verified(6), copy(destination, TestControl()))
        assertEquals(fixture.now.toEpochMilliseconds(), fixture.state().lastFullVerifyAt)
        assertEquals(6L, fixture.fullVerify.load())
    }

    @Test
    fun aRecoveredCopyIsNeverConfirmedBySampleAndWaitsWhenThePolicyForbidsMobileData() =
        runBlocking {
            val fake = FakeBackupDestination()
            fixture.addProduct("Pan de prueba")
            fixture.runner(fake).run(TestControl())
            val cases =
                listOf(
                    Triple("red no medida", TestControl(), "EVERY_60_MIN"),
                    Triple("red medida con permiso", TestControl(metered = true), "EVERY_30_MIN"),
                    Triple("red medida sin permiso", TestControl(metered = true), "WIFI_ONLY"),
                )
            for ((label, control, policy) in cases) {
                // Una subida que murió antes de comprobarse, ya entera en el destino.
                products++
                fixture.addProduct("Producto de prueba $products")
                fixture.advance(1.minutes)
                fake.failNext(
                    Operation.VERIFY,
                    DestinationFailure.Interrupted(InterruptReason.STOPPED),
                )
                fixture.runner(fake).run(TestControl())
                val pending = requireNotNull(fixture.state().pendingGeneration)
                fixture.sql(
                    "UPDATE app_settings SET metered_backup_policy = '$policy' WHERE id = 1",
                )
                val destination = RecordingDestination(fake)

                val run = fixture.runner(destination).run(control)

                if (policy == "WIFI_ONLY") {
                    // Sin leerla ni por muestra: espera una red no medida, con su archivo.
                    assertEquals(
                        label,
                        BackupRun.Retry(
                            RetryReason.INTERRUPTED,
                            interruptReason = InterruptReason.METERED,
                        ),
                        run,
                    )
                    assertEquals(label, emptyList<VerifyMode>(), destination.modes)
                    assertEquals(label, pending, fixture.state().pendingGeneration)
                    assertTrue(label, fixture.stagingFiles().isNotEmpty())
                    // Con red no medida, después, se lee entera y se confirma.
                    assertEquals(
                        label,
                        BackupRun.Verified(pending),
                        fixture.runner(destination).run(TestControl()),
                    )
                } else {
                    assertEquals(label, BackupRun.Verified(pending), run)
                }
                assertEquals(label, VerifyMode.FULL, destination.modes.last())
            }
        }

    @Test
    fun theLastFullVerificationNeverGoesBack() = runBlocking {
        fixture.fullVerify.advanceTo(9)
        fixture.fullVerify.advanceTo(4)
        assertEquals(9L, fixture.fullVerify.load())
        assertEquals("9\n", fixture.fullVerifyFile.readText())
        fixture.fullVerifyFile.writeText("no es una generación\n")
        assertNull(fixture.fullVerify.load())
    }

    @Test
    fun aMismatchIsRejectedBeforeItsDeleteAndAFailedDeleteNeverLetsItBeAdopted() = runBlocking {
        val fake = FakeBackupDestination()
        fixture.addProduct("Pan de prueba")
        fixture.runner(fake).run(TestControl())
        fixture.addProduct("Leche de prueba")
        val rejectedAtDelete = ArrayList<Set<Long>>()
        val destination =
            DeleteWatchingDestination(fake) { rejectedAtDelete += fixture.rejected.load() }
        fake.failNext(Operation.VERIFY, DestinationFailure.VerifyMismatch)
        fake.failNext(Operation.DELETE, DestinationFailure.Transient(TransientKind.NETWORK))

        val run = fixture.runner(destination).run(TestControl())

        assertEquals(BackupRun.Stopped(StopCause.Failure(BackupFailureCode.VERIFY_MISMATCH)), run)
        // La marca ya estaba en disco cuando se pidió borrar.
        assertEquals(listOf(setOf(2L)), rejectedAtDelete)
        assertEquals(setOf(2L), fixture.rejected.load())
        val rejectedCopy = ownCopy(fake, 2)
        assertTrue(rejectedCopy.fileName in fake.fileNames)
        assertNull(fixture.state().pendingGeneration)

        // Por muestra, en red medida, esa copia se vería bien: nunca se adopta, se borra.
        fixture.addProduct("Arroz de prueba")
        val next = fixture.runner(fake).run(TestControl(metered = true))

        assertEquals(BackupRun.Verified(3), next)
        assertEquals(3L, fixture.state().headGeneration)
        assertFalse(rejectedCopy.fileName in fake.fileNames)
        assertFalse(fixture.rejectedFile.exists())
    }

    @Test
    fun aMismatchThatCannotBeMarkedTouchesNothingAndIsRejectedOnTheNextRun() = runBlocking {
        val destination = FakeBackupDestination()
        fixture.addProduct("Pan de prueba")
        fixture.runner(destination).run(TestControl())
        fixture.addProduct("Leche de prueba")
        destination.failNext(Operation.VERIFY, DestinationFailure.VerifyMismatch)
        // El disco no deja escribir la marca: su temporal no se puede crear.
        val blocker = File(fixture.rejectedFile.path + ".tmp").apply { mkdirs() }

        val run = fixture.runner(destination).run(TestControl())

        assertEquals(BackupRun.Retry(RetryReason.LOCAL_STATE_NOT_SAVED), run)
        assertEquals(BackupFailureCode.LOCAL_STORAGE_LOW.name, fixture.state().failureCode)
        assertEquals(2L, fixture.state().pendingGeneration)
        val bad = ownCopy(destination, 2)
        assertTrue(bad.fileName in destination.fileNames)
        assertEquals(
            listOf("snapshot-g00000002.db.gz", "snapshot-g00000002.slmeta"),
            fixture.stagingFiles(),
        )

        // Con el disco de vuelta, la siguiente la comprueba otra vez, la rechaza y la borra.
        blocker.delete()
        val content = requireNotNull(destination.file(bad.fileName))
        destination.putFile(
            bad.fileName,
            content.copyOf().also {
                it[it.size / 2] =
                    (it[it.size / 2] + 1).toByte()
            },
        )
        val next = fixture.runner(destination).run(TestControl())

        assertEquals(BackupRun.Stopped(StopCause.Failure(BackupFailureCode.VERIFY_MISMATCH)), next)
        assertFalse(bad.fileName in destination.fileNames)
        assertFalse(fixture.rejectedFile.exists())
        assertNull(fixture.state().pendingGeneration)
        assertEquals(1L, fixture.state().headGeneration)
    }

    @Test
    fun aMismatchWhoseDeleteWorksLeavesNothingBehind() = runBlocking {
        val destination = FakeBackupDestination()
        fixture.addProduct("Pan de prueba")
        destination.failNext(Operation.VERIFY, DestinationFailure.VerifyMismatch)

        fixture.runner(destination).run(TestControl())

        assertTrue(destination.fileNames.none { BackupFileName.parse(it) != null })
        assertFalse(fixture.rejectedFile.exists())
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun anotherPhoneUploadingAtTheSameTimeIsAConflictAndNothingIsPruned() = runBlocking {
        val fake = FakeBackupDestination()
        val pruner = RecordingPruner()
        fixture.addProduct("Pan de prueba")
        val destination =
            AfterVerifyDestination(fake) { putForeignCopy(fake, generation = 1) }

        val run = fixture.runner(destination, pruner = pruner).run(TestControl())

        assertEquals(BackupRun.Stopped(StopCause.Conflict(BackupConflictKind.OTHER_DEVICE)), run)
        val state = fixture.state()
        // La copia propia quedó comprobada y confirmada, y las copias se detienen.
        assertEquals(1L, state.headGeneration)
        assertEquals(state.changeSeq, state.verifiedSeq)
        assertEquals("CONFLICT", state.linkState)
        assertEquals(BackupConflictKind.OTHER_DEVICE.name, state.conflictKind)
        assertEquals(1L, state.conflictGeneration)
        assertEquals(OTHER_DEVICE_ID, state.conflictDeviceId)
        assertEquals(0, pruner.calls)
    }

    @Test
    fun aSecondListingWithoutTheNewCopyOrThatFailsKeepsItVerifiedWithoutPruning() = runBlocking {
        val fake = FakeBackupDestination()
        val pruner = RecordingPruner()
        val stale = StaleSecondListing(fake)

        fixture.addProduct("Pan de prueba")
        assertEquals(
            BackupRun.Verified(1),
            fixture.runner(stale, pruner = pruner).run(TestControl()),
        )
        assertEquals(0, pruner.calls)

        fixture.addProduct("Leche de prueba")
        stale.failSecond = true
        assertEquals(
            BackupRun.Verified(2),
            fixture.runner(stale, pruner = pruner).run(TestControl()),
        )
        assertEquals(0, pruner.calls)
        assertEquals(2L, fixture.state().headGeneration)
    }

    /** Un cambio y una copia nueva, un minuto después de la anterior. */
    private suspend fun copy(destination: BackupDestination, control: TestControl): BackupRun {
        // El nombre de un producto es único: cada copia trae uno nuevo.
        products++
        fixture.addProduct("Producto de prueba $products")
        fixture.advance(1.minutes)
        return fixture.runner(destination).run(control)
    }

    private fun ownCopy(destination: FakeBackupDestination, generation: Long): BackupFileName =
        destination.fileNames.mapNotNull(BackupFileName::parse).single {
            it.generation == generation
        }

    private fun putForeignCopy(destination: FakeBackupDestination, generation: Long) {
        val name =
            BackupFileName(
                generation,
                AppDatabase.SCHEMA_VERSION,
                requireNotNull(CopyInstant.of(fixture.now)),
                OTHER_DEVICE_ID.take(8),
                BackupFileFormat.GZIP,
            )
        val bytes = ByteArray(64) { it.toByte() }
        destination.putFile(name.fileName, bytes)
        destination.putFile(
            name.metaFileName,
            SlMetaCodec.encode(
                SlMeta(
                    format = 1,
                    keyId = null,
                    deviceId = OTHER_DEVICE_ID,
                    deviceLabel = null,
                    generation = generation,
                    schemaVersion = AppDatabase.SCHEMA_VERSION.toLong(),
                    appVersion = null,
                    sha256 = FakeBackupDestination.sha256(bytes),
                    rawSize = 4_096,
                    fileSize = bytes.size.toLong(),
                    records = 10,
                    catalogCount = 2,
                    valid = true,
                ),
            ),
        )
    }

    /**
     * Anota la lectura pedida en cada comprobación. Con [fullReadRunsOut], una lectura completa
     * termina como si se hubiera agotado el tiempo y hubiera hecho la muestra.
     */
    private class RecordingDestination(private val delegate: FakeBackupDestination) :
        BackupDestination by delegate {
        val modes = ArrayList<VerifyMode>()
        var fullReadRunsOut = false

        override suspend fun verify(
            copy: StoredCopy,
            mode: VerifyMode,
            localFile: File?,
            control: TransferControl,
        ): DestinationResult<VerifiedCopy> {
            modes += mode
            val result = delegate.verify(copy, mode, localFile, control)
            return if (fullReadRunsOut && result is DestinationResult.Success) {
                DestinationResult.Success(VerifiedCopy(fullyRead = false))
            } else {
                result
            }
        }
    }

    /** Corre [beforeDelete] justo antes de cada borrado. */
    private class DeleteWatchingDestination(
        private val delegate: FakeBackupDestination,
        private val beforeDelete: suspend () -> Unit,
    ) : BackupDestination by delegate {
        override suspend fun delete(
            ref: io.github.yesidrobayo.shopledger.data.destination.CopyRef,
            control: TransferControl,
        ): DestinationResult<Unit> {
            beforeDelete()
            return delegate.delete(ref, control)
        }
    }

    /** Corre [afterVerify] justo después de comprobar, antes de la segunda lista. */
    private class AfterVerifyDestination(
        private val delegate: FakeBackupDestination,
        private val afterVerify: () -> Unit,
    ) : BackupDestination by delegate {
        override suspend fun verify(
            copy: StoredCopy,
            mode: VerifyMode,
            localFile: File?,
            control: TransferControl,
        ): DestinationResult<VerifiedCopy> =
            delegate.verify(copy, mode, localFile, control).also { afterVerify() }
    }

    /**
     * La segunda lista de cada ejecución llega como de una caché, sin las copias, o falla si
     * [failSecond].
     */
    private class StaleSecondListing(private val delegate: FakeBackupDestination) :
        BackupDestination by delegate {
        var failSecond = false
        private var verified = false

        override suspend fun verify(
            copy: StoredCopy,
            mode: VerifyMode,
            localFile: File?,
            control: TransferControl,
        ): DestinationResult<VerifiedCopy> =
            delegate.verify(copy, mode, localFile, control).also { verified = true }

        override suspend fun listCopies(control: TransferControl): DestinationResult<CopyListing> {
            val listed = delegate.listCopies(control)
            if (!verified) return listed
            verified = false
            if (failSecond) return DestinationResult.Failure(DestinationFailure.Incomplete)
            val listing = (listed as DestinationResult.Success).value
            return DestinationResult.Success(listing.copy(copies = emptyList()))
        }
    }
}
