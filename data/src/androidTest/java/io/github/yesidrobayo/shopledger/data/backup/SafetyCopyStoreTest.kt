package io.github.yesidrobayo.shopledger.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.DatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.local.texts
import java.io.File
import java.io.RandomAccessFile
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La copia local de seguridad antes de reemplazar la base, con el SQLite empaquetado: entera y
 * comprobada antes de tener nombre, sin destruir nunca el único respaldo de unos cambios que no
 * llegaron al destino, y ofrecida 30 días para deshacer. Todos los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class SafetyCopyStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "safety-copy-test")
    private val files =
        RestoreFiles(
            DatabaseFiles(
                File(root, "databases/shopledger.db").absoluteFile,
                File(root, "no_backup"),
            ),
        )
    private val ops = DurableFileOps()
    private val store = SafetyCopyStore(files, ops, Dispatchers.IO)
    private val newest get() = files.safetyCopies[0]
    private val older get() = files.safetyCopies[1]

    @Before
    fun clean() {
        root.deleteRecursively()
        requireNotNull(files.database.parentFile).mkdirs()
        runBlocking {
            SnapshotCreator(CandidateFixtures.seed(), Dispatchers.IO).create(files.database)
        }
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun theCopyIsWholeCheckedAndKeepsWhatTheDatabaseSaid() = runBlocking {
        assertEquals(SafetyCopyResult.Saved, store.save(changeSeq = 7, verifiedSeq = 7, now = NOW))

        assertEquals(
            listOf("ok"),
            inspectWithoutRoom(newest.database) {
                it.texts("PRAGMA integrity_check")
            },
        )
        assertEquals(
            listOf(SafetyCopy(newest, SafetyCopyFacts(7, 7, NOW))),
            store.copies(),
        )
        assertFalse(files.safetyCopyTemporary.exists())
        assertFalse(older.database.exists())
    }

    @Test
    fun aCopyWithoutUnsyncedChangesIsReplaced() = runBlocking {
        store.save(changeSeq = 7, verifiedSeq = 7, now = NOW)

        store.save(changeSeq = 9, verifiedSeq = 9, now = NOW + 1.hours)

        assertEquals(listOf(SafetyCopyFacts(9, 9, NOW + 1.hours)), store.copies().map { it.facts })
    }

    @Test
    fun aSecondRestoreWithUnsyncedChangesKeepsTheFirstCopy() = runBlocking {
        store.save(changeSeq = 12, verifiedSeq = 9, now = NOW)
        val first = newest.database.readBytes()

        assertEquals(
            SafetyCopyResult.Saved,
            store.save(
                changeSeq = 20,
                verifiedSeq = 20,
                now =
                    NOW + 1.hours,
            ),
        )

        assertArrayEquals(first, older.database.readBytes())
        assertEquals(
            listOf(SafetyCopyFacts(20, 20, NOW + 1.hours), SafetyCopyFacts(12, 9, NOW)),
            store.copies().map { it.facts },
        )
    }

    @Test
    fun withTwoCopiesHoldingUnsyncedChangesThePersonChooses() = runBlocking {
        store.save(changeSeq = 12, verifiedSeq = 9, now = NOW)
        store.save(changeSeq = 30, verifiedSeq = 25, now = NOW + 1.hours)
        val before = store.copies()

        val result = store.save(changeSeq = 40, verifiedSeq = 40, now = NOW + 2.hours)

        assertEquals(SafetyCopyResult.NeedsChoice(before), result)
        assertEquals(before, store.copies())
        // La persona descarta la más vieja, y entonces la copia sale.
        store.discard(before[1])
        assertEquals(
            SafetyCopyResult.Saved,
            store.save(
                changeSeq = 40,
                verifiedSeq = 40,
                now =
                    NOW + 2.hours,
            ),
        )
        assertEquals(
            listOf(SafetyCopyFacts(40, 40, NOW + 2.hours), SafetyCopyFacts(30, 25, NOW + 1.hours)),
            store.copies().map { it.facts },
        )
    }

    @Test
    fun aCopyWhoseFactsCannotBeReadIsKeptAsIfItHeldChanges() = runBlocking {
        store.save(changeSeq = 7, verifiedSeq = 7, now = NOW)
        newest.facts.writeText("{roto")

        store.save(changeSeq = 8, verifiedSeq = 8, now = NOW + 1.hours)

        assertTrue(older.database.exists())
        assertEquals(
            listOf(SafetyCopyFacts(8, 8, NOW + 1.hours), null),
            store.copies().map {
                it.facts
            },
        )
    }

    @Test
    fun aDamagedActiveDatabaseGivesNoCopy() = runBlocking {
        RandomAccessFile(files.database, "rw").use { file ->
            file.seek(file.length() / 2)
            file.write(ByteArray(PAGE * 4) { 0xFF.toByte() })
        }

        assertEquals(SafetyCopyResult.ActiveDatabaseDamaged, store.save(1, 1, NOW))
        assertEquals(emptyList<SafetyCopy>(), store.copies())
        assertFalse(files.safetyCopyTemporary.exists())
    }

    @Test
    fun withoutAnActiveDatabaseThereIsNothingToProtect() = runBlocking {
        files.database.delete()

        assertEquals(SafetyCopyResult.NoActiveDatabase, store.save(1, 1, NOW))
        assertFalse(files.database.exists())
    }

    @Test
    fun copiesAreOfferedForThirtyDaysAndAClockBehindNeverDeletesOne() = runBlocking {
        store.save(changeSeq = 7, verifiedSeq = 7, now = NOW)

        assertEquals(1, store.available(NOW + 29.days).size)
        assertEquals(0, store.available(NOW + 30.days).size)
        store.pruneExpired(NOW - 5.days)
        assertTrue(newest.database.exists())
        store.pruneExpired(NOW + 29.days)
        assertTrue(newest.database.exists())
        store.pruneExpired(NOW + 30.days)
        assertFalse(newest.database.exists())
        assertFalse(newest.facts.exists())
    }

    @Test
    fun anUndoKeepsItsSourceUntilTheSwapIsVerified() = runBlocking {
        store.save(changeSeq = 7, verifiedSeq = 5, now = NOW)
        val copy = store.copies().single()
        val bytes = copy.files.database.readBytes()

        prepareUndo(files, ops, copy)

        assertArrayEquals(bytes, files.candidate.readBytes())
        assertArrayEquals(bytes, files.undoSource.database.readBytes())
        assertFalse(copy.files.database.exists())
        assertFalse(files.candidateTemporary.exists())

        cancelUndo(files, ops)

        assertArrayEquals(bytes, newest.database.readBytes())
        assertFalse(files.undoSource.database.exists())
        assertFalse(files.candidate.exists())
        assertEquals(listOf(SafetyCopyFacts(7, 5, NOW)), store.copies().map { it.facts })
    }

    private companion object {
        val NOW: Instant = Instant.fromEpochMilliseconds(1_793_000_000_000L)
        const val PAGE = 4096
    }
}
