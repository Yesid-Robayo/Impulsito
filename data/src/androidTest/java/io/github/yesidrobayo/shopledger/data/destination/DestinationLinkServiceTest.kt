package io.github.yesidrobayo.shopledger.data.destination

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
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
import io.github.yesidrobayo.shopledger.domain.model.backup.DisconnectResult
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * Desconectar y volver tras iniciar sesión, sobre la base real, la credencial sellada con Keystore
 * y archivos reales en el dispositivo, con documentos inventados. Un corte tras cada paso de
 * desconectar, y dos seguidos, nunca deja la base conectada sin su acceso, y abrir de nuevo termina
 * lo que faltó. Ninguna prueba usa la red ni toca una nube.
 */
@RunWith(AndroidJUnit4::class)
class DestinationLinkServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "destination-link-test")
    private val directory get() = fixture.noBackup
    private val aliases = "test-link-credential-a" to "test-link-credential-b"
    private val credentials get() = WebDavCredentialStore({ directory }, Dispatchers.IO, aliases)
    private val links get() = DestinationLinkStore({ directory }, Dispatchers.IO)
    private val slots get() = FileSlotStateStore(directory, Dispatchers.IO)
    private val pending get() = SlotPendingStore(directory, Dispatchers.IO)
    private val documents = FakeDocuments()
    private var cancels = 0
    private var restored = 0

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

    private fun service(cutAfter: DisconnectStep? = null) = DestinationLinkService(
        open = { DataAccess.Ready(fixture.ports) },
        mutex = Mutex(),
        credentials = credentials,
        links = links,
        slots = slots,
        pending = pending,
        permissions = documents,
        documents = documents,
        cancelWork = { cancels += 1 },
        afterLinkRestored = { restored += 1 },
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
        checkpoint = { if (it == cutAfter) throw Cut() },
    )

    // WebDAV

    @Test
    fun disconnectingWebDavDeletesTheCredentialAndKeepsWhatRecognizesTheDestination() =
        runBlocking<Unit> {
            connectWebDav()
            fixture.addProduct("Producto inventado")
            val before = fixture.state()

            assertEquals(DisconnectResult.DISCONNECTED, service().disconnect())

            val state = fixture.state()
            assertEquals("NOT_CONNECTED", state.linkState)
            assertNull("Sin destino no hace falta el usuario", state.destinationAccount)
            assertEquals(BackupDestinationKind.WEBDAV.name, state.destinationKind)
            assertEquals(TEST_DESTINATION_ID, state.destinationId)
            assertEquals(FOLDER, state.destinationFolder)
            assertEquals(7L, state.headGeneration)
            assertEquals("otro-telefono", state.headDeviceId)
            assertEquals("cabeza.db.gz", state.headFileRef)
            assertEquals(5L, state.shrinkAcceptedGeneration)
            assertEquals("Los cambios no se dan por respaldados", before.changeSeq, state.changeSeq)
            assertEquals(before.verifiedSeq, state.verifiedSeq)
            assertFalse(credentials.exists())
            assertFalse(File(directory, WebDavCredentialStore.FILE_NAME).exists())
            assertNull(links.read())
            assertEquals(1, cancels)
            val audit = fixture.ports.auditEvents.observeLatest(5).first()
            assertEquals(AuditEventType.DESTINATION_DISCONNECTED.name, audit.single().eventType)
        }

    @Test
    fun changesKeepCountingAfterDisconnecting() = runBlocking<Unit> {
        connectWebDav()
        service().disconnect()
        val before = fixture.state().changeSeq

        fixture.addProduct("Otro producto inventado")

        assertEquals(before + 1, fixture.state().changeSeq)
    }

    @Test
    fun disconnectingClearsAConflictThatTheGuardSeesAgainOnReconnecting() = runBlocking<Unit> {
        connectWebDav()
        fixture.sql(
            "UPDATE backup_state SET link_state = 'CONFLICT', conflict_kind = 'OTHER_DEVICE', " +
                "conflict_generation = 9, conflict_device_id = 'otro', conflict_file_ref = 'x'",
        )

        service().disconnect()

        val state = fixture.state()
        assertEquals("NOT_CONNECTED", state.linkState)
        assertNull(state.conflictKind)
        assertNull(state.conflictGeneration)
        assertNull(state.conflictDeviceId)
        assertNull(state.conflictFileRef)
    }

    @Test
    fun reconnectingTheSameWebDavKeepsItsFolderAndHead() = runBlocking<Unit> {
        connectWebDav()
        service().disconnect()

        fixture.ports.backupState.connectDestination(
            kind = BackupDestinationKind.WEBDAV.name,
            account = "persona-de-prueba",
            destinationId = TEST_DESTINATION_ID,
            folder = FOLDER,
            now = fixture.clock.now().toEpochMilliseconds(),
        )

        val state = fixture.state()
        assertEquals("CONNECTED", state.linkState)
        assertEquals(7L, state.headGeneration)
        assertEquals("cabeza.db.gz", state.headFileRef)
        assertEquals(5L, state.shrinkAcceptedGeneration)
    }

    @Test
    fun withNothingConnectedDisconnectingChangesNothingButTidiesLeftovers() = runBlocking<Unit> {
        // Un corte al conectar dejó la credencial sellada sin destino en la base.
        saveCredential()
        links.write(link(BackupDestinationKind.WEBDAV))
        val before = fixture.state()

        assertEquals(DisconnectResult.NOT_CONNECTED, service().disconnect())

        assertEquals(before, fixture.state())
        assertFalse(credentials.exists())
        assertNull(links.read())
        assertTrue(fixture.ports.auditEvents.observeLatest(5).first().isEmpty())
    }

    // Ranuras

    @Test
    fun disconnectingTheSlotsReleasesTheirFilesAndKeepsAHalfDoneSetup() = runBlocking<Unit> {
        connectSlots()
        // Una preparación a medias usa otro archivo: su permiso se queda.
        pending.save(mapOf(BackupSlot.A to PendingSlotFile(SETUP_URI, "Drive", null)))
        documents.held += SETUP_URI

        assertEquals(DisconnectResult.DISCONNECTED, service().disconnect())

        val state = fixture.state()
        assertEquals("NOT_CONNECTED", state.linkState)
        assertEquals(DOCUMENT_SLOTS_DESTINATION_ID, state.destinationId)
        assertEquals(BackupSlot.entries.map(::slotUri).toSet(), documents.released.toSet())
        assertTrue(SETUP_URI in documents.held)
        assertEquals(SlotStateLoad.Missing, slots.load())
        assertNull(links.read())
        assertEquals("Nunca se escribe en un archivo de la nube", 0, documents.writes)
    }

    // Cortes

    @Test
    fun aCutAfterEachStepNeverLeavesTheBaseConnectedWithoutAccess() = runBlocking<Unit> {
        for (kind in BackupDestinationKind.entries) {
            for (step in DisconnectStep.entries) {
                val case = "$kind, corte tras $step"
                reset()
                connect(kind)
                try {
                    service(cutAfter = step).disconnect()
                } catch (_: Cut) {
                }
                // La base ya dice desconectada: nada sube, aunque falten archivos por borrar.
                assertEquals(case, "NOT_CONNECTED", fixture.state().linkState)

                // Al abrir otra vez, el arranque borra lo que quedó.
                service().tidy()
                assertTidied(case)
            }
        }
    }

    @Test
    fun twoCutsInARowStillFinish() = runBlocking<Unit> {
        for (kind in BackupDestinationKind.entries) {
            for (first in DisconnectStep.entries) {
                for (second in DisconnectStep.entries) {
                    val case = "$kind, cortes tras $first y $second"
                    reset()
                    connect(kind)
                    try {
                        service(cutAfter = first).disconnect()
                    } catch (_: Cut) {
                    }
                    // Repetir mientras la base ya dice desconectada termina lo que faltó.
                    try {
                        service(cutAfter = second).disconnect()
                    } catch (_: Cut) {
                    }
                    service().tidy()
                    assertTidied(case)
                }
            }
        }
    }

    @Test
    fun aCutBeforeTheBaseChangesLeavesEverythingConnected() = runBlocking<Unit> {
        connectWebDav()
        fixture.sql("UPDATE backup_state SET destination_id = NULL")

        // Sin destino guardado la base no se puede desconectar: nada cambia y se puede repetir.
        assertEquals(DisconnectResult.FAILED, service().disconnect())

        assertEquals("CONNECTED", fixture.state().linkState)
        assertTrue(credentials.exists())
        assertNotNull(links.read())
    }

    // Sesión cerrada

    @Test
    fun withTheSessionBackTheSlotsResumeWithoutNewFiles() = runBlocking<Unit> {
        connectSlots(sessionRequired = true)
        fixture.sql("UPDATE backup_state SET link_state = 'PERMISSION_LOST'")

        assertTrue(service().resumeAfterSignIn())

        assertEquals("CONNECTED", fixture.state().linkState)
        val saved = (slots.load() as SlotStateLoad.Loaded).state
        assertFalse(saved.sessionRequired)
        assertEquals(BackupSlot.entries.map(::slotUri), BackupSlot.entries.map { saved[it].uri })
        assertEquals(1, restored)
        assertTrue(documents.released.isEmpty())
    }

    @Test
    fun withoutTheSessionTheSlotsStayStoppedAndNothingChanges() = runBlocking<Unit> {
        connectSlots(sessionRequired = true)
        fixture.sql("UPDATE backup_state SET link_state = 'PERMISSION_LOST'")
        documents.signInRequired += slotUri(BackupSlot.C)

        assertFalse(service().resumeAfterSignIn())

        assertEquals("PERMISSION_LOST", fixture.state().linkState)
        assertTrue((slots.load() as SlotStateLoad.Loaded).state.sessionRequired)
        assertEquals(0, restored)
    }

    @Test
    fun anotherLossIsNotResumedBySigningIn() = runBlocking<Unit> {
        // Sin la marca de sesión, la salida es crear archivos nuevos, no iniciar sesión.
        connectSlots(sessionRequired = false)
        fixture.sql("UPDATE backup_state SET link_state = 'PERMISSION_LOST'")

        assertFalse(service().resumeAfterSignIn())

        assertEquals("PERMISSION_LOST", fixture.state().linkState)
    }

    @Test
    fun openingTheAppClearsASessionMarkLeftByACut() = runBlocking<Unit> {
        // Un corte entre la base y la marca deja las ranuras conectadas con la marca puesta.
        connectSlots(sessionRequired = true)

        service().tidy()

        assertFalse((slots.load() as SlotStateLoad.Loaded).state.sessionRequired)
        assertEquals("CONNECTED", fixture.state().linkState)
    }

    @Test
    fun openingTheAppLeavesAConnectedDestinationAlone() = runBlocking<Unit> {
        connectWebDav()

        service().tidy()

        assertTrue(credentials.exists())
        assertNotNull(links.read())
        assertEquals("CONNECTED", fixture.state().linkState)
    }

    // Apoyo

    private suspend fun assertTidied(case: String) {
        val state = fixture.state()
        assertEquals(case, "NOT_CONNECTED", state.linkState)
        assertNull(case, state.destinationAccount)
        assertNotNull(case, state.destinationId)
        assertFalse(case, credentials.exists())
        assertNull(case, links.read())
        assertEquals(case, SlotStateLoad.Missing, slots.load())
        assertTrue(case, documents.held.none { it.startsWith(DRIVE_URI) && it != SETUP_URI })
        assertEquals(case, 0, documents.writes)
    }

    private suspend fun reset() {
        // Una base y una carpeta nuevas, como otra instalación: cerrar borra las dos.
        fixture.close()
        fixture.reopen()
        clearKeys()
        documents.reset()
    }

    private suspend fun connect(kind: BackupDestinationKind) = when (kind) {
        BackupDestinationKind.WEBDAV -> connectWebDav()
        BackupDestinationKind.DOCUMENT_SLOTS -> connectSlots()
    }

    private suspend fun connectWebDav() {
        fixture.connect(BackupDestinationKind.WEBDAV)
        fixture.sql(
            "UPDATE backup_state SET destination_account = 'persona-de-prueba', " +
                "destination_folder = '$FOLDER', head_generation = 7, " +
                "head_device_id = 'otro-telefono', head_file_ref = 'cabeza.db.gz', " +
                "shrink_accepted_generation = 5",
        )
        saveCredential()
        links.write(link(BackupDestinationKind.WEBDAV))
    }

    private suspend fun connectSlots(sessionRequired: Boolean = false) {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        fixture.sql("UPDATE backup_state SET destination_id = '$DOCUMENT_SLOTS_DESTINATION_ID'")
        BackupSlot.entries.forEach { documents.add(slotUri(it)) }
        slots.save(
            SlotState(
                slots = BackupSlot.entries.associateWith {
                    SlotEntry(slotUri(it), "Drive", null, null, null)
                },
                firstHandoffAt = null,
                writeMode = WriteMode.WT,
                sessionRequired = sessionRequired,
            ),
        )
        links.write(link(BackupDestinationKind.DOCUMENT_SLOTS))
    }

    private suspend fun saveCredential() {
        val saved =
            credentials.save(
                StoredWebDavCredential(
                    "https://dav.example.com/",
                    "persona-de-prueba",
                    "contrasena-inventada",
                    TEST_DESTINATION_ID,
                ),
            )
        assertEquals(CredentialSave.Saved, saved)
    }

    private fun link(kind: BackupDestinationKind) = DestinationLink(
        kind = kind,
        account = null,
        destinationId =
            if (kind == BackupDestinationKind.WEBDAV) {
                TEST_DESTINATION_ID
            } else {
                DOCUMENT_SLOTS_DESTINATION_ID
            },
        folderUrl = null,
        connectedAtMillis = 1L,
    )

    private fun slotUri(slot: BackupSlot) = "$DRIVE_URI/${slot.name.lowercase()}"

    private class Cut : RuntimeException()

    /** Documentos inventados, con sus permisos, que nunca se escriben. */
    private class FakeDocuments :
        SlotFileDocuments,
        DocumentAccess {
        private val documents = HashSet<String>()
        val held = LinkedHashSet<String>()
        val released = ArrayList<String>()
        val signInRequired = HashSet<String>()
        var writes = 0

        fun add(uri: String) {
            documents += uri
            held += uri
        }

        fun reset() {
            documents.clear()
            held.clear()
            released.clear()
            signInRequired.clear()
            writes = 0
        }

        override fun takePermission(uri: String): Boolean = held.add(uri)

        override fun releasePermission(uri: String) {
            held -= uri
            released += uri
        }

        override fun inspect(uri: String): PickedDocument? = null

        override suspend fun query(uri: String): DocumentResult<DocumentInfo> = when {
            uri in signInRequired -> DocumentResult.Error(DocumentError.AUTHENTICATION_REQUIRED)
            uri in documents -> DocumentResult.Ok(DocumentInfo(0L, null))
            else -> DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
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
        const val FOLDER = "https://dav.example.com/app/"
        const val DRIVE_URI = "content://com.google.android.apps.docs.storage/document"
        const val SETUP_URI = "$DRIVE_URI/a-nuevo"
    }
}
