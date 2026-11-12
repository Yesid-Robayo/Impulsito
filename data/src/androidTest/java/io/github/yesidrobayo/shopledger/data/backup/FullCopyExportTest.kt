package io.github.yesidrobayo.shopledger.data.backup

import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotTestProvider
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.FullCopyExport
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La copia exportada, de punta a punta en el teléfono y sin red: un teléfono la guarda en un
 * documento del selector, y otro, con una base limpia, la restaura por `Buscar un archivo`.
 * Exportar no cambia el estado del respaldo, deja la auditoría y no deja nada en `cache/exports/`.
 * Un documento que falla al escribir o al releer se borra, o queda vacío si el proveedor no deja
 * borrarlo; y un archivo cortado a mitad, como lo dejaría la muerte del proceso, se rechaza al
 * restaurar sin tocar los datos.
 */
@RunWith(AndroidJUnit4::class)
class FullCopyExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val oldPhone = BackupFixture(context, "export-old")
    private val newPhone = BackupFixture(context, "export-new")
    private val cache = context.cacheDir
    private val exports = File(cache, "export-test-exports")
    private val authority = "${context.packageName}.slottest"

    @Before
    fun open() = runBlocking<Unit> {
        oldPhone.open()
        newPhone.open()
        SlotTestProvider.reset(cache)
        exports.deleteRecursively()
    }

    @After
    fun close() {
        SlotTestProvider.reset(cache)
        exports.deleteRecursively()
        oldPhone.close()
        newPhone.close()
    }

    @Test
    fun anExportedCopyRestoresOnACleanPhone() = runBlocking<Unit> {
        oldPhone.addProduct("Pan de prueba")
        oldPhone.addProduct("Leche de prueba")
        val before = oldPhone.state()
        val uri = document("exportada")

        val result = exporter(oldPhone).export(uri)

        assertTrue("$result", result is FullCopyExport.Saved)
        assertEquals(true, (result as FullCopyExport.Saved).restorable)
        assertEquals(result.sizeBytes, SlotTestProvider.bytesOf(cache, "exportada").size.toLong())
        // Exportar no es respaldar: el estado del respaldo no cambia.
        val after = oldPhone.state()
        assertEquals(before.verifiedSeq, after.verifiedSeq)
        assertEquals(before.headGeneration, after.headGeneration)
        assertEquals(before.lastSuccessAt, after.lastSuccessAt)
        assertEquals(before.failureCode, after.failureCode)
        assertEquals(
            listOf(AuditEventType.EXPORT_FULL_COPY.name),
            oldPhone.ports.auditEvents.observeLatest(10).first().map { it.eventType },
        )
        assertTrue("Nada queda en la carpeta de exportar", exports.listFiles().isNullOrEmpty())

        // Otro teléfono, limpio, la restaura como cualquier archivo elegido.
        assertEquals(0L, newPhone.ports.backupState.catalogCount())
        assertEquals(RestoreProgress.ReadyToRestart(0), restore(newPhone).restoreFile(uri))
        assertTrue(newPhone.reopen() is DataAccess.Ready)
        assertEquals(2L, newPhone.ports.backupState.catalogCount())
        val state = newPhone.state()
        assertEquals(BackupLinkState.NOT_CONNECTED.name, state.linkState)
        assertTrue("Sin destino, nada cuenta como respaldado", state.changeSeq > state.verifiedSeq)
    }

    @Test
    fun aCopyCutInHalfIsRefusedWithoutTouchingTheData() = runBlocking<Unit> {
        oldPhone.addProduct("Pan de prueba")
        val uri = document("cortada")
        assertTrue(exporter(oldPhone).export(uri) is FullCopyExport.Saved)
        // Como si el proceso muriera a mitad de la escritura: queda la primera mitad.
        val file = File(cache, "slot-test/cortada")
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() / 2))

        assertEquals(
            RestoreProgress.Refused(RestoreProblem.DAMAGED),
            restore(newPhone).restoreFile(uri),
        )
        assertEquals(0L, newPhone.ports.backupState.catalogCount())
        assertTrue(!RestoreFiles(DatabaseFiles(newPhone.file, newPhone.noBackup)).lock.exists())
    }

    @Test
    fun aRereadThatDoesNotMatchLeavesNoCopy() = runBlocking<Unit> {
        oldPhone.addProduct("Pan de prueba")
        val uri = document("distinta")
        val sink = Spy(real(uri), rereadAs = ByteArray(10))

        assertEquals(FullCopyExport.Failed, exporter(oldPhone) { sink }.export(uri))

        assertEquals(1, sink.discards)
        // El proveedor de prueba no borra documentos: el archivo queda vacío, nunca a medias.
        assertArrayEquals(ByteArray(0), SlotTestProvider.bytesOf(cache, "distinta"))
        assertTrue(oldPhone.ports.auditEvents.observeLatest(10).first().isEmpty())
        assertTrue(exports.listFiles().isNullOrEmpty())
    }

    @Test
    fun aWriteThatFailsLeavesNoCopyAndNoSpaceIsSaidAsSuch() = runBlocking<Unit> {
        oldPhone.addProduct("Pan de prueba")
        val uri = document("sin-espacio")
        val full =
            Spy(
                real(uri),
                failWrite = IOException("sin espacio", ErrnoException("write", OsConstants.ENOSPC)),
            )
        assertEquals(FullCopyExport.NoSpace, exporter(oldPhone) { full }.export(uri))
        assertEquals(1, full.discards)

        val broken = Spy(real(uri), failWrite = IOException("se cortó"))
        assertEquals(FullCopyExport.Failed, exporter(oldPhone) { broken }.export(uri))
        assertEquals(1, broken.discards)
        assertArrayEquals(ByteArray(0), SlotTestProvider.bytesOf(cache, "sin-espacio"))
        assertTrue(exports.listFiles().isNullOrEmpty())
    }

    @Test
    fun withoutTheDatabaseNothingIsWritten() = runBlocking<Unit> {
        val uri = document("sin-base")
        val sink = Spy(real(uri))
        val exporter =
            FullCopyExporter(
                builder = { null },
                exportsDirectory = { exports },
                sinkFor = { sink },
                recordExport = {},
                ioContext = Dispatchers.IO,
            )

        assertEquals(FullCopyExport.Failed, exporter.export(uri))
        assertEquals(0, sink.writes)
    }

    @Test
    fun theNoSpaceCheckKnowsAFullDiskAndAFailedSync() {
        assertTrue(isNoSpace(IOException(ErrnoException("write", OsConstants.ENOSPC))))
        assertTrue(isNoSpace(java.io.SyncFailedException("sync failed")))
        assertTrue(!isNoSpace(IOException("otra")))
        assertTrue(!isNoSpace(IOException(ErrnoException("write", OsConstants.EIO))))
    }

    // Apoyo

    private fun document(id: String): String {
        SlotTestProvider.create(cache, id)
        return SlotTestProvider.uriOf(authority, id)
    }

    private fun real(uri: String) = DocumentExportSink(context.contentResolver, Uri.parse(uri))

    private fun exporter(phone: BackupFixture, sinkFor: (String) -> ExportSink = ::real) =
        FullCopyExporter(
            builder = {
                LocalCopyBuilder(
                    snapshots = SnapshotCreator(phone.file, Dispatchers.IO),
                    sealer = SnapshotSealer(Dispatchers.IO),
                    validation = SnapshotValidation(CandidateValidator(Dispatchers.IO)),
                    packager = BackupPackager(Dispatchers.IO),
                    headGeneration = { phone.ports.backupState.get()?.headGeneration ?: 0 },
                    deviceId = { "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd" },
                    clock = phone.clock,
                    schemaVersion = AppDatabase.SCHEMA_VERSION,
                )
            },
            exportsDirectory = { exports },
            sinkFor = sinkFor,
            recordExport = { recordFullCopyExported(phone.ports, phone.clock, phone.log) },
            ioContext = Dispatchers.IO,
        )

    private fun restore(phone: BackupFixture): BackupRestoreService {
        val opener = DestinationOpener { DestinationOpen.PermissionLost }
        val coordinator =
            productionRestoreCoordinator(
                context = context,
                ioContext = Dispatchers.IO,
                clock = phone.clock,
                log = phone.log,
                files = RestoreFiles(DatabaseFiles(phone.file, phone.noBackup)),
                ports = { phone.ports },
                destinations = opener,
                backupMutex = Mutex(),
                scheduledWork = ScheduledBackupWork.None,
            )
        return BackupRestoreService(
            saved = { phone.ports.backupState.get()?.savedDestination() },
            destinations = opener,
            coordinator = { coordinator },
            files = ResolverPickedFiles(context.contentResolver),
            beforeUpdate = { DatabaseFiles(phone.file, phone.noBackup).preMigrationCandidate },
            ioContext = Dispatchers.IO,
        )
    }

    /** El documento real, con fallos inventados al escribir o al releer, y lo que se le pidió. */
    private class Spy(
        private val real: ExportSink,
        private val failWrite: IOException? = null,
        private val rereadAs: ByteArray? = null,
    ) : ExportSink {
        var writes = 0
        var discards = 0

        override fun write(input: InputStream) {
            writes += 1
            real.write(input)
            failWrite?.let { throw it }
        }

        override fun read(): InputStream = rereadAs?.let(::ByteArrayInputStream) ?: real.read()

        override fun discard() {
            discards += 1
            real.discard()
        }
    }
}
