package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.APP_FOLDER
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeHttpServer
import io.github.yesidrobayo.shopledger.data.destination.FakeResponse
import io.github.yesidrobayo.shopledger.data.destination.InMemoryWebDav
import io.github.yesidrobayo.shopledger.data.destination.RecordedRequest
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.WebDavDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.ContentResolverDocumentAccess
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentResult
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentSlotDestination
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotEntry
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotTestProvider
import io.github.yesidrobayo.shopledger.data.destination.slots.WriteMode
import io.github.yesidrobayo.shopledger.data.destination.testClient
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El motor con cada destino real: WebDAV contra un servidor falso de la propia máquina, y las
 * ranuras contra un proveedor de documentos del APK de pruebas, con su estado en el disco.
 */
@RunWith(AndroidJUnit4::class)
class BackupDestinationsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "backup-destinations-test")
    private val control = TestControl()
    private val servers = ArrayList<FakeHttpServer>()
    private val cache = context.cacheDir
    private val slotState = File(cache, "backup-destinations-slots")

    @Before
    fun open() = runBlocking {
        fixture.open()
        SlotTestProvider.reset(cache)
        slotState.deleteRecursively()
        fixture.addProduct("Pan de prueba")
    }

    @After
    fun close() {
        servers.forEach { it.close() }
        SlotTestProvider.reset(cache)
        slotState.deleteRecursively()
        fixture.close()
    }

    @Test
    fun webDavUploadsVerifiesAndKeepsEveryCopy() = runBlocking {
        fixture.connect(BackupDestinationKind.WEBDAV)
        val (dav, server) = dav()

        assertEquals(BackupRun.Verified(1), fixture.runner(webDav(server)).run(control))
        fixture.addProduct("Leche de prueba")
        fixture.advance(2.minutes)
        assertEquals(BackupRun.Verified(2), fixture.runner(webDav(server)).run(control))

        val names = dav.files.keys.map { it.removePrefix("/dav/app/") }
        val copies = names.mapNotNull(BackupFileName::parse)
        assertEquals(listOf(1L, 2L), copies.map { it.generation }.sorted())
        assertTrue(names.containsAll(copies.map { it.metaFileName }))
        // Nada con nombre temporal: cada subida terminó con su nombre final.
        assertTrue(names.none { it.endsWith(BackupFileName.TEMPORARY_SUFFIX) })
        assertEquals(copies.maxBy { it.generation }.fileName, fixture.state().headFileRef)
    }

    @Test
    fun webDavRecreatesAMissingFolder() = runBlocking {
        fixture.connect(BackupDestinationKind.WEBDAV)
        val (dav, server) = dav(withFolder = false)

        assertEquals(BackupRun.Verified(1), fixture.runner(webDav(server)).run(control))
        assertTrue("/dav/app/" in dav.collections)
        assertTrue(server.requests.any { it.method == "MKCOL" })
    }

    @Test
    fun webDavRejectedPasswordFullServiceAndUnsupportedMethod() = runBlocking {
        val cases =
            listOf(
                Triple("PROPFIND", 401, BackupRun.Stopped(StopCause.PermissionLost)),
                Triple(
                    "PUT",
                    507,
                    BackupRun.Stopped(StopCause.Failure(BackupFailureCode.QUOTA_EXCEEDED)),
                ),
                Triple(
                    "MOVE",
                    501,
                    BackupRun.Stopped(StopCause.Failure(BackupFailureCode.DESTINATION_UNSUPPORTED)),
                ),
            )
        for ((method, status, expected) in cases) {
            fixture.connect(BackupDestinationKind.WEBDAV)
            // Cada caso empieza sin el fallo del anterior: con el servicio lleno, una copia
            // automática no generaría nada hasta el día siguiente.
            fixture.sql("UPDATE backup_state SET failure_code = NULL WHERE id = 1")
            val (dav, server) =
                dav { request -> FakeResponse.status(status).takeIf { request.method == method } }

            assertEquals("$method $status", expected, fixture.runner(webDav(server)).run(control))
            // Nada quedó con nombre final.
            assertTrue(
                dav.files.keys.none {
                    BackupFileName.parse(it.removePrefix("/dav/app/")) !=
                        null
                },
            )
        }
    }

    @Test
    fun aServerErrorRetriesInTheRunAndThenLater() = runBlocking {
        fixture.connect(BackupDestinationKind.WEBDAV)
        val (_, server) = dav { request ->
            FakeResponse.status(503).takeIf { request.method == "PUT" }
        }

        val run = fixture.runner(webDav(server)).run(control)

        assertTrue(run is BackupRun.Retry)
        assertEquals(RetryReason.TRANSIENT, (run as BackupRun.Retry).reason)
        // El primer intento y los tres reintentos de la ejecución.
        assertEquals(4, server.requests.count { it.method == "PUT" })
    }

    @Test
    fun anotherPhonesNewerCopyIsAConflictOnTheFirstRunWithoutWriting() = runBlocking {
        fixture.connect(BackupDestinationKind.WEBDAV)
        val (dav, server) = dav()
        assertEquals(BackupRun.Verified(1), fixture.runner(webDav(server)).run(control))
        fixture.addProduct("Leche de prueba")
        fixture.advance(2.minutes)
        assertEquals(BackupRun.Verified(2), fixture.runner(webDav(server)).run(control))
        // Como en el simulacro con un servicio real: dos copias viejas de un tercer teléfono sin
        // metadatos, y la copia más nueva de otro teléfono, que restauró la de este y subió encima.
        putCopy(dav, generation = 1, deviceId = THIRD_DEVICE_ID, withMeta = false)
        putCopy(dav, generation = 2, deviceId = THIRD_DEVICE_ID, withMeta = false)
        val foreign = putCopy(dav, generation = 3, deviceId = OTHER_DEVICE_ID)
        fixture.addProduct("Arroz de prueba")
        fixture.advance(2.minutes)
        val before = server.requests.size

        val run = fixture.runner(webDav(server)).run(control)

        assertEquals(BackupRun.Stopped(StopCause.Conflict(BackupConflictKind.OTHER_DEVICE)), run)
        val state = fixture.state()
        assertEquals("CONFLICT", state.linkState)
        assertEquals(3L, state.conflictGeneration)
        assertEquals(OTHER_DEVICE_ID, state.conflictDeviceId)
        assertEquals(foreign.fileName, state.conflictFileRef)
        // Solo la lista y la lectura de los metadatos: nada se escribió ni se borró.
        val methods = server.requests.drop(before).map { it.method }.toSet()
        assertEquals(setOf("PROPFIND", "GET"), methods)
    }

    @Test
    fun aMomentaryFailureReadingAnOldCopyDefersTheConflictWithoutWriting() = runBlocking {
        fixture.connect(BackupDestinationKind.WEBDAV)
        val failing = AtomicBoolean(false)
        val oldMeta = AtomicReference("")
        val (dav, server) = dav { request ->
            FakeResponse.status(503).takeIf {
                failing.get() && request.method == "GET" && request.path == oldMeta.get()
            }
        }
        assertEquals(BackupRun.Verified(1), fixture.runner(webDav(server)).run(control))
        oldMeta.set(
            synchronized(dav) {
                dav.files.keys.single { it.endsWith(BackupFileName.META_SUFFIX) }
            },
        )
        putCopy(dav, generation = 2, deviceId = OTHER_DEVICE_ID)
        fixture.addProduct("Leche de prueba")
        fixture.advance(2.minutes)
        // Los metadatos de una copia vieja de este teléfono no se pueden leer un momento.
        failing.set(true)
        val before = server.requests.size

        val first = fixture.runner(webDav(server)).run(control)

        // La lista no se toma por completa: la ejecución se reintenta, sin decidir ni escribir.
        assertTrue("$first", first is BackupRun.Retry)
        assertEquals(RetryReason.TRANSIENT, (first as BackupRun.Retry).reason)
        assertEquals("CONNECTED", fixture.state().linkState)
        val tried = server.requests.drop(before)
        // El primer intento y los tres reintentos de la ejecución.
        assertEquals(4, tried.count { it.method == "GET" && it.path == oldMeta.get() })
        assertEquals(setOf("PROPFIND", "GET"), tried.map { it.method }.toSet())

        // Con el servicio de vuelta, el intento siguiente ve la copia del otro teléfono.
        failing.set(false)
        val next = server.requests.size
        val second = fixture.runner(webDav(server)).run(control)

        assertEquals(
            BackupRun.Stopped(StopCause.Conflict(BackupConflictKind.OTHER_DEVICE)),
            second,
        )
        assertEquals("CONFLICT", fixture.state().linkState)
        assertEquals(setOf("PROPFIND", "GET"), server.requests.drop(next).map { it.method }.toSet())
    }

    @Test
    fun slotsWriteByTurnsAndRereadThroughTheProvider() = runBlocking {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        prepareSlots()

        assertEquals(BackupRun.Verified(1), fixture.runner(slots()).run(control))
        val first = requireNotNull(fixture.state().headFileRef)
        fixture.addProduct("Leche de prueba")
        fixture.advance(2.minutes)
        assertEquals(BackupRun.Verified(2), fixture.runner(slots()).run(control))
        val second = requireNotNull(fixture.state().headFileRef)

        // Nunca la ranura de la última copia.
        assertNotEquals(first, second)
        val state = (
            FileSlotStateStore(
                slotState,
                Dispatchers.IO,
            ).load() as SlotStateLoad.Loaded
            ).state
        assertEquals(1L, state[BackupSlot.valueOf(first)].copy?.generation)
        assertEquals(2L, state[BackupSlot.valueOf(second)].copy?.generation)
        val written = SlotTestProvider.bytesOf(cache, second.lowercase())
        assertEquals(state[BackupSlot.valueOf(second)].copy?.sizeBytes, written.size.toLong())
    }

    @Test
    fun aSlotChangedOutsideIsAConflictAndIsNotWritten() = runBlocking {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        prepareSlots()
        fixture.runner(slots()).run(control)
        val head = requireNotNull(fixture.state().headFileRef).lowercase()
        val before = SlotTestProvider.bytesOf(cache, head)
        // Otro teléfono, o la persona, escribió ese documento.
        val access = ContentResolverDocumentAccess(context.contentResolver) { true }
        val opened = (access.openForWrite(uriOf(head), WriteMode.WT) as DocumentResult.Ok).value
        opened.stream.write(before + byteArrayOf(1, 2, 3))
        opened.close()
        fixture.addProduct("Leche de prueba")

        val run = fixture.runner(slots()).run(control)

        assertEquals(BackupRun.Stopped(StopCause.Conflict(BackupConflictKind.OTHER_DEVICE)), run)
        assertEquals("CONFLICT", fixture.state().linkState)
        assertArrayEquals(before + byteArrayOf(1, 2, 3), SlotTestProvider.bytesOf(cache, head))
    }

    @Test
    fun withoutTheSlotStateNothingIsWrittenAndAccessIsLost() = runBlocking {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        for (id in listOf("a", "b", "c")) SlotTestProvider.create(cache, id)

        val run = fixture.runner(slots()).run(control)

        assertEquals(BackupRun.Stopped(StopCause.PermissionLost), run)
        assertEquals("PERMISSION_LOST", fixture.state().linkState)
        for (id in listOf("a", "b", "c")) assertEquals(0, SlotTestProvider.bytesOf(cache, id).size)
    }

    private fun dav(
        withFolder: Boolean = true,
        override: (RecordedRequest) -> FakeResponse? = { null },
    ): Pair<InMemoryWebDav, FakeHttpServer> {
        val dav = InMemoryWebDav(override = override)
        if (withFolder) dav.collections += "/dav/app/"
        val server = FakeHttpServer(keepAlive = true, handler = dav::handle).also { servers += it }
        return dav to server
    }

    private fun webDav(server: FakeHttpServer) =
        WebDavDestination(client = testClient(server), folder = APP_FOLDER, sleep = {
        }, jitterMillis = { 0 })

    /**
     * Una copia que subió [deviceId] al servicio falso, con sus metadatos si [withMeta], como la
     * deja un teléfono que no es el de la prueba.
     */
    private fun putCopy(
        dav: InMemoryWebDav,
        generation: Long,
        deviceId: String,
        withMeta: Boolean = true,
    ): BackupFileName {
        val name =
            BackupFileName(
                generation,
                AppDatabase.SCHEMA_VERSION,
                requireNotNull(CopyInstant.of(fixture.now)),
                deviceId.take(8),
                BackupFileFormat.GZIP,
            )
        val bytes = ByteArray(64) { it.toByte() }
        val meta =
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
            )
        synchronized(dav) {
            dav.files["/dav/app/${name.fileName}"] = bytes
            if (withMeta) dav.files["/dav/app/${name.metaFileName}"] = meta
        }
        return name
    }

    private companion object {
        /** Un tercer teléfono inventado, distinto del de la prueba y del otro. */
        const val THIRD_DEVICE_ID = "5e6f7a8b-1c2d-4e3f-9a0b-c1d2e3f4a5b6"
    }

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

    private fun slots() = DocumentSlotDestination(
        store = FileSlotStateStore(slotState, Dispatchers.IO),
        documents = ContentResolverDocumentAccess(context.contentResolver) { true },
        clock = fixture.clock,
        ioContext = Dispatchers.IO,
    )
}
