package io.github.yesidrobayo.shopledger.data.destination.slots

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.backup.BackupFixture
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileRejection
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupConfirm
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La preparación de las ranuras sobre la base real y archivos reales en el dispositivo, con
 * documentos inventados: qué archivo sirve, qué se conserva a mitad, cómo se reconecta y qué deja un
 * corte tras cada paso de la confirmación. Ninguna prueba toca una nube.
 */
@RunWith(AndroidJUnit4::class)
class SlotFilesSetupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "slot-files-setup-test")
    private val directory get() = fixture.noBackup
    private val slots get() = FileSlotStateStore(directory, Dispatchers.IO)
    private val pending get() = SlotPendingStore(directory, Dispatchers.IO)
    private val links get() = DestinationLinkStore({ directory }, Dispatchers.IO)
    private val documents = FakeDocuments()
    private val requested = ArrayList<Boolean>()

    @Before
    fun open() = runBlocking { fixture.open() }

    @After
    fun close() = fixture.close()

    private fun setup(
        cutAfter: SlotConfirmStep? = null,
        providers: List<SlotProvider> = PROVIDERS,
    ) = SlotFilesSetup(
        open = { DataAccess.Ready(fixture.ports) },
        mutex = Mutex(),
        slots = slots,
        pending = pending,
        picked = documents,
        documents = documents,
        links = links,
        afterConfirm = { requested += it },
        clock = fixture.clock,
        ioContext = Dispatchers.IO,
        providers = providers,
        checkpoint = { if (it == cutAfter) throw Cut() },
    )

    // Preparar de cero

    @Test
    fun threeEmptyFilesConnectTheSlotsAndForceAnUpload() = runBlocking<Unit> {
        fixture.sql("UPDATE backup_state SET last_human_check_at = 77, human_check_generation = 4")
        val before = fixture.state()
        val setup = setup()

        createThree(setup)
        assertEquals(SlotSetupConfirm.Done(hasData = false), setup.confirm())

        val state = fixture.state()
        assertEquals("CONNECTED", state.linkState)
        assertEquals(BackupDestinationKind.DOCUMENT_SLOTS.name, state.destinationKind)
        assertEquals(DOCUMENT_SLOTS_DESTINATION_ID, state.destinationId)
        assertNull(state.destinationAccount)
        assertNull(state.destinationFolder)
        assertNull("Una revisión anterior no vale para estos archivos", state.lastHumanCheckAt)
        assertNull(state.humanCheckGeneration)
        assertTrue("Preparar fuerza una subida", state.changeSeq > before.changeSeq)
        val saved = loaded()
        BackupSlot.entries.forEach {
            assertEquals(uriOf(it), saved[it].uri)
            assertEquals("Drive", saved[it].providerLabel)
            assertNull(saved[it].copy)
            assertNull(saved[it].writing)
        }
        assertNull(saved.firstHandoffAt)
        assertEquals(WriteMode.WT, saved.writeMode)
        assertFalse(saved.sessionRequired)
        assertEquals(DOCUMENT_SLOTS_DESTINATION_ID, links.read()?.destinationId)
        assertFalse("El avance se borra al confirmar", pending.exists())
        assertEquals(listOf(false), requested)
        assertTrue(BackupSlot.entries.all { uriOf(it) in documents.held })
    }

    @Test
    fun withDataTheFirstCopyIsRequested() = runBlocking<Unit> {
        fixture.addProduct("Producto inventado")
        val setup = setup()

        createThree(setup)

        assertEquals(SlotSetupConfirm.Done(hasData = true), setup.confirm())
        assertEquals(listOf(true), requested)
    }

    @Test
    fun theSlotsJsonIsWrittenDurablyWithoutATemporaryLeft() = runBlocking<Unit> {
        val setup = setup()
        createThree(setup)
        setup.confirm()

        assertTrue(File(directory, FileSlotStateStore.FILE_NAME).exists())
        assertFalse(File(directory, FileSlotStateStore.TEMPORARY_NAME).exists())
        assertFalse(File(directory, SlotPendingStore.FILE_NAME).exists())
        assertFalse(File(directory, SlotPendingStore.FILE_NAME + ".tmp").exists())
    }

    // Rechazos

    @Test
    fun aFileOutsideTheApprovedCloudsIsRejectedWithoutTakingItsPermission() = runBlocking<Unit> {
        val local = "content://com.android.externalstorage.documents/document/primary%3Aa.slcopy"
        documents.add(local)

        val outcome = setup().addFile(BackupSlot.A, local)

        assertEquals(SlotFileOutcome.Rejected(SlotFileRejection.OUTSIDE_PROVIDERS), outcome)
        assertTrue(documents.taken.isEmpty())
        assertFalse(pending.exists())
    }

    @Test
    fun eachUnusableFileIsRejectedAndItsPermissionReleased() = runBlocking<Unit> {
        val cases =
            listOf(
                SlotFileRejection.NOT_WRITABLE to { uri: String ->
                    documents.add(uri, writable = false)
                },
                SlotFileRejection.SIZE_UNKNOWN to { uri: String ->
                    documents.add(uri, size = null)
                },
                SlotFileRejection.NOT_EMPTY to { uri: String -> documents.add(uri, size = 1) },
                SlotFileRejection.NO_ACCESS to { uri: String ->
                    documents.add(uri)
                    documents.refused += uri
                },
                SlotFileRejection.NO_ACCESS to
                    { uri: String -> documents.add(uri, answers = false) },
            )
        cases.forEachIndexed { index, (reason, prepare) ->
            val uri = "$DRIVE_URI/rejected-$index"
            prepare(uri)

            val outcome = setup().addFile(BackupSlot.A, uri)

            assertEquals(SlotFileOutcome.Rejected(reason), outcome)
            assertFalse("El permiso no se queda: $reason", uri in documents.held)
            assertFalse(pending.exists())
            assertEquals("Nunca se escribe en el documento", 0, documents.writes)
        }
    }

    @Test
    fun aFileInAnotherApprovedCloudThanTheOtherSlotsIsRejected() = runBlocking<Unit> {
        val second = "content://$OTHER_CLOUD/document/b"
        val setup = setup(providers = PROVIDERS + SlotProvider(OTHER_CLOUD, "Otra"))
        documents.add(uriOf(BackupSlot.A))
        documents.add(second)
        setup.addFile(BackupSlot.A, uriOf(BackupSlot.A))

        val outcome = setup.addFile(BackupSlot.B, second)

        assertEquals(SlotFileOutcome.Rejected(SlotFileRejection.OUTSIDE_PROVIDERS), outcome)
        assertFalse(second in documents.taken)
    }

    @Test
    fun theFileOfAnotherSlotIsRejectedAndKeepsItsPermission() = runBlocking<Unit> {
        val setup = setup()
        documents.add(uriOf(BackupSlot.A))
        setup.addFile(BackupSlot.A, uriOf(BackupSlot.A))

        val outcome = setup.addFile(BackupSlot.B, uriOf(BackupSlot.A))

        assertEquals(
            SlotFileOutcome.Rejected(SlotFileRejection.SAME_FILE, sameAs = BackupSlot.A),
            outcome,
        )
        assertTrue(uriOf(BackupSlot.A) in documents.held)
    }

    // A mitad

    @Test
    fun cancellingHalfwayKeepsTheCreatedFilesForLater() = runBlocking<Unit> {
        documents.add(uriOf(BackupSlot.A), name = "invented-a.slcopy")
        val first = setup()
        val accepted = first.addFile(BackupSlot.A, uriOf(BackupSlot.A))
        assertTrue(accepted is SlotFileOutcome.Accepted)

        // Otra vez en la pantalla, como tras cerrar la app.
        val look = requireNotNull(setup().look())

        assertEquals(SlotFileStatus.CREATED, look.files[0].status)
        assertEquals("invented-a.slcopy", look.files[0].fileName)
        assertEquals(SlotFileStatus.MISSING, look.files[1].status)
        assertEquals(BackupSlot.B, look.next)
        assertEquals(SlotSetupConfirm.Incomplete, setup().confirm())
        assertEquals("NOT_CONNECTED", fixture.state().linkState)
        assertFalse(File(directory, FileSlotStateStore.FILE_NAME).exists())
    }

    @Test
    fun aKeptFileThatLostItsPermissionOrGotDataIsAskedForAgain() = runBlocking<Unit> {
        val setup = setup()
        documents.add(uriOf(BackupSlot.A))
        documents.add(uriOf(BackupSlot.B))
        setup.addFile(BackupSlot.A, uriOf(BackupSlot.A))
        setup.addFile(BackupSlot.B, uriOf(BackupSlot.B))

        documents.held -= uriOf(BackupSlot.A)
        documents.add(uriOf(BackupSlot.B), size = 10)

        val look = requireNotNull(setup.look())
        assertEquals(SlotFileStatus.MISSING, look.files[0].status)
        assertEquals(SlotFileStatus.MISSING, look.files[1].status)
        assertEquals(BackupSlot.A, look.next)
    }

    @Test
    fun choosingAnotherFileForTheSameSlotReleasesTheFirst() = runBlocking<Unit> {
        val setup = setup()
        documents.add(uriOf(BackupSlot.A))
        documents.add("$DRIVE_URI/second")
        setup.addFile(BackupSlot.A, uriOf(BackupSlot.A))

        setup.addFile(BackupSlot.A, "$DRIVE_URI/second")

        assertTrue(uriOf(BackupSlot.A) in documents.released)
        assertEquals("$DRIVE_URI/second", pending.load()[BackupSlot.A]?.uri)
    }

    // Otro destino

    @Test
    fun withAnotherDestinationTheFilesAreCreatedButNeverConfirmed() = runBlocking<Unit> {
        for (linkState in listOf("CONNECTED", "NOT_CONNECTED")) {
            fixture.sql(
                "UPDATE backup_state SET link_state = '$linkState', destination_kind = 'WEBDAV', " +
                    "destination_id = 'otro-destino'",
            )
            val before = fixture.state()
            val setup = setup()
            createThree(setup)

            assertTrue(requireNotNull(setup.look()).otherDestination)
            assertEquals(SlotSetupConfirm.OtherDestination, setup.confirm())

            assertEquals(before, fixture.state())
            assertFalse(File(directory, FileSlotStateStore.FILE_NAME).exists())
            assertTrue("Los archivos quedan creados", pending.exists())
            assertTrue(requested.isEmpty())
        }
    }

    // Reconectar

    @Test
    fun reconnectingKeepsTheSlotsThatOpenAndReplacesOnlyTheOneThatFailed() = runBlocking<Unit> {
        lostSlotsWithHeadIn(BackupSlot.A)
        documents.held -= OLD_A
        val before = fixture.state()
        val setup = setup()

        val look = requireNotNull(setup.look())
        assertEquals(
            listOf(SlotFileStatus.MISSING, SlotFileStatus.READY, SlotFileStatus.READY),
            look.files.map { it.status },
        )
        documents.add(uriOf(BackupSlot.A))
        setup.addFile(BackupSlot.A, uriOf(BackupSlot.A))
        assertEquals(SlotSetupConfirm.Done(hasData = false), setup.confirm())

        val state = fixture.state()
        assertEquals("CONNECTED", state.linkState)
        assertNull("La cabeza ya no apunta a un archivo vacío", state.headFileRef)
        assertEquals("La generación de la cabeza se conserva", 3L, state.headGeneration)
        assertTrue(state.changeSeq > before.changeSeq)
        val saved = loaded()
        assertEquals(uriOf(BackupSlot.A), saved[BackupSlot.A].uri)
        assertNull(saved[BackupSlot.A].copy)
        assertEquals(OLD_B, saved[BackupSlot.B].uri)
        assertEquals(2L, saved[BackupSlot.B].copy?.generation)
        assertEquals(OLD_C, saved[BackupSlot.C].uri)
        assertEquals(1L, saved[BackupSlot.C].copy?.generation)
        assertNull("La cuenta para revisar empieza de cero", saved.firstHandoffAt)
        assertEquals(WriteMode.RWT, saved.writeMode)
        assertTrue(OLD_A in documents.released)
        assertFalse(OLD_B in documents.released)
    }

    @Test
    fun aSlotWhoseCloudAsksToSignInIsKept() = runBlocking<Unit> {
        lostSlotsWithHeadIn(BackupSlot.A)
        documents.signInRequired += OLD_A

        val look = requireNotNull(setup().look())

        assertTrue(look.files.all { it.status == SlotFileStatus.READY })
        assertFalse(look.done)
    }

    @Test
    fun anUnreadableSlotsJsonAsksForThreeNewFilesAndForgetsTheHead() = runBlocking<Unit> {
        lostSlotsWithHeadIn(BackupSlot.B)
        File(directory, FileSlotStateStore.FILE_NAME).writeText("{")
        val setup = setup()

        assertTrue(requireNotNull(setup.look()).files.all { it.status == SlotFileStatus.MISSING })
        createThree(setup)
        assertEquals(SlotSetupConfirm.Done(hasData = false), setup.confirm())

        assertNull(fixture.state().headFileRef)
        assertTrue(BackupSlot.entries.all { loaded()[it].copy == null })
    }

    @Test
    fun withConnectedSlotsThatOpenThereIsNothingToDoAndTheCheckSurvives() = runBlocking<Unit> {
        lostSlotsWithHeadIn(BackupSlot.A)
        fixture.sql(
            "UPDATE backup_state SET link_state = 'CONNECTED', last_human_check_at = 77, " +
                "human_check_generation = 3",
        )
        val before = fixture.state()
        val setup = setup()

        assertTrue(requireNotNull(setup.look()).done)
        assertEquals(SlotSetupConfirm.Done(hasData = false), setup.confirm())

        assertEquals(before, fixture.state())
        assertTrue(requested.isEmpty())
    }

    // Cortes en la confirmación

    @Test
    fun aCutAfterEachStepLeavesNothingUnsafeAndTheNextTryFinishes() = runBlocking<Unit> {
        for (step in SlotConfirmStep.entries) {
            for (scenario in Scenario.entries) {
                restart()
                documents.reset()
                requested.clear()
                scenario.arrange(this@SlotFilesSetupTest)

                try {
                    setup(cutAfter = step).confirm()
                    fail("La prueba debía cortar tras $step")
                } catch (_: Cut) {
                }
                assertSafe("$scenario, corte tras $step")

                // Otra vez en la pantalla, como un proceso nuevo.
                val again = setup()
                assertTrue(
                    requireNotNull(again.look()).files.none {
                        it.status == SlotFileStatus.MISSING
                    },
                )
                assertTrue(again.confirm() is SlotSetupConfirm.Done)
                assertFinished(scenario, "$scenario, tras $step")
            }
        }
    }

    @Test
    fun twoCutsInARowStillFinish() = runBlocking<Unit> {
        val steps = SlotConfirmStep.entries
        for (first in steps) {
            for (second in steps) {
                restart()
                documents.reset()
                Scenario.RECONNECT.arrange(this@SlotFilesSetupTest)
                listOf(first, second).forEach { step ->
                    try {
                        setup(cutAfter = step).confirm()
                    } catch (_: Cut) {
                    }
                    assertSafe("cortes tras $first y $second")
                }
                assertTrue(setup().confirm() is SlotSetupConfirm.Done)
                assertFinished(Scenario.RECONNECT, "cortes tras $first y $second")
            }
        }
    }

    /** Lo que nunca puede pasar tras un corte. */
    private suspend fun assertSafe(case: String) {
        val state = fixture.state()
        val head = BackupSlot.fromFileRefOrNull(state.headFileRef)
        if (head != null) {
            val load = slots.load()
            assertTrue(case, load is SlotStateLoad.Loaded)
            assertNotNull(
                "$case: la cabeza apunta a un archivo vacío",
                (load as SlotStateLoad.Loaded).state[head].copy,
            )
        }
        if (state.linkState == "CONNECTED") {
            // Conectado sin `slots.json` sería un destino sin dueño.
            assertTrue(case, slots.load() is SlotStateLoad.Loaded)
        }
        assertTrue(case, documents.writes == 0)
    }

    private suspend fun assertFinished(scenario: Scenario, case: String) {
        val state = fixture.state()
        assertEquals(case, "CONNECTED", state.linkState)
        assertEquals(case, DOCUMENT_SLOTS_DESTINATION_ID, state.destinationId)
        assertNull(case, state.headFileRef)
        assertEquals(case, DOCUMENT_SLOTS_DESTINATION_ID, links.read()?.destinationId)
        assertFalse(case, pending.exists())
        val saved = loaded()
        assertEquals(case, uriOf(BackupSlot.A), saved[BackupSlot.A].uri)
        when (scenario) {
            Scenario.FRESH -> assertEquals(case, uriOf(BackupSlot.B), saved[BackupSlot.B].uri)

            Scenario.RECONNECT -> {
                assertEquals(case, OLD_B, saved[BackupSlot.B].uri)
                assertEquals(case, 2L, saved[BackupSlot.B].copy?.generation)
            }
        }
        // Confirmar de nuevo no cambia nada ni olvida una revisión.
        fixture.sql("UPDATE backup_state SET last_human_check_at = 99, human_check_generation = 1")
        val settled = fixture.state()
        assertTrue(case, setup().confirm() is SlotSetupConfirm.Done)
        assertEquals(case, settled, fixture.state())
    }

    private enum class Scenario {
        FRESH {
            override suspend fun arrange(test: SlotFilesSetupTest) {
                test.createThree(test.setup())
            }
        },
        RECONNECT {
            override suspend fun arrange(test: SlotFilesSetupTest) {
                test.lostSlotsWithHeadIn(BackupSlot.A)
                test.documents.held -= OLD_A
                test.documents.add(test.uriOf(BackupSlot.A))
                test.setup().addFile(BackupSlot.A, test.uriOf(BackupSlot.A))
            }
        },
        ;

        abstract suspend fun arrange(test: SlotFilesSetupTest)
    }

    // Ayudas

    /** Una base y una carpeta nuevas, como otra instalación: cerrar borra las dos. */
    private suspend fun restart() {
        fixture.close()
        assertTrue(fixture.reopen() is DataAccess.Ready)
    }

    private suspend fun createThree(setup: SlotFilesSetup) {
        for (slot in BackupSlot.entries) {
            documents.add(uriOf(slot))
            val outcome = setup.addFile(slot, uriOf(slot))
            assertTrue("$slot: $outcome", outcome is SlotFileOutcome.Accepted)
        }
    }

    /**
     * Ranuras ya conectadas que perdieron el acceso, con copias en las tres y la cabeza en
     * [head]: las generaciones son 3 en A, 2 en B y 1 en C.
     */
    private suspend fun lostSlotsWithHeadIn(head: BackupSlot) {
        val uris = mapOf(BackupSlot.A to OLD_A, BackupSlot.B to OLD_B, BackupSlot.C to OLD_C)
        val generations = mapOf(BackupSlot.A to 3L, BackupSlot.B to 2L, BackupSlot.C to 1L)
        uris.values.forEach {
            documents.add(it, size = 40)
            documents.held += it
        }
        slots.save(
            SlotState(
                slots = BackupSlot.entries.associateWith {
                    SlotEntry(
                        uri = uris.getValue(it),
                        providerLabel = "Drive",
                        displayName = null,
                        copy = SlotRecord(
                            generations.getValue(it),
                            1_793_876_400_000,
                            40,
                            "ab".repeat(32),
                            SlotCopyFacts(true, 5, 1),
                            byteArrayOf(1),
                        ),
                        writing = null,
                    )
                },
                firstHandoffAt = 1_793_880_000_000,
                writeMode = WriteMode.RWT,
                sessionRequired = false,
            ),
        )
        fixture.sql(
            "UPDATE backup_state SET link_state = 'PERMISSION_LOST', " +
                "destination_kind = 'DOCUMENT_SLOTS', destination_id = 'document-slots', " +
                "head_generation = 3, head_file_ref = '${head.name}'",
        )
    }

    private suspend fun loaded(): SlotState = (slots.load() as SlotStateLoad.Loaded).state

    private fun uriOf(slot: BackupSlot) = "$DRIVE_URI/new-${slot.name.lowercase()}"

    private class Cut : RuntimeException()

    /**
     * Documentos inventados: lo que dice cada uno, qué permisos conserva la app y si alguien
     * escribió. Sirve a la vez de selector y de acceso del respaldo.
     */
    private class FakeDocuments :
        SlotFileDocuments,
        DocumentAccess {
        private val documents = HashMap<String, Pair<PickedDocument, Boolean>>()
        val held = LinkedHashSet<String>()
        val taken = ArrayList<String>()
        val released = ArrayList<String>()
        val refused = HashSet<String>()
        val signInRequired = HashSet<String>()
        var writes = 0

        fun add(
            uri: String,
            writable: Boolean = true,
            size: Long? = 0,
            name: String? = null,
            answers: Boolean = true,
        ) {
            val authority = uri.removePrefix("content://").substringBefore('/')
            documents[uri] = PickedDocument(authority, writable, size, name) to answers
        }

        fun reset() {
            documents.clear()
            held.clear()
            taken.clear()
            released.clear()
            refused.clear()
            signInRequired.clear()
            writes = 0
        }

        override fun takePermission(uri: String): Boolean {
            taken += uri
            if (uri in refused) return false
            held += uri
            return true
        }

        override fun releasePermission(uri: String) {
            held -= uri
            released += uri
        }

        override fun inspect(uri: String): PickedDocument? =
            documents[uri]?.takeIf { it.second }?.first

        override suspend fun query(uri: String): DocumentResult<DocumentInfo> {
            if (uri in signInRequired) {
                return DocumentResult.Error(DocumentError.AUTHENTICATION_REQUIRED)
            }
            val document = documents[uri]?.first
                ?: return DocumentResult.Error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE)
            return DocumentResult.Ok(DocumentInfo(document.sizeBytes, document.displayName))
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
        const val DRIVE = "com.google.android.apps.docs.storage"
        const val DRIVE_URI = "content://$DRIVE/document"
        const val OTHER_CLOUD = "com.example.othercloud.documents"
        const val OLD_A = "$DRIVE_URI/old-a"
        const val OLD_B = "$DRIVE_URI/old-b"
        const val OLD_C = "$DRIVE_URI/old-c"
        val PROVIDERS = listOf(SlotProvider(DRIVE, "Drive"))
    }
}
