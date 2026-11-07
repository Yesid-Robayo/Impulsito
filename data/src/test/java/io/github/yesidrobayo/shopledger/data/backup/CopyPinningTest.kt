package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fijar y liberar copias de WebDAV sobre el destino falso: el reemplazo de metadatos, el máximo de
 * tres, la liberación solo a pedido y que una copia fijada sobrevive a la poda.
 */
class CopyPinningTest {
    private val destination = FakeBackupDestination()
    private val control = TransferControl.Unbounded
    private val pruner = RetentionPruner(deviceId = { OWN }, log = SilentLog)

    @Test
    fun pinningMarksTheCopyAndKeepsKeysThisVersionDoesNotKnow() = runBlocking {
        val ref = put(5, at(0, 9))
        val name = requireNotNull(BackupFileName.parse(ref.ref.value))
        // Una versión más nueva de la app agregó una clave: fijar no la borra.
        val original = requireNotNull(destination.file(name.metaFileName)).decodeToString()
        destination.putFile(
            name.metaFileName,
            original.replace("{", "{\"sl_future\":\"x\",").encodeToByteArray(),
        )

        assertEquals(PinOutcome.Pinned, pinOf(ref))

        val after = requireNotNull(destination.file(name.metaFileName))
        assertTrue(metaOf(ref)?.pinned == true)
        assertTrue(after.decodeToString().contains("\"sl_future\":\"x\""))
        // Fijar otra vez una copia fijada no la reescribe.
        assertEquals(PinOutcome.Pinned, pinOf(ref))
        assertEquals(1, destination.calls.count { it == Operation.REPLACE_META })
    }

    @Test
    fun atMostThreeCopiesArePinnedAndNoneIsFreedByItself() = runBlocking {
        val refs = (1L..4L).map { put(it, at(it.toInt(), 9)) }
        assertEquals(
            PinOutcome.Pinned,
            CopyPinning.pin(
                destination,
                refs.take(3).map {
                    it.ref
                },
                control,
            ),
        )

        // Una cuarta no entra, y no se fija ninguna de las pedidas.
        assertEquals(
            PinOutcome.TooManyPinned,
            CopyPinning.pin(
                destination,
                refs.drop(3).map {
                    it.ref
                },
                control,
            ),
        )
        assertEquals(false, metaOf(refs[3])?.pinned)
        assertEquals(listOf(true, true, true), refs.take(3).map { metaOf(it)?.pinned })

        // Solo liberando una, a pedido, entra la cuarta.
        assertEquals(
            DestinationResult.Success(Unit),
            CopyPinning.release(destination, refs[0].ref, control),
        )
        assertEquals(false, metaOf(refs[0])?.pinned)
        assertEquals(
            PinOutcome.Pinned,
            CopyPinning.pin(
                destination,
                refs.drop(3).map {
                    it.ref
                },
                control,
            ),
        )
    }

    @Test
    fun aPinCountsOnlyAfterRereadingTheMetadata() = runBlocking {
        val ref = put(5, at(0, 9))
        // El servidor borra los anteriores y falla al mover, dos veces seguidas.
        destination.crashReplaceMeta = 2

        val outcome = pinOf(ref)

        assertEquals(PinOutcome.Failed(DestinationFailure.Incomplete), outcome)
        // La copia quedó sin metadatos: no cuenta ni se poda, así que hay más protección, no menos.
        assertNull(metaOf(ref))
        val confirmed = put(6, at(0, 10))
        prune(confirmed)
        assertTrue(destination.fileNames.contains(ref.ref.value))
    }

    @Test
    fun aSingleCutWhileReplacingIsRepeatedWithTheSameBytes() = runBlocking {
        val ref = put(5, at(0, 9))
        destination.crashReplaceMeta = 1

        assertEquals(PinOutcome.Pinned, pinOf(ref))
        assertEquals(true, metaOf(ref)?.pinned)
    }

    @Test
    fun aFailingDestinationPinsNothing() = runBlocking {
        val ref = put(5, at(0, 9))
        val failure = DestinationFailure.Transient(TransientKind.NETWORK)
        destination.failNext(Operation.LIST, failure)
        assertEquals(PinOutcome.Failed(failure), pinOf(ref))
        destination.failNext(Operation.READ_META, failure)
        assertEquals(PinOutcome.Failed(failure), pinOf(ref))
        assertEquals(false, metaOf(ref)?.pinned)
    }

    @Test
    fun aCopyWithoutValidMetadataIsNotPinned() = runBlocking {
        val ref = put(5, at(0, 9))
        val name = requireNotNull(BackupFileName.parse(ref.ref.value))
        destination.putFile(name.metaFileName, "{}".encodeToByteArray())

        assertEquals(PinOutcome.Failed(DestinationFailure.Incomplete), pinOf(ref))
        assertFalse(destination.calls.contains(Operation.REPLACE_META))
    }

    @Test
    fun slotsAreNeverPinned() = runBlocking {
        val slots = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)
        assertEquals(PinOutcome.Unavailable, CopyPinning.pin(slots, listOf(CopyRef("A")), control))
        assertTrue(CopyPinning.release(slots, CopyRef("A"), control) is DestinationResult.Failure)
        assertEquals(emptyList<Operation>(), slots.calls)
    }

    @Test
    fun aPinnedCopySurvivesTenDaysOfNewCopies() = runBlocking {
        val pinned = put(1, at(0, 9))
        pinOf(pinned)
        var generation = 1L
        for (day in 1..10) {
            for (hour in listOf(9, 18)) {
                generation++
                prune(put(generation, at(day, hour)))
            }
        }

        assertTrue(destination.fileNames.contains(pinned.ref.value))
        // Además de la fijada, la más reciente y una por cada uno de los últimos 7 días.
        assertEquals(1 + 7, generations().size)
    }

    @Test
    fun scenarioBTheHeadPinnedBeforeTheMistakenRestoreSurvives() = runBlocking {
        // 122 y 121 de hoy y 120 de ayer, con más datos que la base restaurada desde la 115.
        put(120, at(-1, 9), records = 980)
        put(121, at(0, 9), records = 990)
        val head = put(122, at(0, 10), records = 1_000)
        // Restaurar una copia que no es la cabeza fija la cabeza antes de reemplazar.
        assertEquals(PinOutcome.Pinned, pinOf(head))

        // Se vende y sube la 123, con los datos de la 115, y luego 10 días de copias.
        assertEquals(PruneResult.PRUNED, prune(put(123, at(0, 11), records = 700)).result)
        assertTrue(destination.fileNames.contains(head.ref.value))
        var generation = 123L
        for (day in 1..10) {
            generation++
            prune(put(generation, at(day, 9), records = 700L + day))
        }

        assertTrue(destination.fileNames.contains(head.ref.value))
        assertEquals(true, metaOf(head)?.pinned)
    }

    @Test
    fun scenarioBWithoutThePinStopsThePruneInstead() = runBlocking {
        put(121, at(0, 9), records = 990)
        val head = put(122, at(0, 10), records = 1_000)

        val report = prune(put(123, at(0, 11), records = 700))

        // Un error de la app no fijó la 122: no se poda nada, y la persona lo ve.
        assertEquals(PruneResult.BLOCKED_BY_RECORDS, report.result)
        assertTrue(destination.fileNames.contains(head.ref.value))
        assertEquals(3, generations().size)
    }

    private suspend fun prune(confirmed: ConfirmedCopy): PruneReport =
        when (val result = pruner.prune(destination, confirmed, control)) {
            is DestinationResult.Success -> result.value
            is DestinationResult.Failure -> throw AssertionError("Falló: ${result.failure}")
        }

    private fun put(generation: Long, at: CopyInstant, records: Long = 10): ConfirmedCopy {
        val name = BackupFileName(generation, 1, at, OWN.take(8), BackupFileFormat.GZIP)
        destination.putFile(name.fileName, ByteArray(64))
        destination.putFile(
            name.metaFileName,
            SlMetaCodec.encode(
                SlMeta(
                    format = 1,
                    keyId = null,
                    deviceId = OWN,
                    deviceLabel = null,
                    generation = generation,
                    schemaVersion = 1,
                    appVersion = null,
                    sha256 = "ab".repeat(32),
                    rawSize = 4_096,
                    fileSize = 64,
                    records = records,
                    catalogCount = 3,
                    valid = true,
                ),
            ),
        )
        return ConfirmedCopy(generation, CopyRef(name.fileName))
    }

    private suspend fun pinOf(ref: ConfirmedCopy): PinOutcome =
        CopyPinning.pin(destination, listOf(ref.ref), control)

    private fun metaOf(ref: CopyRef): SlMeta? {
        val name = requireNotNull(BackupFileName.parse(ref.value))
        val bytes = destination.file(name.metaFileName) ?: return null
        return (SlMetaCodec.decode(bytes, name.identity()) as? CopyMetadata.Valid)?.meta
    }

    private fun metaOf(copy: ConfirmedCopy): SlMeta? = metaOf(copy.ref)

    private fun generations(): List<Long> =
        destination.fileNames.mapNotNull { BackupFileName.parse(it)?.generation }.sorted()

    private fun at(day: Int, hour: Int): CopyInstant = requireNotNull(
        CopyInstant.of(Instant.fromEpochSeconds(START + day * 86_400L + hour * 3_600L)),
    )

    private object SilentLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }

    private companion object {
        const val OWN = "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd"

        /** 2026-11-01T00:00Z. */
        const val START = 1_793_491_200L
    }
}
