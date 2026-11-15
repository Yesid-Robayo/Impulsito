package io.github.yesidrobayo.shopledger.data.erase

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.backup.BackupRun
import io.github.yesidrobayo.shopledger.data.backup.DurableFileOps
import io.github.yesidrobayo.shopledger.data.backup.OperationListener
import io.github.yesidrobayo.shopledger.data.backup.TestControl
import io.github.yesidrobayo.shopledger.data.destination.APP_FOLDER
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.FakeResponse
import io.github.yesidrobayo.shopledger.data.destination.InMemoryWebDav
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestination
import io.github.yesidrobayo.shopledger.data.destination.WebDavMediaChannel
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotEntry
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.WriteMode
import io.github.yesidrobayo.shopledger.data.destination.testClient
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.media.MediaOpen
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseOutcome
import io.github.yesidrobayo.shopledger.domain.model.erase.ErasePhase
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.port.AuditRecorder
import io.github.yesidrobayo.shopledger.domain.usecase.erase.EraseMyDataUseCase
import java.io.File
import kotlin.time.Duration.Companion.minutes
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
 * `Borrar mis datos` sobre una base real y un servicio WebDAV falso: primero las copias, sus
 * `.slmeta` y las fotos del servicio, sin tocar lo que no escribió la app, y después todo este
 * teléfono. Sin red, con un servicio que no deja borrar o con copias que quedan, este teléfono no
 * se toca; una muerte a mitad se retoma. Los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class EraseOnDatabaseTest {
    /** La muerte del proceso en una operación: nada la atrapa, como en un teléfono. */
    private class ProcessDeath : Error()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "erase-on-database-test")
    private val control = TestControl()
    private val servers = ArrayList<FakeHttpServer>()
    private val appFolder = File(context.cacheDir, "erase-on-database-test-files")
    private val slotDirectory = File(fixture.noBackup, "slots")
    private val audits = mutableListOf<Pair<AuditEventType, AuditResult>>()
    private val audit = AuditRecorder { type, result -> audits += type to result }
    private var online = true
    private var released = 0
    private var refuseDeletes = false
    private var failAfterFirstCopy = false
    private var databaseClosed = false
    private var slotAnswer = SlotDocumentDelete.DELETED
    private lateinit var dav: InMemoryWebDav
    private lateinit var server: FakeHttpServer

    @Before
    fun open() = runBlocking {
        fixture.open()
        fixture.addProduct("Pan de prueba")
        appFolder.deleteRecursively()
        File(appFolder, "media/ab").mkdirs()
        File(appFolder, "media/ab/foto.webp").writeBytes(byteArrayOf(1, 2, 3))
        File(fixture.noBackup, "link.json").writeText("{}")
        // Las marcas de la higiene de la cuenta, por su ruta: se borran con lo demás.
        File(fixture.noBackup, ACCOUNT_CHECKLIST).writeText("{}")
        // Lo que dejó una demostración, en su carpeta: también se borra.
        File(fixture.noBackup, "demo").mkdirs()
        File(fixture.noBackup, "demo/demo.db").writeBytes(byteArrayOf(4))
        File(fixture.noBackup, "recovery").mkdirs()
        File(fixture.noBackup, "recovery/state.json").writeText("{}")
        var deletes = 0
        dav =
            InMemoryWebDav(override = { request ->
                if (request.method != "DELETE") return@InMemoryWebDav null
                deletes += 1
                when {
                    refuseDeletes -> FakeResponse.status(FORBIDDEN)

                    // La primera copia y su `.slmeta` se borran; desde ahí, el servicio falla.
                    failAfterFirstCopy && deletes >= 3 -> FakeResponse.status(UNAVAILABLE)

                    else -> null
                }
            })
        dav.collections += "/dav/app/"
        server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
    }

    @After
    fun close() {
        servers.forEach { it.close() }
        appFolder.deleteRecursively()
        fixture.close()
    }

    private fun webDav() = WebDavDestination(
        client = testClient(server),
        folder = APP_FOLDER,
        sleep = {},
        jitterMillis = { 0 },
    )

    /** Dos copias en el servicio, una foto con su `.slmeta` y un archivo de la persona. */
    private suspend fun cloudWithCopiesAndAPhoto() {
        fixture.connect(BackupDestinationKind.WEBDAV)
        assertEquals(BackupRun.Verified(1), fixture.runner(webDav()).run(control))
        fixture.addProduct("Leche de prueba")
        fixture.advance(2.minutes)
        assertEquals(BackupRun.Verified(2), fixture.runner(webDav()).run(control))
        dav.collections += "/dav/app/media/"
        dav.collections += "/dav/app/media/ab/"
        dav.files["/dav/app/media/ab/$PHOTO.webp"] = byteArrayOf(9, 9)
        dav.files["/dav/app/media/ab/$PHOTO.webp.slmeta"] = "{}".toByteArray()
        dav.files["/dav/app/notas.txt"] = "de la persona".toByteArray()
    }

    private fun appFiles(): List<String> = dav.files.keys.filter { it != "/dav/app/notas.txt" }

    private fun service(ops: DurableFileOps = DurableFileOps()): EraseService {
        val files = DatabaseFiles(fixture.file, fixture.noBackup)
        val state: suspend () -> BackupStateEntity? = { fixture.ports.backupState.get() }
        return EraseService(
            journal = EraseJournalStore({ fixture.noBackup }, ops, Dispatchers.IO),
            state = state,
            restoreInProgress = { false },
            hasNetwork = { online },
            remote =
                RemoteEraser(
                    state = state,
                    openWebDav = {
                        val media = WebDavMediaChannel(testClient(server), APP_FOLDER)
                        MediaOpen.Opened(webDav(), media)
                    },
                    slots = FileSlotStateStore(slotDirectory, Dispatchers.IO),
                    deleter = { slotAnswer },
                    ioContext = Dispatchers.IO,
                ),
            local =
                LocalEraser(
                    files = { files },
                    appFolders = { listOf(appFolder) },
                    ops = ops,
                    releaseAccess = { released += 1 },
                    ioContext = Dispatchers.IO,
                    log = fixture.log,
                ),
            mutex = Mutex(),
            stopWork = {},
            exclusive = { block ->
                // Como en la app: la base se cierra una sola vez, y el proceso se reinicia.
                if (!databaseClosed) fixture.ports.closeForRestore()
                databaseClosed = true
                block()
            },
            log = fixture.log,
        )
    }

    private fun erase(ops: DurableFileOps = DurableFileOps()) =
        EraseMyDataUseCase(service(ops), audit)

    private fun journalText(): String? =
        File(fixture.noBackup, EraseJournalStore.FILE_NAME).takeIf { it.exists() }?.readText()

    private fun assertThisPhoneIsUntouched() {
        assertTrue(fixture.file.exists())
        assertTrue(File(fixture.noBackup, DatabaseFiles.INITIALIZED_MARKER_NAME).exists())
        assertTrue(File(fixture.noBackup, "link.json").exists())
        assertTrue(File(fixture.noBackup, ACCOUNT_CHECKLIST).exists())
        assertTrue(File(appFolder, "media/ab/foto.webp").exists())
        assertEquals(0, released)
    }

    private fun assertThisPhoneIsEmpty() {
        assertFalse(fixture.file.exists())
        assertFalse(File(fixture.file.path + "-wal").exists())
        assertFalse(File(fixture.noBackup, ACCOUNT_CHECKLIST).exists())
        assertFalse(File(fixture.noBackup, "demo").exists())
        assertEquals(emptyList<String>(), fixture.noBackup.list().orEmpty().toList())
        assertEquals(emptyList<String>(), appFolder.list().orEmpty().toList())
        assertEquals(1, released)
    }

    @Test
    fun copiesMetasAndPhotosGoFirstAndOnlyThenThisPhone() = runBlocking {
        cloudWithCopiesAndAPhoto()
        assertEquals(6, appFiles().size)
        // Lo que quedaba de la app en el servicio cuando se borró lo primero de este teléfono.
        var cloudAtFirstLocalDelete: List<String>? = null
        val ops =
            DurableFileOps(
                listener =
                    OperationListener { operation ->
                        if (operation.startsWith("delete") && cloudAtFirstLocalDelete == null) {
                            cloudAtFirstLocalDelete = appFiles()
                        }
                    },
            )

        val outcome = erase(ops)(EraseScope.DEVICE_AND_COPIES)

        assertEquals(EraseOutcome.Finished, outcome)
        assertEquals(emptyList<String>(), cloudAtFirstLocalDelete)
        // Lo que la app no escribió sigue en el servicio, con su carpeta.
        assertEquals(listOf("/dav/app/notas.txt"), dav.files.keys.toList())
        assertTrue("/dav/app/" in dav.collections)
        assertThisPhoneIsEmpty()
        assertEquals(listOf(AuditEventType.DELETE_MY_DATA to AuditResult.SUCCESS), audits)
    }

    @Test
    fun withoutNetworkNothingIsTouchedAnywhere() = runBlocking {
        cloudWithCopiesAndAPhoto()
        online = false
        val before = dav.files.keys.toList()

        val outcome = erase()(EraseScope.DEVICE_AND_COPIES)

        assertEquals(EraseOutcome.Stopped(EraseStop.OFFLINE), outcome)
        assertEquals(before, dav.files.keys.toList())
        assertNull(journalText())
        assertThisPhoneIsUntouched()
    }

    @Test
    fun aServiceThatRefusesSaysSoBeforeThisPhoneAndThisPhoneOnlyNeedsItsOwnStep() = runBlocking {
        cloudWithCopiesAndAPhoto()
        refuseDeletes = true
        val before = dav.files.keys.toList()

        val refused = erase()(EraseScope.DEVICE_AND_COPIES)

        assertEquals(EraseOutcome.Stopped(EraseStop.REFUSED), refused)
        assertEquals(before, dav.files.keys.toList())
        assertEquals(ErasePhase.REMOTE.name, journalText())
        assertThisPhoneIsUntouched()

        val deviceOnly = erase().deviceOnly()

        assertEquals(EraseOutcome.Finished, deviceOnly)
        // Las copias siguen en el servicio, para restaurarlas.
        assertEquals(before, dav.files.keys.toList())
        assertThisPhoneIsEmpty()
    }

    @Test
    fun copiesLeftInTheCloudKeepThisPhoneUntilARetryEmptiesIt() = runBlocking {
        cloudWithCopiesAndAPhoto()
        failAfterFirstCopy = true

        val partial = erase()(EraseScope.DEVICE_AND_COPIES)

        assertEquals(EraseOutcome.Stopped(EraseStop.PARTIAL), partial)
        assertTrue(appFiles().isNotEmpty())
        assertThisPhoneIsUntouched()

        failAfterFirstCopy = false
        assertEquals(EraseOutcome.Finished, erase().resume())
        assertEquals(emptyList<String>(), appFiles())
        assertThisPhoneIsEmpty()
    }

    @Test
    fun aDeathInTheMiddleOfThisPhoneIsResumedWithoutTouchingTheCloudAgain() = runBlocking {
        cloudWithCopiesAndAPhoto()
        val dying =
            DurableFileOps(
                listener =
                    OperationListener { operation ->
                        if (operation.endsWith("/${fixture.file.name}")) throw ProcessDeath()
                    },
            )
        try {
            erase(dying)(EraseScope.DEVICE_AND_COPIES)
            error("El proceso debía morir")
        } catch (_: ProcessDeath) {
            Unit
        }
        // La nube ya está vacía; este teléfono, a medias, con su registro y su marca.
        assertEquals(emptyList<String>(), appFiles())
        assertFalse(fixture.file.exists())
        assertEquals(ErasePhase.LOCAL.name, journalText())
        assertTrue(File(fixture.noBackup, DatabaseFiles.INITIALIZED_MARKER_NAME).exists())
        val requests = server.requests.size

        val resumed = erase().resume()

        assertEquals(EraseOutcome.Finished, resumed)
        assertEquals(requests, server.requests.size)
        assertFalse(File(fixture.noBackup, DatabaseFiles.INITIALIZED_MARKER_NAME).exists())
        assertNull(journalText())
    }

    @Test
    fun slotsAskTheCloudWithoutNetworkAndARefusalLeavesThisPhone() = runBlocking {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        FileSlotStateStore(slotDirectory, Dispatchers.IO).save(
            SlotState(
                slots =
                    BackupSlot.entries.associateWith {
                        val uri = "content://nube/${it.name.lowercase()}"
                        SlotEntry(uri, "Nube", null, null, null)
                    },
                firstHandoffAt = null,
                writeMode = WriteMode.WT,
                sessionRequired = false,
            ),
        )
        online = false
        slotAnswer = SlotDocumentDelete.REFUSED

        assertEquals(
            EraseOutcome.Stopped(EraseStop.REFUSED),
            erase()(EraseScope.DEVICE_AND_COPIES),
        )
        assertThisPhoneIsUntouched()

        slotAnswer = SlotDocumentDelete.GONE
        assertEquals(EraseOutcome.Finished, erase().resume())
        assertThisPhoneIsEmpty()
    }

    @Test
    fun aJournalThatCannotBeReadNeverLeadsToDeletingThisPhone() = runBlocking {
        File(fixture.noBackup, EraseJournalStore.FILE_NAME).writeText("??")
        fixture.connect(BackupDestinationKind.WEBDAV)
        // El servicio no responde, como sin red.
        server.close()

        assertEquals(ErasePhase.REMOTE, erase().pending())
        // Sin llegar a la nube, no sigue con lo local.
        assertEquals(EraseOutcome.Stopped(EraseStop.UNREACHABLE), erase().resume())
        assertThisPhoneIsUntouched()
    }

    private companion object {
        const val ACCOUNT_CHECKLIST = "account-checklist.json"
        const val FORBIDDEN = 403
        const val UNAVAILABLE = 503
        const val PHOTO = "ab0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd"
    }
}
