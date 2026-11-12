package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.slots.FileSlotStateStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotCopyFacts
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotEntry
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotRecord
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.WriteMode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Ya la vi en {provider}` sobre la base real: anota la hora y la generación sin tocar lo que cuenta
 * como respaldado, solo con la nube del teléfono; y la última entrega sale de `slots.json`. Datos
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class CloudCheckDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = BackupFixture(context, "cloud-check-test")
    private val outside = File(context.noBackupFilesDir, "cloud-check-test-outside")
    private val slots = FileSlotStateStore(outside, Dispatchers.IO)

    @Before
    fun open() = runBlocking {
        outside.deleteRecursively()
        fixture.open()
        fixture.addProduct("Pan de prueba")
    }

    @After
    fun close() {
        fixture.close()
        outside.deleteRecursively()
    }

    private fun reader() = BackupCopiesReader(
        saved = { fixture.ports.backupState.get()?.savedDestination() },
        destinations = DestinationOpener { error("Con ranuras no se abre nada para leer") },
        slots = slots,
        deviceId = { "dispositivo-de-prueba" },
        hasNetwork = { false },
    )

    private fun record(generation: Long, writtenAt: Long) = SlotRecord(
        generation = generation,
        writtenAt = writtenAt,
        sizeBytes = 2_400_000L + generation,
        sha256 = "a".repeat(64),
        facts = SlotCopyFacts(valid = true, records = 10, catalogCount = 1),
        metaBytes = ByteArray(0),
    )

    @Test
    fun theCheckIsNotedOnlyWithTheCloudOfThePhoneAndNothingElseChanges() = runBlocking {
        val recorder = RoomHumanCheckRecorder(fixture.ports.backupState)
        fixture.connect(BackupDestinationKind.WEBDAV)
        assertFalse(recorder.recordSeen(7, Instant.fromEpochMilliseconds(SEEN_AT)))

        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        val before = requireNotNull(fixture.ports.backupState.get())
        assertTrue(recorder.recordSeen(7, Instant.fromEpochMilliseconds(SEEN_AT)))

        val after = requireNotNull(fixture.ports.backupState.get())
        assertEquals(SEEN_AT, after.lastHumanCheckAt)
        assertEquals(7L, after.humanCheckGeneration)
        // No es una comprobación: lo respaldado y lo pendiente siguen igual.
        assertEquals(before.copy(lastHumanCheckAt = SEEN_AT, humanCheckGeneration = 7L), after)
    }

    @Test
    fun theLatestHandoffIsTheNewestSlotWithItsLastCheck() = runBlocking {
        fixture.connect(BackupDestinationKind.DOCUMENT_SLOTS)
        assertNull(reader().latestHandoff())

        slots.save(
            SlotState(
                slots = mapOf(
                    BackupSlot.A to
                        SlotEntry(
                            "content://example.documents/document/A",
                            "Nube",
                            "copia-a.slcopy",
                            record(3, 1_000),
                            null,
                        ),
                    BackupSlot.B to
                        SlotEntry(
                            "content://example.documents/document/B",
                            "Nube",
                            "copia-b.slcopy",
                            record(4, 2_000),
                            null,
                        ),
                    BackupSlot.C to
                        SlotEntry(
                            "content://example.documents/document/C",
                            "Nube",
                            "copia-c.slcopy",
                            null,
                            null,
                        ),
                ),
                firstHandoffAt = 1_000,
                writeMode = WriteMode.WT,
                sessionRequired = false,
            ),
        )
        RoomHumanCheckRecorder(fixture.ports.backupState)
            .recordSeen(3, Instant.fromEpochMilliseconds(SEEN_AT))

        val latest = requireNotNull(reader().latestHandoff())

        assertEquals("copia-b.slcopy", latest.fileName)
        assertEquals(4L, latest.generation)
        assertEquals(Instant.fromEpochMilliseconds(2_000), latest.writtenAt)
        assertEquals(2_400_004L, latest.sizeBytes)
        assertEquals("content://example.documents/document/B", latest.documentUri)
        assertEquals(Instant.fromEpochMilliseconds(SEEN_AT), latest.lastCheckAt)
    }

    @Test
    fun withWebDavThereIsNoHandoffToCheck() = runBlocking {
        fixture.connect(BackupDestinationKind.WEBDAV)

        assertNull(reader().latestHandoff())
    }

    private companion object {
        const val SEEN_AT = 1_793_876_400_000L
    }
}
