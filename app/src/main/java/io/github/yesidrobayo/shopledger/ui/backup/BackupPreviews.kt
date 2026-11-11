package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.runtime.Composable
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusReport
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// Vistas previas del respaldo, con datos inventados: WebDAV al día con su lista, la nube del
// teléfono con la copia entregada y su revisión vencida, un día sin copia, un fallo y sin destino.

private val PREVIEW_NOW = Instant.parse("2026-11-02T15:00:00Z")

internal val PREVIEW_BACKUP_ZONE = BusinessTimeZone.of("Etc/GMT+5")

/** La última entrega de muestra, con su archivo y sin revisión todavía. */
internal val PREVIEW_HANDOFF = HandoffCopy(
    fileName = "copia-a.slcopy",
    sizeBytes = 2_412_000,
    writtenAt = PREVIEW_NOW - 12.minutes,
    generation = 48,
    documentUri = "content://example.documents/document/A",
    lastCheckAt = null,
)

internal fun previewOverview(
    status: BackupStatus,
    kind: BackupDestinationKind? = BackupDestinationKind.WEBDAV,
    pending: Long = 0,
    failure: BackupFailureCode? = null,
    notices: Set<BackupNotice> = emptySet(),
) = BackupOverview(
    report = BackupStatusReport(status, pending, savingSoon = true, notices = notices),
    destinationKind = kind,
    lastSuccessAt = PREVIEW_NOW - 12.minutes,
    failureCode = failure,
    staleReason = null,
    meteredPolicy = MeteredBackupPolicy.EVERY_60_MIN,
    nextMeteredUploadAt = null,
    lastFullReadAt = PREVIEW_NOW - 9.days,
    computedAt = PREVIEW_NOW,
)

internal fun previewCopies(slots: Boolean): BackupCopyList = BackupCopyList.Listed(
    listOf(
        BackupCopySummary(
            "a", PREVIEW_NOW - 12.minutes, 2_412_000, CopyOrigin.THIS_PHONE,
            if (slots) BackupSlot.A else null, 4_812, newest = true, pinned = false,
            withDifferences = false, restorable = true,
        ),
        BackupCopySummary(
            "b", PREVIEW_NOW - 1.days, 2_398_000, CopyOrigin.THIS_PHONE,
            if (slots) BackupSlot.B else null, 4_799, newest = false, pinned = !slots,
            withDifferences = false, restorable = true,
        ),
        BackupCopySummary(
            "c", PREVIEW_NOW - 4.days - 3.hours, 2_301_000, CopyOrigin.OTHER_PHONE,
            if (slots) BackupSlot.C else null, null, newest = false, pinned = false,
            withDifferences = true, restorable = true,
        ),
    ),
)

internal fun previewBackupState(
    overview: BackupOverview,
    copies: BackupCopyList? = previewCopies(
        overview.destinationKind == BackupDestinationKind.DOCUMENT_SLOTS,
    ),
): BackupUiState {
    val slots = overview.destinationKind == BackupDestinationKind.DOCUMENT_SLOTS
    val destination =
        when (overview.destinationKind) {
            BackupDestinationKind.WEBDAV -> BackupDestinationInfo.WebDav(
                "negocio@example.com",
                "dav.example.com",
            )

            BackupDestinationKind.DOCUMENT_SLOTS -> BackupDestinationInfo.Slots("Drive")

            null -> null
        }
    return BackupUiState(
        wording = BackupWording(overview, if (slots) "Drive" else null, PREVIEW_BACKUP_ZONE),
        destination = destination,
        copies = if (overview.destinationKind == null) BackupCopyList.NotConnected else copies,
        handoff = if (slots) PREVIEW_HANDOFF else null,
    )
}

@ThemePreviews
@Composable
private fun BackupUpToDatePreview() {
    PreviewTheme { BackupScreen(previewBackupState(previewOverview(BackupStatus.UP_TO_DATE)), {}) }
}

@ThemePreviews
@Composable
private fun BackupHandedOffPreview() {
    PreviewTheme {
        BackupScreen(
            previewBackupState(
                previewOverview(
                    BackupStatus.HANDED_OFF,
                    BackupDestinationKind.DOCUMENT_SLOTS,
                    notices = setOf(BackupNotice.HUMAN_CHECK_DUE),
                ),
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun BackupFailedPreview() {
    PreviewTheme {
        BackupScreen(
            previewBackupState(
                previewOverview(BackupStatus.FAILED, failure = BackupFailureCode.QUOTA_EXCEEDED),
                copies = BackupCopyList.Failed,
            ),
            {},
        )
    }
}

@ThemePreviews
@Composable
private fun BackupNotConnectedPreview() {
    PreviewTheme {
        BackupScreen(previewBackupState(previewOverview(BackupStatus.NOT_CONNECTED, kind = null)), {
        })
    }
}
