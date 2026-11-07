package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.inspectWithoutRoom
import io.github.yesidrobayo.shopledger.data.seed.seedConfigOf
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import java.io.File
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
 * Las copias que se deben aceptar: una base sana del generador, de cualquier moneda, decimales, zona
 * y formato regional, con o sin metadatos que comparar, y una vacía solo cuando la persona lo
 * decidió. El validador nunca escribe en la candidata. Todos los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class CandidateValidatorTest {
    @Before
    fun clean() {
        CandidateFixtures.clean()
    }

    @After
    fun cleanUp() {
        CandidateFixtures.clean()
    }

    @Test
    fun aHealthyCopyIsRestorableWithoutAnyFinding() {
        val candidate = CandidateFixtures.copyOf("healthy")
        assertClean("copia sana", candidate)

        // Cada consulta de invariantes da cero filas sobre datos sanos.
        inspectWithoutRoom(candidate) { connection ->
            for (query in InvariantQueries.MODEL) {
                assertEquals(query.label, 0L, connection.countRows(query.sql))
            }
        }
    }

    @Test
    fun copiesOfEveryCurrencyZoneAndFormatAreAccepted() {
        val rows = RegionalMatrix.ROWS.filter { seedConfigOf(it) != null }
        assertTrue("La matriz no tiene filas con negocio", rows.size >= 4)
        for ((index, row) in rows.withIndex()) {
            val case = "${row.currencyCode} ${row.region?.formatLocaleTag}"
            assertClean(
                case,
                CandidateFixtures.copyOf("regional-$index", CandidateFixtures.seed(row)),
            )
        }
    }

    @Test
    fun theValidatorNeverWritesToTheCandidate() {
        val candidate = CandidateFixtures.copyOf("read-only")
        val before = candidate.readBytes()

        validate(candidate)

        assertArrayEquals(before, candidate.readBytes())
        val folder = requireNotNull(candidate.parentFile)
        assertEquals(listOf("candidate.db"), folder.list().orEmpty().toList())
    }

    @Test
    fun leftoverJournalFilesBesideTheCandidateAreRemovedBeforeOpening() {
        val candidate = CandidateFixtures.copyOf("side-files")
        // Un diario ajeno al lado de la candidata se aplicaría al abrirla: nunca se usa.
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            File(candidate.path + suffix).writeBytes(ByteArray(4096) { 1 })
        }

        assertClean("con diarios al lado", candidate)
        val folder = requireNotNull(candidate.parentFile)
        assertEquals(listOf("candidate.db"), folder.list().orEmpty().toList())
    }

    @Test
    fun theCopyMustMatchItsMetadataWhenThereAreAny() {
        val candidate = CandidateFixtures.copyOf("sealed")
        corrupt(candidate) {
            execSQL(
                "UPDATE backup_state SET copy_device_id = 'device-a', copy_generation = 42, " +
                    "copy_created_at = 1",
            )
        }
        val sealed = ExpectedCopy(deviceId = "device-a", generation = 42, schemaVersion = 1)

        assertEquals(
            CandidateValidation.Checked(1, CandidateVerdict.Restorable(emptyList())),
            validate(candidate, CandidateContext(expectedCopy = sealed)),
        )
        for (
        other in
        listOf(
            sealed.copy(deviceId = "device-b"),
            sealed.copy(generation = 43),
            sealed.copy(schemaVersion = 2),
        )
        ) {
            assertEquals(
                "$other",
                mapOf(CandidateCheck.COPY_METADATA_MATCH to 1L),
                validate(candidate, CandidateContext(expectedCopy = other)).findings(),
            )
        }
        // Sin metadatos, la misma copia no tiene con qué compararse.
        assertClean("sin metadatos", candidate)
    }

    @Test
    fun aCopyWithoutSealFailsAgainstMetadata() {
        val candidate = CandidateFixtures.copyOf("unsealed")
        val expected = ExpectedCopy(deviceId = "device-a", generation = 1, schemaVersion = 1)

        val result = validate(candidate, CandidateContext(expectedCopy = expected))

        assertEquals(mapOf(CandidateCheck.COPY_METADATA_MATCH to 1L), result.findings())
        assertTrue((result as CandidateValidation.Checked).verdict is CandidateVerdict.Rejected)
    }

    @Test
    fun anEmptyCopyOnlyReplacesDataAfterAnExplicitDecision() {
        val empty = CandidateFixtures.emptyCopy("empty")

        assertClean("vacía al crear una copia", empty)
        assertEquals(
            mapOf(CandidateCheck.EMPTY_OVER_DATA to 1L),
            validate(empty, CandidateContext(activeHasData = true)).findings(),
        )
        assertEquals(
            emptyMap<CandidateCheck, Long>(),
            validate(
                empty,
                CandidateContext(activeHasData = true, emptyCopyConfirmed = true),
            ).findings(),
        )
        // Una copia con datos reemplaza a otra con datos sin preguntar nada de esto.
        val full = CandidateFixtures.copyOf("full")
        assertEquals(
            emptyMap<CandidateCheck, Long>(),
            validate(full, CandidateContext(activeHasData = true)).findings(),
        )
    }

    @Test
    fun aCompressedCopyGoesThroughTheWholeWayAndIsAccepted() = runBlocking {
        val snapshot = CandidateFixtures.copyOf("packed-source")
        val packed = File(CandidateFixtures.root, "incoming/incoming.db.gz")
        requireNotNull(packed.parentFile).mkdirs()
        val result = BackupPackager(Dispatchers.IO).compress(snapshot, packed)
        val sha256 = (result as PackageResult.Packed).fileSha256
        val target = File(CandidateFixtures.root, "restore/candidate/candidate.db")

        val unpacked = CandidateUnpacker(Dispatchers.IO).unpack(packed, target, sha256)

        assertTrue("$unpacked", unpacked is UnpackResult.Unpacked)
        assertClean("copia comprimida y descomprimida", target)
        assertFalse("La instantánea se borra al comprimir", snapshot.exists())
    }
}
