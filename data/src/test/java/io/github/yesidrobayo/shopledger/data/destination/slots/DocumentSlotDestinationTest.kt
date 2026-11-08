package io.github.yesidrobayo.shopledger.data.destination.slots

import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.OutgoingCopy
import io.github.yesidrobayo.shopledger.data.destination.PendingCopy
import io.github.yesidrobayo.shopledger.data.destination.PendingLocation
import io.github.yesidrobayo.shopledger.data.destination.StoredCopy
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.destination.UnsupportedReason
import io.github.yesidrobayo.shopledger.data.destination.UploadPlan
import io.github.yesidrobayo.shopledger.data.destination.VerifiedCopy
import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El destino de ranuras con documentos en memoria: la escritura en orden, cada fallo del proveedor
 * con su resultado, y una muerte después de cada paso.
 */
class DocumentSlotDestinationTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private val clock = Clock { now }
    private val documents = FakeDocuments()
    private val files = mutableListOf<File>()
    private val control = TransferControl.Unbounded

    /** Las ranuras de partida: A con la copia 2, B con la 3 (la cabeza) y C vacía. */
    private val oldA = bytes(2, 300)
    private val oldB = bytes(3, 310)
    private val docA = documents.add(URI_A, oldA)
    private val docB = documents.add(URI_B, oldB)
    private val docC = documents.add(URI_C)
    private val store = MemorySlotStore(
        SlotState(
            slots = mapOf(
                BackupSlot.A to entry(URI_A, record(2, oldA)),
                BackupSlot.B to entry(URI_B, record(3, oldB)),
                BackupSlot.C to entry(URI_C, null),
            ),
            firstHandoffAt = null,
            writeMode = WriteMode.WT,
            sessionRequired = false,
        ),
    )
    private val destination =
        DocumentSlotDestination(store, documents, clock, Dispatchers.Unconfined)

    @After
    fun deleteFiles() {
        files.forEach(File::delete)
    }

    @Test
    fun aValidCopyGoesToTheEmptyAnchorAndIsConfirmedAfterRereading() = runBlocking<Unit> {
        val content = bytes(4, 500)
        val copy = outgoing(4, content)

        val plan = destination.planUpload(copy, CopyRef("B")).value()
        assertEquals(CopyRef("C"), plan.ref)
        val stored = destination.upload(plan, control).value()
        assertArrayEquals(content, docC.bytes)
        assertEquals(
            VerifiedCopy(fullyRead = true),
            destination.verify(stored, VerifyMode.SAMPLE, null, control).value(),
        )
        destination.confirm(stored).value()

        val state = requireNotNull(store.state)
        assertEquals(record(4, content, meta = copy.metaBytes), state[BackupSlot.C].copy)
        assertNull(state[BackupSlot.C].writing)
        assertEquals(now.toEpochMilliseconds(), state.firstHandoffAt)
        assertArrayEquals(oldA, docA.bytes)
        assertArrayEquals(oldB, docB.bytes)
        val listed = destination.listCopies(control).value().copies.map { it.ref.value }
        assertEquals(listOf("A", "B", "C"), listed)
    }

    @Test
    fun anUnreadableCopyStaysButNoLongerCountsAsValid() = runBlocking<Unit> {
        destination.markUnreadable(CopyRef("B")).value()

        val state = requireNotNull(store.state)
        assertFalse(requireNotNull(state[BackupSlot.B].copy).facts.valid)
        assertEquals(3L, state[BackupSlot.B].copy?.generation)
        assertArrayEquals(oldB, docB.bytes)
        // Las demás no cambian, y una ranura vacía no tiene qué marcar.
        assertTrue(requireNotNull(state[BackupSlot.A].copy).facts.valid)
        destination.markUnreadable(CopyRef("C")).value()
        assertNull(store.state?.get(BackupSlot.C)?.copy)
    }

    @Test
    fun theFirstHandoffIsKeptWhileTheSlotsRotate() = runBlocking<Unit> {
        // Cada día una copia nueva va a otra ranura; los días para revisar la nube siguen
        // contando desde la primera entrega.
        for (day in 0..3) {
            val later =
                DocumentSlotDestination(
                    store,
                    documents,
                    Clock { now + day.days },
                    Dispatchers.Unconfined,
                )
            val generation = 4L + day
            val head = requireNotNull(store.state).slots.entries
                .filter { it.value.copy != null }
                .maxBy { it.value.copy?.generation ?: 0 }
                .key
            val plan = later.planUpload(
                outgoing(generation, bytes(generation.toInt(), 500 + day)),
                CopyRef(head.name),
            ).value()
            val stored = later.upload(plan, control).value()
            later.verify(stored, VerifyMode.SAMPLE, null, control).value()
            later.confirm(stored).value()
        }

        assertEquals(now.toEpochMilliseconds(), store.state?.firstHandoffAt)
    }

    @Test
    fun theSlotIsMarkedDurablyBeforeTheDocumentIsOpened() = runBlocking<Unit> {
        // Una copia válida y reciente va a la ranura más antigua de A y B, que es A.
        store.save(
            requireNotNull(store.state).with(BackupSlot.C, entry(URI_C, record(1, bytes(1, 10)))),
        )
        documents.docs[URI_C]?.bytes = bytes(1, 10)
        var atOpen: SlotEntry? = null
        documents.onOpenForWrite = { atOpen = store.state?.get(BackupSlot.A) }

        val plan = destination.planUpload(outgoing(4, bytes(4, 50)), CopyRef("B")).value()
        assertEquals(CopyRef("A"), plan.ref)
        destination.upload(plan, control).value()

        val marked = requireNotNull(atOpen)
        assertNull("la ranura ya no afirma la copia vieja", marked.copy)
        assertEquals(4L, marked.writing?.generation)
        assertEquals(sha(bytes(4, 50)), marked.writing?.meta?.sha256)
    }

    @Test
    fun aRejectedWtIsRetriedOnceWithRwtAndTheModeIsKept() = runBlocking<Unit> {
        docC.openErrors[WriteMode.WT] = DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE

        val stored = uploadToC(outgoing(4, bytes(4, 40)))

        assertEquals(listOf(URI_C to WriteMode.WT, URI_C to WriteMode.RWT), documents.opened)
        assertEquals(WriteMode.RWT, store.state?.writeMode)
        destination.verify(stored, VerifyMode.FULL, null, control).value()
        destination.confirm(stored).value()
        // La siguiente escritura abre directo con el modo guardado.
        documents.opened.clear()
        val next = destination.planUpload(outgoing(5, bytes(5, 40)), CopyRef("C")).value()
        destination.upload(next, control).value()
        assertEquals(listOf(next.ref.slotUri() to WriteMode.RWT), documents.opened)
    }

    @Test
    fun whenRwtIsRejectedTooTheProviderIsUnsupportedAndTheSlotStaysHalfWritten() =
        runBlocking<Unit> {
            docC.openErrors[WriteMode.WT] = DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE
            docC.openErrors[WriteMode.RWT] = DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE

            assertEquals(
                unsupported(UnsupportedReason.PROVIDER_REJECTED),
                uploadFailure(outgoing(4, bytes(4, 40))),
            )
            assertEquals(4L, store.state?.get(BackupSlot.C)?.writing?.generation)
            assertEquals(WriteMode.WT, store.state?.writeMode)
        }

    @Test
    fun aRejectedWtWithoutThePersistedGrantOrWithoutAnswerIsLostPermission() = runBlocking<Unit> {
        docC.openErrors[WriteMode.WT] = DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE
        documents.persisted -= URI_C
        assertEquals(DestinationFailure.PermissionLost(), uploadFailure(outgoing(4, bytes(4, 40))))

        documents.persisted += URI_C
        docC.present = false
        val plan = UploadPlan(outgoing(5, bytes(5, 40)), CopyRef("C"))
        // Con la ranura a medias no hay tamaño que contrastar, pero el documento debe responder
        // antes de marcar nada.
        assertEquals(
            DestinationFailure.PermissionLost(),
            destination.upload(plan, control).failure(),
        )
        assertEquals(listOf(URI_C to WriteMode.WT), documents.opened)
    }

    @Test
    fun securityAndSessionErrorsAreLostPermissionAndOnlyTheSessionOneAsksToSignIn() =
        runBlocking<Unit> {
            docC.openErrors[WriteMode.WT] = DocumentError.SECURITY
            assertEquals(
                DestinationFailure.PermissionLost(sessionRequired = false),
                uploadFailure(outgoing(4, bytes(4, 40))),
            )
            assertFalse(requireNotNull(store.state).sessionRequired)

            docC.openErrors[WriteMode.WT] = DocumentError.AUTHENTICATION_REQUIRED
            val plan = UploadPlan(outgoing(5, bytes(5, 40)), CopyRef("C"))
            assertEquals(
                DestinationFailure.PermissionLost(sessionRequired = true),
                destination.upload(plan, control).failure(),
            )
            assertTrue(requireNotNull(store.state).sessionRequired)
        }

    @Test
    fun anIoErrorWhileWritingIsRetriedLaterAndKeepsTheSlotHalfWritten() = runBlocking<Unit> {
        docC.failWriteAfter = 10
        val copy = outgoing(4, bytes(4, 40))

        assertEquals(DestinationFailure.Transient(TransientKind.NETWORK), uploadFailure(copy))
        assertEquals(4L, store.state?.get(BackupSlot.C)?.writing?.generation)
        assertTrue("el archivo local sigue para el siguiente intento", copy.file.isFile)
    }

    @Test
    fun anErrorOnCloseIsAWriteError() = runBlocking<Unit> {
        docC.failClose = true

        assertEquals(
            DestinationFailure.Transient(TransientKind.NETWORK),
            uploadFailure(outgoing(4, bytes(4, 40))),
        )
    }

    @Test
    fun aProviderThatCannotSyncIsNotAFailureAndTheRereadDecides() = runBlocking<Unit> {
        docC.syncUnsupported = true

        val stored = uploadToC(outgoing(4, bytes(4, 40)))

        assertEquals(1, documents.syncs)
        assertEquals(
            VerifiedCopy(true),
            destination.verify(stored, VerifyMode.FULL, null, control).value(),
        )
    }

    @Test
    fun aDifferentRereadIsAMismatchAndTheSlotIsReusedFirst() = runBlocking<Unit> {
        val stored = uploadToC(outgoing(4, bytes(4, 40)))
        docC.bytes = bytes(9, 40)

        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.verify(stored, VerifyMode.FULL, null, control).failure(),
        )
        assertEquals(4L, store.state?.get(BackupSlot.C)?.writing?.generation)
        // La siguiente copia, aunque sea inválida, vuelve a la misma ranura a medias.
        val next = destination.planUpload(
            outgoing(5, bytes(5, 40), valid = false),
            CopyRef("B"),
        ).value()
        assertEquals(CopyRef("C"), next.ref)
    }

    @Test
    fun extraBytesFromAProviderThatDoesNotTruncateAreAMismatch() = runBlocking<Unit> {
        docC.bytes = bytes(8, 90)
        docC.noTruncate = true
        // La ranura está vacía según el estado, pero el documento ya tiene bytes: no es lo
        // esperado.
        assertEquals(changedOutside("C"), uploadFailure(outgoing(4, bytes(4, 40))))

        // Con la ranura a medias, el documento puede tener cualquier cosa, y se escribe encima.
        val stored = destination.upload(
            UploadPlan(outgoing(4, bytes(4, 40)), CopyRef("C")),
            control,
        )
        assertEquals(DestinationResult.Failure(changedOutside("C")), stored)
        store.save(
            requireNotNull(
                store.state,
            ).with(BackupSlot.C, entry(URI_C, null).copy(writing = WritingMark(7, null))),
        )
        val written = destination.upload(
            UploadPlan(outgoing(4, bytes(4, 40)), CopyRef("C")),
            control,
        ).value()

        assertEquals(90, docC.bytes.size)
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.verify(written, VerifyMode.FULL, null, control).failure(),
        )
    }

    @Test
    fun listingContrastsTheSizeOfEverySlotThatIsNotHalfWritten() = runBlocking<Unit> {
        docB.reportedSize = oldB.size + 1L
        assertEquals(changedOutside("B"), destination.listCopies(control).failure())

        docB.reportedSize = null
        docB.sizeUnknown = true
        assertEquals(
            unsupported(UnsupportedReason.PROVIDER_REJECTED),
            destination.listCopies(control).failure(),
        )

        docB.sizeUnknown = false
        docB.present = false
        assertEquals(DestinationFailure.PermissionLost(), destination.listCopies(control).failure())

        docB.present = true
        docB.queryError = DocumentError.SECURITY
        assertEquals(DestinationFailure.PermissionLost(), destination.listCopies(control).failure())

        docB.queryError = DocumentError.AUTHENTICATION_REQUIRED
        assertEquals(
            DestinationFailure.PermissionLost(sessionRequired = true),
            destination.listCopies(control).failure(),
        )

        docB.queryError = DocumentError.IO
        assertEquals(
            DestinationFailure.Transient(TransientKind.NETWORK),
            destination.listCopies(control).failure(),
        )

        docB.queryError = DocumentError.REJECTED
        assertEquals(
            unsupported(UnsupportedReason.PROVIDER_REJECTED),
            destination.listCopies(control).failure(),
        )
        assertTrue("nada se escribió", documents.opened.isEmpty())
    }

    @Test
    fun listingSkipsAHalfWrittenSlotWritesNothingAndTheUploadRefreshesTheName() =
        runBlocking<Unit> {
            store.save(
                requireNotNull(
                    store.state,
                ).with(BackupSlot.A, entry(URI_A, null).copy(writing = WritingMark(5, null))),
            )
            docA.bytes = bytes(1, 3)
            docB.displayName = "otro nombre"
            val saves = store.saves

            val listing = destination.listCopies(control).value()

            assertEquals(listOf(CopyRef("B")), listing.copies.map { it.ref })
            assertEquals(oldB.size.toLong(), listing.copies.single().sizeBytes)
            assertTrue(listing.copies.single().meta is ListedMeta.Present)
            assertEquals("la lista solo lee", saves, store.saves)
            docC.displayName = "renombrado"
            uploadToC(outgoing(4, bytes(4, 40)))
            assertEquals("renombrado", store.state?.get(BackupSlot.C)?.displayName)
        }

    @Test
    fun aSlotChangedOutsideIsNeverWrittenOver() = runBlocking<Unit> {
        docA.reportedSize = 1
        val plan = UploadPlan(outgoing(4, bytes(4, 40)), CopyRef("A"))

        assertEquals(changedOutside("A"), destination.upload(plan, control).failure())
        assertTrue(documents.opened.isEmpty())
        assertEquals(record(2, oldA), store.state?.get(BackupSlot.A)?.copy)
    }

    @Test
    fun missingOrUnreadableStateNeverMeansEmptySlots() = runBlocking<Unit> {
        store.unreadable = true
        val copy = outgoing(4, bytes(4, 40))
        val lost = DestinationFailure.PermissionLost()

        assertEquals(lost, destination.listCopies(control).failure())
        assertEquals(lost, destination.planUpload(copy, null).failure())
        assertEquals(lost, destination.upload(UploadPlan(copy, CopyRef("C")), control).failure())
        assertEquals(
            lost,
            destination.confirm(
                StoredCopy(CopyRef("C"), 1, sha(bytes(1, 1)), ByteArray(0)),
            ).failure(),
        )
        assertEquals(lost, destination.readMeta(CopyRef("A"), control).failure())
        assertTrue(documents.opened.isEmpty())
        val empty = MemorySlotStore(null)
        assertEquals(
            lost,
            DocumentSlotDestination(
                empty,
                documents,
                clock,
                Dispatchers.Unconfined,
            ).listCopies(control).failure(),
        )
    }

    @Test
    fun theRememberedHeadIsProtectedAndAllLockedSlotsGiveNoSafeSlot() = runBlocking<Unit> {
        // A es la cabeza recordada, B la de mayor generación y C vacía: la copia válida va a C.
        assertEquals(
            CopyRef("C"),
            destination.planUpload(outgoing(4, bytes(4, 40)), CopyRef("A")).value().ref,
        )

        // B es la cabeza por generación, C la recordada y A la válida más reciente y la de más
        // registros: las tres protegidas, y no hay ranura segura.
        val state = requireNotNull(store.state)
        store.save(
            state
                .with(BackupSlot.A, entry(URI_A, record(2, oldA, valid = true, records = 900)))
                .with(BackupSlot.B, entry(URI_B, record(3, oldB, valid = false)))
                .with(BackupSlot.C, entry(URI_C, record(1, bytes(1, 5), valid = true))),
        )
        documents.docs[URI_C]?.bytes = bytes(1, 5)
        val poorer = outgoing(4, bytes(4, 40), valid = false, records = 10)
        assertEquals(
            DestinationFailure.NoSafeSlot(CopyRef("A")),
            destination.planUpload(poorer, CopyRef("C")).failure(),
        )
    }

    @Test
    fun aMarkThatDoesNotDescribeTheCopyIsNeverConfirmed() = runBlocking<Unit> {
        val stored = uploadToC(outgoing(4, bytes(4, 40)))
        val other = StoredCopy(stored.ref, stored.sizeBytes, sha(bytes(9, 40)), stored.metaBytes)

        assertEquals(DestinationFailure.VerifyMismatch, destination.confirm(other).failure())
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.confirm(StoredCopy(CopyRef("A"), 1, sha(oldA), ByteArray(0))).failure(),
        )
        assertNull(store.state?.get(BackupSlot.C)?.copy)
    }

    @Test
    fun aStopBeforeWritingMarksNothingAndRunningOutOfTimeStopsTheWrite() = runBlocking<Unit> {
        val plan = UploadPlan(outgoing(4, bytes(4, 40)), CopyRef("C"))
        val stopped = control(stopped = true)
        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.STOPPED),
            destination.upload(plan, stopped).failure(),
        )
        assertNull(store.state?.get(BackupSlot.C)?.writing)

        var checks = 0
        val running = object : TransferControl by control {
            override fun remainingMillis(): Long = if (checks++ < 2) 1_000 else 0
        }
        val big = UploadPlan(outgoing(4, ByteArray(300_000) { it.toByte() }), CopyRef("C"))
        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.OUT_OF_TIME),
            destination.upload(big, running).failure(),
        )
        assertEquals(4L, store.state?.get(BackupSlot.C)?.writing?.generation)
    }

    @Test
    fun aFailedSaveOfTheMarkOpensNothing() = runBlocking<Unit> {
        store.failSave = true

        // Un fallo del disco del teléfono nunca se cuenta como uno de red.
        assertEquals(DestinationFailure.LocalStorage, uploadFailure(outgoing(4, bytes(4, 40))))
        assertTrue(documents.opened.isEmpty())
    }

    @Test
    fun aDeathAfterEveryStepLeavesTheHeadSlotIntactAndTheStateTruthful() = runBlocking<Unit> {
        val steps = listOf<(FakeDocuments.Doc, MemorySlotStore) -> Unit>(
            { _, s -> s.crashBeforeSave = s.saves + 1 },
            { d, _ -> d.crashOnOpen = true },
            { d, _ -> d.crashAfterWriteBytes = 0 },
            { d, _ -> d.crashAfterWriteBytes = 20 },
            { d, _ -> d.crashOnClose = true },
            { d, _ -> d.crashOnRead = true },
            { _, s -> s.crashBeforeSave = s.saves + 2 },
        )
        for ((index, arm) in steps.withIndex()) {
            val docs = FakeDocuments()
            val headBytes = bytes(3, 310)
            val a = docs.add(URI_A, bytes(2, 300))
            val b = docs.add(URI_B, headBytes)
            val c = docs.add(URI_C)
            val memory = MemorySlotStore(requireNotNull(store.state))
            val target = DocumentSlotDestination(memory, docs, clock, Dispatchers.Unconfined)
            val copy = outgoing(4, bytes(4, 40))
            val plan = target.planUpload(copy, CopyRef("B")).value()
            assertEquals(CopyRef("C"), plan.ref)
            arm(c, memory)
            val died = try {
                val stored = target.upload(plan, control).value()
                target.verify(stored, VerifyMode.FULL, null, control).value()
                target.confirm(stored).value()
                false
            } catch (_: Crash) {
                true
            }

            assertTrue("el paso $index debía morir", died)
            assertArrayEquals("la cabeza, tras morir en el paso $index", headBytes, b.bytes)
            assertArrayEquals(bytes(2, 300), a.bytes)
            val after = requireNotNull(memory.state)
            assertEquals(record(3, headBytes), after[BackupSlot.B].copy)
            // El estado nunca afirma en C una copia que su documento no tenga.
            val claimed = after[BackupSlot.C].copy
            assertTrue("el paso $index", claimed == null || claimed.sha256 == sha(c.bytes))
        }
    }

    @Test
    fun aPowerCutAfterTheMarkNeverRevivesTheOldCopy() = runBlocking<Unit> {
        // Lo durable tras la marca es lo que una app nueva lee después del corte.
        docA.crashOnOpen = true
        store.save(
            requireNotNull(store.state).with(BackupSlot.C, entry(URI_C, record(1, bytes(1, 10)))),
        )
        docC.bytes = bytes(1, 10)
        val plan = destination.planUpload(outgoing(4, bytes(4, 40)), CopyRef("B")).value()
        assertEquals(CopyRef("A"), plan.ref)
        try {
            destination.upload(plan, control)
        } catch (_: Crash) {
            // El corte.
        }

        val reborn = DocumentSlotDestination(store, documents, clock, Dispatchers.Unconfined)
        assertNull(store.state?.get(BackupSlot.A)?.copy)
        assertEquals(
            listOf(CopyRef("B"), CopyRef("C")),
            reborn.listCopies(control).value().copies.map {
                it.ref
            },
        )
    }

    @Test
    fun aPendingUploadIsLocatedByTheStateOfTheSlots() = runBlocking<Unit> {
        val content = bytes(4, 40)
        val copy = outgoing(4, content)
        val pending = PendingCopy(copy.name, copy.sizeBytes, copy.sha256)
        assertEquals(PendingLocation.Absent, destination.locatePending(pending, control).value())

        val stored = uploadToC(copy)
        val uploaded = destination.locatePending(pending, control).value()
        assertTrue(uploaded is PendingLocation.Uploaded)
        assertEquals(stored.sha256, (uploaded as PendingLocation.Uploaded).copy.sha256)

        val otherFile = PendingCopy(copy.name, copy.sizeBytes, sha(bytes(9, 40)))
        assertEquals(PendingLocation.Stale, destination.locatePending(otherFile, control).value())

        destination.confirm(stored).value()
        val confirmed = destination.locatePending(pending, control).value()
        assertEquals(CopyRef("C"), (confirmed as PendingLocation.Confirmed).copy.ref)

        store.save(
            requireNotNull(
                store.state,
            ).with(BackupSlot.A, entry(URI_A, null).copy(writing = WritingMark(6, null))),
        )
        val noMeta = PendingCopy(name(6), 1, sha(bytes(6, 1)))
        assertEquals(PendingLocation.Stale, destination.locatePending(noMeta, control).value())
    }

    @Test
    fun downloadStopsAtTheLimitAndSlotsHaveNoSeparateMetaNorDeletion() = runBlocking<Unit> {
        val sink = ByteArrayOutputStream()
        val downloaded = destination.download(
            CopyRef("B"),
            sink,
            oldB.size.toLong(),
            control,
        ).value()
        assertEquals(sha(oldB), downloaded.sha256)
        assertArrayEquals(oldB, sink.toByteArray())
        assertEquals(
            unsupported(UnsupportedReason.TOO_LARGE),
            destination.download(
                CopyRef("B"),
                ByteArrayOutputStream(),
                oldB.size - 1L,
                control,
            ).failure(),
        )

        assertTrue(destination.readMeta(CopyRef("B"), control).value() is ListedMeta.Present)
        assertEquals(ListedMeta.Missing, destination.readMeta(CopyRef("C"), control).value())
        val notForSlots = unsupported(UnsupportedReason.METHOD_NOT_ALLOWED)
        assertEquals(notForSlots, destination.delete(CopyRef("A"), control).failure())
        val plan = UploadPlan(outgoing(4, bytes(4, 4)), CopyRef("C"))
        assertEquals(notForSlots, destination.uploadMeta(plan, control).failure())
        assertArrayEquals(oldA, docA.bytes)
    }

    @Test
    fun readErrorsAreClassifiedLikeOpenErrors() = runBlocking<Unit> {
        val stored = StoredCopy(CopyRef("B"), oldB.size.toLong(), sha(oldB), ByteArray(0))
        docB.failReadAfter = 5
        assertEquals(
            DestinationFailure.Transient(TransientKind.NETWORK),
            destination.verify(stored, VerifyMode.FULL, null, control).failure(),
        )
        docB.failReadAfter = null
        docB.readError = DocumentError.SECURITY
        assertEquals(
            DestinationFailure.PermissionLost(),
            destination.verify(stored, VerifyMode.FULL, null, control).failure(),
        )
        docB.readError = null
        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.STOPPED),
            destination.verify(stored, VerifyMode.FULL, null, control(stopped = true)).failure(),
        )
        assertEquals(
            VerifiedCopy(true),
            destination.verify(stored, VerifyMode.SIZE_AND_META, null, control).value(),
        )
    }

    private suspend fun uploadToC(copy: OutgoingCopy): StoredCopy {
        val plan = destination.planUpload(copy, CopyRef("B")).value()
        assertEquals(CopyRef("C"), plan.ref)
        return destination.upload(plan, control).value()
    }

    private suspend fun uploadFailure(copy: OutgoingCopy): DestinationFailure {
        val plan = destination.planUpload(copy, CopyRef("B")).value()
        assertEquals(CopyRef("C"), plan.ref)
        return destination.upload(plan, control).failure()
    }

    private fun CopyRef.slotUri(): String =
        requireNotNull(store.state)[BackupSlot.valueOf(value)].uri

    private fun outgoing(
        generation: Long,
        content: ByteArray,
        valid: Boolean = true,
        records: Long = 500,
    ): OutgoingCopy {
        val file = File.createTempFile("slot-copy", ".db.gz").apply { writeBytes(content) }
        files += file
        return OutgoingCopy(
            file = file,
            name = name(generation),
            sizeBytes = content.size.toLong(),
            sha256 = sha(content),
            metaBytes = "meta-$generation".toByteArray(),
            candidate = SlotCandidate(NewCopyMarks(valid = valid, fatal = false), records, 50),
        )
    }

    private fun record(
        generation: Long,
        content: ByteArray,
        valid: Boolean = true,
        records: Long = 500,
        meta: ByteArray = "meta-$generation".toByteArray(),
    ): SlotRecord = SlotRecord(
        generation = generation,
        writtenAt = requireNotNull(CopyInstant.parse(STAMP)).epochSeconds * 1_000,
        sizeBytes = content.size.toLong(),
        sha256 = sha(content),
        facts = SlotCopyFacts(valid, records, 50),
        metaBytes = meta,
    )

    private fun control(stopped: Boolean): TransferControl = object : TransferControl by control {
        override fun isStopped(): Boolean = stopped
    }

    private fun <T> DestinationResult<T>.value(): T = when (this) {
        is DestinationResult.Success -> value
        is DestinationResult.Failure -> throw AssertionError("Se esperaba éxito y llegó $failure")
    }

    private fun DestinationResult<*>.failure(): DestinationFailure = when (this) {
        is DestinationResult.Success -> throw AssertionError("Se esperaba un fallo y llegó $value")
        is DestinationResult.Failure -> failure
    }

    private companion object {
        const val URI_A = "content://example.documents/document/a"
        const val URI_B = "content://example.documents/document/b"
        const val URI_C = "content://example.documents/document/c"
        const val STAMP = "20261107T110000Z"

        fun entry(uri: String, record: SlotRecord?): SlotEntry =
            SlotEntry(uri, "Nube", "copia", record, null)

        fun name(generation: Long): BackupFileName = BackupFileName(
            generation,
            1,
            requireNotNull(CopyInstant.parse(STAMP)),
            "0a1b2c3d",
            BackupFileFormat.GZIP,
        )

        fun bytes(seed: Int, size: Int): ByteArray = ByteArray(size) { (seed * 31 + it).toByte() }

        fun sha(content: ByteArray): String = newSha256().digest(content).toLowerHex()

        fun unsupported(reason: UnsupportedReason): DestinationFailure =
            DestinationFailure.Unsupported(reason)
    }

    private fun changedOutside(slot: String) = DestinationFailure.ChangedOutside(CopyRef(slot))
}
