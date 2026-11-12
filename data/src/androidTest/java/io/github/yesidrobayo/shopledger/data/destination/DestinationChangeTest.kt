package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.backup.BackupStaging
import io.github.yesidrobayo.shopledger.data.backup.ConflictResolver
import io.github.yesidrobayo.shopledger.data.backup.TEST_DESTINATION_ID
import io.github.yesidrobayo.shopledger.data.crypto.KeystoreSealer
import io.github.yesidrobayo.shopledger.data.destination.slots.DOCUMENT_SLOTS_DESTINATION_ID
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentAccess
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentError
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentInfo
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentResult
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.PendingSlotFile
import io.github.yesidrobayo.shopledger.data.destination.slots.PickedDocument
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotEntry
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotFileDocuments
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotPendingStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.slots.WritableDocument
import io.github.yesidrobayo.shopledger.data.destination.slots.WriteMode
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
 * Cambiar de destino sobre la base real, la credencial sellada con Keystore y archivos reales en
 * el dispositivo, con documentos y servicios inventados: de WebDAV a otro WebDAV, de las ranuras a
 * WebDAV y de WebDAV a las ranuras. El destino nuevo no hereda nada del anterior, el anterior queda
 * suelto y sus copias sin tocar, y un corte tras cada paso, o dos seguidos, deja un solo destino,
 * sin mezclar sus datos. Ninguna prueba usa la red.
 */
@RunWith(AndroidJUnit4::class)
class DestinationChangeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "destination-change-test")
    private val directory get() = fixture.noBackup
    private val aliases = "test-change-credential-a" to "test-change-credential-b"
    private val credentials get() = WebDavCredentialStore({ directory }, Dispatchers.IO, aliases)
    private val links get() = DestinationLinkStore({ directory }, Dispatchers.IO)
    private val slots get() = FileSlotStateStore(directory, Dispatchers.IO)
    private val pending get() = SlotPendingStore(directory, Dispatchers.IO)
    private val documents = FakeDocuments()
    private var cancels = 0
    private var changes = 0

    @Before
    fun open() = runBlocking {
        fixture.open()
        clearKeys()
    }

    @After
    fun close() {
        fixture.close()
        clearKeys()
    }

    private fun clearKeys() = listOf(aliases.first, aliases.second).forEach {
        KeystoreSealer(it).deleteKey()
    }

    private fun service(cutAfter: ChangeStep? = null) = DestinationLinkService(
        open = { DataAccess.Ready(fixture.ports) },
        mutex = Mutex(),
        credentials = credentials,
        links = links,
        slots = slots,
        pending = pending,
        permissions = documents,
        documents = documents,
        cancelWork = { cancels += 1 },
        afterLinkRestored = {},
        conflicts = { dao ->
            ConflictResolver(
                state = dao,
                destinations = { DestinationOpen.PermissionLost },
                deviceId = { "telefono-de-prueba" },
                clock = fixture.clock,
                schemaVersion = AppDatabase.SCHEMA_VERSION,
                mutex = Mutex(),
            )
        },
        clock = fixture.clock,
        log = fixture.log,
        ioContext = Dispatchers.IO,
        memory = DestinationMemory({ directory }, Dispatchers.IO),
        afterChange = { changes += 1 },
        changeCheckpoint = { if (it == cutAfter) throw Cut() },
    )

    // Cada camino

    @Test
    fun anotherWebDavInheritsNothingAndTheOldCredentialIsGone() = runBlocking<Unit> {
        connectWebDav(A)
        fixture.addProduct("Producto inventado")
        val before = fixture.state()

        assertEquals(ChangeOutcome.CHANGED, service().changeToWebDav(credentialOf(B), linkOf(B)))

        val state = fixture.state()
        assertEquals("CONNECTED", state.linkState)
        assertEquals(B.id, state.destinationId)
        assertEquals(B.folder, state.destinationFolder)
        assertEquals("persona-b", state.destinationAccount)
        assertNoInheritance(state)
        assertEquals("Fuerza una subida", before.changeSeq + 1, state.changeSeq)
        assertTrue("Lo pendiente sigue sin copia", state.changeSeq > state.verifiedSeq)
        assertCredential(B)
        assertEquals(B.id, links.read()?.destinationId)
        assertForgotten()
        assertEquals(1, cancels)
        assertEquals(1, changes)
        val audit = fixture.ports.auditEvents.observeLatest(5).first()
        assertEquals(AuditEventType.DESTINATION_CHANGED.name, audit.single().eventType)
    }

    @Test
    fun aFailureOfTheOldDestinationIsNotInheritedButALocalOneIs() = runBlocking<Unit> {
        connectWebDav(A)
        fixture.sql("UPDATE backup_state SET failure_code = 'QUOTA_EXCEEDED'")
        service().changeToWebDav(credentialOf(B), linkOf(B))
        assertNull(fixture.state().failureCode)

        fixture.sql("UPDATE backup_state SET failure_code = 'LOCAL_INVARIANT'")
        service().changeToWebDav(credentialOf(A), linkOf(A))
        assertEquals("LOCAL_INVARIANT", fixture.state().failureCode)
    }

    @Test
    fun aWorkerWithTheOldCredentialNeverConfirmsOnTheNewDestination() = runBlocking<Unit> {
        connectWebDav(A)
        service().changeToWebDav(credentialOf(B), linkOf(B))
        val before = fixture.state()

        val rows =
            fixture.ports.backupState.confirm(
                generation = 9,
                deviceId = "telefono-de-prueba",
                fileRef = "copia-vieja",
                seq = before.changeSeq,
                now = 1,
                copiedAt = 1,
                failureCode = null,
                destinationId = A.id,
            )

        assertEquals(0, rows)
        assertEquals(before, fixture.state())
    }

    @Test
    fun fromTheSlotsTheirFilesAreReleasedAndTheirCopiesLeftAlone() = runBlocking<Unit> {
        connectSlots()

        assertEquals(ChangeOutcome.CHANGED, service().changeToWebDav(credentialOf(B), linkOf(B)))

        assertEquals(B.id, fixture.state().destinationId)
        assertEquals(SlotStateLoad.Missing, slots.load())
        assertEquals(BackupSlot.entries.map(::oldSlotUri).toSet(), documents.released.toSet())
        assertEquals("Ningún archivo de la nube se escribe", 0, documents.writes)
        assertCredential(B)
        assertEquals(B.id, links.read()?.destinationId)
    }

    @Test
    fun toTheSlotsTheCredentialIsDeletedAndTheSetupIsDone() = runBlocking<Unit> {
        connectWebDav(A)
        val state = newSlots()

        assertEquals(ChangeOutcome.CHANGED, service().changeToSlots(state))

        val row = fixture.state()
        assertEquals(DOCUMENT_SLOTS_DESTINATION_ID, row.destinationId)
        assertEquals(BackupDestinationKind.DOCUMENT_SLOTS.name, row.destinationKind)
        assertNull(row.destinationAccount)
        assertNull(row.destinationFolder)
        assertNoInheritance(row)
        assertEquals(state, (slots.load() as SlotStateLoad.Loaded).state)
        assertFalse(credentials.exists())
        assertFalse(credentials.nextExists())
        assertFalse("La preparación terminó", pending.exists())
        assertTrue(BackupSlot.entries.all { newSlotUri(it) in documents.held })
        assertEquals(DOCUMENT_SLOTS_DESTINATION_ID, links.read()?.destinationId)
        assertForgotten()
    }

    @Test
    fun aDisconnectedDestinationIsAlsoChangedWithItsCleanUp() = runBlocking<Unit> {
        connectWebDav(A)
        service().disconnect()

        assertEquals(ChangeOutcome.CHANGED, service().changeToWebDav(credentialOf(B), linkOf(B)))

        val state = fixture.state()
        assertEquals("CONNECTED", state.linkState)
        assertEquals(B.id, state.destinationId)
        assertNoInheritance(state)
    }

    @Test
    fun theSameDestinationOrNoneIsNotAChange() = runBlocking<Unit> {
        assertEquals(
            "Sin destino guardado es conectar",
            ChangeOutcome.NOT_A_CHANGE,
            service().changeToWebDav(credentialOf(B), linkOf(B)),
        )
        connectWebDav(A)
        val before = fixture.state()

        assertEquals(
            ChangeOutcome.NOT_A_CHANGE,
            service().changeToWebDav(credentialOf(A), linkOf(A)),
        )

        assertEquals(before, fixture.state())
        assertFalse(credentials.nextExists())
        assertEquals(0, changes)
    }

    // Al abrir

    @Test
    fun openingCompletesAMissingLinkFromTheBase() = runBlocking<Unit> {
        connectWebDav(A)
        links.delete()

        service().tidy()

        val link = links.read()
        assertEquals(A.id, link?.destinationId)
        assertEquals(A.folder, link?.folderUrl)
        assertEquals("persona-a", link?.account)
        assertCredential(A)
    }

    @Test
    fun openingDeletesANewCredentialThatTheBaseNeverConfirmed() = runBlocking<Unit> {
        connectWebDav(A)
        assertEquals(CredentialSave.Saved, credentials.saveNext(credentialOf(B)))

        service().tidy()

        assertFalse(credentials.nextExists())
        assertCredential(A)
    }

    @Test
    fun theWorkerFinishesARenameThatACutLeftBehind() = runBlocking<Unit> {
        connectWebDav(A)
        try {
            service(cutAfter = ChangeStep.STATE_CHANGED).changeToWebDav(credentialOf(B), linkOf(B))
        } catch (_: Cut) {
        }

        val opened = WebDavDestinationOpener(OkHttpClient(), credentials).open(B.id, B.folder)

        assertTrue(opened is DestinationOpen.Opened)
        assertCredential(B)
    }

    // Cortes

    @Test
    fun aCutAfterEachStepLeavesASingleDestination() = runBlocking<Unit> {
        for (path in Path.entries) {
            for (step in ChangeStep.entries) {
                val case = "$path, corte tras $step"
                reset()
                path.arrange(this@DestinationChangeTest)
                try {
                    path.change(this@DestinationChangeTest, service(cutAfter = step))
                } catch (_: Cut) {
                }
                service().tidy()
                assertSingle(path, committed = step != ChangeStep.NEXT_WRITTEN, case = case)
            }
        }
    }

    @Test
    fun twoCutsInARowStillLeaveASingleDestination() = runBlocking<Unit> {
        for (path in Path.entries) {
            for (first in ChangeStep.entries) {
                for (second in ChangeStep.entries) {
                    val case = "$path, cortes tras $first y $second"
                    reset()
                    path.arrange(this@DestinationChangeTest)
                    try {
                        path.change(this@DestinationChangeTest, service(cutAfter = first))
                    } catch (_: Cut) {
                    }
                    // El arranque también puede cortarse a mitad.
                    try {
                        service(cutAfter = second).tidy()
                    } catch (_: Cut) {
                    }
                    service().tidy()
                    assertSingle(path, committed = first != ChangeStep.NEXT_WRITTEN, case = case)
                }
            }
        }
    }

    /** Un solo destino, sin datos del otro: la base, la credencial, las ranuras y `link.json`. */
    private suspend fun assertSingle(path: Path, committed: Boolean, case: String) {
        val state = fixture.state()
        val expected = if (committed) path.target else path.source
        assertEquals(case, expected.id, state.destinationId)
        assertEquals(case, expected.id, links.read()?.destinationId)
        assertFalse(case, credentials.nextExists())
        when (expected.kind) {
            BackupDestinationKind.WEBDAV -> {
                assertCredential(expected, case)
                assertEquals(case, SlotStateLoad.Missing, slots.load())
            }

            BackupDestinationKind.DOCUMENT_SLOTS -> {
                assertFalse(case, credentials.exists())
                assertTrue(case, slots.load() is SlotStateLoad.Loaded)
            }
        }
        if (committed) {
            assertNoInheritance(state, case)
            assertForgotten(case)
        } else {
            // El destino anterior sigue con todo lo suyo.
            assertEquals(case, 7L, state.headGeneration)
        }
        assertEquals(case, 0, documents.writes)
    }

    private suspend fun assertNoInheritance(
        state: io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity,
        case: String = "",
    ) {
        assertEquals(case, 0L, state.headGeneration)
        assertNull(case, state.headDeviceId)
        assertNull(case, state.headFileRef)
        assertNull(case, state.shrinkAcceptedGeneration)
        assertNull(case, state.pendingGeneration)
        assertNull(case, state.pendingSha256)
        assertNull(case, state.lastFullVerifyAt)
        assertNull(case, state.lastDrillAt)
        assertNull(case, state.lastHumanCheckAt)
        assertNull(case, state.humanCheckGeneration)
        assertNull(case, state.conflictKind)
    }

    private fun assertForgotten(case: String = "") {
        DestinationMemory.FILES.forEach { assertFalse("$case: $it", File(directory, it).exists()) }
        assertTrue(case, staging().list().isNullOrEmpty())
    }

    private suspend fun assertCredential(target: Target, case: String = "") {
        val read = credentials.read(target.id)
        assertTrue("$case: $read", read is CredentialRead.Usable)
        assertEquals(case, target.url, (read as CredentialRead.Usable).credential.url)
    }

    // Escenarios

    private enum class Path(val source: Target, val target: Target) {
        WEBDAV_TO_WEBDAV(A, B) {
            override suspend fun arrange(test: DestinationChangeTest) = test.connectWebDav(A)

            override suspend fun change(
                test: DestinationChangeTest,
                service: DestinationLinkService,
            ) {
                service.changeToWebDav(test.credentialOf(B), test.linkOf(B))
            }
        },
        SLOTS_TO_WEBDAV(SLOTS, B) {
            override suspend fun arrange(test: DestinationChangeTest) = test.connectSlots()

            override suspend fun change(
                test: DestinationChangeTest,
                service: DestinationLinkService,
            ) {
                service.changeToWebDav(test.credentialOf(B), test.linkOf(B))
            }
        },
        WEBDAV_TO_SLOTS(A, SLOTS) {
            override suspend fun arrange(test: DestinationChangeTest) = test.connectWebDav(A)

            override suspend fun change(
                test: DestinationChangeTest,
                service: DestinationLinkService,
            ) {
                service.changeToSlots(test.newSlots())
            }
        },
        ;

        abstract suspend fun arrange(test: DestinationChangeTest)

        abstract suspend fun change(test: DestinationChangeTest, service: DestinationLinkService)
    }

    private class Target(
        val kind: BackupDestinationKind,
        val id: String,
        val url: String?,
        val folder: String,
    )

    private suspend fun reset() {
        // Una base y una carpeta nuevas, como otra instalación: cerrar borra las dos.
        fixture.close()
        fixture.reopen()
        clearKeys()
        documents.reset()
        cancels = 0
        changes = 0
    }

    /** El destino [target] conectado, con su cabeza, una subida a medias y lo recordado. */
    private suspend fun connectWebDav(target: Target) {
        fixture.connect(BackupDestinationKind.WEBDAV)
        fixture.sql(
            "UPDATE backup_state SET destination_id = '${target.id}', " +
                "destination_account = 'persona-a', destination_folder = '${target.folder}'",
        )
        rememberThings()
        assertEquals(CredentialSave.Saved, credentials.save(credentialOf(target)))
        links.write(linkOf(target).copy(account = "persona-a"))
    }

    private suspend fun connectSlots() {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        fixture.sql("UPDATE backup_state SET destination_id = '$DOCUMENT_SLOTS_DESTINATION_ID'")
        rememberThings()
        BackupSlot.entries.forEach { documents.add(oldSlotUri(it)) }
        slots.save(stateOf(::oldSlotUri))
        links.write(
            DestinationLink(
                BackupDestinationKind.DOCUMENT_SLOTS,
                null,
                DOCUMENT_SLOTS_DESTINATION_ID,
                null,
                1L,
            ),
        )
    }

    /** Lo que el destino actual deja en la base y en el teléfono, y que el nuevo no hereda. */
    private suspend fun rememberThings() {
        fixture.sql(
            "UPDATE backup_state SET head_generation = 7, head_device_id = 'otro-telefono', " +
                "head_file_ref = 'cabeza', shrink_accepted_generation = 5, " +
                "pending_generation = 8, pending_seq = 1, pending_sha256 = 'ab', " +
                "pending_size_bytes = 10, last_full_verify_at = 3, last_drill_at = 3, " +
                "last_drill_result = 'PASSED', last_human_check_at = 3, human_check_generation = 7",
        )
        directory.mkdirs()
        DestinationMemory.FILES.forEach { File(directory, it).writeText("recordado") }
        staging().mkdirs()
        File(staging(), "snapshot-g00000008.db.gz").writeText("copia a medio subir")
    }

    /** Tres archivos nuevos, ya preparados, para cambiar a las ranuras. */
    private suspend fun newSlots(): SlotState {
        BackupSlot.entries.forEach { documents.add(newSlotUri(it)) }
        pending.save(
            BackupSlot.entries.associateWith { PendingSlotFile(newSlotUri(it), "Drive", null) },
        )
        return stateOf(::newSlotUri)
    }

    private fun stateOf(uri: (BackupSlot) -> String) = SlotState(
        slots = BackupSlot.entries.associateWith { SlotEntry(uri(it), "Drive", null, null, null) },
        firstHandoffAt = null,
        writeMode = WriteMode.WT,
        sessionRequired = false,
    )

    private fun staging() = File(directory, BackupStaging.DIRECTORY_NAME)

    private fun credentialOf(target: Target) = StoredWebDavCredential(
        requireNotNull(target.url),
        if (target === A) "persona-a" else "persona-b",
        "contrasena-inventada",
        target.id,
    )

    private fun linkOf(target: Target) = DestinationLink(
        kind = BackupDestinationKind.WEBDAV,
        account = "persona-b",
        destinationId = target.id,
        folderUrl = target.folder,
        connectedAtMillis = 1L,
    )

    private fun oldSlotUri(slot: BackupSlot) = "$DRIVE_URI/viejo-${slot.name.lowercase()}"

    private fun newSlotUri(slot: BackupSlot) = "$DRIVE_URI/nuevo-${slot.name.lowercase()}"

    private class Cut : RuntimeException()

    /** Documentos inventados, con sus permisos, que nunca se escriben. */
    private class FakeDocuments :
        SlotFileDocuments,
        DocumentAccess {
        private val documents = HashSet<String>()
        val held = LinkedHashSet<String>()
        val released = ArrayList<String>()
        var writes = 0

        fun add(uri: String) {
            documents += uri
            held += uri
        }

        fun reset() {
            documents.clear()
            held.clear()
            released.clear()
            writes = 0
        }

        override fun takePermission(uri: String): Boolean = held.add(uri)

        override fun releasePermission(uri: String) {
            held -= uri
            released += uri
        }

        override fun inspect(uri: String): PickedDocument? = null

        override suspend fun query(uri: String): DocumentResult<DocumentInfo> =
            if (uri in documents) {
                DocumentResult.Ok(DocumentInfo(0L, null))
            } else {
                DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
            }

        override suspend fun isPersisted(uri: String): Boolean = uri in held

        override suspend fun openForWrite(
            uri: String,
            mode: WriteMode,
        ): DocumentResult<WritableDocument> {
            writes++
            return DocumentResult.Error(DocumentError.REJECTED)
        }

        override suspend fun openForRead(uri: String): DocumentResult<InputStream> =
            DocumentResult.Error(DocumentError.REJECTED)
    }

    private companion object {
        const val DRIVE_URI = "content://com.google.android.apps.docs.storage/document"
        val A =
            Target(
                BackupDestinationKind.WEBDAV,
                TEST_DESTINATION_ID,
                "https://dav-a.example.com/",
                "https://dav-a.example.com/app/",
            )
        val B =
            Target(
                BackupDestinationKind.WEBDAV,
                "fedcba9876543210fedcba9876543210",
                "https://dav-b.example.com/",
                "https://dav-b.example.com/app/",
            )
        val SLOTS =
            Target(
                BackupDestinationKind.DOCUMENT_SLOTS,
                DOCUMENT_SLOTS_DESTINATION_ID,
                null,
                "",
            )
    }
}
