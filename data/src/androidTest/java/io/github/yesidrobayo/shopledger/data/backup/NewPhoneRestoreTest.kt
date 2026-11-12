package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.destination.APP_FOLDER
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.InMemoryWebDav
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.ContentResolverDocumentAccess
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentSlotDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotEntry
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotTestProvider
import io.github.yesidrobayo.shopledger.data.destination.slots.WriteMode
import io.github.yesidrobayo.shopledger.data.destination.testClient
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.RestoreProgress
import java.io.File
import java.io.InputStream
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restaurar en un teléfono nuevo con cada destino, de punta a punta y sin red: un teléfono escribe y
 * respalda, y otro, con una base limpia, trae los mismos datos.
 *
 * - **WebDAV:** el teléfono nuevo conecta el mismo servicio falso, lista las copias, restaura la más
 *   nueva por su clave y, al reiniciar, tiene los mismos datos. La copia ya está en el destino
 *   conectado: `verified_seq` alcanza a `change_seq`.
 * - **Ranuras:** el teléfono nuevo no puede listar esa nube. Abre los archivos con el selector, del
 *   más nuevo al más viejo: el más nuevo quedó a medias y se rechaza sin tocar nada, y el siguiente
 *   abre. Sin destino conectado, la copia no cuenta como respaldada.
 */
@RunWith(AndroidJUnit4::class)
class NewPhoneRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val oldPhone = BackupFixture(context, "new-phone-old")
    private val newPhone = BackupFixture(context, "new-phone-new")
    private val control = TestControl()
    private val servers = ArrayList<FakeHttpServer>()
    private val cache = context.cacheDir
    private val slotState = File(cache, "new-phone-slots")

    @Before
    fun open() = runBlocking<Unit> {
        oldPhone.open()
        newPhone.open()
        SlotTestProvider.reset(cache)
        slotState.deleteRecursively()
    }

    @After
    fun close() {
        servers.forEach { it.close() }
        SlotTestProvider.reset(cache)
        slotState.deleteRecursively()
        oldPhone.close()
        newPhone.close()
    }

    @Test
    fun webDavRestoresTheSameDataOnACleanPhone() = runBlocking<Unit> {
        val dav = InMemoryWebDav().also { it.collections += "/dav/app/" }
        val server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
        oldPhone.connect(BackupDestinationKind.WEBDAV)
        oldPhone.addProduct("Pan de prueba")
        oldPhone.addProduct("Leche de prueba")
        assertEquals(BackupRun.Verified(1), oldPhone.runner(webDav(server)).run(control))
        assertEquals(0L, newPhone.ports.backupState.catalogCount())

        // El teléfono nuevo conecta el mismo servicio y ve la copia del otro.
        newPhone.connect(BackupDestinationKind.WEBDAV)
        val listing = webDav(server).listCopies(TransferControl.Unbounded)
        val copy = (listing as DestinationResult.Success).value.copies.single()
        val service = service(newPhone) { webDav(server) }

        assertEquals(RestoreProgress.ReadyToRestart(0), service.restoreCopy(copy.ref.value))

        val access = newPhone.reopen()
        assertTrue("$access", access is DataAccess.Ready)
        assertEquals(2L, newPhone.ports.backupState.catalogCount())
        val state = newPhone.state()
        assertEquals(BackupLinkState.CONNECTED.name, state.linkState)
        assertEquals("La copia ya está en el destino", state.changeSeq, state.verifiedSeq)
    }

    @Test
    fun slotsOpenFromTheNewestAndATruncatedOneIsRefused() = runBlocking<Unit> {
        prepareSlots()
        oldPhone.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        oldPhone.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), oldPhone.runner(slots()).run(control))
        oldPhone.addProduct("Leche de prueba")
        oldPhone.advance(2.minutes)
        assertEquals(BackupRun.Verified(2), oldPhone.runner(slots()).run(control))
        oldPhone.addProduct("Arroz de prueba")
        oldPhone.advance(2.minutes)
        assertEquals(BackupRun.Verified(3), oldPhone.runner(slots()).run(control))
        SlotTestProvider.awaitDrains()

        // La ranura más nueva quedó a medias: un corte dejó solo su primera mitad.
        val newest = requireNotNull(oldPhone.state().headFileRef).lowercase()
        val newestFile = File(cache, "slot-test/$newest")
        newestFile.writeBytes(newestFile.readBytes().copyOf(newestFile.length().toInt() / 2))
        val byAge = slotsNewestFirst()
        assertEquals(newest, byAge.first())

        val service = service(newPhone) { error("El teléfono nuevo no tiene destino") }
        assertEquals(
            RestoreProgress.Refused(RestoreProblem.DAMAGED),
            service.restoreFile(uriOf(byAge[0])),
        )
        // El rechazo no tocó nada: el teléfono nuevo sigue vacío y sin restauración en curso.
        assertEquals(0L, newPhone.ports.backupState.catalogCount())
        assertTrue(!RestoreFiles(DatabaseFiles(newPhone.file, newPhone.noBackup)).lock.exists())

        assertEquals(RestoreProgress.ReadyToRestart(0), service.restoreFile(uriOf(byAge[1])))

        assertTrue(newPhone.reopen() is DataAccess.Ready)
        assertEquals(2L, newPhone.ports.backupState.catalogCount())
        val state = newPhone.state()
        assertEquals(BackupLinkState.NOT_CONNECTED.name, state.linkState)
        assertTrue("Sin destino, nada cuenta como respaldado", state.changeSeq > state.verifiedSeq)
    }

    // Apoyo

    /** La restauración del teléfono [phone], con [destination] como su destino conectado. */
    private fun service(
        phone: BackupFixture,
        destination: () -> BackupDestination,
    ): BackupRestoreService {
        val opener = DestinationOpener { DestinationOpen.Opened(destination()) }
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
            files = ResolverFiles(),
        )
    }

    /** Los archivos del proveedor de pruebas, leídos como los leería el selector. */
    private inner class ResolverFiles : PickedFiles {
        private val real = ResolverPickedFiles(context.contentResolver)

        override fun declaredSize(uri: String): Long? = real.declaredSize(uri)

        override fun open(uri: String): InputStream = real.open(uri)
    }

    private fun webDav(server: FakeHttpServer) =
        WebDavDestination(client = testClient(server), folder = APP_FOLDER, sleep = {
        }, jitterMillis = { 0 })

    private fun uriOf(id: String) = SlotTestProvider.uriOf("${context.packageName}.slottest", id)

    private suspend fun prepareSlots() {
        for (id in listOf("a", "b", "c")) SlotTestProvider.create(cache, id)
        FileSlotStateStore(slotState, Dispatchers.IO).save(
            SlotState(
                slots =
                    BackupSlot.entries.associateWith {
                        SlotEntry(uriOf(it.name.lowercase()), "Nube", null, null, null)
                    },
                firstHandoffAt = null,
                writeMode = WriteMode.WT,
                sessionRequired = false,
            ),
        )
    }

    /**
     * Los tres archivos, del más nuevo al más viejo, como los ve la persona en su nube por la hora:
     * aquí, por la generación que recuerda el teléfono que los escribió.
     */
    private suspend fun slotsNewestFirst(): List<String> {
        val loaded = FileSlotStateStore(slotState, Dispatchers.IO).load() as SlotStateLoad.Loaded
        return loaded.state.slots.entries
            .sortedByDescending { (_, entry) -> entry.copy?.generation ?: -1 }
            .map { (slot, _) -> slot.name.lowercase() }
    }

    private fun slots() = DocumentSlotDestination(
        store = FileSlotStateStore(slotState, Dispatchers.IO),
        documents = ContentResolverDocumentAccess(context.contentResolver) { true },
        clock = oldPhone.clock,
        ioContext = Dispatchers.IO,
    )
}
