package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
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
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDrillResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.ServiceSpace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La sonda y la comprobación completa de la cabeza sobre la base real y el destino falso: una cabeza
 * borrada o con otro tamaño fuerza una subida, una más nueva de otro teléfono da conflicto sin un
 * cambio local, una subida propia sin confirmar nunca da un conflicto falso, y la comprobación anota
 * una lectura sana, marca una cabeza dañada sin borrarla y no baja una copia que la app no
 * restauraría. Con la política pendiente ninguna toca el destino. Todos los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class HeadProbeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "head-probe-test")
    private val control = TestControl()
    private val mutex = Mutex()
    private var accepted = true
    private val lastProbeFile = File(fixture.noBackup, LastProbeStore.FILE_NAME)
    private val lastProbe = LastProbeStore(lastProbeFile, Dispatchers.IO)
    private val damaged =
        DamagedGenerationStore(
            File(fixture.noBackup, DamagedGenerationStore.FILE_NAME),
            Dispatchers.IO,
        )
    private var destination = FakeBackupDestination()

    private suspend fun access(): ProbeAccess = probeAccess(
        restorePending = { RestoreMarkers(fixture.noBackup).present() },
        policyAccepted = { accepted },
        access = { DataAccess.Ready(fixture.ports) },
    )

    private val probe
        get() = HeadProbe(
            prepare = ::access,
            destinations = { DestinationOpen.Opened(destination) },
            deviceId = { TEST_DEVICE_ID },
            schemaVersion = AppDatabase.SCHEMA_VERSION,
            rejected = { fixture.rejected },
            lastProbe = { lastProbe },
            mutex = mutex,
            clock = fixture.clock,
            log = fixture.log,
        )

    private fun deepCheck(freeSpace: (File) -> Long = File::getUsableSpace) = HeadDeepCheck(
        prepare = ::access,
        destinations = { DestinationOpen.Opened(destination) },
        stagingDirectory = { fixture.stagingDir },
        unpacker = CandidateUnpacker(Dispatchers.IO),
        validator = CandidateValidator(Dispatchers.IO),
        fullVerify = { fixture.fullVerify },
        damaged = { damaged },
        mutex = mutex,
        clock = fixture.clock,
        log = fixture.log,
        ioContext = Dispatchers.IO,
        freeSpace = freeSpace,
    )

    @Before
    fun open() = runBlocking {
        fixture.open()
        fixture.connect()
        fixture.addProduct("Pan de prueba")
        assertTrue(fixture.runner(destination).run(control) is BackupRun.Verified)
    }

    @After
    fun close() {
        // Cada prueba abre su base: se cierra para no agotar los archivos abiertos del proceso, que
        // en Android 8 tiene un tope que la suite entera alcanza.
        runBlocking { runCatching { fixture.ports.closeForRestore() } }
        fixture.close()
    }

    @Test
    fun aHeadThatIsStillThereChangesNothingAndTheProbeIsNoted() = runBlocking {
        val before = fixture.state()

        assertEquals(ProbeRun.DONE, probe.run(control))

        assertEquals(before, fixture.state())
        assertEquals(fixture.now, lastProbe.load()?.at)
    }

    @Test
    fun aDeletedHeadForcesAnUpload() = runBlocking {
        val before = fixture.state()
        val head = requireNotNull(before.headFileRef)
        destination.removeFile(head)
        destination.removeFile(requireNotNull(BackupFileName.parse(head)).metaFileName)

        probe.run(control)

        val after = fixture.state()
        assertEquals(before.changeSeq + 1, after.changeSeq)
        assertNotNull(after.firstUnsyncedAt)
        assertEquals(BackupLinkState.CONNECTED.name, after.linkState)
    }

    @Test
    fun aHeadWithAnotherSizeForcesAnUpload() = runBlocking {
        val before = fixture.state()
        val head = requireNotNull(before.headFileRef)
        destination.putFile(head, requireNotNull(destination.file(head)) + ByteArray(10))

        probe.run(control)

        assertEquals(before.changeSeq + 1, fixture.state().changeSeq)
    }

    @Test
    fun aNewerHeadFromAnotherPhoneIsAConflictWithoutALocalChange() = runBlocking {
        val before = fixture.state()
        putForeignCopy(generation = 5)

        probe.run(control)

        val after = fixture.state()
        assertEquals(BackupLinkState.CONFLICT.name, after.linkState)
        assertEquals(BackupConflictKind.OTHER_DEVICE.name, after.conflictKind)
        assertEquals(5L, after.conflictGeneration)
        assertEquals(OTHER_DEVICE_ID, after.conflictDeviceId)
        assertEquals(before.changeSeq, after.changeSeq)
    }

    @Test
    fun anOwnUnconfirmedUploadNeverGivesAFalseConflict() = runBlocking {
        // Una subida propia más nueva quedó sin confirmar: la sonda no la toma por otra cabeza.
        val name = putOwnCopy(generation = 2)
        fixture.ports.backupState.saveIntent(2, fixture.state().changeSeq, "ab".repeat(32), 64)

        probe.run(control)

        assertEquals(BackupLinkState.CONNECTED.name, fixture.state().linkState)
        assertTrue(name.fileName in destination.fileNames)
    }

    @Test
    fun withThePolicyPendingNeitherCheckTouchesTheDestination() = runBlocking {
        accepted = false
        val calls = destination.calls.size

        assertEquals(ProbeRun.DONE, probe.run(control))
        assertEquals(ProbeRun.DONE, deepCheck().run(control))

        assertEquals(calls, destination.calls.size)
        assertNull(lastProbe.load())
    }

    @Test
    fun aBusyBackupLeavesTheProbeUnnoted() = runBlocking {
        mutex.lock()

        assertEquals(ProbeRun.DONE, probe.run(control))

        assertNull(lastProbe.load())
        mutex.unlock()
    }

    @Test
    fun aPassingErrorRetriesWithoutNotingTheProbe() = runBlocking {
        destination.failNext(Operation.LIST, DestinationFailure.Transient(TransientKind.NETWORK))

        assertEquals(ProbeRun.RETRY, probe.run(control))

        assertNull(lastProbe.load())
    }

    @Test
    fun theProbeNotesTheServiceSpaceAndTheStatusWarnsWhenLessThanThreeCopiesFit() = runBlocking {
        val headSize = requireNotNull(
            destination.file(requireNotNull(fixture.state().headFileRef)),
        ).size.toLong()
        destination.quotaAvailableBytes = headSize * 3 - 1

        probe.run(control)

        assertEquals(ServiceSpace(headSize * 3 - 1, headSize), lastProbe.load()?.serviceSpace)
    }

    @Test
    fun withSlotsAChangedSlotIsAConflictAndALostOneLosesTheAccess() = runBlocking {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
        destination.failNext(Operation.LIST, DestinationFailure.ChangedOutside(CopyRef("B")))

        probe.run(control)

        val conflict = fixture.state()
        assertEquals(BackupLinkState.CONFLICT.name, conflict.linkState)
        assertEquals("B", conflict.conflictFileRef)

        fixture.sql("UPDATE backup_state SET link_state = 'CONNECTED' WHERE id = 1")
        destination.failNext(Operation.LIST, DestinationFailure.PermissionLost())
        probe.run(control)
        assertEquals(BackupLinkState.PERMISSION_LOST.name, fixture.state().linkState)
    }

    @Test
    fun aSoundHeadPassesTheDeepCheckAndNotesTheWholeRead() = runBlocking {
        val generation = fixture.state().headGeneration
        damaged.add(generation)
        fixture.sql("UPDATE backup_state SET last_full_verify_at = NULL WHERE id = 1")

        assertEquals(ProbeRun.DONE, deepCheck().run(control))

        val state = fixture.state()
        assertEquals(BackupDrillResult.PASSED.name, state.lastDrillResult)
        assertEquals(fixture.now.toEpochMilliseconds(), state.lastDrillAt)
        assertEquals(fixture.now.toEpochMilliseconds(), state.lastFullVerifyAt)
        assertEquals(generation, fixture.fullVerify.load())
        assertFalse(generation in damaged.load())
        assertNull(state.failureCode)
        assertEquals(emptyList<String>(), stagingFolders())
    }

    @Test
    fun aDamagedHeadFailsMarksTheCopyAndForcesAnUploadWithoutDeletingIt() = runBlocking {
        val before = fixture.state()
        val head = requireNotNull(before.headFileRef)
        val bytes = requireNotNull(destination.file(head))
        // El mismo tamaño, otro contenido: la lista no lo nota, la lectura completa sí.
        destination.putFile(head, ByteArray(bytes.size) { (bytes[it] + 1).toByte() })

        deepCheck().run(control)

        val after = fixture.state()
        assertEquals(BackupDrillResult.FAILED.name, after.lastDrillResult)
        assertEquals(BackupFailureCode.VERIFY_MISMATCH.name, after.failureCode)
        assertTrue(before.headGeneration in damaged.load())
        assertEquals(before.changeSeq + 1, after.changeSeq)
        assertTrue(head in destination.fileNames)
        assertFalse(Operation.DELETE in destination.calls)
        assertEquals(emptyList<String>(), stagingFolders())
    }

    @Test
    fun anUnrestorableHeadIsNotDownloaded() = runBlocking {
        val head = requireNotNull(fixture.state().headFileRef)
        val name = requireNotNull(BackupFileName.parse(head))
        val meta = metaOf(name).copy(fatal = true, valid = false)
        destination.putFile(name.metaFileName, SlMetaCodec.encode(meta))

        deepCheck().run(control)

        assertFalse(Operation.DOWNLOAD in destination.calls)
        assertNull(fixture.state().lastDrillResult)
    }

    @Test
    fun withoutSpaceTheDeepCheckIsSkipped() = runBlocking {
        deepCheck(freeSpace = { 0 }).run(control)

        assertEquals(BackupDrillResult.SKIPPED.name, fixture.state().lastDrillResult)
        assertFalse(Operation.DOWNLOAD in destination.calls)
    }

    @Test
    fun aDeepCheckThatEndsOnAnotherDestinationNotesNothing() = runBlocking {
        val check = deepCheck()
        // La persona cambia de destino mientras se baja la cabeza: la segunda lista ya es del nuevo.
        destination.onDownload = {
            fixture.sql("UPDATE backup_state SET destination_id = 'otro' WHERE id = 1")
        }

        check.run(control)

        assertNull(fixture.state().lastDrillResult)
        assertNull(fixture.state().failureCode)
    }

    @Test
    fun theStoresKeepTheirLinesAndForgetWhatIsGone() = runBlocking {
        damaged.add(3)
        damaged.add(7)
        damaged.removeAll(setOf(3, 9))
        assertEquals(setOf(7L), damaged.load())
        damaged.remove(7)
        assertEquals(emptySet<Long>(), damaged.load())

        lastProbe.save(LastProbe(fixture.now, null))
        assertEquals(LastProbe(fixture.now, null), lastProbe.load())
        lastProbeFile.writeText("no es un instante\n")
        assertNull(lastProbe.load())
    }

    private fun stagingFolders(): List<String> =
        fixture.stagingDir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }

    private fun metaOf(name: BackupFileName): SlMeta {
        val decoded =
            SlMetaCodec.decode(requireNotNull(destination.file(name.metaFileName)), name.identity())
        return (decoded as CopyMetadata.Valid).meta
    }

    private fun putForeignCopy(generation: Long) = putCopy(generation, OTHER_DEVICE_ID)

    private fun putOwnCopy(generation: Long) = putCopy(generation, TEST_DEVICE_ID)

    /** Una copia con sus metadatos, como si la hubiera subido [deviceId]. */
    private fun putCopy(generation: Long, deviceId: String): BackupFileName {
        val name =
            BackupFileName(
                generation,
                AppDatabase.SCHEMA_VERSION,
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
        return name
    }
}
