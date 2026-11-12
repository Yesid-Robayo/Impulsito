package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictLookup
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictSides
import io.github.yesidrobayo.shopledger.domain.model.backup.newFileName
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Lo que se abre encima de la pantalla del respaldo para el enlace con el destino: el diálogo de
 * desconectar, la hoja del conflicto y la confirmación de conservar este teléfono.
 */
@Composable
fun BackupLinkLayer(uiState: BackupUiState, onAction: (BackupAction) -> Unit) {
    if (uiState.disconnect != DisconnectUi.HIDDEN) {
        DisconnectDialog(uiState.destination, uiState.wording?.providerLabel, onAction)
    }
    val conflict = uiState.conflict ?: return
    ConflictSheet(conflict, uiState.wording, onAction)
    when (val confirm = conflict.confirm) {
        is KeepConfirm.Sides -> KeepDialog(confirm, uiState.wording, onAction)
        is KeepConfirm.NewFile -> NewFileDialog(confirm.need, uiState.wording, onAction)
        null -> Unit
    }
}

/**
 * `¿Desconectar {provider}?`: los datos y las copias quedan intactos, y con WebDAV la contraseña
 * sigue valiendo en el servicio, que es donde se anula.
 */
@Composable
private fun DisconnectDialog(
    destination: BackupDestinationInfo?,
    providerLabel: String?,
    onAction: (BackupAction) -> Unit,
) {
    val name =
        when (destination) {
            is BackupDestinationInfo.WebDav -> destination.host ?: destination.account
            is BackupDestinationInfo.Slots -> destination.providerLabel ?: providerLabel
            null -> providerLabel
        } ?: NO_VALUE_TEXT
    val body = stringResource(R.string.dialog_disconnect_destination_body)
    ConfirmDialog(
        title = stringResource(R.string.dialog_disconnect_destination_title, name),
        body =
            if (destination is BackupDestinationInfo.WebDav) {
                body + PARAGRAPH + stringResource(R.string.dialog_disconnect_webdav_note)
            } else {
                body
            },
        confirmLabel = stringResource(R.string.backup_action_disconnect),
        onConfirm = { onAction(BackupAction.ConfirmDisconnect) },
        onDismiss = { onAction(BackupAction.DismissDisconnect) },
        variant = ConfirmVariant.DESTRUCTIVE,
        sensitive = true,
    )
}

/**
 * La hoja del conflicto: qué pasó, los dos lados y las tres opciones. Cerrarla es decidir después:
 * nada cambia y el aviso sigue a la vista.
 */
@Composable
private fun ConflictSheet(
    conflict: ConflictUi,
    wording: BackupWording?,
    onAction: (BackupAction) -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = { onAction(BackupAction.DecideLater) },
        title = stringResource(R.string.backup_state_conflict),
    ) { ConflictSheetContent(conflict, wording, onAction) }
}

/** El cuerpo de la hoja del conflicto, aparte para poder verlo sin abrir la hoja. */
@Composable
internal fun ConflictSheetContent(
    conflict: ConflictUi,
    wording: BackupWording?,
    onAction: (BackupAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16)) {
        when (val lookup = conflict.lookup) {
            null ->
                DelayedSkeleton {
                    SkeletonBlock(
                        Modifier.fillMaxWidth().heightIn(min = AppTheme.spacing.s64 * 2),
                    )
                }

            ConflictLookup.Unreachable ->
                EmptyStateInline(
                    title =
                        stringResource(
                            if (conflict.offline) {
                                R.string.backup_list_no_internet
                            } else {
                                R.string.backup_list_error
                            },
                        ),
                    action = {
                        InlineTextButton(
                            stringResource(R.string.action_retry),
                            { onAction(BackupAction.RetryConflict) },
                        )
                    },
                )

            // Ya no hay conflicto: la hoja se cierra sola.
            ConflictLookup.NotInConflict -> Unit

            // Con la base vacía no hay nada que conservar: solo restaurar o decidir después.
            ConflictLookup.EmptyDatabase -> {
                Text(
                    stringResource(R.string.backup_conflict_detail),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Options(conflict, keepBody = null, restoreKey = null, onAction = onAction)
            }

            is ConflictLookup.Shown -> SidesBlock(conflict, lookup.sides, wording, onAction)

            is ConflictLookup.NewSlotFileRequired ->
                NewFileBlock(conflict, lookup, wording, onAction)
        }
    }
}

/** Los dos lados, con el cuerpo de su clase de conflicto, y las opciones. */
@Composable
private fun SidesBlock(
    conflict: ConflictUi,
    sides: ConflictSides,
    wording: BackupWording?,
    onAction: (BackupAction) -> Unit,
) {
    val date = cloudDate(sides, wording)
    val app = stringResource(R.string.app_name)
    Text(
        when (sides.kind) {
            BackupConflictKind.OTHER_DEVICE ->
                stringResource(R.string.conflict_body_other_device, date)

            BackupConflictKind.CLOUD_AHEAD ->
                stringResource(R.string.conflict_body_cloud_ahead, date)

            BackupConflictKind.LOCAL_SHRUNK -> stringResource(R.string.conflict_body_local_shrunk)
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(
            R.string.conflict_comparison,
            date,
            deviceText(sides),
            recordsText(sides.cloudRecords),
            recordsText(sides.localRecords),
            pendingText(sides.pendingChanges),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (sides.bothSidesChanged) {
        NoticeCard(stringResource(R.string.conflict_both_sides), NoticeVariant.WARNING)
    }
    val keepBody =
        when (sides.destinationKind) {
            BackupDestinationKind.WEBDAV -> stringResource(R.string.conflict_option_keep_body)

            BackupDestinationKind.DOCUMENT_SLOTS ->
                stringResource(R.string.conflict_option_keep_slots_body, app)
        }
    Options(conflict, keepBody, restoreKey = sides.cloudCopyKey, onAction = onAction)
}

/**
 * Con la nube del teléfono, una ranura que cambió por fuera o las tres protegidas: ese lado no
 * tiene fecha ni registros, y conservar este teléfono pide un archivo nuevo.
 */
@Composable
private fun NewFileBlock(
    conflict: ConflictUi,
    need: ConflictLookup.NewSlotFileRequired,
    wording: BackupWording?,
    onAction: (BackupAction) -> Unit,
) {
    val app = stringResource(R.string.app_name)
    val provider = wording?.providerLabel ?: NO_VALUE_TEXT
    val file = fileNameOf(need)
    Text(
        if (need.threeBlocked) {
            stringResource(R.string.conflict_body_slots_blocked, provider)
        } else {
            stringResource(R.string.conflict_body_slot_changed, file, app)
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(
            R.string.conflict_comparison_slot_changed,
            app,
            file,
            recordsText(need.localRecords),
            pendingText(need.pendingChanges),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val spacing = AppTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
        PrimaryActionButton(
            text = stringResource(R.string.conflict_option_restore),
            onClick = { onAction(BackupAction.FindFile) },
            variant = ActionVariant.TONAL,
            modifier = Modifier.fillMaxWidth(),
            enabled = !conflict.working,
        )
        OptionBody(stringResource(R.string.conflict_option_restore_slot_body))
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
        PrimaryActionButton(
            text = stringResource(R.string.conflict_option_keep),
            onClick = { onAction(BackupAction.KeepPhone) },
            variant = ActionVariant.TONAL,
            modifier = Modifier.fillMaxWidth(),
            enabled = !conflict.working,
        )
        OptionBody(
            stringResource(R.string.conflict_option_keep_slots_new_file_body, app, file, provider),
        )
    }
    LaterOption(onAction)
}

/**
 * Restaurar, conservar este teléfono y decidir después. Sin [keepBody], conservar no se ofrece:
 * la base está vacía.
 */
@Composable
private fun Options(
    conflict: ConflictUi,
    keepBody: String?,
    restoreKey: String?,
    onAction: (BackupAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val working = conflict.working
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
        PrimaryActionButton(
            text = stringResource(R.string.conflict_option_restore),
            onClick = { onAction(BackupAction.RestoreCloud(restoreKey)) },
            variant = ActionVariant.TONAL,
            modifier = Modifier.fillMaxWidth(),
            enabled = !working,
        )
        OptionBody(stringResource(R.string.conflict_option_restore_body))
    }
    if (keepBody != null) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
            PrimaryActionButton(
                text = stringResource(R.string.conflict_option_keep),
                onClick = { onAction(BackupAction.KeepPhone) },
                variant = ActionVariant.TONAL,
                modifier = Modifier.fillMaxWidth(),
                state = if (working) ActionState.WORKING else ActionState.IDLE,
            )
            OptionBody(keepBody)
        }
    }
    LaterOption(onAction)
}

@Composable
private fun LaterOption(onAction: (BackupAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        PrimaryActionButton(
            text = stringResource(R.string.conflict_option_later),
            onClick = { onAction(BackupAction.DecideLater) },
            variant = ActionVariant.OUTLINED,
            modifier = Modifier.fillMaxWidth(),
        )
        OptionBody(stringResource(R.string.conflict_option_later_body))
    }
}

@Composable
private fun OptionBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * `¿Conservar este teléfono?` con los registros de cada lado. Si este teléfono tiene menos, la
 * confirmación es reforzada: dice cuántos faltan y el botón se activa solo al marcar que se entiende.
 */
@Composable
private fun KeepDialog(
    confirm: KeepConfirm.Sides,
    wording: BackupWording?,
    onAction: (BackupAction) -> Unit,
) {
    val sides = confirm.sides
    val app = stringResource(R.string.app_name)
    val date = cloudDate(sides, wording)
    val local = recordsText(sides.localRecords)
    val cloud = recordsText(sides.cloudRecords)
    val body =
        when (sides.destinationKind) {
            BackupDestinationKind.WEBDAV ->
                stringResource(R.string.conflict_keep_confirm_body, local, date, cloud, app)

            BackupDestinationKind.DOCUMENT_SLOTS ->
                stringResource(R.string.conflict_keep_confirm_slots_body, local, date, cloud, app)
        }
    val missing = sides.missingRecords.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    ConfirmDialog(
        title = stringResource(R.string.conflict_keep_confirm_title),
        body =
            if (confirm.reinforced && missing > 0) {
                body + PARAGRAPH +
                    pluralStringResource(R.plurals.conflict_keep_fewer_body, missing, missing, date)
            } else {
                body
            },
        confirmLabel = stringResource(R.string.conflict_option_keep),
        onConfirm = { onAction(BackupAction.ConfirmKeep) },
        onDismiss = { onAction(BackupAction.DismissKeep) },
        variant = if (confirm.reinforced) ConfirmVariant.DESTRUCTIVE else ConfirmVariant.STANDARD,
        acknowledgement =
            if (confirm.reinforced) stringResource(R.string.conflict_keep_fewer_check) else null,
    )
}

/**
 * Con la nube del teléfono, conservar este teléfono crea un archivo nuevo para la ranura que no se
 * usa: el anterior queda allí sin tocar. Sin ese archivo, nada cambia.
 */
@Composable
private fun NewFileDialog(
    need: ConflictLookup.NewSlotFileRequired,
    wording: BackupWording?,
    onAction: (BackupAction) -> Unit,
) {
    val provider = wording?.providerLabel ?: NO_VALUE_TEXT
    ConfirmDialog(
        title = stringResource(R.string.conflict_keep_confirm_title),
        body =
            stringResource(
                R.string.conflict_keep_confirm_slots_new_file_body,
                recordsText(need.localRecords),
                provider,
                fileNameOf(need),
            ),
        confirmLabel = stringResource(R.string.conflict_option_keep),
        onConfirm = { onAction(BackupAction.CreateConflictFile) },
        onDismiss = { onAction(BackupAction.DismissKeep) },
    )
}

/** El archivo de la ranura, o su nombre propuesto si la nube no dijo otro. */
private fun fileNameOf(need: ConflictLookup.NewSlotFileRequired): String =
    need.fileName ?: need.slot?.newFileName ?: NO_VALUE_TEXT

/** La fecha de la copia de la nube, en la zona del negocio, o un guion si no se sabe. */
@Composable
private fun cloudDate(sides: ConflictSides, wording: BackupWording?): String =
    instantText(sides.cloudCreatedAt, wording)

/** El teléfono de la copia de la nube: su modelo si la copia lo dice, o de quién es. */
@Composable
private fun deviceText(sides: ConflictSides): String = sides.cloudDeviceLabel
    ?: stringResource(
        if (sides.cloudFromThisPhone) R.string.backup_device_this else R.string.backup_device_other,
    )

@Composable
private fun recordsText(records: Long?): String {
    records ?: return NO_VALUE_TEXT
    val count = records.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(R.plurals.backup_records_count, count, count)
}

@Composable
private fun pendingText(pending: Long): String {
    val count = pending.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(R.plurals.backup_pending_changes, count, count)
}

/** Entre dos párrafos de un diálogo. */
private const val PARAGRAPH = "\n\n"
