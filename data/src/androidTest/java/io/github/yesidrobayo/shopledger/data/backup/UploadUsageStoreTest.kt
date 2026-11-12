package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadUsage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `upload-usage.json` en el disco del teléfono: suma en el mes, empieza de cero en otro, se escribe
 * sin dejar un temporal y lee como ausente lo que no entiende. Sin la zona del negocio no se inventa
 * un mes, y una falla al escribir no sale del contador.
 */
class UploadUsageStoreTest {
    private val dir =
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "upload-usage-test",
        ).also { it.deleteRecursively() }
    private val file = File(dir, UploadUsageStore.FILE_NAME)
    private val store = UploadUsageStore({ file }, Dispatchers.IO)
    private val failures = ArrayList<Exception>()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun threeBackupsOf28MegabytesAddUpTo84() = runBlocking<Unit> {
        repeat(3) { store.add("2026-11", 28_000_000) }

        assertEquals(UploadUsage("2026-11", 84_000_000), store.load())
        assertEquals("""{"month":"2026-11","bytes":84000000}""", file.readText())
        assertFalse(File(dir, UploadUsageStore.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun anotherMonthStartsAgain() = runBlocking<Unit> {
        store.add("2026-10", 90_000_000)
        store.add("2026-11", 5_000_000)

        assertEquals(UploadUsage("2026-11", 5_000_000), store.load())
    }

    @Test
    fun zeroBytesWriteNothing() = runBlocking<Unit> {
        store.add("2026-11", 0)

        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test
    fun anUnreadableFileIsReadAsAbsentAndStartsAgain() = runBlocking<Unit> {
        dir.mkdirs()
        file.writeText("""{"month":"2026-11","bytes":"mucho"}""")
        assertNull(store.load())

        store.add("2026-11", 7)
        assertEquals(UploadUsage("2026-11", 7), store.load())
    }

    @Test
    fun withoutTheBusinessZoneTheStoredMonthIsKept() = runBlocking<Unit> {
        val recorder = UploadUsageRecorder(store, month = { null }, onFailure = { failures += it })

        // Sin nada guardado, no hay mes al que sumar.
        recorder.sent(10)
        assertNull(store.load())

        store.add("2026-11", 5)
        recorder.sent(10)
        assertEquals(UploadUsage("2026-11", 15), store.load())
        assertEquals(emptyList<Exception>(), failures)
    }

    @Test
    fun aFailureToWriteIsReportedAndDoesNotEscape() = runBlocking<Unit> {
        // Un archivo donde iría la carpeta: no se puede escribir.
        dir.parentFile?.mkdirs()
        dir.writeText("no es una carpeta")
        val recorder =
            UploadUsageRecorder(store, month = { "2026-11" }, onFailure = { failures += it })

        recorder.sent(10)

        assertEquals(1, failures.size)
        assertEquals(true, failures.single() is IOException)
        dir.delete()
    }
}
