package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDrillResult
import io.github.yesidrobayo.shopledger.domain.model.backup.DrillOutcome
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
 * `Probar restauración` sobre la base real y el destino falso, con WebDAV y con ranuras: baja la
 * cabeza a un temporal, la valida y la abre con la base de la app, y anota el resultado. Nunca toca
 * las tablas del negocio ni las copias, también cuando falla. Todos los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class RestoreDrillTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "restore-drill-test")
    private val control = TestControl()
    private var destination = FakeBackupDestination()
    private val damaged =
        DamagedGenerationStore(
            File(fixture.noBackup, DamagedGenerationStore.FILE_NAME),
            Dispatchers.IO,
        )
    private var migrations = 0

    private fun drill(
        migrator: CandidateMigrator =
            CandidateMigrator {
                migrations++
                RoomCandidateMigrator(context, Dispatchers.IO, fixture.clock).migrate(it)
            },
        freeSpace: (File) -> Long = File::getUsableSpace,
    ) = HeadDeepCheck(
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
        migrator = migrator,
    )

    @Before
    fun open() = runBlocking { fixture.open() }

    @After
    fun close() {
        runBlocking { runCatching { fixture.ports.closeForRestore() } }
        fixture.close()
    }

    private suspend fun backUp(kind: BackupDestinationKind) {
        fixture.connect(kind)
        destination = FakeBackupDestination(kind)
        fixture.addProduct("Pan de prueba")
        fixture.addProduct("Queso de prueba")
        assertTrue(fixture.runner(destination).run(control) is BackupRun.Verified)
    }

    @Test
    fun withWebDavTheHeadIsRestorableAndOnlyTheDrillIsNoted() = runBlocking<Unit> {
        backUp(BackupDestinationKind.WEBDAV)
        val business = businessRows()
        val copies = destination.fileNames.toSet()
        val callsBefore = destination.calls.size

        assertEquals(DrillOutcome.PASSED, drill().runNow(TransferControl.Unbounded))

        val state = fixture.state()
        assertEquals(BackupDrillResult.PASSED.name, state.lastDrillResult)
        assertEquals(fixture.now.toEpochMilliseconds(), state.lastDrillAt)
        assertEquals("La copia se abrió con la base de la app", 1, migrations)
        assertEquals(business, businessRows())
        assertEquals(copies, destination.fileNames.toSet())
        assertFalse(Operation.UPLOAD in destination.calls.drop(callsBefore))
        assertTrue("El temporal no queda", stagingFolders().isEmpty())
    }

    @Test
    fun withSlotsTheHeadIsReadThroughTheCloudAndNoted() = runBlocking<Unit> {
        backUp(BackupDestinationKind.DOCUMENT_SLOTS)
        val business = businessRows()

        assertEquals(DrillOutcome.PASSED, drill().runNow(TransferControl.Unbounded))

        val state = fixture.state()
        assertEquals(BackupDrillResult.PASSED.name, state.lastDrillResult)
        // Leer por esa nube no prueba que la copia haya subido: no es una lectura completa.
        assertNull(state.lastFullVerifyAt)
        assertEquals(business, businessRows())
    }

    @Test
    fun anInjectedFailureGivesFailedWithTheBusinessTablesIntact() = runBlocking<Unit> {
        backUp(BackupDestinationKind.WEBDAV)
        val business = businessRows()
        val before = fixture.state()

        // La copia valida, pero la base de la app no la abre: no se podría restaurar.
        val outcome =
            drill(migrator = { error("Falla inventada al abrir la candidata") })
                .runNow(TransferControl.Unbounded)

        assertEquals(DrillOutcome.FAILED, outcome)
        val after = fixture.state()
        assertEquals(BackupDrillResult.FAILED.name, after.lastDrillResult)
        assertTrue(before.headGeneration in damaged.load())
        assertEquals("Sale una copia nueva", before.changeSeq + 1, after.changeSeq)
        assertEquals(business, businessRows())
        assertTrue(requireNotNull(before.headFileRef) in destination.fileNames)
        assertFalse(Operation.DELETE in destination.calls)
        assertTrue(stagingFolders().isEmpty())
    }

    @Test
    fun aDamagedCopyFailsTooAndNothingOfTheBusinessChanges() = runBlocking<Unit> {
        backUp(BackupDestinationKind.WEBDAV)
        val business = businessRows()
        val head = requireNotNull(fixture.state().headFileRef)
        val bytes = requireNotNull(destination.file(head))
        destination.putFile(head, ByteArray(bytes.size) { (bytes[it] + 1).toByte() })

        assertEquals(DrillOutcome.FAILED, drill().runNow(TransferControl.Unbounded))

        assertEquals(0, migrations)
        assertEquals(business, businessRows())
    }

    @Test
    fun withoutSpaceOrWithoutACopyNothingIsDownloaded() = runBlocking<Unit> {
        assertEquals(
            "Sin destino no hay qué probar",
            DrillOutcome.NO_COPY,
            drill().runNow(TransferControl.Unbounded),
        )
        backUp(BackupDestinationKind.WEBDAV)

        assertEquals(
            DrillOutcome.SKIPPED,
            drill(freeSpace = { 0 }).runNow(TransferControl.Unbounded),
        )

        assertEquals(BackupDrillResult.SKIPPED.name, fixture.state().lastDrillResult)
        assertFalse(Operation.DOWNLOAD in destination.calls)
    }

    @Test
    fun aDestinationThatDoesNotAnswerCanBeTriedAgain() = runBlocking<Unit> {
        backUp(BackupDestinationKind.WEBDAV)
        destination.failNext(
            Operation.LIST,
            io.github.yesidrobayo.shopledger.data.destination.DestinationFailure.Incomplete,
        )

        assertEquals(DrillOutcome.UNREACHABLE, drill().runNow(TransferControl.Unbounded))
        assertNull(fixture.state().lastDrillResult)
    }

    /** Las filas del negocio, completas, para comparar antes y después. */
    private fun businessRows(): List<String> {
        val rows = ArrayList<String>()
        inspectWithoutRoom(fixture.file) { db ->
            db.prepare("SELECT id, name, sale_price FROM products ORDER BY id").use { stmt ->
                while (stmt.step()) {
                    rows += "${stmt.getLong(0)}|${stmt.getText(1)}|${stmt.getLong(2)}"
                }
            }
            db.prepare("SELECT COUNT(*) FROM stock_movements").use { stmt ->
                stmt.step()
                rows += "movimientos ${stmt.getLong(0)}"
            }
        }
        return rows
    }

    private fun stagingFolders(): List<String> =
        File(fixture.stagingDir, HeadDeepCheck.DIRECTORY_NAME).list()?.toList().orEmpty()
}
