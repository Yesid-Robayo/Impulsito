package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.FakeSlot
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.OutgoingCopy
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.StoredCopy
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una escritura cortada en una ranura se retoma por el estado de las ranuras: se confirma sin
 * escribir si la ranura ya es ese archivo, se reescribe en la misma ranura si no, y nunca toca las
 * otras dos.
 */
@RunWith(AndroidJUnit4::class)
class SlotRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val control = TestControl()

    /** Dónde muere el proceso, y qué hace falta al volver. */
    private class Cut(
        val label: String,
        val writesAgain: Boolean,
        val apply: (FakeBackupDestination) -> Unit,
    )

    @Test
    fun aDeathAfterEachStepResumesInTheSameSlotWithoutTouchingTheOthers() = runBlocking {
        val stopped = DestinationFailure.Interrupted(InterruptReason.STOPPED)
        val cuts =
            listOf(
                Cut("antes de marcar", writesAgain = true) {
                    it.failNext(Operation.UPLOAD, stopped)
                },
                Cut("con la ranura a medias", writesAgain = true) {
                    it.crashUploadAfter = FakeBackupDestination.UploadStep.AFTER_FILE
                },
                Cut("antes de releer", writesAgain = false) {
                    it.failNext(Operation.VERIFY, stopped)
                },
                Cut("antes de anotar", writesAgain = false) {
                    it.failNext(Operation.CONFIRM, stopped)
                },
            )
        for ((index, cut) in cuts.withIndex()) {
            withSlots("cut-$index") { fixture ->
                val destination =
                    FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS, ::halfWrittenFirst)
                val first = firstCopy(fixture, destination)
                fixture.addProduct("Leche de prueba")
                fixture.advance(1.minutes)
                cut.apply(destination)

                assertTrue(cut.label, fixture.runner(destination).run(control) is BackupRun.Retry)
                assertEquals(cut.label, 2L, fixture.state().pendingGeneration)
                val before = destination.calls.size

                val retried = fixture.runner(destination).run(control)

                assertEquals(cut.label, BackupRun.Verified(2), retried)
                val calls = destination.calls.drop(before)
                assertEquals(cut.label, cut.writesAgain, Operation.UPLOAD in calls)
                // La copia anterior sigue igual, y la nueva quedó en la segunda ranura.
                assertArrayEquals(cut.label, first, destination.slot(CopyRef("A"))?.bytes)
                assertTrue(cut.label, requireNotNull(destination.slot(CopyRef("A"))).confirmed)
                assertTrue(cut.label, requireNotNull(destination.slot(CopyRef("B"))).confirmed)
                assertNull(cut.label, destination.slot(CopyRef("C")))
                val state = fixture.state()
                assertEquals(cut.label, "B", state.headFileRef)
                assertEquals(cut.label, 2L, state.headGeneration)
                assertEquals(cut.label, state.changeSeq, state.verifiedSeq)
                assertNull(cut.label, state.pendingGeneration)
                assertEquals(cut.label, emptyList<String>(), fixture.stagingFiles())
            }
        }
    }

    @Test
    fun aDeathAfterTheSlotStateOnlyConfirmsInTheDatabase() = withSlots("recorded") { fixture ->
        val fake = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS, ::halfWrittenFirst)
        firstCopy(fixture, fake)
        fixture.addProduct("Leche de prueba")
        val dying = DyingAfterConfirm(fake)
        assertTrue(fixture.runner(dying).run(control) is BackupRun.Retry)
        assertTrue(requireNotNull(fake.slot(CopyRef("B"))).confirmed)
        assertEquals(1L, fixture.state().headGeneration)
        val before = fake.calls.size

        assertEquals(BackupRun.Verified(2), fixture.runner(fake).run(control))

        // Ya estaba releída y anotada: ni se escribe, ni se lee, ni se anota otra vez.
        val calls = fake.calls.drop(before)
        assertFalse(Operation.UPLOAD in calls)
        assertFalse(Operation.VERIFY in calls)
        assertFalse(Operation.CONFIRM in calls)
        assertEquals("B", fixture.state().headFileRef)
    }

    @Test
    fun theConfirmationUsesTheMarkOfTheSlotEvenForAnInvalidCopy() =
        withSlots("invalid") { fixture ->
            val destination =
                FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS, ::halfWrittenFirst)
            firstCopy(fixture, destination)
            fixture.addProduct("Leche de prueba")
            destination.failNext(
                Operation.VERIFY,
                DestinationFailure.Interrupted(InterruptReason.STOPPED),
            )
            fixture.runner(destination).run(control)
            // La marca guardó una copia que el validador dio por inválida.
            val half = requireNotNull(destination.slot(CopyRef("B")))
            val meta = requireNotNull(
                SlMetaCodec.decodeUnnamed(half.meta) as? CopyMetadata.Valid,
            ).meta
            val invalid = SlMetaCodec.encode(meta.copy(valid = false))
            destination.putSlot(
                CopyRef("B"),
                FakeSlot(half.bytes, invalid, half.writingSha256, false),
            )

            assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))

            val slot = requireNotNull(destination.slot(CopyRef("B")))
            assertTrue(slot.confirmed)
            assertArrayEquals(invalid, slot.meta)
        }

    @Test
    fun aMarkOfAnUnrestorableCopyIsNeverConfirmedInASlot() = withSlots("fatal") { fixture ->
        val destination =
            FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS, ::halfWrittenFirst)
        firstCopy(fixture, destination)
        fixture.addProduct("Leche de prueba")
        destination.failNext(
            Operation.VERIFY,
            DestinationFailure.Interrupted(InterruptReason.STOPPED),
        )
        fixture.runner(destination).run(control)
        // La marca dice que la copia no se restaura: una ranura nunca guarda una así.
        val half = requireNotNull(destination.slot(CopyRef("B")))
        val meta = requireNotNull(SlMetaCodec.decodeUnnamed(half.meta) as? CopyMetadata.Valid).meta
        val fatal = SlMetaCodec.encode(meta.copy(valid = false, fatal = true))
        destination.putSlot(CopyRef("B"), FakeSlot(half.bytes, fatal, half.writingSha256, false))

        // Empieza de nuevo: una copia nueva, que se restaura, en esa misma ranura.
        assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))

        val slot = requireNotNull(destination.slot(CopyRef("B")))
        assertTrue(slot.confirmed)
        assertFalse(slot.meta.contentEquals(fatal))
    }

    @Test
    fun aMarkOfAnotherFileIsNeverConfirmedAndANewCopyGoesToThatSlot() =
        withSlots("stale") { fixture ->
            val destination =
                FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS, ::halfWrittenFirst)
            firstCopy(fixture, destination)
            fixture.addProduct("Leche de prueba")
            destination.failNext(
                Operation.VERIFY,
                DestinationFailure.Interrupted(InterruptReason.STOPPED),
            )
            fixture.runner(destination).run(control)
            val half = requireNotNull(destination.slot(CopyRef("B")))
            // El proceso murió entre la intención y una marca nueva: la marca es de otro archivo.
            destination.putSlot(
                CopyRef("B"),
                FakeSlot(half.bytes, half.meta, "ab".repeat(32), false),
            )
            val before = destination.calls.size

            val run = fixture.runner(destination).run(control)

            // Una copia nueva, en la ranura a medias, que la regla de turnos usa primero.
            assertEquals(BackupRun.Verified(2), run)
            assertTrue(Operation.UPLOAD in destination.calls.drop(before))
            assertTrue(requireNotNull(destination.slot(CopyRef("B"))).confirmed)
            assertNull(destination.slot(CopyRef("C")))
        }

    @Test
    fun withoutTheFileAMismatchedSlotStartsOverAndStaysHalfWritten() =
        withSlots("no-file") { fixture ->
            val destination =
                FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS, ::halfWrittenFirst)
            firstCopy(fixture, destination)
            fixture.addProduct("Leche de prueba")
            destination.crashUploadAfter = FakeBackupDestination.UploadStep.AFTER_FILE
            fixture.runner(destination).run(control)
            File(fixture.stagingDir, "snapshot-g00000002.db.gz").delete()

            assertEquals(BackupRun.Verified(2), fixture.runner(destination).run(control))

            // La copia nueva sale de otra instantánea, en la misma ranura a medias.
            assertTrue(requireNotNull(destination.slot(CopyRef("B"))).confirmed)
            assertNull(destination.slot(CopyRef("C")))
        }

    @Test
    fun anotherSlotChangedOutsideBeforeConfirmingIsAConflictAndTheIntentStays() =
        withSlots("changed") { fixture ->
            val fake =
                FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS, ::halfWrittenFirst)
            firstCopy(fixture, fake)
            fixture.addProduct("Leche de prueba")
            fake.failNext(Operation.VERIFY, DestinationFailure.Interrupted(InterruptReason.STOPPED))
            fixture.runner(fake).run(control)
            val destination = SecondListingChanged(fake)

            val run = fixture.runner(destination).run(control)

            assertEquals(
                BackupRun.Stopped(StopCause.Conflict(BackupConflictKind.OTHER_DEVICE)),
                run,
            )
            val state = fixture.state()
            assertEquals("CONFLICT", state.linkState)
            assertEquals("A", state.conflictFileRef)
            assertEquals(1L, state.headGeneration)
            assertEquals(2L, state.pendingGeneration)
            assertNotNull(fake.slot(CopyRef("B")))
            assertFalse(requireNotNull(fake.slot(CopyRef("B"))).confirmed)
        }

    /** Corre [block] sobre una base propia, conectada a las ranuras, que se borra al terminar. */
    private fun withSlots(name: String, block: suspend (BackupFixture) -> Unit) = runBlocking {
        val fixture = BackupFixture(context, "slot-recovery-$name")
        try {
            fixture.open()
            fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    /** La primera copia, en la ranura `A`. Devuelve su contenido. */
    private suspend fun firstCopy(
        fixture: BackupFixture,
        destination: FakeBackupDestination,
    ): ByteArray {
        fixture.addProduct("Pan de prueba")
        assertEquals(BackupRun.Verified(1), fixture.runner(destination).run(control))
        return requireNotNull(destination.slot(CopyRef("A"))).bytes
    }

    /** Muere justo después de anotar la ranura, antes de confirmar en la base. */
    private class DyingAfterConfirm(private val delegate: FakeBackupDestination) :
        BackupDestination by delegate {
        override suspend fun confirm(copy: StoredCopy): DestinationResult<Unit> {
            delegate.confirm(copy)
            return DestinationResult.Failure(
                DestinationFailure.Interrupted(InterruptReason.STOPPED),
            )
        }
    }

    /** La lista de antes de confirmar por recuperación ve la ranura `A` cambiada por fuera. */
    private class SecondListingChanged(private val delegate: FakeBackupDestination) :
        BackupDestination by delegate {
        private var listings = 0

        override suspend fun listCopies(control: TransferControl): DestinationResult<CopyListing> {
            listings++
            return if (listings == 2) {
                DestinationResult.Failure(DestinationFailure.ChangedOutside(CopyRef("A")))
            } else {
                delegate.listCopies(control)
            }
        }
    }

    private companion object {
        /**
         * La regla de turnos de la prueba: primero la ranura a medias, que ya no tiene nada que
         * perder, después la primera libre, y nunca la cabeza.
         */
        internal fun halfWrittenFirst(
            copy: OutgoingCopy,
            slots: Map<CopyRef, FakeSlot>,
            headFileRef: CopyRef?,
        ): CopyRef? = slots.entries.firstOrNull { !it.value.confirmed }?.key
            ?: FakeBackupDestination.firstFreeSlot(copy, slots, headFileRef)
    }
}
