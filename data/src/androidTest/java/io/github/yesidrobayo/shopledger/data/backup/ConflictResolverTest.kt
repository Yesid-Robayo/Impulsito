package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las tres opciones de un conflicto con el destino falso y la base real. Restaurar es la
 * restauración de siempre; decidir después no cambia nada; conservar este teléfono nunca borra una
 * copia, protege la cabeza antes de seguir y, sin poder protegerla, no confirma.
 */
@RunWith(AndroidJUnit4::class)
class ConflictResolverTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "conflict-resolver-test")
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
    fun keepingThisPhoneOverAnotherPhonePinsTheHeadAndUploadsAboveIt() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        val foreign =
            putCopy(destination, generation = 5, deviceId = OTHER_DEVICE_ID, records = 900)
        assertConflict(BackupConflictKind.OTHER_DEVICE, fixture.runner(destination).run(control))
        val pinner = RecordingPinner()
        val resolver = resolver(destination, pinner)

        // La otra copia tiene más registros: primero se confirma, con los dos lados a la vista.
        val asked = resolver.keepThisPhone(fewerRecordsConfirmed = false, control)
        val view = (asked as KeepOutcome.NeedsConfirmation).view
        assertEquals(900L, view.cloudRecords)
        assertEquals(5L, view.cloudGeneration)
        assertTrue(view.localRecords < 900)
        assertEquals(emptyList<List<CopyRef>>(), pinner.pinned)
        assertEquals("CONFLICT", fixture.state().linkState)

        val before = fixture.state().changeSeq
        assertEquals(
            KeepOutcome.Kept,
            resolver.keepThisPhone(fewerRecordsConfirmed = true, control),
        )

        assertEquals(listOf(listOf(CopyRef(foreign.fileName))), pinner.pinned)
        val state = fixture.state()
        assertEquals("CONNECTED", state.linkState)
        assertEquals(5L, state.headGeneration)
        assertEquals(OTHER_DEVICE_ID, state.headDeviceId)
        assertEquals(foreign.fileName, state.headFileRef)
        assertEquals(5L, state.shrinkAcceptedGeneration)
        assertNull(state.conflictKind)
        assertNull(state.conflictGeneration)
        assertEquals(before + 1, state.changeSeq)
        // La copia siguiente va por encima, y la del otro teléfono sigue ahí.
        assertEquals(BackupRun.Verified(6), fixture.runner(destination).run(control))
        assertTrue(destination.fileNames.contains(foreign.fileName))
        assertTrue(destination.calls.none { it == Operation.DELETE })
    }

    @Test
    fun withoutPinningTheHeadKeepingThisPhoneIsNeverConfirmed() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        putCopy(destination, generation = 5, deviceId = OTHER_DEVICE_ID, records = 0)
        fixture.runner(destination).run(control)
        val failure = DestinationFailure.Transient(TransientKind.NETWORK)
        val cases =
            listOf(
                HeadPinner.Unavailable to KeepOutcome.PinUnavailable,
                HeadPinner { _, _, _ -> PinOutcome.TooManyPinned } to KeepOutcome.TooManyPinned,
                HeadPinner { _, _, _ -> PinOutcome.Failed(failure) } to
                    KeepOutcome.Unreachable(failure),
            )
        for ((pinner, expected) in cases) {
            assertEquals(expected, resolver(destination, pinner).keepThisPhone(true, control))
            assertEquals("CONFLICT", fixture.state().linkState)
            assertEquals(0L, fixture.state().headGeneration)
        }
        // Sin red tampoco: no se decide sin ver el destino.
        destination.failNext(Operation.LIST, failure)
        assertEquals(
            KeepOutcome.Unreachable(failure),
            resolver(destination, RecordingPinner()).keepThisPhone(true, control),
        )
        assertEquals("CONFLICT", fixture.state().linkState)
    }

    @Test
    fun aTieWithAnotherPhoneIsSolvedByKeepingThisPhone() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        val own = requireNotNull(fixture.state().headFileRef)
        // Otro teléfono subió la misma generación a la vez.
        val foreign = putCopy(destination, generation = 1, deviceId = OTHER_DEVICE_ID, records = 0)
        fixture.addProduct("Leche de prueba")
        assertConflict(BackupConflictKind.OTHER_DEVICE, fixture.runner(destination).run(control))
        val pinner = RecordingPinner()

        assertEquals(KeepOutcome.Kept, resolver(destination, pinner).keepThisPhone(false, control))

        // Las dos copias de la cabeza quedan fijadas, y el empate ya no detiene la siguiente.
        assertEquals(setOf(own, foreign.fileName), pinner.pinned.single().map { it.value }.toSet())
        assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))
    }

    @Test
    fun aHeadThatChangedSinceTheConflictIsShownAgain() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        putCopy(destination, generation = 5, deviceId = OTHER_DEVICE_ID, records = 0)
        fixture.runner(destination).run(control)
        putCopy(destination, generation = 6, deviceId = OTHER_DEVICE_ID, records = 0)

        val outcome = resolver(destination, RecordingPinner()).keepThisPhone(true, control)

        assertEquals(6L, (outcome as KeepOutcome.CloudChanged).view.cloudGeneration)
        assertEquals("CONFLICT", fixture.state().linkState)
    }

    @Test
    fun withSlotsAPhoneThatCameBackKeepsItsCopiesWithoutPinning() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
        // Una copia más nueva de este mismo teléfono, de una base que ya no es esta.
        destination.putSlot(CopyRef("C"), slot(generation = 9, deviceId = TEST_DEVICE_ID))
        assertConflict(BackupConflictKind.CLOUD_AHEAD, fixture.runner(destination).run(control))
        val pinner = RecordingPinner()

        assertEquals(
            KeepOutcome.Kept,
            resolver(destination, pinner).keepThisPhone(fewerRecordsConfirmed = true, control),
        )

        // Las ranuras no se fijan: la regla de turnos protege la cabeza.
        assertEquals(emptyList<List<CopyRef>>(), pinner.pinned)
        assertEquals("C", fixture.state().headFileRef)
        assertEquals(BackupRun.Verified(10), fixture.runner(destination).run(control))
        assertEquals(9L, generationIn(destination, "C"))
    }

    @Test
    fun withSlotsAChangedOrBlockedSlotNeedsANewFileBeforeKeeping() = runBlocking {
        fixture.addProduct("Pan de prueba")
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val cases =
            listOf(
                Operation.LIST to DestinationFailure.ChangedOutside(CopyRef("B")),
                Operation.PLAN to DestinationFailure.NoSafeSlot(CopyRef("A")),
            )
        for ((operation, failure) in cases) {
            fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
            val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
            destination.failNext(operation, failure)
            fixture.runner(destination).run(control)
            val calls = destination.calls.size

            val outcome = resolver(destination, RecordingPinner()).keepThisPhone(true, control)

            val slot = if (operation == Operation.LIST) "B" else "A"
            assertEquals(KeepOutcome.NewSlotFileRequired(CopyRef(slot)), outcome)
            // Ni siquiera lee el destino: sin el archivo nuevo, nada cambia.
            assertEquals(calls, destination.calls.size)
            assertEquals("CONFLICT", fixture.state().linkState)
        }
    }

    @Test
    fun keepingIsNotOfferedOutsideAConflictNorWithAnEmptyDatabase() = runBlocking {
        val destination = FakeBackupDestination()
        val resolver = resolver(destination, RecordingPinner())
        assertEquals(KeepOutcome.NotInConflict, resolver.keepThisPhone(true, control))
        assertEquals(ConflictLook.NotInConflict, resolver.describe(control))

        fixture.sql(
            "UPDATE backup_state SET link_state = 'CONFLICT', conflict_kind = 'OTHER_DEVICE', " +
                "conflict_generation = 5 WHERE id = 1",
        )
        assertEquals(KeepOutcome.EmptyDatabase, resolver.keepThisPhone(true, control))
        assertEquals(ConflictLook.EmptyDatabase, resolver.describe(control))
        assertEquals(emptyList<Operation>(), destination.calls)
        assertEquals("CONFLICT", fixture.state().linkState)
    }

    @Test
    fun ifThePersonDeletedTheCopiesKeepingSimplyResumes() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        val foreign = putCopy(destination, generation = 5, deviceId = OTHER_DEVICE_ID, records = 0)
        fixture.runner(destination).run(control)
        destination.removeFile(foreign.fileName)
        destination.removeFile(foreign.metaFileName)

        assertEquals(
            KeepOutcome.Kept,
            resolver(destination, RecordingPinner()).keepThisPhone(false, control),
        )

        assertEquals("CONNECTED", fixture.state().linkState)
        assertEquals(0L, fixture.state().headGeneration)
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
    }

    @Test
    fun theDialogComparesBothSidesInNeutralCounts() = runBlocking {
        fixture.addProduct("Pan de prueba")
        val destination = FakeBackupDestination()
        putCopy(destination, generation = 5, deviceId = OTHER_DEVICE_ID, records = 12)
        fixture.runner(destination).run(control)

        val look = resolver(destination, RecordingPinner()).describe(control)

        val view = (look as ConflictLook.Shown).view
        assertEquals(BackupConflictKind.OTHER_DEVICE, view.kind)
        assertEquals(12L, view.cloudRecords)
        assertEquals(2L, view.cloudCatalogCount)
        assertEquals(1L, view.localCatalogCount)
        assertEquals(false, view.cloudFromThisPhone)
        assertEquals(fixture.state().changeSeq - fixture.state().verifiedSeq, view.pendingChanges)
    }

    private fun assertConflict(kind: BackupConflictKind, run: BackupRun) {
        assertEquals(BackupRun.Stopped(StopCause.Conflict(kind)), run)
    }

    private fun resolver(destination: BackupDestination, pinner: HeadPinner) = ConflictResolver(
        state = fixture.ports.backupState,
        destinations = { DestinationOpen.Opened(destination) },
        deviceId = { TEST_DEVICE_ID },
        clock = fixture.clock,
        schemaVersion = AppDatabase.SCHEMA_VERSION,
        mutex = Mutex(),
        pinner = pinner,
    )

    private fun meta(generation: Long, deviceId: String, bytes: ByteArray, records: Long) = SlMeta(
        format = 1,
        keyId = null,
        deviceId = deviceId,
        deviceLabel = null,
        generation = generation,
        schemaVersion = AppDatabase.SCHEMA_VERSION.toLong(),
        appVersion = null,
        sha256 = FakeBackupDestination.sha256(bytes),
        rawSize = 4_096,
        fileSize = bytes.size.toLong(),
        records = records,
        catalogCount = 2,
        valid = true,
    )

    /** Una copia con sus metadatos, como si la hubiera subido [deviceId]. */
    private fun putCopy(
        destination: FakeBackupDestination,
        generation: Long,
        deviceId: String,
        records: Long,
    ): BackupFileName {
        val name =
            BackupFileName(
                generation,
                AppDatabase.SCHEMA_VERSION,
                requireNotNull(CopyInstant.of(fixture.now)),
                deviceId.take(8),
                BackupFileFormat.GZIP,
            )
        val bytes = ByteArray(64) { (it + generation).toByte() }
        destination.putFile(name.fileName, bytes)
        destination.putFile(
            name.metaFileName,
            SlMetaCodec.encode(meta(generation, deviceId, bytes, records)),
        )
        return name
    }

    private fun slot(generation: Long, deviceId: String): FakeBackupDestination.FakeSlot {
        val bytes = ByteArray(64) { (it + generation).toByte() }
        return FakeBackupDestination.FakeSlot(
            bytes,
            SlMetaCodec.encode(meta(generation, deviceId, bytes, records = 0)),
            null,
            true,
        )
    }

    private fun generationIn(destination: FakeBackupDestination, slot: String): Long? {
        val meta = destination.slot(CopyRef(slot))?.meta ?: return null
        val decoded = SlMetaCodec.decodeUnnamed(meta)
        return (decoded as? CopyMetadata.Valid)
            ?.meta?.generation
    }

    /** Fija sin tocar el destino, y anota qué le pidieron fijar. */
    private class RecordingPinner : HeadPinner {
        val pinned = mutableListOf<List<CopyRef>>()

        override suspend fun pin(
            destination: BackupDestination,
            refs: List<CopyRef>,
            control: TransferControl,
        ): PinOutcome {
            pinned += refs
            return PinOutcome.Pinned
        }
    }
}
