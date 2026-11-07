package io.github.yesidrobayo.shopledger.data.backup

import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Los nombres de la carpeta de preparación salen solo de la generación, con cifras ASCII, y su
 * limpieza no deja nada salvo la copia comprimida de una subida pendiente, y esa por un día como
 * máximo.
 */
class BackupStagingTest {
    @get:Rule val folder = TemporaryFolder()

    private val names = BackupStaging(File("staging"), Dispatchers.IO)

    @Test
    fun `the names carry the generation with at least eight digits`() {
        assertEquals(File("staging", "snapshot-g00000001.db"), names.snapshotFile(1))
        assertEquals(File("staging", "snapshot-g00000042.db"), names.snapshotFile(42))
        assertEquals(File("staging", "snapshot-g99999999.db"), names.snapshotFile(99_999_999))
        // Más de ocho cifras no se recortan: el nombre sigue siendo único.
        assertEquals(File("staging", "snapshot-g123456789.db"), names.snapshotFile(123_456_789))
        assertEquals(File("staging", "snapshot-g00000042.db.gz"), names.compressedFile(42))
    }

    @Test
    fun `a generation starts at one`() {
        assertThrows(IllegalArgumentException::class.java) { names.snapshotFile(0) }
        assertThrows(IllegalArgumentException::class.java) { names.compressedFile(-1) }
    }

    @Test
    fun `cleaning up without a pending upload leaves nothing`() = runBlocking {
        val staging = staging()
        staging.snapshotFile(GENERATION).writeText("instantánea")
        staging.compressedFile(GENERATION).writeText("copia")
        File(staging.directory, "otro.tmp").writeText("resto")

        staging.cleanUp(pendingGeneration = null, now = WRITTEN_AT)

        assertEquals(emptyList<String>(), staging.directory.list()?.toList())
    }

    @Test
    fun `the pending upload is kept for less than a day`() = runBlocking {
        val staging = staging()
        staging.snapshotFile(GENERATION).writeText("instantánea")
        staging.compressedFile(GENERATION - 1).writeText("copia de antes")
        val pending = staging.compressedFile(GENERATION)
        pending.writeText("copia pendiente")
        pending.setLastModified(WRITTEN_AT.toEpochMilliseconds())

        staging.cleanUp(GENERATION, WRITTEN_AT + 23.hours + 59.minutes)

        assertEquals(listOf(pending.name), staging.directory.list()?.toList())
    }

    @Test
    fun `the pending upload is deleted after a day`() = runBlocking {
        val staging = staging()
        val pending = staging.compressedFile(GENERATION)
        pending.writeText("copia pendiente")
        pending.setLastModified(WRITTEN_AT.toEpochMilliseconds())

        staging.cleanUp(GENERATION, WRITTEN_AT + 24.hours)

        assertEquals(emptyList<String>(), staging.directory.list()?.toList())
    }

    @Test
    fun `a pending upload from the future is deleted`() = runBlocking {
        val staging = staging()
        val pending = staging.compressedFile(GENERATION)
        pending.writeText("copia pendiente")
        pending.setLastModified(WRITTEN_AT.toEpochMilliseconds())

        // El reloj retrocedió: la edad sale negativa.
        staging.cleanUp(GENERATION, WRITTEN_AT - 1.minutes)

        assertEquals(emptyList<String>(), staging.directory.list()?.toList())
    }

    @Test
    fun `folders of other jobs are left alone`() = runBlocking {
        val staging = staging()
        val other = File(staging.directory, "second")
        assertTrue(other.mkdirs())
        File(other, "copia.db.gz").writeText("copia del segundo destino")

        staging.cleanUp(pendingGeneration = null, now = WRITTEN_AT)

        assertEquals(listOf("second"), staging.directory.list()?.toList())
        assertEquals(listOf("copia.db.gz"), other.list()?.toList())
    }

    @Test
    fun `a missing folder is nothing to clean`() = runBlocking {
        val staging = BackupStaging(File(folder.root, "nunca-creada"), Dispatchers.IO)

        staging.cleanUp(GENERATION, WRITTEN_AT)

        assertEquals(false, staging.directory.exists())
    }

    private fun staging(): BackupStaging =
        BackupStaging(folder.newFolder("backup-staging"), Dispatchers.IO)

    private companion object {
        const val GENERATION = 42L

        /** Un instante fijo, con milisegundos enteros, que el sistema de archivos guarda tal cual. */
        val WRITTEN_AT = Instant.fromEpochMilliseconds(1_793_000_000_000L)
    }
}
