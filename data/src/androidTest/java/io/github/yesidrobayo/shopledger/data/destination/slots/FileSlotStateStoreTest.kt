package io.github.yesidrobayo.shopledger.data.destination.slots

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** `slots.json` en el disco del dispositivo: se guarda entero, se relee igual y se lee estricto. */
@RunWith(AndroidJUnit4::class)
class FileSlotStateStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "slot-store-test")
    private val store = FileSlotStateStore(directory, Dispatchers.IO)
    private val file = File(directory, FileSlotStateStore.FILE_NAME)

    @Before
    fun clean() {
        directory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun aSavedStateReadsBackTheSameAndLeavesNoTemporary() = runBlocking<Unit> {
        val state = sampleState()

        store.save(state)

        assertEquals(SlotStateLoad.Loaded(state), store.load())
        assertFalse(File(directory, FileSlotStateStore.TEMPORARY_NAME).exists())
        val changed = state.copy(
            writeMode = WriteMode.RWT,
            sessionRequired = true,
            firstHandoffAt = null,
        )
        store.save(changed)
        assertEquals(SlotStateLoad.Loaded(changed), store.load())
    }

    @Test
    fun noFileIsMissingAndALeftoverTemporaryIsIgnored() = runBlocking<Unit> {
        directory.mkdirs()
        File(directory, FileSlotStateStore.TEMPORARY_NAME).writeText("{")

        assertEquals(SlotStateLoad.Missing, store.load())
    }

    @Test
    fun aDamagedFileIsUnreadableNeverEmpty() = runBlocking<Unit> {
        val good = FileSlotStateStore.encode(sampleState())
        val damaged = listOf(
            "",
            "{",
            "[]",
            good.replace("\"version\":1", "\"version\":2"),
            // Un entero escrito como texto: org.json lo aceptaría sin avisar.
            good.replace("\"size\":40", "\"size\":\"40\""),
            good.replace("\"valid\":true", "\"valid\":\"true\""),
            good.replace("\"write_mode\":\"wt\"", "\"write_mode\":\"w\""),
            good.replace("\"first_handoff_at\":1793880000000,", ""),
            good.replace("\"sl_records\":500", "\"sl_records\":-1"),
            good.replace(Regex("\"sha256\":\"[0-9a-f]{64}\""), "\"sha256\":\"00\""),
            // Dos ranuras con el mismo documento destruirían una cabeza al escribir la otra.
            // org.json escribe la barra como \/.
            good.replace("document\\/b", "document\\/a"),
            good.replace("\"C\":{", "\"D\":{"),
        )
        for (text in damaged) {
            assertNotEquals("cada caso cambia el texto", good, text)
            directory.mkdirs()
            file.writeText(text)
            assertEquals(text, SlotStateLoad.Unreadable, store.load())
        }
    }

    private fun sampleState(): SlotState {
        val record =
            SlotRecord(
                4,
                1_793_876_400_000,
                40,
                "ab".repeat(32),
                SlotCopyFacts(true, 500, 50),
                byteArrayOf(1, 2, 3),
            )
        return SlotState(
            slots = mapOf(
                BackupSlot.A to
                    SlotEntry(
                        "content://example.documents/document/a",
                        "Nube",
                        "copia a",
                        record,
                        null,
                    ),
                BackupSlot.B to
                    SlotEntry(
                        "content://example.documents/document/b",
                        "Nube",
                        null,
                        null,
                        WritingMark(5, null),
                    ),
                BackupSlot.C to SlotEntry(
                    "content://example.documents/document/c",
                    "Nube",
                    "copia c",
                    null,
                    WritingMark(
                        6,
                        SlotRecord(
                            6,
                            1,
                            0,
                            "cd".repeat(32),
                            SlotCopyFacts(false, 0, 0),
                            ByteArray(0),
                        ),
                    ),
                ),
            ),
            firstHandoffAt = 1_793_880_000_000,
            writeMode = WriteMode.WT,
            sessionRequired = false,
        )
    }
}
