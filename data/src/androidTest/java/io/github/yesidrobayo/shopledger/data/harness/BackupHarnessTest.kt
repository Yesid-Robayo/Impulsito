package io.github.yesidrobayo.shopledger.data.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataGraph
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.BackupNowRequest
import io.github.yesidrobayo.shopledger.data.backup.CandidateFixtures
import io.github.yesidrobayo.shopledger.data.backup.MemoryLog
import io.github.yesidrobayo.shopledger.data.backup.RestoreFiles
import io.github.yesidrobayo.shopledger.data.crypto.KeystoreSealer
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.FakeResponse
import io.github.yesidrobayo.shopledger.data.destination.InMemoryWebDav
import io.github.yesidrobayo.shopledger.data.destination.RecordedRequest
import io.github.yesidrobayo.shopledger.data.destination.TEST_PASSWORD
import io.github.yesidrobayo.shopledger.data.destination.TEST_USER
import io.github.yesidrobayo.shopledger.data.destination.WebDavConnector
import io.github.yesidrobayo.shopledger.data.destination.WebDavCredentialStore
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.testHttp
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.safety.PolicyConsentStatus
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El arnés de depuración del respaldo sobre la base real, el servicio WebDAV falso en memoria y
 * documentos inventados: conectar, preparar las ranuras con las reglas de la app, registrar cambios,
 * `Respaldar ahora` y restaurar un archivo hasta reiniciar. Todo sin red y con datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class BackupHarnessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = testDatabaseFile(context, "backup-harness-test.db")
    private val noBackup = File(context.noBackupFilesDir, "backup-harness-test")
    private val aliases = "test-backup-harness-a" to "test-backup-harness-b"
    private val credentials = WebDavCredentialStore({ noBackup }, Dispatchers.IO, aliases)
    private val links = DestinationLinkStore({ noBackup }, Dispatchers.IO)
    private val slots = FileSlotStateStore(noBackup, Dispatchers.IO)
    private val documents = FakeDocuments()
    private val servers = ArrayList<FakeHttpServer>()
    private val clock = Clock { NOW }
    private var graph = newGraph()

    /** Las bases que abrió la prueba: se cierran al terminar, para no gastar archivos abiertos. */
    private val openedPorts = LinkedHashSet<DataPorts>()

    @Before
    fun clean() {
        deleteDatabaseFiles(file)
        noBackup.deleteRecursively()
        listOf(aliases.first, aliases.second).forEach { KeystoreSealer(it).deleteKey() }
    }

    @After
    fun cleanUp() {
        servers.forEach { it.close() }
        openedPorts.forEach { ports ->
            try {
                runBlocking { ports.closeForRestore() }
            } catch (_: Exception) {
                // La restauración ya la cerró.
            }
        }
        clean()
        CandidateFixtures.clean()
    }

    private fun newGraph() =
        DataGraph(context, Dispatchers.IO, clock, { DatabaseFiles(file, noBackup) }, MemoryLog())

    private fun harness(http: OkHttpClient = testHttp()) = BackupHarness(
        open = graph::open,
        backupLock = graph.backupMutex,
        scheduler = graph.backupScheduler,
        restore = graph::restoreCoordinator,
        policy = graph.policyConsent,
        connector = WebDavConnector(http, credentials, links),
        links = links,
        slots = slots,
        documents = documents,
        clock = clock,
        ioContext = Dispatchers.IO,
    )

    /** El servicio falso, y el cliente que le lleva cada petición a `https://` en claro. */
    private fun dav(
        override: (RecordedRequest) -> FakeResponse? = { null },
    ): Pair<InMemoryWebDav, OkHttpClient> {
        val dav = InMemoryWebDav(override = override)
        val server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
        val http =
            testHttp().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val local =
                    request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.port)
                        .build()
                chain.proceed(request.newBuilder().url(local).build())
            }.build()
        return dav to http
    }

    private suspend fun ports(): DataPorts =
        (graph.open() as DataAccess.Ready).ports.also { openedPorts += it }

    private suspend fun state(): BackupStateEntity = requireNotNull(ports().backupState.get())

    // Conectar WebDAV

    @Test
    fun aPassingCheckConnectsAndForcesAnUpload() = runBlocking<Unit> {
        val (dav, http) = dav()
        val result = harness(http).connectWebDav(BASE_URL, TEST_USER, TEST_PASSWORD, APP_NAME)

        assertEquals(HarnessResult.Done("CONNECTED"), result)
        assertTrue("/dav/$APP_NAME/" in dav.collections)
        val state = state()
        assertEquals("CONNECTED", state.linkState)
        assertEquals(BackupDestinationKind.WEBDAV.name, state.destinationKind)
        assertEquals(TEST_USER, state.destinationAccount)
        assertEquals("$BASE_URL$APP_NAME/", state.destinationFolder)
        assertEquals("Conectar fuerza una subida", 1L, state.changeSeq)
        val link = requireNotNull(links.read())
        assertEquals(state.destinationId, link.destinationId)
        assertEquals(NOW.toEpochMilliseconds(), link.connectedAtMillis)
        // La contraseña solo queda sellada.
        assertFalse(
            File(noBackup, DestinationLinkStore.FILE_NAME).readText().contains(TEST_PASSWORD),
        )
        val shown = requireNotNull(harness().observeState().first())
        assertEquals(HarnessState("CONNECTED", "WEBDAV", 1, 0, 0, null, 0, null, null), shown)
    }

    @Test
    fun aFailedCheckOrAnHttpAddressSavesNothing() = runBlocking<Unit> {
        val (_, http) = dav { FakeResponse.status(401) }
        val rejected = harness(http).connectWebDav(BASE_URL, TEST_USER, TEST_PASSWORD, APP_NAME)
        assertTrue("$rejected", rejected is HarnessResult.Stopped)
        assertTrue(rejected.code, rejected.code.endsWith("CredentialRejected"))

        assertEquals(
            HarnessResult.Stopped("NOT_HTTPS"),
            harness(
                http,
            ).connectWebDav("http://webdav.example.com/dav/", TEST_USER, TEST_PASSWORD, APP_NAME),
        )
        assertEquals(
            HarnessResult.Stopped("MISSING_FIELD"),
            harness(http).connectWebDav(BASE_URL, TEST_USER, "", APP_NAME),
        )
        val state = state()
        assertNull(state.destinationKind)
        assertEquals(0L, state.changeSeq)
        assertNull(links.read())
    }

    @Test
    fun aRunningCopyMakesConnectingAndPreparingWait() = runBlocking<Unit> {
        ports()
        graph.backupMutex.lock()
        try {
            assertEquals(
                HarnessResult.Stopped("BUSY"),
                harness().connectWebDav(BASE_URL, TEST_USER, TEST_PASSWORD, APP_NAME),
            )
            assertEquals(HarnessResult.Stopped("BUSY"), harness().prepareSlots(driveSlots()))
        } finally {
            graph.backupMutex.unlock()
        }
        assertNull(state().destinationKind)
    }

    // Ranuras

    @Test
    fun threeDriveFilesBecomeTheSlots() = runBlocking<Unit> {
        val uris = driveSlots()

        assertEquals(HarnessResult.Done("SLOTS_PREPARED"), harness().prepareSlots(uris))

        val saved = (slots.load() as SlotStateLoad.Loaded).state
        assertEquals(uris, BackupSlot.entries.map { saved[it].uri })
        assertEquals(setOf("Drive"), saved.slots.values.map { it.providerLabel }.toSet())
        assertEquals("shopledger-backup-a.slcopy", saved[BackupSlot.A].displayName)
        assertNull(saved.firstHandoffAt)
        assertTrue(saved.slots.values.all { it.copy == null && it.writing == null })
        val state = state()
        assertEquals("CONNECTED", state.linkState)
        assertEquals(BackupDestinationKind.DOCUMENT_SLOTS.name, state.destinationKind)
        assertEquals("document-slots", state.destinationId)
        assertNull(state.destinationAccount)
        assertNull(state.destinationFolder)
        assertEquals(1L, state.changeSeq)
        assertEquals(BackupDestinationKind.DOCUMENT_SLOTS, links.read()?.kind)
        assertEquals(uris.toSet(), documents.held)
        assertTrue(documents.released.isEmpty())
    }

    @Test
    fun filesThatBreakARuleAreRefusedAndReleased() = runBlocking<Unit> {
        val cases =
            listOf(
                SlotRejection.PROVIDER_NOT_ALLOWED to { uris: List<String> ->
                    documents.add(uris[1], authority = "com.android.externalstorage.documents")
                },
                SlotRejection.NOT_WRITABLE to { uris: List<String> ->
                    documents.add(uris[2], writable = false)
                },
                SlotRejection.SIZE_UNKNOWN to { uris: List<String> ->
                    documents.add(uris[0], size = null)
                },
                SlotRejection.NO_ACCESS to { uris: List<String> -> documents.refused += uris[1] },
            )
        for ((reason, breakRule) in cases) {
            documents.reset()
            val uris = driveSlots()
            breakRule(uris)

            assertEquals(HarnessResult.Stopped(reason.name), harness().prepareSlots(uris))
            assertTrue("$reason", documents.held.isEmpty())
        }
        documents.reset()
        val uris = driveSlots()
        assertEquals(
            HarnessResult.Stopped(SlotRejection.SAME_DOCUMENT.name),
            harness().prepareSlots(listOf(uris[0], uris[1], uris[0])),
        )
        assertEquals(
            HarnessResult.Stopped(SlotRejection.NOT_THREE.name),
            harness().prepareSlots(uris.take(2)),
        )
        assertEquals(SlotStateLoad.Missing, slots.load())
        assertNull(state().destinationKind)
    }

    @Test
    fun aRefusalKeepsThePermissionsOfTheCurrentSlots() = runBlocking<Unit> {
        val current = driveSlots()
        harness().prepareSlots(current)
        val outside = "content://com.android.externalstorage.documents/document/otro"
        documents.add(outside, authority = "com.android.externalstorage.documents")

        val result = harness().prepareSlots(listOf(current[0], current[1], outside))

        assertEquals(HarnessResult.Stopped(SlotRejection.PROVIDER_NOT_ALLOWED.name), result)
        assertEquals(listOf(outside), documents.released)
        assertEquals(current.toSet(), documents.held)
        val saved = (slots.load() as SlotStateLoad.Loaded).state
        assertEquals(current, BackupSlot.entries.map { saved[it].uri })
    }

    // Cambios, política y Respaldar ahora

    @Test
    fun inventedChangesCountAsChangesOfTheBusiness() = runBlocking<Unit> {
        assertTrue(ports().backupState.isEmpty())

        assertEquals(
            HarnessResult.Done("RECORDED_3"),
            harness().recordInventedChanges("Proveedor de prueba", 3),
        )

        assertEquals(3L, state().changeSeq)
        assertFalse(ports().backupState.isEmpty())
        assertEquals(3L, ports().backupState.catalogCount())
    }

    @Test
    fun theBackupStartsOnlyWithADestinationAndThePolicy() = runBlocking<Unit> {
        ports()
        assertEquals(BackupNowRequest.NOT_CONNECTED, harness().backupNow())
        harness().prepareSlots(driveSlots())
        assertEquals(PolicyConsentStatus.NEVER_ACCEPTED, harness().policyStatus())
        assertEquals(BackupNowRequest.POLICY_PENDING, harness().backupNow())

        assertEquals(HarnessResult.Done("SAVED"), harness().acceptPolicy())

        assertEquals(PolicyConsentStatus.ACCEPTED, harness().policyStatus())
        // Sin la biblioteca de trabajos en esta capa de datos, la subida queda encolada en nada.
        assertEquals(BackupNowRequest.QUEUED, harness().backupNow())
    }

    // Restaurar

    @Test
    fun aFileThatIsNotACopyIsRefusedAndLeavesNothing() = runBlocking<Unit> {
        ports()
        documents.files[PICKED] = "esto no es una copia".toByteArray()

        val result = harness().restoreFromFile(PICKED)

        assertTrue("$result", result is HarnessRestore.Stopped)
        assertTrue((result as HarnessRestore.Stopped).code, result.code.startsWith("REFUSED"))
        assertFalse(RestoreFiles(DatabaseFiles(file, noBackup)).lock.exists())
        assertFalse(harness().cancelRestore())
        assertEquals(
            HarnessRestore.Stopped("NO_SUCH_SAFETY_COPY"),
            harness().answer(RestoreAnswer.DiscardSafetyCopy(0)),
        )
    }

    @Test
    fun anEmptyCopyOverDataAsksAndReplacesTheDataAfterRestarting() = runBlocking<Unit> {
        val harness = harness()
        harness.recordInventedChanges("Proveedor de prueba", 2)
        documents.files[PICKED] = emptyCopy()

        val asked = harness.restoreFromFile(PICKED)
        assertEquals(HarnessRestore.Question(RestoreQuestion.EMPTY_COPY, 0), asked)
        assertTrue(harness.cancelRestore())
        assertFalse(RestoreFiles(DatabaseFiles(file, noBackup)).lock.exists())

        assertEquals(
            HarnessRestore.Question(RestoreQuestion.EMPTY_COPY, 0),
            harness.restoreFromFile(PICKED),
        )
        assertEquals(
            HarnessRestore.ReadyToRestart(0),
            harness.answer(RestoreAnswer.ReplaceWithEmptyCopy),
        )
        // Hasta reiniciar, la base activa sigue igual: el arranque hace el reemplazo.
        graph = newGraph()
        assertEquals(0L, ports().backupState.catalogCount())
    }

    // Apoyo

    /** Tres documentos de Drive, nuevos y vacíos, en orden A, B y C. */
    private fun driveSlots(): List<String> = listOf("a", "b", "c").map { letter ->
        "content://$DRIVE/document/$letter".also {
            documents.add(it, name = "shopledger-backup-$letter.slcopy")
        }
    }

    private fun emptyCopy(): ByteArray {
        val raw = CandidateFixtures.emptyCopy("harness-empty").readBytes()
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        return output.toByteArray()
    }

    /** Documentos inventados: lo que dice cada uno y qué permisos tiene la app. */
    private class FakeDocuments : HarnessDocuments {
        private val documents = HashMap<String, PickedDocument>()
        val files = HashMap<String, ByteArray>()
        val held = LinkedHashSet<String>()
        val released = ArrayList<String>()
        val refused = HashSet<String>()

        fun add(
            uri: String,
            authority: String = DRIVE,
            writable: Boolean = true,
            size: Long? = 0,
            name: String? = null,
        ) {
            documents[uri] = PickedDocument(authority, writable, size, name)
        }

        fun reset() {
            documents.clear()
            held.clear()
            released.clear()
            refused.clear()
        }

        override fun takePermission(uri: String): Boolean {
            if (uri in refused) return false
            held += uri
            return true
        }

        override fun releasePermission(uri: String) {
            held -= uri
            released += uri
        }

        override fun inspect(uri: String): PickedDocument? = documents[uri]
            ?: files[uri]?.let { PickedDocument(null, false, it.size.toLong(), null) }

        override fun open(uri: String): InputStream =
            files[uri]?.inputStream() ?: throw IOException("Sin archivo")
    }

    private companion object {
        /**
         * Una hora por delante del reloj real: así ningún archivo que la prueba escribe parece del
         * futuro, que la limpieza borraría.
         */
        val NOW: Instant = Instant.fromEpochSeconds(System.currentTimeMillis() / 1000 + 3_600)
        const val BASE_URL = "https://webdav.example.com/dav/"
        const val APP_NAME = "TiendaDePrueba"
        const val DRIVE = "com.google.android.apps.docs.storage"
        const val PICKED = "content://example.documents/document/copia"
    }
}
