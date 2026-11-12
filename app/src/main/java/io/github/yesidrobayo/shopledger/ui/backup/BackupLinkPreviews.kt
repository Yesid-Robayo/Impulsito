package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictLookup
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictSides
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheetContent
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlin.time.Instant

// Vistas previas del enlace con el destino, con datos inventados: el conflicto con otro teléfono
// que tiene más registros, una ranura que cambió por fuera y la sesión de la nube cerrada.

/** Los dos lados de muestra: la nube de otro teléfono, con más registros que este. */
internal val PREVIEW_CONFLICT_SIDES =
    ConflictSides(
        kind = BackupConflictKind.OTHER_DEVICE,
        destinationKind = BackupDestinationKind.WEBDAV,
        localRecords = 4_650,
        localCatalogCount = 120,
        pendingChanges = 3,
        cloudCopyKey = "a",
        cloudCreatedAt = Instant.parse("2026-11-01T22:14:00Z"),
        cloudDeviceLabel = "Fabricante Modelo 5",
        cloudFromThisPhone = false,
        cloudRecords = 4_812,
        cloudCatalogCount = 124,
    )

@ThemePreviews
@Composable
private fun ConflictOtherPhonePreview() {
    PreviewTheme {
        val state =
            previewBackupState(previewOverview(BackupStatus.CONFLICT, pending = 3))
        AppBottomSheetContent(stringResource(R.string.backup_state_conflict)) {
            ConflictSheetContent(
                ConflictUi(lookup = ConflictLookup.Shown(PREVIEW_CONFLICT_SIDES)),
                state.wording,
                {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun ConflictSlotChangedPreview() {
    PreviewTheme {
        val state =
            previewBackupState(
                previewOverview(BackupStatus.CONFLICT, BackupDestinationKind.DOCUMENT_SLOTS),
            )
        AppBottomSheetContent(stringResource(R.string.backup_state_conflict)) {
            ConflictSheetContent(
                ConflictUi(
                    lookup =
                        ConflictLookup.NewSlotFileRequired(
                            slot = BackupSlot.B,
                            fileName = PreviewSamples.LONG_NAME,
                            threeBlocked = false,
                            localRecords = 4_650,
                            pendingChanges = 0,
                        ),
                ),
                state.wording,
                {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun ConflictLoadingAndOfflinePreview() {
    PreviewTheme {
        Column {
            ConflictSheetContent(ConflictUi(), null, {})
            ConflictSheetContent(
                ConflictUi(lookup = ConflictLookup.Unreachable, offline = true),
                null,
                {},
            )
        }
    }
}

@ThemePreviews
@Composable
private fun BackupSessionLostPreview() {
    PreviewTheme {
        val base =
            previewBackupState(
                previewOverview(BackupStatus.PERMISSION_LOST, BackupDestinationKind.DOCUMENT_SLOTS),
            )
        BackupScreen(
            base.copy(
                destination = BackupDestinationInfo.Slots("Drive", sessionRequired = true),
                wording = base.wording?.copy(sessionRequired = true),
            ),
            {},
        )
    }
}
