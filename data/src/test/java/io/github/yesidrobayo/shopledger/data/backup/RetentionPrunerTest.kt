package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination
import io.github.yesidrobayo.shopledger.data.destination.FakeBackupDestination.Operation
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogLevel
import io.github.yesidrobayo.shopledger.domain.port.LogLine
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La poda de WebDAV sobre el destino falso: lista otra vez, poda lo que dice la regla, relee antes
 * de cada borrado y no poda nada con una lista en la que falta la copia nueva.
 */
class RetentionPrunerTest {
    private val destination = FakeBackupDestination()
    private var marks = RetentionMarks.NONE
    private var forced = 0
    private val forgotten = mutableListOf<Set<Long>>()
    private val pruner =
        RetentionPruner(
            deviceId = { OWN },
            log = SilentLog,
            marks = { marks },
            forceUpload = { forced++ },
            forgetDamaged = { forgotten += it },
        )

    @Test
    fun aMonthOfDailyCopiesWithAFakeClockNeverKeepsFewerThanTheRule() = runBlocking {
        var generation = 0L
        for (day in 0 until 30) {
            // Tres copias al día, la última de cada día a las 20:00 UTC.
            for (hour in listOf(9, 14, 20)) {
                generation++
                val copy = put(generation, at(day, hour))
                assertPruned(prune(copy))
            }
            val left = generations()
            assertTrue(generation in left)
            val expectedDays = minOf(day + 1, 7)
            assertEquals("día $day", expectedDays, left.size)
            // La más reciente y la última de cada uno de los días anteriores.
            assertEquals(
                (0 until expectedDays).map {
                    generation - 3 * it
                },
                left.sortedDescending(),
            )
        }
        assertTrue(destination.calls.contains(Operation.READ_META))
    }

    @Test
    fun aListingWithoutTheNewCopyPrunesNothing() = runBlocking {
        put(1, at(0, 9))
        put(2, at(1, 9))
        val missing = ConfirmedCopy(3, CopyRef(nameOf(3, at(2, 9)).fileName))

        val report = pruner.prune(destination, missing, TransferControl.Unbounded).value()

        assertEquals(PruneResult.INCOMPLETE_LISTING, report.result)
        assertEquals(listOf(1L, 2L), generations())
        assertEquals(0, forced)
    }

    @Test
    fun aNewCopyWithAnotherSizeIsNotCountedAndForcesAnUpload() = runBlocking {
        put(1, at(0, 9))
        val confirmed = put(2, at(0, 10))
        destination.putFile(confirmed.ref.value, ByteArray(7))

        val report = pruner.prune(destination, confirmed, TransferControl.Unbounded).value()

        assertEquals(PruneResult.INCOMPLETE_LISTING, report.result)
        assertEquals(1, forced)
        assertEquals(listOf(1L, 2L), generations())
    }

    @Test
    fun aCopyWithAnotherSizeNeitherCountsNorIsPruned() = runBlocking {
        val shrunk = put(1, at(0, 9))
        destination.putFile(shrunk.ref.value, ByteArray(7))
        val confirmed = put(2, at(0, 10))

        assertPruned(prune(confirmed))

        // No ocupa el día de la 2, y la poda no la toca: no tiene metadatos en que confiar.
        assertEquals(listOf(1L, 2L), generations())
    }

    @Test
    fun aCopyWithoutValidMetadataIsNeverPruned() = runBlocking {
        val bare = put(1, at(0, 9))
        destination.removeFile(nameOf(1, at(0, 9)).metaFileName)
        destination.putFile(nameOf(2, at(0, 10)).fileName, ByteArray(64))
        destination.putFile(nameOf(2, at(0, 10)).metaFileName, "{}".encodeToByteArray())
        val confirmed = put(3, at(0, 11))

        assertPruned(prune(confirmed))

        assertTrue(destination.fileNames.contains(bare.ref.value))
        assertTrue(destination.fileNames.contains(nameOf(2, at(0, 10)).fileName))
    }

    @Test
    fun aCopyPinnedAfterTheListingIsNotDeleted() = runBlocking {
        put(1, at(0, 9))
        val confirmed = put(2, at(0, 10))
        // La persona la fija entre la lista y el borrado.
        val pinning =
            object : BackupDestination by destination {
                override suspend fun readMeta(ref: CopyRef, control: TransferControl) = run {
                    putMeta(1, at(0, 9), pinned = true)
                    destination.readMeta(ref, control)
                }
            }

        val report = pruner.prune(pinning, confirmed, TransferControl.Unbounded).value()

        assertEquals(PruneReport(PruneResult.PRUNED, 0), report)
        assertEquals(listOf(1L, 2L), generations())
    }

    @Test
    fun anUnreadableMetadataBeforeDeletingKeepsTheCopy() = runBlocking {
        put(1, at(0, 9))
        val confirmed = put(2, at(0, 10))
        destination.failNext(
            Operation.READ_META,
            DestinationFailure.Transient(TransientKind.NETWORK),
        )

        assertEquals(PruneReport(PruneResult.PRUNED, 0), prune(confirmed))
        assertEquals(listOf(1L, 2L), generations())
    }

    @Test
    fun aRejectedGenerationIsNotCountedAndADamagedOneDoesNotTakeADay() = runBlocking {
        put(1, at(0, 9))
        put(2, at(1, 9))
        put(3, at(1, 10))
        val confirmed = put(4, at(2, 9))
        marks = RetentionMarks(rejectedGenerations = setOf(3), damagedGenerations = setOf(2))

        assertPruned(prune(confirmed))

        // La 3 no cuenta ni se poda; la 2, dañada, no ocupa su día, y sale.
        assertEquals(listOf(1L, 3L, 4L), generations())
        assertEquals(emptyList<Set<Long>>(), forgotten)

        // En la siguiente poda la 2 ya no está: su marca de dañada sale.
        val next = put(5, at(3, 9))
        prune(next)
        assertEquals(listOf(setOf(2L)), forgotten)
    }

    @Test
    fun aFatalHeadKeepsTheNewestCopyThatRestores() = runBlocking {
        put(1, at(0, 9))
        put(2, at(0, 10), valid = false)
        put(3, at(0, 11), valid = false)
        val confirmed = put(4, at(0, 12), valid = false, fatal = true)

        assertPruned(prune(confirmed))

        // La 3 es reparable y la más reciente que se restaura: se queda aunque no ocupe un día. La
        // 1, válida, tiene el día; la 2, inválida y más vieja, sale.
        assertEquals(listOf(1L, 3L, 4L), generations())
    }

    @Test
    fun theLastFullyReadCopyStays() = runBlocking {
        put(1, at(0, 9))
        put(2, at(0, 10))
        val confirmed = put(3, at(0, 11))
        marks = RetentionMarks(fullVerifyGeneration = 1)

        assertPruned(prune(confirmed))

        assertEquals(listOf(1L, 3L), generations())
    }

    @Test
    fun aForeignCopyAsNewAsTheNewOnePrunesNothing() = runBlocking {
        put(1, at(0, 9))
        put(2, at(0, 10), device = OTHER)
        val confirmed = put(2, at(0, 11))

        assertEquals(PruneResult.FOREIGN_AHEAD, prune(confirmed).result)
        assertEquals(3, destination.fileNames.count { BackupFileName.parse(it) != null })
    }

    @Test
    fun theRecordsRuleStopsThePruneWithoutDeletingAnything() = runBlocking {
        put(1, at(0, 9), records = 900)
        val confirmed = put(2, at(0, 10), records = 10)

        assertEquals(PruneResult.BLOCKED_BY_RECORDS, prune(confirmed).result)
        assertEquals(listOf(1L, 2L), generations())
    }

    @Test
    fun atMostTheLimitIsDeletedPerRunOldestFirst() = runBlocking {
        val limited =
            RetentionPruner(deviceId = { OWN }, log = SilentLog, maxDeletes = 2)
        for (generation in 1L..5L) put(generation, at(0, generation.toInt()))
        val confirmed = put(6, at(0, 6))

        assertEquals(
            PruneReport(PruneResult.PRUNED, 2),
            limited.prune(destination, confirmed, TransferControl.Unbounded).value(),
        )

        assertEquals(listOf(3L, 4L, 5L, 6L), generations())
    }

    @Test
    fun anOrphanMetadataFromACutPruneIsDeleted() = runBlocking {
        val orphan = nameOf(1, at(0, 9))
        destination.putFile(orphan.metaFileName, SlMetaCodec.encode(meta(1, OWN, 64, 10)))
        val confirmed = put(2, at(0, 10))

        assertEquals(PruneReport(PruneResult.PRUNED, 1), prune(confirmed))

        assertFalse(destination.fileNames.contains(orphan.metaFileName))
    }

    @Test
    fun aDeleteThatFailsIsReportedAndTheRestWaits() = runBlocking {
        put(1, at(0, 9))
        val confirmed = put(2, at(0, 10))
        val failure = DestinationFailure.Transient(TransientKind.NETWORK)
        destination.failNext(Operation.DELETE, failure)

        assertEquals(
            DestinationResult.Failure(failure),
            pruner.prune(destination, confirmed, TransferControl.Unbounded),
        )
        assertEquals(listOf(1L, 2L), generations())
    }

    @Test
    fun withSlotsThereIsNoPrune() = runBlocking {
        val slots = FakeBackupDestination(BackupDestinationKind.DOCUMENT_SLOTS)

        val report =
            pruner.prune(slots, ConfirmedCopy(1, CopyRef("A")), TransferControl.Unbounded).value()

        assertEquals(PruneReport(PruneResult.NOT_APPLICABLE, 0), report)
        assertEquals(emptyList<Operation>(), slots.calls)
    }

    @Test
    fun aListingThatFailsIsReported() = runBlocking {
        val failure = DestinationFailure.Incomplete
        destination.failNext(Operation.LIST, failure)

        assertEquals(
            DestinationResult.Failure(failure),
            pruner.prune(destination, ConfirmedCopy(1, CopyRef("x")), TransferControl.Unbounded),
        )
    }

    private suspend fun prune(confirmed: ConfirmedCopy): PruneReport =
        pruner.prune(destination, confirmed, TransferControl.Unbounded).value()

    @Test
    fun copiesOfAnotherKeyStayUntilAValidCopyWithTheCurrentKeyExists() = runBlocking {
        // Diez días de copias cifradas con la clave anterior.
        for (day in 0 until 10) putEncrypted(day + 1L, at(day, 9), OLD_KEY)
        marks = RetentionMarks.NONE.copy(currentKeyId = NEW_KEY)

        // La primera copia con la clave nueva no es válida: ninguna de la anterior se poda.
        val invalid = putEncrypted(11, at(10, 9), NEW_KEY, valid = false)
        assertPruned(prune(invalid))
        assertEquals((1L..11L).toList(), generations())

        // Con una válida con la clave nueva, las de la anterior siguen la regla de siempre.
        val valid = putEncrypted(12, at(10, 10), NEW_KEY)
        assertPruned(prune(valid))
        assertTrue(generations().size < 12)
        assertTrue(12L in generations())
    }

    @Test
    fun withEncryptionOffEncryptedCopiesStayUntilAValidPlainCopyExists() = runBlocking {
        for (day in 0 until 10) putEncrypted(day + 1L, at(day, 9), OLD_KEY)
        marks = RetentionMarks.NONE

        val invalid = put(11, at(10, 9), valid = false)
        assertPruned(prune(invalid))
        assertEquals((1L..11L).toList(), generations())

        val valid = put(12, at(10, 10))
        assertPruned(prune(valid))
        assertTrue(generations().size < 12)
    }

    private fun putEncrypted(
        generation: Long,
        at: CopyInstant,
        key: String,
        valid: Boolean = true,
    ): ConfirmedCopy {
        val name = BackupFileName(generation, 1, at, OWN.take(8), BackupFileFormat.ENCRYPTED)
        destination.putFile(name.fileName, ByteArray(64))
        val encrypted = meta(generation, OWN, 64, 10, valid = valid).copy(format = 2, keyId = key)
        destination.putFile(name.metaFileName, SlMetaCodec.encode(encrypted))
        return ConfirmedCopy(generation, CopyRef(name.fileName))
    }

    private fun assertPruned(report: PruneReport) {
        assertEquals(PruneResult.PRUNED, report.result)
    }

    private fun generations(): List<Long> =
        destination.fileNames.mapNotNull { BackupFileName.parse(it)?.generation }.sorted()

    private fun at(day: Int, hour: Int): CopyInstant = requireNotNull(
        CopyInstant.of(Instant.fromEpochSeconds(START + day * 86_400L + hour * 3_600L)),
    )

    private fun nameOf(generation: Long, at: CopyInstant, device: String = OWN) = BackupFileName(
        generation,
        1,
        at,
        device.take(8),
        BackupFileFormat.GZIP,
    )

    private fun meta(
        generation: Long,
        device: String,
        size: Long,
        records: Long,
        pinned: Boolean = false,
        valid: Boolean = true,
        fatal: Boolean = false,
    ) = SlMeta(
        format = 1,
        keyId = null,
        deviceId = device,
        deviceLabel = null,
        generation = generation,
        schemaVersion = 1,
        appVersion = null,
        sha256 = "ab".repeat(32),
        rawSize = 4_096,
        fileSize = size,
        records = records,
        catalogCount = 3,
        valid = valid,
        fatal = fatal,
        pinned = pinned,
    )

    private fun putMeta(
        generation: Long,
        at: CopyInstant,
        pinned: Boolean = false,
        records: Long = 10,
    ) {
        destination.putFile(
            nameOf(generation, at).metaFileName,
            SlMetaCodec.encode(meta(generation, OWN, 64, records, pinned)),
        )
    }

    private fun put(
        generation: Long,
        at: CopyInstant,
        device: String = OWN,
        records: Long = 10,
        valid: Boolean = true,
        fatal: Boolean = false,
    ): ConfirmedCopy {
        val name = nameOf(generation, at, device)
        destination.putFile(name.fileName, ByteArray(64))
        destination.putFile(
            name.metaFileName,
            SlMetaCodec.encode(meta(generation, device, 64, records, valid = valid, fatal = fatal)),
        )
        return ConfirmedCopy(generation, CopyRef(name.fileName))
    }

    private fun <T> DestinationResult<T>.value(): T = when (this) {
        is DestinationResult.Success -> value
        is DestinationResult.Failure -> throw AssertionError("Falló: $failure")
    }

    private object SilentLog : EventLog {
        override fun write(level: LogLevel, line: LogLine) = Unit
    }

    private companion object {
        const val OWN = "7f3a9c1e-0b2d-4c6e-8f10-23456789abcd"
        const val OTHER = "0c4d2e7a-91b3-4f5e-a6d7-8899aabbccdd"

        /** 2026-11-01T00:00Z. */
        const val START = 1_793_491_200L

        /** Dos `key_id` inventados: el anterior y el vigente. */
        const val OLD_KEY = "0011223344556677"
        const val NEW_KEY = "8899aabbccddeeff"
    }
}
