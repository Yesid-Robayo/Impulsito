package io.github.yesidrobayo.shopledger.data.destination.slots

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.OutgoingCopy
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.UnsupportedReason
import io.github.yesidrobayo.shopledger.data.destination.VerifiedCopy
import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotTestProvider.Failure
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las ranuras con `ContentResolver` de verdad, contra un proveedor de documentos local del APK de
 * pruebas: cómo llega cada excepción del proveedor, y una escritura completa con `slots.json` en el
 * disco.
 */
@RunWith(AndroidJUnit4::class)
class DocumentSlotDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "${context.packageName}.slottest"
    private val cache = context.cacheDir
    private val stateDir = File(cache, "slot-device-state")
    private val access = ContentResolverDocumentAccess(context.contentResolver) { true }
    private val files = mutableListOf<File>()

    @Before
    fun clean() {
        SlotTestProvider.reset(cache)
        stateDir.deleteRecursively()
    }

    @After
    fun cleanUp() {
        SlotTestProvider.reset(cache)
        stateDir.deleteRecursively()
        files.forEach(File::delete)
    }

    @Test
    fun queryGivesSizeAndNameAndSeparatesAnUnknownSizeFromAMissingDocument() = runBlocking<Unit> {
        val behavior = SlotTestProvider.create(cache, "a")
        val uri = SlotTestProvider.uriOf(authority, "a")
        assertEquals(
            DocumentResult.Ok(DocumentInfo(0, "shopledger-backup-a.slcopy")),
            access.query(uri),
        )

        behavior.sizeUnknown = true
        assertEquals(
            DocumentResult.Ok(DocumentInfo(null, "shopledger-backup-a.slcopy")),
            access.query(uri),
        )

        behavior.present = false
        assertEquals(error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE), access.query(uri))
        assertEquals(
            error(DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE),
            access.query(SlotTestProvider.uriOf(authority, "nadie")),
        )
    }

    @Test
    fun everyProviderExceptionIsClassifiedAndTheSessionOneBeforeSecurity() = runBlocking<Unit> {
        val behavior = SlotTestProvider.create(cache, "a")
        val uri = SlotTestProvider.uriOf(authority, "a")
        val expected = mapOf(
            Failure.AUTHENTICATION to DocumentError.AUTHENTICATION_REQUIRED,
            Failure.SECURITY to DocumentError.SECURITY,
            Failure.NOT_FOUND to DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE,
            Failure.UNSUPPORTED to DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE,
            Failure.OTHER to DocumentError.REJECTED,
        )
        for ((failure, error) in expected) {
            behavior.openFailures["wt"] = failure
            assertEquals(failure.name, error(error), access.openForWrite(uri, WriteMode.WT))
            behavior.openFailures.clear()
            behavior.queryFailure = failure
            assertEquals(failure.name, error(error), access.query(uri))
            behavior.queryFailure = null
        }
    }

    @Test
    fun wtTruncatesAndAPipeCannotBeSyncedButStillDelivers() = runBlocking<Unit> {
        val behavior = SlotTestProvider.create(cache, "a")
        val uri = SlotTestProvider.uriOf(authority, "a")
        writeAll(uri, ByteArray(100) { 1 })
        val opened = (access.openForWrite(uri, WriteMode.WT) as DocumentResult.Ok).value
        opened.stream.write(byteArrayOf(7, 8))
        assertTrue(opened.sync())
        opened.close()
        assertArrayEquals(byteArrayOf(7, 8), SlotTestProvider.bytesOf(cache, "a"))

        behavior.pipe = true
        val piped = (access.openForWrite(uri, WriteMode.WT) as DocumentResult.Ok).value
        piped.stream.write(byteArrayOf(9))
        assertFalse("una tubería no se lleva al disco, y no es un fallo", piped.sync())
        piped.close()
        SlotTestProvider.awaitDrains()
        assertArrayEquals(byteArrayOf(9), SlotTestProvider.bytesOf(cache, "a"))
    }

    @Test
    fun theOwnProviderIsNotAPersistedGrant() = runBlocking<Unit> {
        SlotTestProvider.create(cache, "a")
        val real = ContentResolverDocumentAccess(context.contentResolver)

        assertFalse(real.isPersisted(SlotTestProvider.uriOf(authority, "a")))
    }

    @Test
    fun aFullWriteWithTheStateOnDiskAndAProviderThatRejectsWt() = runBlocking<Unit> {
        for (id in listOf("a", "b", "c")) SlotTestProvider.create(cache, id)
        SlotTestProvider.behaviors.getValue("c").openFailures["wt"] = Failure.UNSUPPORTED
        val store = FileSlotStateStore(stateDir, Dispatchers.IO)
        store.save(
            SlotState(
                slots = BackupSlot.entries.associateWith {
                    SlotEntry(
                        SlotTestProvider.uriOf(authority, it.name.lowercase()),
                        "Nube",
                        null,
                        null,
                        null,
                    )
                },
                firstHandoffAt = null,
                writeMode = WriteMode.WT,
                sessionRequired = false,
            ),
        )
        val now = Instant.parse("2026-11-07T12:00:00Z")
        val destination = DocumentSlotDestination(store, access, Clock { now }, Dispatchers.IO)
        val content = ByteArray(200_000) { (it * 7).toByte() }
        val copy = outgoing(1, content)

        val plan = destination.planUpload(copy, null).value()
        assertEquals(CopyRef("C"), plan.ref)
        val stored = destination.upload(plan, TransferControl.Unbounded).value()
        assertEquals(
            VerifiedCopy(true),
            destination.verify(stored, VerifyMode.FULL, null, TransferControl.Unbounded).value(),
        )
        destination.confirm(stored).value()

        assertArrayEquals(content, SlotTestProvider.bytesOf(cache, "c"))
        val state = (store.load() as SlotStateLoad.Loaded).state
        assertEquals(WriteMode.RWT, state.writeMode)
        assertEquals(1L, state[BackupSlot.C].copy?.generation)
        assertNull(state[BackupSlot.C].writing)
        destination.listCopies(TransferControl.Unbounded).value()
        assertEquals("shopledger-backup-c.slcopy", state[BackupSlot.C].displayName)

        // Un documento que cambió de tamaño por fuera no se pisa.
        writeAll(SlotTestProvider.uriOf(authority, "c"), byteArrayOf(1))
        assertEquals(
            DestinationFailure.ChangedOutside,
            destination.listCopies(TransferControl.Unbounded).failure(),
        )
        // Uno sin tamaño conocido no sirve para las ranuras.
        writeAll(SlotTestProvider.uriOf(authority, "c"), content)
        SlotTestProvider.behaviors.getValue("a").sizeUnknown = true
        assertEquals(
            DestinationFailure.Unsupported(UnsupportedReason.PROVIDER_REJECTED),
            destination.listCopies(TransferControl.Unbounded).failure(),
        )
    }

    private fun writeAll(uri: String, bytes: ByteArray) = runBlocking {
        // Con rwt, para escribir también en el documento que rechaza wt a propósito.
        val opened = (access.openForWrite(uri, WriteMode.RWT) as DocumentResult.Ok).value
        opened.stream.write(bytes)
        opened.close()
    }

    private fun outgoing(generation: Long, content: ByteArray): OutgoingCopy {
        val file = File.createTempFile("slot-device", ".db.gz", cache).apply { writeBytes(content) }
        files += file
        val name =
            BackupFileName(
                generation,
                1,
                requireNotNull(CopyInstant.parse("20261107T110000Z")),
                "0a1b2c3d",
                BackupFileFormat.GZIP,
            )
        return OutgoingCopy(
            file = file,
            name = name,
            sizeBytes = content.size.toLong(),
            sha256 = newSha256().digest(content).toLowerHex(),
            metaBytes = byteArrayOf(1, 2, 3),
            candidate = SlotCandidate(NewCopyMarks(valid = true, fatal = false), 10, 5),
        )
    }

    private fun error(error: DocumentError): DocumentResult<Nothing> = DocumentResult.Error(error)

    private fun <T> DestinationResult<T>.value(): T = when (this) {
        is DestinationResult.Success -> value
        is DestinationResult.Failure -> throw AssertionError("Se esperaba éxito y llegó $failure")
    }

    private fun DestinationResult<*>.failure(): DestinationFailure = when (this) {
        is DestinationResult.Success -> throw AssertionError("Se esperaba un fallo y llegó $value")
        is DestinationResult.Failure -> failure
    }
}
