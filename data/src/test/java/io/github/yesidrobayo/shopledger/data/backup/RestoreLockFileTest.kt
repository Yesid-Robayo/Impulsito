package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * El cerrojo de la restauración detiene las escrituras mientras dura, y nunca para siempre: sin
 * diario se abandona a la hora, y con el reloj atrasado también.
 */
class RestoreLockFileTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val files by lazy {
        RestoreFiles(
            DatabaseFiles(
                File(folder.root, "databases/shopledger.db").absoluteFile,
                File(folder.root, "no_backup").absoluteFile,
            ),
        )
    }
    private val lock by lazy {
        RestoreLockFile(
            files,
            DurableFileOps(directorySync = { check(it.isDirectory) }),
            Clock { now },
            Dispatchers.Unconfined,
        )
    }

    @Test
    fun `without a lock nothing is held`() = runBlocking {
        assertFalse(lock.isHeld())
    }

    @Test
    fun `a taken lock holds the writes and keeps when it was taken`() = runBlocking {
        val takenAt = checkNotNull(lock.acquire())

        assertTrue(lock.isHeld())
        assertEquals(now.toEpochMilliseconds().toString(), files.lock.readText())
        assertFalse(files.lockTemporary.exists())
        assertTrue(lock.isOwned(takenAt))
        // Nadie más lo toma mientras dure.
        assertNull(lock.acquire())
    }

    @Test
    fun `a lock without a journal is abandoned after one hour`() = runBlocking {
        lock.acquire()
        now += RestoreLockFile.LIFETIME - 1.seconds
        assertTrue(lock.isHeld())

        now += 1.seconds

        assertFalse(lock.isHeld())
        assertFalse("El cerrojo abandonado se borra", files.lock.exists())
    }

    @Test
    fun `a clock set back counts the lock as expired`() = runBlocking {
        lock.acquire()
        now -= 1.seconds

        assertFalse(lock.isHeld())
        assertFalse(files.lock.exists())
    }

    @Test
    fun `with a journal the lock holds no matter its age`() = runBlocking {
        lock.acquire()
        files.journal.writeText("{}")
        now += RestoreLockFile.LIFETIME * 3

        assertTrue(lock.isHeld())
        assertTrue(files.lock.exists())
        // Y un diario sin el archivo del cerrojo también retiene.
        files.lock.delete()
        assertTrue(lock.isHeld())
        assertNull("Con diario no empieza otra restauración", lock.acquire())
    }

    @Test
    fun `an expired lock can be taken again and the old owner no longer owns it`() = runBlocking {
        val first = checkNotNull(lock.acquire())
        now += RestoreLockFile.LIFETIME

        val second = checkNotNull(lock.acquire())

        assertFalse(lock.isOwned(first))
        assertTrue(lock.isOwned(second))
    }

    @Test
    fun `the owner stops before the lock gets close to expiring`() = runBlocking {
        val takenAt = checkNotNull(lock.acquire())
        now += RestoreLockFile.LIFETIME - RestoreLockFile.OWNER_MARGIN - 1.seconds
        assertTrue(lock.isOwned(takenAt))

        now += 1.seconds

        assertFalse(lock.isOwned(takenAt))
        // Pero las escrituras siguen detenidas hasta que venza de verdad.
        assertTrue(lock.isHeld())
    }

    @Test
    fun `an unreadable lock counts from the date of its file`() = runBlocking {
        files.restoreDirectory.mkdirs()
        files.lock.writeText("no es un instante")
        files.lock.setLastModified(now.toEpochMilliseconds() - 10.minutes.inWholeMilliseconds)
        assertTrue(lock.isHeld())

        now += RestoreLockFile.LIFETIME

        assertFalse(lock.isHeld())
    }

    @Test
    fun `releasing removes the lock and its temporary`() = runBlocking {
        lock.acquire()
        files.lockTemporary.writeText("resto")

        lock.releaseNow()

        assertFalse(files.lock.exists())
        assertFalse(files.lockTemporary.exists())
        assertFalse(lock.isHeld())
    }
}
