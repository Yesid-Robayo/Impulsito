package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Las bases originales apartadas sin copia de seguridad: cada restauración en su carpeta, nunca
 * una encima de otra, y fuera a los 30 días, nunca con el reloj atrasado. Los archivos son
 * inventados.
 */
class DamagedOriginalsTest {
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
    private val operations = mutableListOf<String>()
    private val damaged by lazy {
        DamagedOriginals(
            files,
            DurableFileOps(directorySync = {
                check(it.isDirectory)
            }, listener = { operations += it }),
            Clock { now },
        )
    }

    @Test
    fun `without a database waiting in previous nothing is set aside`() {
        assertNull(damaged.setAsidePrevious())
        assertTrue(damaged.folders().isEmpty())
    }

    @Test
    fun `the database moves with its side files in a single rename`() {
        write(files.previous, "base original")
        write(files.previousWal, "wal de la base original")
        write(files.previousShm, "")

        val aside = checkNotNull(damaged.setAsidePrevious())

        assertEquals(now.toEpochMilliseconds().toString(), aside.name)
        assertEquals("base original", File(aside, DatabaseFiles.DATABASE_NAME).readText())
        assertEquals("wal de la base original", File(aside, "shopledger.db-wal").readText())
        assertTrue(File(aside, "shopledger.db-shm").exists())
        assertFalse(files.previousDirectory.exists())
        assertEquals("Una sola operación: nada queda separado de su -wal", 1, operations.size)
    }

    @Test
    fun `a second restore never overwrites the first one even in the same millisecond`() {
        write(files.previous, "primera")
        val first = checkNotNull(damaged.setAsidePrevious())
        write(files.previous, "segunda")

        val second = checkNotNull(damaged.setAsidePrevious())

        assertEquals(listOf(first, second), damaged.folders())
        assertEquals("primera", File(first, DatabaseFiles.DATABASE_NAME).readText())
        assertEquals("segunda", File(second, DatabaseFiles.DATABASE_NAME).readText())
    }

    @Test
    fun `a folder is pruned at thirty days and never with the clock set back`() {
        write(files.previous, "vieja")
        val old = checkNotNull(damaged.setAsidePrevious())
        now += 10.milliseconds
        write(files.previous, "nueva")
        val recent = checkNotNull(damaged.setAsidePrevious())

        damaged.pruneExpired(Instant.fromEpochMilliseconds(old.name.toLong()) - 1.milliseconds)
        assertEquals("Una carpeta del futuro no está vencida", 2, damaged.folders().size)

        damaged.pruneExpired(
            Instant.fromEpochMilliseconds(old.name.toLong()) + DamagedOriginals.RETENTION -
                1.milliseconds,
        )
        assertEquals(2, damaged.folders().size)

        damaged.pruneExpired(
            Instant.fromEpochMilliseconds(old.name.toLong()) + DamagedOriginals.RETENTION,
        )
        assertEquals(listOf(recent), damaged.folders())
        assertFalse(old.exists())
    }

    @Test
    fun `a folder that is not one of the app is never touched`() {
        val foreign = File(files.damagedDirectory, "soporte").apply { mkdirs() }
        write(File(foreign, "nota.txt"), "de otra cosa")

        damaged.pruneExpired(now + DamagedOriginals.RETENTION * 10)

        assertTrue(damaged.folders().isEmpty())
        assertTrue(File(foreign, "nota.txt").exists())
    }

    private fun write(file: File, text: String) {
        requireNotNull(file.parentFile).mkdirs()
        file.writeText(text)
    }
}
