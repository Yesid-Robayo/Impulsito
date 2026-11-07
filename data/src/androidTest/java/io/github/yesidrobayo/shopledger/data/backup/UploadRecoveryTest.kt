package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.UploadStep
import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.StoredCopy
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.destination.VerifiedCopy
import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una subida cortada se retoma por su nombre, sin generar otra copia y sin tocar nunca la copia
 * anterior. Lo que nunca se confirmó y no sirve se rechaza antes de borrarlo, y nunca se adopta.
 */
@RunWith(AndroidJUnit4::class)
class UploadRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "upload-recovery-test")
    private val control = TestControl()

    @Before
    fun open() = runBlocking {
        fixture.open()
        fixture.connect()
    }

    @After
    fun close() {
        fixture.close()
    }

    /** Dónde muere el proceso, y si al volver hace falta subir el archivo otra vez. */
    private class Cut(
        val label: String,
        val uploadsAgain: Boolean,
        val apply: (FakeBackupDestination) -> Unit,
    )

    @Test
    fun aDeathAfterEachRequestResumesTheSameCopyWithoutTouchingThePreviousOne() = runBlocking {
        val stopped = DestinationFailure.Interrupted(InterruptReason.STOPPED)
        val cuts =
            listOf(
                Cut("antes de subir", uploadsAgain = true) {
                    it.failNext(Operation.UPLOAD, stopped)
                },
                Cut("con el temporal", uploadsAgain = true) {
                    it.crashUploadAfter = UploadStep.AFTER_TEMPORARY_FILE
                },
                Cut("con el archivo", uploadsAgain = false) {
                    it.crashUploadAfter = UploadStep.AFTER_FILE
                },
                Cut("con los metadatos temporales", uploadsAgain = false) {
                    it.crashUploadAfter = UploadStep.AFTER_TEMPORARY_META
                },
                Cut("antes de comprobar", uploadsAgain = false) {
                    it.failNext(Operation.VERIFY, stopped)
                },
                Cut("antes de confirmar", uploadsAgain = false) {
                    it.failNext(Operation.CONFIRM, stopped)
                },
            )
        for ((index, cut) in cuts.withIndex()) {
            withFixture("cut-$index") { own ->
                val destination = FakeBackupDestination()
                val good = goodCopy(destination, own)
                own.addProduct("Leche de prueba")
                own.advance(1.minutes)
                cut.apply(destination)

                val cutRun = own.runner(destination).run(control)

                assertTrue(cut.label, cutRun is BackupRun.Retry)
                assertEquals(cut.label, 2L, own.state().pendingGeneration)
                own.advance(1.minutes)
                val before = destination.calls.size

                val retried = own.runner(destination).run(control)

                // La misma generación: la subida se retomó, no se generó otra copia.
                assertEquals(cut.label, BackupRun.Verified(2), retried)
                val calls = destination.calls.drop(before)
                assertEquals(cut.label, cut.uploadsAgain, Operation.UPLOAD in calls)
                good.forEach { (name, bytes) ->
                    assertArrayEquals(cut.label, bytes, destination.file(name))
                }
                val second = destination.fileNames.filter { it !in good }
                val copy = second.mapNotNull(BackupFileName::parse).single()
                assertEquals(cut.label, 2L, copy.generation)
                assertEquals(
                    cut.label,
                    listOf(copy.fileName, copy.metaFileName).sorted(),
                    second.sorted(),
                )
                val state = own.state()
                assertEquals(cut.label, 2L, state.headGeneration)
                assertEquals(cut.label, copy.fileName, state.headFileRef)
                assertEquals(cut.label, state.changeSeq, state.verifiedSeq)
                assertNull(cut.label, state.pendingGeneration)
                assertEquals(cut.label, emptyList<String>(), own.stagingFiles())
            }
        }
    }

    @Test
    fun aCopyWithAnotherSizeIsRejectedBeforeItIsDeletedAndThenRemoved() = runBlocking {
        val destination = FakeBackupDestination()
        goodCopy(destination)
        val pending = cutAfterTheFile(destination)
        // El servicio guardó con ese nombre otra cosa, más corta.
        destination.putFile(pending.fileName, ByteArray(10))
        destination.failNext(Operation.DELETE, DestinationFailure.Transient(TransientKind.NETWORK))

        val run = fixture.runner(destination).run(control)

        // No se adopta, y la generación nueva no repite la rechazada.
        assertEquals(BackupRun.Verified(3), run)
        assertEquals(setOf(2L), fixture.rejected.load())
        assertTrue(pending.fileName in destination.fileNames)
        assertEquals(3L, fixture.state().headGeneration)

        fixture.addProduct("Arroz de prueba")
        assertEquals(BackupRun.Verified(4), fixture.runner(destination).run(control))

        // El borrado de la próxima ejecución la quitó, y con ella su línea.
        assertFalse(pending.fileName in destination.fileNames)
        assertFalse(fixture.rejectedFile.exists())
    }

    @Test
    fun aRejectedCopyIsNeverConfirmedBySampleEvenIfItLooksComplete() = runBlocking {
        val destination = FakeBackupDestination()
        goodCopy(destination)
        fixture.addProduct("Leche de prueba")
        destination.failNext(
            Operation.VERIFY,
            DestinationFailure.Interrupted(InterruptReason.STOPPED),
        )
        fixture.runner(destination).run(control)
        val pending =
            destination.fileNames.mapNotNull(BackupFileName::parse).single { it.generation == 2L }
        // Una lectura completa la rechazó, y su borrado no llegó.
        fixture.rejected.add(2)
        destination.failNext(Operation.DELETE, DestinationFailure.Transient(TransientKind.NETWORK))

        val run = fixture.runner(destination).run(TestControl(metered = true))

        assertEquals(BackupRun.Verified(3), run)
        val state = fixture.state()
        assertEquals(3L, state.headGeneration)
        assertNotEquals(pending.fileName, state.headFileRef)
        assertEquals(setOf(2L), fixture.rejected.load())

        // La siguiente la borra, y la lista que todavía la muestra no la cuenta como cabeza.
        fixture.addProduct("Arroz de prueba")
        assertEquals(BackupRun.Verified(4), fixture.runner(destination).run(control))
        assertFalse(pending.fileName in destination.fileNames)
        assertFalse(fixture.rejectedFile.exists())
    }

    @Test
    fun aCopyWithoutMetadataAndNoStagedMetadataStaysWithoutDecidingAndIsNotRepeated() =
        runBlocking {
            val destination = FakeBackupDestination()
            goodCopy(destination)
            val pending = cutAfterTheFile(destination)
            File(fixture.stagingDir, "snapshot-g00000002.slmeta").delete()

            val run = fixture.runner(destination).run(control)

            assertEquals(BackupRun.Verified(3), run)
            assertTrue(pending.fileName in destination.fileNames)
            assertFalse(pending.metaFileName in destination.fileNames)
            assertNull(fixture.state().pendingGeneration)
            assertEquals(emptyList<String>(), fixture.stagingFiles())
        }

    @Test
    fun aRecoveredCopyMarkedInvalidIsConfirmedWithItsFailureInSight() = runBlocking {
        val destination = FakeBackupDestination()
        goodCopy(destination)
        val pending = cutAfterTheFile(destination)
        markStagedMeta(pending) { it.copy(valid = false) }

        assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))

        val state = fixture.state()
        assertEquals(2L, state.headGeneration)
        assertEquals(state.changeSeq, state.verifiedSeq)
        assertEquals(BackupFailureCode.LOCAL_INVARIANT.name, state.failureCode)
    }

    @Test
    fun aRecoveredFatalCopyNeverCountsAsBackedUp() = runBlocking {
        val destination = FakeBackupDestination()
        goodCopy(destination)
        val before = fixture.state()
        val pending = cutAfterTheFile(destination)
        markStagedMeta(pending) { it.copy(valid = false, fatal = true) }

        assertEquals(
            BackupRun.Verified(2, unrestorable = true),
            fixture.runner(destination).run(control),
        )

        val state = fixture.state()
        assertEquals(2L, state.headGeneration)
        assertEquals(before.verifiedSeq, state.verifiedSeq)
        assertEquals(before.lastSuccessAt, state.lastSuccessAt)
        assertEquals(BackupFailureCode.LOCAL_INVARIANT_FATAL.name, state.failureCode)
    }

    @Test
    fun theStagedFileNeverLastsMoreThanADay() = runBlocking {
        val destination = FakeBackupDestination()
        goodCopy(destination)
        fixture.addProduct("Leche de prueba")
        destination.crashUploadAfter = UploadStep.AFTER_TEMPORARY_FILE
        fixture.runner(destination).run(control)
        assertEquals(
            listOf("snapshot-g00000002.db.gz", "snapshot-g00000002.slmeta"),
            fixture.stagingFiles(),
        )
        fixture.advance(25.hours)
        val before = destination.calls.size

        val run = fixture.runner(destination).run(control)

        // El archivo vencido se borró al empezar: no se sube, se genera otra copia. Su generación
        // queda por encima del temporal que esa misma lista todavía mostraba.
        assertEquals(BackupRun.Verified(3), run)
        assertEquals(1, destination.calls.drop(before).count { it == Operation.UPLOAD })
        assertEquals(emptyList<String>(), fixture.stagingFiles())
        assertTrue(destination.fileNames.none { it.endsWith(BackupFileName.TEMPORARY_SUFFIX) })
    }

    @Test
    fun withoutTheLocalFileARecoveredCopyIsReadWholeAndThenPruned() = runBlocking {
        for (metered in listOf(false, true)) {
            withFixture("metered-$metered") { own ->
                val fake = FakeBackupDestination()
                goodCopy(fake, own)
                own.addProduct("Leche de prueba")
                fake.failNext(
                    Operation.VERIFY,
                    DestinationFailure.Interrupted(InterruptReason.STOPPED),
                )
                own.runner(fake).run(control)
                File(own.stagingDir, "snapshot-g00000002.db.gz").delete()
                val destination = ModeRecordingDestination(fake)
                val pruner = RecordingPruner()

                val run =
                    own.runner(destination, pruner = pruner).run(TestControl(metered = metered))

                // Una copia retomada siempre se lee entera; en red medida, porque la política de
                // la prueba lo permite.
                assertEquals(BackupRun.Verified(2), run)
                assertEquals(listOf(VerifyMode.FULL), destination.modes)
                assertEquals(1, pruner.calls)
            }
        }
    }

    @Test
    fun ownTemporariesAreDeletedAndThoseOfAnotherPhoneAreNeverTouched() = runBlocking {
        val destination = FakeBackupDestination()
        val own = name(7, TEST_DEVICE_ID).fileName + BackupFileName.TEMPORARY_SUFFIX
        val foreign = name(7, OTHER_DEVICE_ID).fileName + BackupFileName.TEMPORARY_SUFFIX
        destination.putFile(own, ByteArray(5))
        destination.putFile(foreign, ByteArray(5))
        fixture.addProduct("Pan de prueba")

        // La generación nueva queda por encima del temporal propio, que la lista mostraba.
        assertEquals(BackupRun.Verified(8), fixture.runner(destination).run(control))

        assertFalse(own in destination.fileNames)
        assertTrue(foreign in destination.fileNames)
    }

    @Test
    fun anIncompleteIntentIsDiscardedAndAFailedSearchKeepsIt() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.sql("UPDATE backup_state SET pending_generation = 5 WHERE id = 1")
        val destination = FakeBackupDestination()

        // Sin suma ni tamaño no hay con qué reconocerla: se empieza de nuevo.
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))

        fixture.addProduct("Leche de prueba")
        destination.failNext(
            Operation.VERIFY,
            DestinationFailure.Interrupted(InterruptReason.STOPPED),
        )
        fixture.runner(destination).run(control)
        destination.failNext(Operation.LOCATE, DestinationFailure.Transient(TransientKind.NETWORK))

        val run = fixture.runner(destination).run(control)

        assertEquals(
            BackupRun.Retry(RetryReason.TRANSIENT, transientKind = TransientKind.NETWORK),
            run,
        )
        assertEquals(2L, fixture.state().pendingGeneration)
        assertEquals(
            listOf("snapshot-g00000002.db.gz", "snapshot-g00000002.slmeta"),
            fixture.stagingFiles(),
        )
    }

    @Test
    fun theRejectedFileKeepsOneGenerationPerLineAndIgnoresWhatIsNotOne() = runBlocking {
        val store = fixture.rejected
        assertEquals(emptySet<Long>(), store.load())
        store.add(9)
        store.add(3)
        store.add(9)
        assertEquals("3\n9\n", fixture.rejectedFile.readText())
        fixture.rejectedFile.writeText("3\n007\n-1\nabc\n\n12\n")
        assertEquals(setOf(3L, 12L), store.load())
        store.remove(3)
        store.remove(12)
        assertFalse(fixture.rejectedFile.exists())
    }

    /** Corre [block] sobre una base propia, que se borra al terminar. */
    private suspend fun withFixture(name: String, block: suspend (BackupFixture) -> Unit) {
        val own = BackupFixture(context, "upload-recovery-$name")
        try {
            own.open()
            own.connect()
            block(own)
        } finally {
            own.close()
        }
    }

    /** Una primera copia buena; devuelve sus archivos con su contenido. */
    private suspend fun goodCopy(
        destination: FakeBackupDestination,
        fixture: BackupFixture = this.fixture,
    ): Map<String, ByteArray> {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        return destination.fileNames.associateWith { requireNotNull(destination.file(it)) }
    }

    /** Cambia los metadatos que esperan en el teléfono para la copia [name]. */
    private fun markStagedMeta(name: BackupFileName, change: (SlMeta) -> SlMeta) {
        val file = File(
            fixture.stagingDir,
            "snapshot-g" + BackupStaging.generationText(name.generation) + ".slmeta",
        )
        val meta = (
            SlMetaCodec.decode(
                file.readBytes(),
                name.identity(),
            ) as CopyMetadata.Valid
            ).meta
        file.writeBytes(SlMetaCodec.encode(change(meta)))
    }

    /** Un cambio y una subida que muere con el archivo en su nombre final, sin metadatos. */
    private suspend fun cutAfterTheFile(destination: FakeBackupDestination): BackupFileName {
        fixture.addProduct("Leche de prueba")
        fixture.advance(1.minutes)
        destination.crashUploadAfter = UploadStep.AFTER_FILE
        fixture.runner(destination).run(control)
        return destination.fileNames.mapNotNull(BackupFileName::parse).single {
            it.generation == 2L
        }
    }

    private fun name(generation: Long, device: String) = BackupFileName(
        generation,
        AppDatabase.SCHEMA_VERSION,
        requireNotNull(CopyInstant.of(fixture.now)),
        device.take(8),
        BackupFileFormat.GZIP,
    )

    /** Anota con qué lectura se pidió comprobar cada copia. */
    private class ModeRecordingDestination(private val delegate: FakeBackupDestination) :
        BackupDestination by delegate {
        val modes = ArrayList<VerifyMode>()

        override suspend fun verify(
            copy: StoredCopy,
            mode: VerifyMode,
            localFile: File?,
            control: TransferControl,
        ): DestinationResult<VerifiedCopy> {
            modes += mode
            return delegate.verify(copy, mode, localFile, control)
        }
    }
}
