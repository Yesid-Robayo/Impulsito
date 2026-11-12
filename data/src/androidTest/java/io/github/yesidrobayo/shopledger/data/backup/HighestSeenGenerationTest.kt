package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.data.destination.DestinationMemory
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * El ancla del aviso de copias que faltan, en el disco del teléfono: empieza sin ancla, solo sube,
 * un archivo raro no inventa una generación, un corte a mitad de la escritura deja la de antes, y
 * cambiar de destino la olvida con lo demás que se recordaba del anterior.
 */
class HighestSeenGenerationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "highest-seen-test")
    private val file = File(directory, HighestSeenGenerationStore.FILE_NAME)
    private val store = HighestSeenGenerationStore({ directory }, Dispatchers.IO)

    @Before
    fun clean() {
        directory.deleteRecursively()
        directory.mkdirs()
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun itStartsWithoutAnAnchorAndOnlyGoesUp() = runBlocking {
        assertNull(store.load())

        assertNull(store.raise(7))
        assertEquals(7L, store.raise(5))
        assertEquals(7L, store.load())
        assertEquals(7L, store.raise(9))
        assertEquals(9L, store.load())
        // Una lista vacía no dice nada: no baja ni crea un ancla.
        assertEquals(9L, store.raise(0))
        assertEquals(9L, store.load())
    }

    @Test
    fun aStrangeFileIsNoAnchor() = runBlocking {
        listOf("", "0\n", "-3\n", "007\n", "abc", "1".repeat(80)).forEach { content ->
            file.writeText(content)
            assertNull(content, store.load())
        }
    }

    @Test
    fun aCutBeforeTheRenameKeepsThePreviousAnchor() = runBlocking {
        store.raise(4)
        // Lo que deja un corte entre escribir el temporal y renombrarlo, dos veces seguidas.
        repeat(2) { DurableFile.temporaryOf(file).writeText("12") }

        assertEquals(4L, store.load())
        store.raise(6)
        assertEquals(6L, store.load())
        assertFalse(DurableFile.temporaryOf(file).exists())
    }

    @Test
    fun changingTheDestinationForgetsTheAnchor() = runBlocking {
        store.raise(11)

        DestinationMemory({
            directory
        }, Dispatchers.IO).forget(Instant.parse("2026-11-12T10:00:00Z"))

        assertFalse(file.exists())
        assertNull(store.load())
    }
}
