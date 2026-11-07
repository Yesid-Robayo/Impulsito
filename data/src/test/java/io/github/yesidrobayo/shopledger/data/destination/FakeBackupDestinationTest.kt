package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.UploadStep
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El destino falso cumple las mismas reglas que piden los destinos reales al motor. */
class FakeBackupDestinationTest {
    private val content = "copia inventada".toByteArray()
    private val meta = """{"sl_kind":"backup"}""".toByteArray()
    private val file = File.createTempFile("fake-destination", ".db.gz").apply {
        writeBytes(content)
    }
    private val name =
        BackupFileName(
            7,
            1,
            requireNotNull(CopyInstant.parse("20261107T120000Z")),
            "0a1b2c3d",
            BackupFileFormat.GZIP,
        )
    private val copy =
        OutgoingCopy(
            file,
            name,
            content.size.toLong(),
            FakeBackupDestination.sha256(content),
            meta,
            SlotCandidate(
                NewCopyMarks(valid = true, fatal = false),
                records = 10,
                catalogCount = 3,
            ),
        )
    private val control = TransferControl.Unbounded

    @After
    fun deleteFile() {
        file.delete()
    }

    @Test
    fun webDavUploadsVerifiesListsAndDeletes() = runBlocking<Unit> {
        val destination = FakeBackupDestination()
        val plan = destination.planUpload(copy, null).value()
        val stored = destination.upload(plan, control).value()
        assertEquals(listOf(name.fileName, name.metaFileName), destination.fileNames)
        assertTrue(destination.verify(stored, VerifyMode.FULL, null, control).value().fullyRead)

        val listing = destination.listCopies(control).value()
        assertEquals(CopyRef(name.fileName), listing.copies.single().ref)
        assertArrayEquals(meta, (listing.copies.single().meta as ListedMeta.Present).bytes)

        destination.delete(stored.ref, control).value()
        assertTrue(destination.fileNames.isEmpty())
    }

    @Test
    fun aCrashNeverLeavesAFinalNameWithoutItsTemporaryStep() = runBlocking<Unit> {
        val destination = FakeBackupDestination()
        destination.crashUploadAfter = UploadStep.AFTER_TEMPORARY_FILE
        val plan = destination.planUpload(copy, null).value()
        assertEquals(
            DestinationFailure.Interrupted(InterruptReason.STOPPED),
            destination.upload(plan, control).failure(),
        )
        val listing = destination.listCopies(control).value()
        assertTrue(listing.copies.isEmpty())
        assertEquals("0a1b2c3d", listing.temporaries.single().devicePrefix)
        assertEquals(
            PendingLocation.Absent,
            destination.locatePending(
                PendingCopy(name, copy.sizeBytes, copy.sha256),
                control,
            ).value(),
        )
    }

    @Test
    fun aCrashBetweenFileAndMetaIsRecoveredByUploadingTheMeta() = runBlocking<Unit> {
        val destination = FakeBackupDestination()
        destination.crashUploadAfter = UploadStep.AFTER_FILE
        val plan = destination.planUpload(copy, null).value()
        destination.upload(plan, control)
        val location =
            destination.locatePending(
                PendingCopy(name, copy.sizeBytes, copy.sha256),
                control,
            ).value()
        assertEquals(ListedMeta.Missing, (location as PendingLocation.Uploaded).meta)
        destination.uploadMeta(plan, control).value()
        destination.verify(
            location.copy.withMeta(meta),
            VerifyMode.SIZE_AND_META,
            null,
            control,
        ).value()
    }

    @Test
    fun injectedFailuresComeInOrderAndThenStop() = runBlocking<Unit> {
        val destination = FakeBackupDestination()
        destination.failNext(Operation.LIST, DestinationFailure.Incomplete, times = 2)
        assertEquals(DestinationFailure.Incomplete, destination.listCopies(control).failure())
        assertEquals(DestinationFailure.Incomplete, destination.listCopies(control).failure())
        destination.listCopies(control).value()
        assertEquals(listOf(Operation.LIST, Operation.LIST, Operation.LIST), destination.calls)
    }

    @Test
    fun aChangedCopyFailsVerification() = runBlocking<Unit> {
        val destination = FakeBackupDestination()
        val stored = destination.upload(destination.planUpload(copy, null).value(), control).value()
        destination.putFile(name.fileName, "otra cosa".toByteArray())
        assertEquals(
            DestinationFailure.VerifyMismatch,
            destination.verify(stored, VerifyMode.FULL, null, control).failure(),
        )
    }

    @Test
    fun slotsWriteMarkConfirmAndNeverDelete() = runBlocking<Unit> {
        val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
        val plan = destination.planUpload(copy, null).value()
        assertEquals(CopyRef("A"), plan.ref)
        val stored = destination.upload(plan, control).value()
        assertTrue(destination.listCopies(control).value().copies.isEmpty())
        val marked =
            destination.locatePending(
                PendingCopy(name, copy.sizeBytes, copy.sha256),
                control,
            ).value()
        assertEquals(stored.ref, (marked as PendingLocation.Uploaded).copy.ref)
        destination.verify(stored, VerifyMode.SIZE_AND_META, null, control).value()
        destination.confirm(stored).value()
        assertEquals(CopyRef("A"), destination.listCopies(control).value().copies.single().ref)
        assertTrue(
            destination.locatePending(
                PendingCopy(name, copy.sizeBytes, copy.sha256),
                control,
            ).value()
                is PendingLocation.Confirmed,
        )
        assertTrue(
            destination.delete(stored.ref, control).failure() is DestinationFailure.Unsupported,
        )

        val out = ByteArrayOutputStream()
        destination.download(stored.ref, out, Long.MAX_VALUE, control).value()
        assertArrayEquals(content, out.toByteArray())
    }

    @Test
    fun slotsWithoutASafePlaceRefuseToPlan() = runBlocking<Unit> {
        val destination =
            FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS) { _, _, _ -> null }
        assertEquals(
            DestinationFailure.NoSafeSlot(null),
            destination.planUpload(copy, null).failure(),
        )
    }

    private fun StoredCopy.withMeta(bytes: ByteArray) = StoredCopy(ref, sizeBytes, sha256, bytes)

    private fun <T> DestinationResult<T>.value(): T = when (this) {
        is DestinationResult.Success -> value
        is DestinationResult.Failure -> throw AssertionError("Falló: $failure")
    }

    private fun DestinationResult<*>.failure(): DestinationFailure = when (this) {
        is DestinationResult.Success -> throw AssertionError("Debía fallar")
        is DestinationResult.Failure -> failure
    }

    @Test
    fun theFakeSlotRuleNeverTakesTheHeadNorAFatalCopy() = runBlocking<Unit> {
        val destination = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
        for (ref in FakeBackupDestination.SLOT_REFS) {
            destination.putSlot(ref, FakeBackupDestination.FakeSlot(content, meta, null, true))
        }
        assertEquals(CopyRef("B"), destination.planUpload(copy, CopyRef("A")).value().ref)
        assertEquals(CopyRef("A"), destination.lastHeadFileRef)
        val fatal =
            OutgoingCopy(
                file,
                name,
                copy.sizeBytes,
                copy.sha256,
                meta,
                SlotCandidate(NewCopyMarks(valid = false, fatal = true), 0, 0),
            )
        assertEquals(
            DestinationFailure.NoSafeSlot(null),
            destination.planUpload(fatal, null).failure(),
        )
    }
}
