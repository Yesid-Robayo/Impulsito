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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** `slots-pending.json` en el disco: se relee igual, sin temporal, y un archivo dañado no manda. */
@RunWith(AndroidJUnit4::class)
class SlotPendingStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "slot-pending-test")
    private val store = SlotPendingStore(directory, Dispatchers.IO)
    private val file = File(directory, SlotPendingStore.FILE_NAME)

    @Before
    fun clean() {
        directory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun savedFilesReadBackTheSameAndLeaveNoTemporary() = runBlocking<Unit> {
        val files =
            mapOf(
                BackupSlot.A to PendingSlotFile("content://example.documents/a", "Drive", "a"),
                BackupSlot.C to PendingSlotFile("content://example.documents/c", "Drive", null),
            )

        store.save(files)

        assertEquals(files, store.load())
        assertTrue(store.exists())
        assertFalse(File(directory, SlotPendingStore.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun noFileOrADamagedOneCountsAsNothingCreated() = runBlocking<Unit> {
        assertEquals(emptyMap<BackupSlot, PendingSlotFile>(), store.load())
        val good =
            SlotPendingStore.encode(
                mapOf(
                    BackupSlot.A to PendingSlotFile("content://example.documents/a", "Drive", null),
                ),
            )
        directory.mkdirs()
        for (text in listOf("", "{", good.replace("\"A\"", "\"D\""), good.replace("1", "2"))) {
            file.writeText(text)
            assertEquals(text, emptyMap<BackupSlot, PendingSlotFile>(), store.load())
        }
    }

    @Test
    fun deletingRemovesTheFile() = runBlocking<Unit> {
        store.save(
            mapOf(BackupSlot.B to PendingSlotFile("content://example.documents/b", "Drive", null)),
        )

        assertTrue(store.delete())

        assertFalse(file.exists())
        assertFalse(store.exists())
    }
}
