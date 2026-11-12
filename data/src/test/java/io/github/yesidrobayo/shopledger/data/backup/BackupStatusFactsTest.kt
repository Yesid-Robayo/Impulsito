package io.github.yesidrobayo.shopledger.data.backup

import androidx.work.WorkInfo
import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.work.BackupWorkNames
import io.github.yesidrobayo.shopledger.data.work.uploadWorkOf
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNetwork
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupUploadWork
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo que el estado del respaldo lee de la fila guardada y del teléfono, y qué ejecuciones cuentan
 * como un fallo de transporte. Un valor guardado que no se reconoce nunca se ve como algo bueno.
 */
class BackupStatusFactsTest {
    private val network =
        BackupNetwork(validated = true, metered = false, dataSaverRestricted = true)

    private fun facts(row: BackupStateEntity?, firstHandoffAt: Long? = null): BackupStatusFacts =
        backupStatusFacts(
            row = row,
            restoreInProgress = true,
            policyAccepted = false,
            databaseEmpty = true,
            uploadWork = BackupUploadWork.ENQUEUED,
            firstHandoffAt = firstHandoffAt,
            meteredPolicy = MeteredBackupPolicy.EVERY_30_MIN,
            network = network,
        )

    @Test
    fun `every stored column reaches the status with its instant in UTC milliseconds`() {
        val row =
            row().copy(
                changeSeq = 9,
                verifiedSeq = 4,
                firstUnsyncedAt = 1_000,
                lastSuccessAt = 2_000,
                lastUploadFinishedAt = 3_000,
                failureCode = "LOCAL_INVARIANT",
                lastHumanCheckAt = 4_000,
                invariantFailures = 3,
            )

        assertEquals(
            BackupStatusFacts(
                restoreInProgress = true,
                linkState = BackupLinkState.CONFLICT,
                destinationKind = BackupDestinationKind.DOCUMENT_SLOTS,
                policyAccepted = false,
                databaseEmpty = true,
                uploadWork = BackupUploadWork.ENQUEUED,
                changeSeq = 9,
                verifiedSeq = 4,
                firstUnsyncedAt = Instant.fromEpochMilliseconds(1_000),
                lastSuccessAt = Instant.fromEpochMilliseconds(2_000),
                lastUploadFinishedAt = Instant.fromEpochMilliseconds(3_000),
                failureCode = BackupFailureCode.LOCAL_INVARIANT,
                firstHandoffAt = Instant.fromEpochMilliseconds(5_000),
                lastHumanCheckAt = Instant.fromEpochMilliseconds(4_000),
                meteredPolicy = MeteredBackupPolicy.EVERY_30_MIN,
                network = network,
                invariantFailures = 3,
            ),
            facts(row, firstHandoffAt = 5_000),
        )
    }

    @Test
    fun `an unknown link or destination counts as not connected`() {
        assertEquals(BackupLinkState.NOT_CONNECTED, facts(row(link = "SOMETHING")).linkState)
        assertNull(facts(row(kind = "SOMEWHERE")).destinationKind)
        assertNull(facts(row(kind = null)).destinationKind)
    }

    @Test
    fun `an unknown failure is shown as an unexpected one, never as no failure`() {
        assertEquals(
            BackupFailureCode.UNEXPECTED,
            facts(row().copy(failureCode = "SOMETHING")).failureCode,
        )
        assertNull(facts(row()).failureCode)
    }

    @Test
    fun `without the row there is no backup to show`() {
        val missing = facts(null)

        assertEquals(BackupLinkState.NOT_CONNECTED, missing.linkState)
        assertNull(missing.destinationKind)
        assertEquals(0L, missing.changeSeq)
        assertEquals(0L, missing.verifiedSeq)
        assertNull(missing.firstUnsyncedAt)
        assertNull(missing.lastSuccessAt)
        assertNull(missing.lastUploadFinishedAt)
        assertNull(missing.failureCode)
        assertNull(missing.lastHumanCheckAt)
    }

    @Test
    fun `a running upload wins over a queued one`() {
        assertEquals(BackupUploadWork.NONE, uploadWorkOf(emptyList()))
        assertEquals(
            BackupUploadWork.NONE,
            uploadWorkOf(
                listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED),
            ),
        )
        assertEquals(BackupUploadWork.ENQUEUED, uploadWorkOf(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(BackupUploadWork.ENQUEUED, uploadWorkOf(listOf(WorkInfo.State.BLOCKED)))
        assertEquals(
            BackupUploadWork.RUNNING,
            uploadWorkOf(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)),
        )
    }

    @Test
    fun `the upload work names never change`() {
        assertEquals(listOf("backup-upload", "backup-upload-unmetered"), BackupWorkNames.UPLOADS)
    }

    @Test
    fun `every transport class is kept with its stored name`() {
        assertEquals(BackupFailureKind.NETWORK, failureKindOf(TransientKind.NETWORK))
        assertEquals(BackupFailureKind.TLS, failureKindOf(TransientKind.TLS))
        assertEquals(BackupFailureKind.HTTP_5XX, failureKindOf(TransientKind.SERVER_ERROR))
        assertEquals(BackupFailureKind.RATE_LIMIT, failureKindOf(TransientKind.RATE_LIMIT))
        for (kind in TransientKind.entries) {
            assertEquals(
                failureKindOf(kind),
                failureKindOf(BackupRun.Retry(RetryReason.TRANSIENT, transientKind = kind)),
            )
        }
    }

    @Test
    fun `running out of time counts as a network failure and stopping does not`() {
        fun interrupted(reason: InterruptReason?) =
            failureKindOf(BackupRun.Retry(RetryReason.INTERRUPTED, interruptReason = reason))

        assertEquals(BackupFailureKind.NETWORK, interrupted(InterruptReason.OUT_OF_TIME))
        assertNull(interrupted(InterruptReason.STOPPED))
        assertNull(interrupted(InterruptReason.METERED))
        assertNull(interrupted(null))
    }

    @Test
    fun `nothing else counts as a transport failure`() {
        val others = RetryReason.entries - setOf(RetryReason.TRANSIENT, RetryReason.INTERRUPTED)
        for (reason in others) {
            assertNull(reason.name, failureKindOf(BackupRun.Retry(reason)))
        }
        assertNull(failureKindOf(BackupRun.Retry(RetryReason.TRANSIENT)))
        assertNull(failureKindOf(BackupRun.Verified(1)))
        assertNull(failureKindOf(BackupRun.Skipped(SkipReason.NOTHING_PENDING)))
        assertNull(
            failureKindOf(BackupRun.Stopped(StopCause.Conflict(BackupConflictKind.OTHER_DEVICE))),
        )
    }

    private fun row(link: String = "CONFLICT", kind: String? = "DOCUMENT_SLOTS") =
        BackupStateEntity(
            id = 1,
            changeSeq = 0,
            verifiedSeq = 0,
            firstUnsyncedAt = null,
            linkState = link,
            failureCode = null,
            consecutiveFailures = 0,
            lastFailureKind = null,
            invariantFailures = 0,
            lastAttemptAt = null,
            lastSuccessAt = null,
            lastUploadFinishedAt = null,
            lastStaleNoticeAt = null,
            lastDrillAt = null,
            lastDrillResult = null,
            lastFullVerifyAt = null,
            lastHumanCheckAt = null,
            humanCheckGeneration = null,
            destinationKind = kind,
            destinationAccount = null,
            destinationId = "huella",
            destinationFolder = null,
            headGeneration = 0,
            headDeviceId = null,
            headFileRef = null,
            shrinkAcceptedGeneration = null,
            pendingGeneration = null,
            pendingSeq = null,
            pendingSha256 = null,
            pendingSizeBytes = null,
            conflictKind = null,
            conflictGeneration = null,
            conflictDeviceId = null,
            conflictFileRef = null,
            copyGeneration = null,
            copyDeviceId = null,
            copyCreatedAt = null,
        )
}
