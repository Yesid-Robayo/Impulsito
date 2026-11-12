package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.FullVerifyOutcome
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
import org.junit.runner.RunWith

/**
 * `Comprobar ahora` sobre la base real y el destino falso: baja la cabeza entera y compara su suma.
 * Si coincide anota la lectura completa; si no, la marca como dañada y fuerza una subida, sin
 * borrarla. Si el destino cambió durante la lectura no escribe nada, y con ranuras no aplica. Es la
 * única escritura de `Salud de mis datos`. Todos los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class FullVerifyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "full-verify-test")
    private val control = TestControl()
    private var destination = FakeBackupDestination()
    private val damaged =
        DamagedGenerationStore(
            File(fixture.noBackup, DamagedGenerationStore.FILE_NAME),
            Dispatchers.IO,
        )

    private fun check(freeSpace: (File) -> Long = File::getUsableSpace) = HeadDeepCheck(
        prepare = {
            probeAccess(
                restorePending = { RestoreMarkers(fixture.noBackup).present() },
                policyAccepted = { true },
                access = { DataAccess.Ready(fixture.ports) },
            )
        },
        destinations = { DestinationOpen.Opened(destination) },
        stagingDirectory = { fixture.stagingDir },
        unpacker = CandidateUnpacker(Dispatchers.IO),
        validator = CandidateValidator(Dispatchers.IO),
        fullVerify = { fixture.fullVerify },
        damaged = { damaged },
        mutex = Mutex(),
        clock = fixture.clock,
        log = fixture.log,
        ioContext = Dispatchers.IO,
        freeSpace = freeSpace,
    )

    @Before
    fun open() = runBlocking { fixture.open() }

    @After
    fun close() {
        runBlocking { runCatching { fixture.ports.closeForRestore() } }
        fixture.close()
    }

    private suspend fun backUp(kind: BackupDestinationKind = BackupDestinationKind.WEBDAV) {
        fixture.connect(kind)
        destination = FakeBackupDestination(kind)
        fixture.addProduct("Pan de prueba")
        assertTrue(fixture.runner(destination).run(control) is BackupRun.Verified)
        // La subida ya leyó la copia entera: se borra lo anotado para medir solo `Comprobar`.
        fixture.sql("UPDATE backup_state SET last_full_verify_at = NULL WHERE id = 1")
        fixture.fullVerifyFile.delete()
    }

    @Test
    fun aMatchingCopyNotesTheFullReadAndNothingElse() = runBlocking<Unit> {
        backUp()
        val generation = fixture.state().headGeneration
        damaged.add(generation)
        val before = fixture.state()

        assertEquals(FullVerifyOutcome.MATCHED, check().verifyNow(TransferControl.Unbounded))

        val after = fixture.state()
        assertEquals(fixture.now.toEpochMilliseconds(), after.lastFullVerifyAt)
        assertEquals(generation, fixture.fullVerify.load())
        assertFalse(generation in damaged.load())
        // Solo la lectura completa: ni el simulacro, ni los cambios, ni la cabeza.
        assertEquals(before.copy(lastFullVerifyAt = after.lastFullVerifyAt), after)
        assertTrue(File(fixture.stagingDir, HeadDeepCheck.DIRECTORY_NAME).list().isNullOrEmpty())
    }

    @Test
    fun aMismatchMarksTheCopyForcesAnUploadAndDeletesNothing() = runBlocking<Unit> {
        backUp()
        val before = fixture.state()
        val head = requireNotNull(before.headFileRef)
        val bytes = requireNotNull(destination.file(head))
        destination.putFile(head, ByteArray(bytes.size) { (bytes[it] + 1).toByte() })

        assertEquals(FullVerifyOutcome.MISMATCH, check().verifyNow(TransferControl.Unbounded))

        val after = fixture.state()
        assertEquals(BackupFailureCode.VERIFY_MISMATCH.name, after.failureCode)
        assertTrue(before.headGeneration in damaged.load())
        assertEquals(before.changeSeq + 1, after.changeSeq)
        assertNull(after.lastFullVerifyAt)
        assertTrue(head in destination.fileNames)
        assertFalse(Operation.DELETE in destination.calls)
    }

    @Test
    fun aDestinationChangedDuringTheReadWritesNothing() = runBlocking<Unit> {
        backUp()
        val before = fixture.state()
        destination.onDownload = {
            fixture.sql("UPDATE backup_state SET destination_id = 'otro-destino' WHERE id = 1")
        }

        assertEquals(FullVerifyOutcome.NO_COPY, check().verifyNow(TransferControl.Unbounded))

        val after = fixture.state()
        assertNull(after.lastFullVerifyAt)
        assertEquals(before.changeSeq, after.changeSeq)
        assertNull(fixture.fullVerify.load())
    }

    @Test
    fun withSlotsOrWithoutSpaceNothingIsDownloadedOrWritten() = runBlocking<Unit> {
        backUp(BackupDestinationKind.DOCUMENT_SLOTS)
        val before = fixture.state()
        assertEquals(FullVerifyOutcome.NO_COPY, check().verifyNow(TransferControl.Unbounded))
        assertFalse(Operation.DOWNLOAD in destination.calls)
        assertEquals(before, fixture.state())

        fixture.close()
        fixture.reopen()
        backUp()
        assertEquals(
            FullVerifyOutcome.NO_SPACE,
            check(freeSpace = { 0 }).verifyNow(TransferControl.Unbounded),
        )
        assertFalse(Operation.DOWNLOAD in destination.calls)
        assertNull(fixture.state().lastFullVerifyAt)
    }
}
