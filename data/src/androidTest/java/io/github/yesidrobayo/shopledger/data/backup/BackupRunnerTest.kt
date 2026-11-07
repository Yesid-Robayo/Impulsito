package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
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
import io.github.yesidrobayo.shopledger.data.destination.UnsupportedReason
import io.github.yesidrobayo.shopledger.data.destination.UploadPlan
import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El motor del respaldo con el destino falso en memoria y la base real: sube, comprueba y solo
 * entonces confirma y deja podar. Cada fallo del destino deja su estado a la vista, y ninguno toca
 * la última copia buena.
 */
@RunWith(AndroidJUnit4::class)
class BackupRunnerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "backup-runner-test")
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

    @Test
    fun aCopyIsUploadedVerifiedAndConfirmedAndOnlyThenPruned() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        val pruner = PruneAfterVerifyCheck(destination)

        val run = fixture.runner(destination, pruner = pruner).run(control)

        assertEquals(BackupRun.Verified(1), run)
        assertEquals(1, pruner.calls)
        val name = destination.fileNames.single { BackupFileName.parse(it) != null }
        val copyName = requireNotNull(BackupFileName.parse(name))
        assertEquals(1L, copyName.generation)
        assertEquals(AppDatabase.SCHEMA_VERSION, copyName.schemaVersion)
        assertEquals(TEST_DEVICE_ID.take(8), copyName.devicePrefix)
        assertEquals(CopyInstant.of(fixture.now), copyName.createdAt)
        assertEquals(listOf(name, copyName.metaFileName), destination.fileNames)
        val state = fixture.state()
        assertEquals(1L, state.headGeneration)
        assertEquals(TEST_DEVICE_ID, state.headDeviceId)
        assertEquals(name, state.headFileRef)
        assertEquals(state.changeSeq, state.verifiedSeq)
        assertNull(state.firstUnsyncedAt)
        assertNull(state.pendingGeneration)
        assertNull(state.failureCode)
        assertEquals(fixture.now.toEpochMilliseconds(), state.lastSuccessAt)
        assertEquals(fixture.now.toEpochMilliseconds(), state.lastUploadFinishedAt)
        assertEquals(fixture.now.toEpochMilliseconds(), state.lastAttemptAt)
        // Nada queda en el teléfono: la copia ya está comprobada en el destino.
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun theCopyIsTheSealedDatabaseAndItsMetadataSayWhatItHas() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()

        fixture.runner(destination).run(control)

        val name = requireNotNull(destination.fileNames.firstNotNullOfOrNull(BackupFileName::parse))
        val bytes = requireNotNull(destination.file(name.fileName))
        val meta =
            SlMetaCodec.decode(destination.file(name.metaFileName), name.identity())
                as CopyMetadata.Valid
        assertEquals(bytes.size.toLong(), meta.meta.fileSize)
        assertEquals(FakeBackupDestination.sha256(bytes), meta.meta.sha256)
        assertEquals(TEST_DEVICE_ID, meta.meta.deviceId)
        assertEquals("Fabricante Modelo", meta.meta.deviceLabel)
        assertEquals("1.0.0", meta.meta.appVersion)
        assertTrue(meta.meta.valid)
        assertFalse(meta.meta.fatal)
        // Un producto sin stock inicial: ningún registro del libro, y uno del catálogo.
        assertEquals(1L, meta.meta.catalogCount)
        assertEquals(0L, meta.meta.records)

        val restored = File(context.cacheDir, "backup-runner-restored.db")
        try {
            restored.writeBytes(GZIPInputStream(bytes.inputStream()).readBytes())
            assertEquals(restored.length(), meta.meta.rawSize)
            BundledSQLiteDriver().open(restored.path, SQLITE_OPEN_READONLY).use { copy ->
                // El sello: qué copia es, y nada del destino ni de la subida en curso.
                assertEquals(1L, copy.longValue("SELECT copy_generation FROM backup_state"))
                assertEquals(
                    1L,
                    copy.longValue(
                        "SELECT COUNT(*) FROM backup_state WHERE copy_device_id = " +
                            "'$TEST_DEVICE_ID' AND copy_created_at = " +
                            "${fixture.now.toEpochMilliseconds()} AND destination_kind IS NULL " +
                            "AND destination_id IS NULL AND destination_folder IS NULL " +
                            "AND destination_account IS NULL AND pending_generation IS NULL " +
                            "AND conflict_kind IS NULL AND last_full_verify_at IS NULL " +
                            "AND human_check_generation IS NULL AND last_human_check_at IS NULL",
                    ),
                )
                assertEquals(1L, copy.longValue("SELECT COUNT(*) FROM products"))
            }
            // La base del teléfono sigue conectada: el sello solo va en la copia.
            assertEquals(TEST_DESTINATION_ID, fixture.state().destinationId)
            assertNull(fixture.state().copyGeneration)
        } finally {
            restored.delete()
        }
    }

    @Test
    fun theRetentionPruneRunsAfterConfirmingAndKeepsTheNewestOfTheDay() = runBlocking {
        val destination = FakeBackupDestination()
        val pruner = RetentionPruner(deviceId = { TEST_DEVICE_ID }, log = fixture.log)
        // Las tres copias deben caer en el mismo día UTC.
        if (fixture.now.epochSeconds % SECONDS_PER_DAY > SECONDS_PER_DAY - 600) {
            fixture.advance(20.minutes)
        }
        for (generation in 1L..3L) {
            fixture.addProduct("Producto de prueba $generation")
            assertEquals(
                BackupRun.Verified(generation),
                fixture.runner(destination, pruner = pruner).run(control),
            )
            fixture.advance(1.minutes)
        }

        // Las tres son del mismo día: queda la cabeza, con sus metadatos.
        val head = requireNotNull(fixture.state().headFileRef)
        val parsed = requireNotNull(BackupFileName.parse(head))
        assertEquals(listOf(head, parsed.metaFileName).sorted(), destination.fileNames.sorted())
        assertEquals(3L, parsed.generation)
    }

    @Test
    fun withSlotsTheCopyGoesToTheChosenSlotAndIsNeverPruned() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
        val pruner = RecordingPruner()

        val run = fixture.runner(destination, pruner = pruner).run(control)

        assertEquals(BackupRun.Verified(1), run)
        val slot = requireNotNull(destination.slot(CopyRef("A")))
        assertTrue(slot.confirmed)
        assertEquals("A", fixture.state().headFileRef)
        assertEquals(0, pruner.calls)
        assertEquals(
            listOf(
                Operation.LIST,
                Operation.PLAN,
                Operation.UPLOAD,
                Operation.VERIFY,
                Operation.CONFIRM,
            ),
            destination.calls,
        )
    }

    @Test
    fun theNextCopyTakesTheNextGenerationAndTheHeadAsItsReference() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
        fixture.runner(destination).run(control)
        fixture.addProduct("Leche de prueba")

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Verified(2), run)
        assertEquals(CopyRef("A"), destination.lastHeadFileRef)
        assertEquals("B", fixture.state().headFileRef)
        assertEquals(2L, fixture.state().headGeneration)
    }

    @Test
    fun withoutAConnectedDestinationNothingIsTouched() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.sql("UPDATE backup_state SET link_state = 'NOT_CONNECTED' WHERE id = 1")
        val destination = FakeBackupDestination()

        assertEquals(
            BackupRun.Skipped(SkipReason.NOT_CONNECTED),
            fixture.runner(destination).run(control),
        )
        assertEquals(emptyList<Operation>(), destination.calls)
    }

    @Test
    fun aLostOrConflictedLinkWaitsForThePerson() = runBlocking {
        fixture.addProduct("Pan de prueba")
        for (link in listOf("PERMISSION_LOST", "CONFLICT")) {
            fixture.sql("UPDATE backup_state SET link_state = '$link' WHERE id = 1")
            val destination = FakeBackupDestination()
            assertEquals(
                link,
                BackupRun.Skipped(SkipReason.NOT_CONNECTED),
                fixture.runner(destination).run(control),
            )
            assertEquals(emptyList<Operation>(), destination.calls)
        }
    }

    @Test
    fun withEveryChangeBackedUpThereIsNothingToDo() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        fixture.runner(destination).run(control)
        val calls = destination.calls.size

        assertEquals(
            BackupRun.Skipped(SkipReason.NOTHING_PENDING),
            fixture.runner(destination).run(control),
        )
        assertEquals(calls, destination.calls.size)
    }

    @Test
    fun anEmptyDatabaseNeverUploads() = runBlocking {
        // Un cambio contado sin nada del negocio, como un ajuste de la app.
        fixture.sql("UPDATE backup_state SET change_seq = change_seq + 1 WHERE id = 1")
        val destination = FakeBackupDestination()

        assertEquals(
            BackupRun.Skipped(SkipReason.EMPTY_DATABASE),
            fixture.runner(destination).run(control),
        )
        assertEquals(emptyList<Operation>(), destination.calls)
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun aDeathMidUploadLeavesTheGoodCopyAndNoFinalNameAndUploadsTheSameFileAgain() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        fixture.runner(destination).run(control)
        val good = destination.fileNames.toList()
        val goodBytes = good.map { requireNotNull(destination.file(it)) }
        fixture.addProduct("Leche de prueba")
        fixture.advance(1.minutes)
        destination.crashUploadAfter = UploadStep.AFTER_TEMPORARY_FILE

        val cut = fixture.runner(destination).run(control)

        assertEquals(
            BackupRun.Retry(RetryReason.INTERRUPTED, interruptReason = InterruptReason.STOPPED),
            cut,
        )
        // Solo el temporal: ningún nombre final a medias, y la copia buena intacta.
        val added = destination.fileNames - good.toSet()
        assertEquals(1, added.size)
        assertTrue(added.single().endsWith(BackupFileName.TEMPORARY_SUFFIX))
        good.zip(goodBytes).forEach { (name, bytes) ->
            assertArrayEquals(bytes, destination.file(name))
        }
        val pending = fixture.state()
        assertEquals(2L, pending.pendingGeneration)
        assertEquals(1L, pending.headGeneration)
        // El archivo se conserva para el siguiente intento.
        assertEquals(
            listOf("snapshot-g00000002.db.gz", "snapshot-g00000002.slmeta"),
            fixture.stagingFiles(),
        )

        fixture.advance(1.minutes)
        val retried = fixture.runner(destination).run(control)

        // Sube otra vez el mismo archivo, con la misma generación, y el temporal ya no está.
        assertEquals(BackupRun.Verified(2), retried)
        good.zip(goodBytes).forEach { (name, bytes) ->
            assertArrayEquals(bytes, destination.file(name))
        }
        assertTrue(destination.fileNames.none { it.endsWith(BackupFileName.TEMPORARY_SUFFIX) })
        assertEquals(2L, fixture.state().headGeneration)
        assertNull(fixture.state().pendingGeneration)
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun aDeathAfterTheFileUploadsOnlyTheMetadataAndConfirmsThatCopy() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        destination.crashUploadAfter = UploadStep.AFTER_FILE

        fixture.runner(destination).run(control)
        val before = destination.calls.size
        val retried = fixture.runner(destination).run(control)

        // El archivo ya estaba entero: solo faltaban sus metadatos, que esperaban en el teléfono.
        assertEquals(BackupRun.Verified(1), retried)
        val calls = destination.calls.drop(before)
        assertFalse(Operation.UPLOAD in calls)
        assertTrue(Operation.UPLOAD_META in calls)
    }

    @Test
    fun aVerificationThatFailsConfirmsNothingAndPrunesNothing() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        fixture.runner(destination).run(control)
        val good = destination.fileNames.toList()
        fixture.addProduct("Leche de prueba")
        destination.failNext(Operation.VERIFY, DestinationFailure.VerifyMismatch)
        val pruner = RecordingPruner()

        val run = fixture.runner(destination, pruner = pruner).run(control)

        assertEquals(BackupRun.Stopped(StopCause.Failure(BackupFailureCode.VERIFY_MISMATCH)), run)
        assertEquals(0, pruner.calls)
        val state = fixture.state()
        assertEquals("VERIFY_MISMATCH", state.failureCode)
        assertEquals(1L, state.headGeneration)
        assertNull(state.pendingGeneration)
        assertTrue(state.changeSeq > state.verifiedSeq)
        assertTrue(destination.fileNames.containsAll(good))
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun aRejectedCredentialLeavesTheLinkLostAndChangesKeepCounting() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        destination.failNext(Operation.LIST, DestinationFailure.PermissionLost())

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Stopped(StopCause.PermissionLost), run)
        assertEquals("PERMISSION_LOST", fixture.state().linkState)
        val before = fixture.state().changeSeq
        fixture.addProduct("Leche de prueba")
        assertEquals(before + 1, fixture.state().changeSeq)
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun aFullServiceStopsWithoutPruningAndKeepsTheLastGoodCopy() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        fixture.runner(destination).run(control)
        val good = destination.fileNames.toList()
        fixture.addProduct("Leche de prueba")
        destination.failNext(Operation.UPLOAD, DestinationFailure.QuotaExceeded)
        val pruner = RecordingPruner()

        val run = fixture.runner(destination, pruner = pruner).run(control)

        assertEquals(BackupRun.Stopped(StopCause.Failure(BackupFailureCode.QUOTA_EXCEEDED)), run)
        assertEquals("QUOTA_EXCEEDED", fixture.state().failureCode)
        assertEquals(0, pruner.calls)
        assertEquals(good, destination.fileNames)
        // Un fallo que no se reintenta solo no deja el archivo en el teléfono.
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun aDestinationThatDoesNotServeStopsAsUnsupported() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        destination.failNext(
            Operation.UPLOAD,
            DestinationFailure.Unsupported(UnsupportedReason.NAME_REJECTED),
        )

        val run = fixture.runner(destination).run(control)

        assertEquals(
            BackupRun.Stopped(StopCause.Failure(BackupFailureCode.DESTINATION_UNSUPPORTED)),
            run,
        )
        assertEquals("DESTINATION_UNSUPPORTED", fixture.state().failureCode)
    }

    @Test
    fun aTransportFailureRetriesAndKeepsTheFileAndTheIntent() = runBlocking {
        fixture.addProduct("Pan de prueba")
        for (kind in TransientKind.entries) {
            val destination = FakeBackupDestination()
            destination.failNext(Operation.UPLOAD, DestinationFailure.Transient(kind))

            val run = fixture.runner(destination).run(control)

            assertEquals(BackupRun.Retry(RetryReason.TRANSIENT, transientKind = kind), run)
            val pending = BackupStaging.generationText(
                requireNotNull(fixture.state().pendingGeneration),
            )
            assertEquals(
                listOf("snapshot-g$pending.db.gz", "snapshot-g$pending.slmeta"),
                fixture.stagingFiles(),
            )
            assertNull(fixture.state().failureCode)
        }
    }

    @Test
    fun sixTlsFailuresInARowLeaveTheTransportFailurePersistentUntilACopyIsConfirmed() =
        runBlocking {
            fixture.addProduct("Pan de prueba")
            val destination = FakeBackupDestination()
            destination.failNext(
                Operation.UPLOAD,
                DestinationFailure.Transient(TransientKind.TLS),
                times = 6,
            )

            for (attempt in 1L..5L) {
                fixture.runner(destination).run(control)
                assertEquals(attempt, fixture.state().consecutiveFailures)
                assertEquals("TLS", fixture.state().lastFailureKind)
                // Antes del sexto, solo es una espera que se reintenta: nada a la vista.
                assertNull(fixture.state().failureCode)
            }
            fixture.runner(destination).run(control)
            assertEquals(6L, fixture.state().consecutiveFailures)
            assertEquals("TLS", fixture.state().lastFailureKind)
            assertEquals(BackupFailureCode.TRANSPORT_PERSISTENT.name, fixture.state().failureCode)

            // El trabajo sigue reintentando, y la primera copia confirmada borra todo.
            assertTrue(fixture.runner(destination).run(control) is BackupRun.Verified)
            assertEquals(0L, fixture.state().consecutiveFailures)
            assertNull(fixture.state().lastFailureKind)
            assertNull(fixture.state().failureCode)
        }

    @Test
    fun eachTransportClassIsKeptAndRunningOutOfTimeCountsAsNetwork() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val expected =
            mapOf(
                DestinationFailure.Transient(TransientKind.NETWORK) to "NETWORK",
                DestinationFailure.Transient(TransientKind.SERVER_ERROR) to "HTTP_5XX",
                DestinationFailure.Transient(TransientKind.RATE_LIMIT) to "RATE_LIMIT",
                // Una subida cortada por el tiempo no dejó nada: cuenta, como red.
                DestinationFailure.Interrupted(InterruptReason.OUT_OF_TIME) to "NETWORK",
            )
        var count = 0L
        for ((failure, kind) in expected) {
            val destination = FakeBackupDestination()
            destination.failNext(Operation.UPLOAD, failure)

            fixture.runner(destination).run(control)

            count++
            assertEquals(count, fixture.state().consecutiveFailures)
            assertEquals(kind, fixture.state().lastFailureKind)
        }
    }

    @Test
    fun stoppingOrALocalFailureIsNotATransportFailure() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val notCounted =
            listOf(
                DestinationFailure.Interrupted(InterruptReason.STOPPED),
                DestinationFailure.Interrupted(InterruptReason.METERED),
                DestinationFailure.Incomplete,
                DestinationFailure.LocalStorage,
            )
        for (failure in notCounted) {
            val destination = FakeBackupDestination()
            destination.failNext(Operation.UPLOAD, failure)

            assertTrue(fixture.runner(destination).run(control) is BackupRun.Retry)
        }
        control.stopped = true
        fixture.runner(FakeBackupDestination()).run(control)

        assertEquals(0L, fixture.state().consecutiveFailures)
        assertNull(fixture.state().lastFailureKind)
    }

    @Test
    fun anIncompleteListingIsRetriedWithoutCopyingTheDatabase() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        destination.failNext(Operation.LIST, DestinationFailure.Incomplete)

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Retry(RetryReason.INCOMPLETE_LISTING), run)
        assertEquals(listOf(Operation.LIST), destination.calls)
        assertEquals(emptyList<String>(), fixture.stagingFiles())
        assertNull(fixture.state().pendingGeneration)
    }

    @Test
    fun aSlotChangedOutsideOrNoSafeSlotStopsAsAConflict() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val cases =
            mapOf(
                Operation.LIST to
                    (
                        DestinationFailure.ChangedOutside(CopyRef("B")) to
                            BackupConflictKind.OTHER_DEVICE
                        ),
                Operation.PLAN to
                    (
                        DestinationFailure.NoSafeSlot(CopyRef("A")) to
                            BackupConflictKind.LOCAL_SHRUNK
                        ),
            )
        for ((operation, case) in cases) {
            fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
            val (failure, kind) = case
            val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
            destination.failNext(operation, failure)

            val run = fixture.runner(destination).run(control)

            assertEquals(BackupRun.Stopped(StopCause.Conflict(kind)), run)
            val state = fixture.state()
            assertEquals("CONFLICT", state.linkState)
            assertEquals(kind.name, state.conflictKind)
            // La ranura queda para crear un archivo nuevo en su lugar, sin generación: de ese lado
            // no hay una copia que contar.
            assertEquals(if (operation == Operation.LIST) "B" else "A", state.conflictFileRef)
            assertNull(state.conflictGeneration)
            assertFalse(destination.calls.contains(Operation.UPLOAD))
        }
    }

    @Test
    fun aPhoneThatCannotSaveTheSlotStateRetriesAndShowsLowStorage() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
        destination.failNext(Operation.CONFIRM, DestinationFailure.LocalStorage)

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Retry(RetryReason.LOCAL_STATE_NOT_SAVED), run)
        assertEquals("LOCAL_STORAGE_LOW", fixture.state().failureCode)
        assertEquals(0L, fixture.state().headGeneration)
    }

    @Test
    fun withoutSpaceForACopyNothingIsCopiedOrListed() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()

        val run = fixture.runner(destination, freeSpace = { 0L }).run(control)

        assertEquals(BackupRun.Stopped(StopCause.Failure(BackupFailureCode.LOCAL_STORAGE_LOW)), run)
        assertEquals("LOCAL_STORAGE_LOW", fixture.state().failureCode)
        assertEquals(emptyList<Operation>(), destination.calls)
        assertEquals(emptyList<String>(), fixture.stagingFiles())
    }

    @Test
    fun aDamagedDatabaseIsNeverUploaded() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val damaged = File(context.cacheDir, "backup-runner-damaged.db")
        try {
            damaged.writeBytes(ByteArray(8_192) { 0x5A })
            val destination = FakeBackupDestination()

            val run =
                fixture.runner(destination, snapshots = SnapshotCreator(damaged, Dispatchers.IO))
                    .run(control)

            assertEquals(BackupRun.Stopped(StopCause.Failure(BackupFailureCode.LOCAL_CORRUPT)), run)
            assertEquals("LOCAL_CORRUPT", fixture.state().failureCode)
            assertFalse(destination.calls.contains(Operation.UPLOAD))
            assertFalse(destination.calls.contains(Operation.PLAN))
        } finally {
            damaged.delete()
        }
    }

    @Test
    fun aNewerCopyOfAnotherPhoneIsAConflictWithoutTouchingAnything() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        val foreign = putForeignCopy(destination, generation = 5, deviceId = OTHER_DEVICE_ID)

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Stopped(StopCause.Conflict(BackupConflictKind.OTHER_DEVICE)), run)
        assertEquals(listOf(Operation.LIST), destination.calls)
        assertEquals(emptyList<String>(), fixture.stagingFiles())
        val state = fixture.state()
        assertNull(state.pendingGeneration)
        assertEquals("CONFLICT", state.linkState)
        assertEquals(5L, state.conflictGeneration)
        assertEquals(OTHER_DEVICE_ID, state.conflictDeviceId)
        assertEquals(foreign.fileName, state.conflictFileRef)
        // Un conflicto espera a la persona: la ejecución siguiente no hace nada.
        fixture.addProduct("Leche de prueba")
        assertEquals(
            BackupRun.Skipped(SkipReason.NOT_CONNECTED),
            fixture.runner(destination).run(control),
        )
    }

    @Test
    fun aHeadFromANewerAppStopsWithTheCodeToUpdate() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        putForeignCopy(
            destination,
            generation = 5,
            deviceId = OTHER_DEVICE_ID,
            schemaVersion = AppDatabase.SCHEMA_VERSION + 1,
        )

        val run = fixture.runner(destination).run(control)

        assertEquals(
            BackupRun.Stopped(StopCause.Failure(BackupFailureCode.CLOUD_SCHEMA_NEWER)),
            run,
        )
        assertEquals("CLOUD_SCHEMA_NEWER", fixture.state().failureCode)
        assertEquals("CONNECTED", fixture.state().linkState)
        assertEquals(listOf(Operation.LIST), destination.calls)
    }

    @Test
    fun aBaseWithFewerRecordsThanItsOwnHeadIsAShrunkBase() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        assertTrue(fixture.runner(destination).run(control) is BackupRun.Verified)
        val head = fixture.state()
        // La cabeza que la base confirmó dice tener más de lo que la base tiene ahora, como tras
        // una migración que perdiera filas.
        val name = requireNotNull(head.headFileRef)
        val parsed = requireNotNull(BackupFileName.parse(name))
        val meta = requireNotNull(destination.file(parsed.metaFileName))
        val decoded = SlMetaCodec.decode(meta, parsed.identity()) as CopyMetadata.Valid
        destination.putFile(
            parsed.metaFileName,
            SlMetaCodec.encode(decoded.meta.copy(records = decoded.meta.records + 50)),
        )
        fixture.addProduct("Leche de prueba")

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Stopped(StopCause.Conflict(BackupConflictKind.LOCAL_SHRUNK)), run)
        val state = fixture.state()
        assertEquals(head.headGeneration, state.conflictGeneration)
        assertEquals(name, state.conflictFileRef)
    }

    @Test
    fun aStopRequestBetweenStepsRetriesWithoutUploading() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        control.stopped = true

        val run = fixture.runner(destination).run(control)

        assertEquals(
            BackupRun.Retry(RetryReason.INTERRUPTED, interruptReason = InterruptReason.STOPPED),
            run,
        )
        assertEquals(emptyList<Operation>(), destination.calls)
    }

    @Test
    fun aSaleDuringTheCopyStaysPendingFromTheInstantOfTheCopy() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.advance(5.minutes)
        val copiedAt = fixture.now
        val destination =
            HookedDestination(FakeBackupDestination()) {
                // Llega una venta mientras la copia sube: la copia no la trae.
                fixture.advance(1.minutes)
                fixture.addProduct("Leche de prueba")
            }

        val run = fixture.runner(destination).run(control)

        assertEquals(BackupRun.Verified(1), run)
        val state = fixture.state()
        assertEquals(state.changeSeq - 1, state.verifiedSeq)
        assertEquals(copiedAt.toEpochMilliseconds(), state.firstUnsyncedAt)
    }

    @Test
    fun aDestinationChangedDuringTheUploadIsNotConfirmed() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val pruner = RecordingPruner()
        val destination =
            HookedDestination(FakeBackupDestination()) {
                fixture.sql("UPDATE backup_state SET destination_id = 'otro-destino' WHERE id = 1")
            }

        val run = fixture.runner(destination, pruner = pruner).run(control)

        assertEquals(BackupRun.Skipped(SkipReason.DESTINATION_CHANGED), run)
        assertEquals(0L, fixture.state().headGeneration)
        assertEquals(0, pruner.calls)
    }

    @Test
    fun aMeteredNetworkSamplesAndAnUnmeteredOneReadsItAll() = runBlocking {
        assertEquals(VerifyMode.FULL, verifyModeFor(TestControl(metered = false)))
        assertEquals(VerifyMode.SAMPLE, verifyModeFor(TestControl(metered = true)))
    }

    /** Una copia de otro teléfono, con sus metadatos, como si la hubiera subido él. */
    private fun putForeignCopy(
        destination: FakeBackupDestination,
        generation: Long,
        deviceId: String,
        schemaVersion: Int = AppDatabase.SCHEMA_VERSION,
    ): BackupFileName {
        val name =
            BackupFileName(
                generation,
                schemaVersion,
                requireNotNull(CopyInstant.of(fixture.now)),
                deviceId.take(8),
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
                    deviceId = deviceId,
                    deviceLabel = null,
                    generation = generation,
                    schemaVersion = schemaVersion.toLong(),
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
        return name
    }

    /** Una poda que solo acepta correr con la copia nueva ya confirmada en el estado. */
    private inner class PruneAfterVerifyCheck(private val destination: FakeBackupDestination) :
        CopyPruner {
        var calls = 0
            private set

        override suspend fun prune(
            destination: BackupDestination,
            confirmed: ConfirmedCopy,
            control: TransferControl,
        ): DestinationResult<PruneReport> {
            calls++
            assertEquals(fixture.state().headFileRef, confirmed.ref.value)
            assertEquals(fixture.state().headGeneration, confirmed.generation)
            assertNull(fixture.state().pendingGeneration)
            assertEquals(Operation.CONFIRM, this.destination.calls.last())
            return DestinationResult.Success(PruneReport(PruneResult.PRUNED, 0))
        }
    }

    /** Un destino que corre [afterUpload] justo después de subir, antes de comprobar. */
    private class HookedDestination(
        private val delegate: FakeBackupDestination,
        private val afterUpload: suspend () -> Unit,
    ) : BackupDestination by delegate {
        override suspend fun upload(
            plan: UploadPlan,
            control: TransferControl,
        ): DestinationResult<StoredCopy> {
            val result = delegate.upload(plan, control)
            afterUpload()
            return result
        }
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
