package io.github.yesidrobayo.shopledger.ui.storage

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseDestination
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseScope
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseStop
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseSummary
import io.github.yesidrobayo.shopledger.domain.model.erase.StorageUsage
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * `Almacenamiento y borrado`, sin ViewModel: el estado de las copias con `Respaldar ahora`, lo que
 * ocupa cada parte, `Copias locales`, el aviso de desinstalar y `Borrar mis datos`, que nunca es
 * la acción principal. El borrado va paso a paso en una hoja que no se cierra mientras borra.
 *
 * @param backupStatus el estado de las copias, con el mismo chip de Ajustes. `null` mientras se
 *   lee.
 * @param topBarActions lo que va a la derecha de la barra, como la ayuda.
 */
@Composable
fun StorageScreen(
    uiState: StorageUiState,
    modifier: Modifier = Modifier,
    backupStatus: (@Composable () -> Unit)? = null,
    topBarActions: @Composable () -> Unit = {},
    onAction: (StorageAction) -> Unit,
) {
    val app = stringResource(R.string.app_name)
    val spacing = AppTheme.spacing
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.storage_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(StorageAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                actions = { topBarActions() },
            )
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = spacing.s24),
            ) {
                if (uiState.fromAndroid) {
                    NoticeCard(
                        text = stringResource(R.string.storage_opened_from_android),
                        variant = NoticeVariant.INFO,
                        modifier =
                            Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                    )
                }
                SectionHeader(stringResource(R.string.storage_backup_status_title))
                Column(
                    Modifier.padding(horizontal = spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(spacing.s8),
                ) {
                    backupStatus?.invoke()
                    // Con cambios sin respaldar, `Respaldar ahora` es la acción principal.
                    PrimaryActionButton(
                        text = stringResource(R.string.backup_action_now),
                        onClick = { onAction(StorageAction.BackupNow) },
                        variant =
                            if (uiState.unsyncedChanges > 0) {
                                ActionVariant.PRIMARY
                            } else {
                                ActionVariant.TONAL
                            },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SpaceUsed(uiState.usage)
                uiState.summary?.photosPending?.takeIf { it > 0 }?.let { pending ->
                    Text(
                        stringResource(R.string.storage_photos_pending, pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.warning,
                        modifier =
                            Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.offsite_title),
                    action =
                        SettingsRowAction.Navigation { onAction(StorageAction.OpenLocalCopies) },
                )
                NoticeCard(
                    text =
                        stringResource(R.string.storage_uninstall_warning, app) + " " +
                            stringResource(R.string.storage_keep_data_note),
                    variant = NoticeVariant.WARNING,
                    modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                )
                SectionHeader(stringResource(R.string.erase_title))
                Text(
                    stringResource(R.string.erase_body, app),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.s16),
                )
                if (uiState.eraseAvailable) {
                    InlineTextButton(
                        text = stringResource(R.string.erase_action),
                        onClick = { onAction(StorageAction.OpenErase) },
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = spacing.s8),
                    )
                } else {
                    Text(
                        stringResource(R.string.erase_disabled_restore),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.settings_delete_data),
                    action = SettingsRowAction.Navigation { onAction(StorageAction.OpenGuide) },
                )
            }
        }
    }
    uiState.flow?.let { EraseFlowSheet(it, uiState.summary, onAction) }
}

/** `Espacio usado`, con un esqueleto mientras se mide y `No disponible` en lo que no se midió. */
@Composable
private fun SpaceUsed(usage: StorageUsage?) {
    SectionHeader(stringResource(R.string.storage_space_title))
    val rows =
        listOf(
            R.string.storage_row_business to usage?.businessBytes,
            R.string.storage_row_photos to usage?.photoBytes,
            R.string.storage_row_local_copies to usage?.safetyCopyBytes,
            R.string.storage_row_other to usage?.otherBytes,
        )
    for ((label, bytes) in rows) SizeRow(stringResource(label), bytes, measuring = usage == null)
    val known =
        listOfNotNull(
            usage?.businessBytes,
            usage?.photoBytes,
            usage?.safetyCopyBytes,
            usage?.otherBytes,
        )
    val total = if (usage == null || known.isEmpty()) null else known.sum()
    SizeRow(stringResource(R.string.storage_total), total, measuring = usage == null)
    SizeRow(stringResource(R.string.storage_free), usage?.freeBytes, measuring = usage == null)
}

/** Una fila con su tamaño, que se lee entera con TalkBack. Con letra grande, el tamaño baja. */
@Composable
private fun SizeRow(label: String, bytes: Long?, measuring: Boolean) {
    val context = LocalContext.current
    val spacing = AppTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .padding(horizontal = spacing.s16, vertical = spacing.s4)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        when {
            measuring ->
                SkeletonBlock(Modifier.widthIn(min = spacing.s64).heightIn(min = spacing.s16))

            bytes == null ->
                Text(
                    stringResource(R.string.storage_not_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            else ->
                Text(
                    Formatter.formatShortFileSize(context, bytes),
                    style = MaterialTheme.typography.bodyLarge,
                )
        }
    }
}

/** La hoja de `Borrar mis datos`, en el paso que va. Mientras borra no se cierra. */
@Composable
private fun EraseFlowSheet(
    flow: EraseFlow,
    summary: EraseSummary?,
    onAction: (StorageAction) -> Unit,
) {
    if (flow == EraseFlow.ConfirmDeviceOnly) {
        ConfirmDialog(
            title = stringResource(R.string.erase_device_only_confirm_title),
            body = stringResource(R.string.erase_device_only_confirm_body),
            confirmLabel = stringResource(R.string.erase_device_only_action),
            onConfirm = { onAction(StorageAction.DeviceOnlyAuthorized) },
            onDismiss = { onAction(StorageAction.Dismiss) },
            variant = ConfirmVariant.DESTRUCTIVE,
        )
        return
    }
    val title =
        when (flow) {
            is EraseFlow.Scope -> R.string.erase_step_scope_title
            is EraseFlow.Review -> R.string.erase_step_review_title
            is EraseFlow.Confirm -> R.string.erase_step_confirm_title
            is EraseFlow.Running -> R.string.erase_step_running_title
            is EraseFlow.Stopped, EraseFlow.ConfirmDeviceOnly -> R.string.erase_title
        }
    AppBottomSheet(
        onDismissRequest = { onAction(StorageAction.Dismiss) },
        title = stringResource(title),
    ) {
        when (flow) {
            is EraseFlow.Scope -> ScopeStep(flow, onAction)
            is EraseFlow.Review -> ReviewStep(flow.scope, summary, onAction)
            is EraseFlow.Confirm -> ConfirmStep(flow, summary, onAction)
            is EraseFlow.Running -> RunningStep(flow)
            is EraseFlow.Stopped -> StoppedStep(flow.reason, summary, onAction)
            EraseFlow.ConfirmDeviceOnly -> Unit
        }
    }
}

@Composable
private fun ColumnScope.ScopeStep(flow: EraseFlow.Scope, onAction: (StorageAction) -> Unit) {
    Column(Modifier.selectableGroup()) {
        ScopeOption(
            title = stringResource(R.string.erase_option_device),
            help = stringResource(R.string.erase_option_device_help),
            selected = flow.selected == EraseScope.DEVICE_ONLY,
        ) { onAction(StorageAction.ChooseScope(EraseScope.DEVICE_ONLY)) }
        ScopeOption(
            title = stringResource(R.string.erase_option_all),
            help = stringResource(R.string.erase_option_all_help),
            selected = flow.selected == EraseScope.DEVICE_AND_COPIES,
        ) { onAction(StorageAction.ChooseScope(EraseScope.DEVICE_AND_COPIES)) }
    }
    PrimaryActionButton(
        text = stringResource(R.string.action_continue),
        onClick = { onAction(StorageAction.Continue) },
        variant = ActionVariant.PRIMARY,
        enabled = flow.selected != null,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Una opción del primer paso. Con letra grande, crece en alto. */
@Composable
private fun ScopeOption(title: String, help: String, selected: Boolean, onSelect: () -> Unit) {
    val spacing = AppTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.listRow)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                help,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColumnScope.ReviewStep(
    scope: EraseScope,
    summary: EraseSummary?,
    onAction: (StorageAction) -> Unit,
) {
    val app = stringResource(R.string.app_name)
    val count = LocalAppFormats.current.quantity
    summary?.let {
        BodyText(stringResource(R.string.erase_summary_records, count.formatCount(it.records)))
        BodyText(stringResource(R.string.erase_summary_products, count.formatCount(it.products)))
        BodyText(
            stringResource(R.string.erase_summary_photos, count.formatCount(it.photos.toLong())),
        )
        if (it.unsyncedChanges > 0) {
            Text(
                pluralStringResource(
                    R.plurals.erase_unsynced_warning,
                    it.unsyncedChanges.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    count.formatCount(it.unsyncedChanges),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.warning,
            )
        }
        if (it.backupFailed) {
            Text(
                stringResource(R.string.erase_failed_backup_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.warning,
            )
        }
    }
    if (scope == EraseScope.DEVICE_AND_COPIES) {
        when (summary?.destination) {
            EraseDestination.WEBDAV -> BodyText(stringResource(R.string.erase_trash_note, app))

            EraseDestination.SLOTS ->
                BodyText(
                    stringResource(
                        R.string.erase_slots_cloud_note,
                        app,
                        summary.providerLabel.orEmpty(),
                    ),
                )

            else -> Unit
        }
    }
    Text(
        stringResource(R.string.erase_out_of_reach_title, app),
        style = MaterialTheme.typography.titleSmall,
    )
    BodyText(stringResource(R.string.erase_out_of_reach_body, app))
    InlineTextButton(
        text = stringResource(R.string.erase_export_first_action),
        onClick = { onAction(StorageAction.ExportFirst) },
    )
    PrimaryActionButton(
        text = stringResource(R.string.action_continue),
        onClick = { onAction(StorageAction.Continue) },
        variant = ActionVariant.TONAL,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.ConfirmStep(
    flow: EraseFlow.Confirm,
    summary: EraseSummary?,
    onAction: (StorageAction) -> Unit,
) {
    val word = stringResource(R.string.erase_confirm_word)
    if (flow.scope == EraseScope.DEVICE_AND_COPIES &&
        summary?.destination == EraseDestination.WEBDAV
    ) {
        BodyText(stringResource(R.string.erase_password_note))
    }
    AppTextField(
        value = flow.typed,
        onValueChange = { onAction(StorageAction.Typed(it)) },
        label = stringResource(R.string.erase_type_word_hint, word),
        autoCorrect = false,
    )
    PrimaryActionButton(
        text = stringResource(R.string.erase_confirm_action),
        onClick = { onAction(StorageAction.EraseAuthorized) },
        variant = ActionVariant.DESTRUCTIVE,
        enabled = flow.typed.trim().equals(word, ignoreCase = true),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RunningStep(flow: EraseFlow.Running) {
    BodyText(
        stringResource(
            if (flow.cloud) R.string.erase_stage_destination else R.string.erase_stage_local,
        ),
    )
    SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.spacing.s8))
}

/** Por qué se detuvo, con su salida. Salvo un fallo local, este teléfono no se tocó. */
@Composable
private fun ColumnScope.StoppedStep(
    reason: EraseStop,
    summary: EraseSummary?,
    onAction: (StorageAction) -> Unit,
) {
    val app = stringResource(R.string.app_name)
    val provider = summary?.providerLabel.orEmpty()
    val text =
        when (reason) {
            EraseStop.OFFLINE -> stringResource(R.string.erase_offline)

            EraseStop.UNREACHABLE -> stringResource(R.string.erase_error_destination)

            EraseStop.PARTIAL -> stringResource(R.string.erase_error_partial)

            EraseStop.REFUSED ->
                if (summary?.destination == EraseDestination.SLOTS) {
                    stringResource(R.string.erase_error_slots, provider, app)
                } else {
                    stringResource(R.string.erase_error_refused, app)
                }

            EraseStop.ACCESS_LOST -> stringResource(R.string.erase_error_access_lost, app)

            EraseStop.LOCAL_FAILED ->
                stringResource(R.string.erase_error_local) + " " +
                    stringResource(R.string.erase_error_local_help, app)

            EraseStop.RESTORE_IN_PROGRESS -> stringResource(R.string.erase_disabled_restore)
        }
    BodyText(text)
    when (reason) {
        EraseStop.ACCESS_LOST ->
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_reconnect),
                onClick = { onAction(StorageAction.Reconnect) },
                variant = ActionVariant.TONAL,
                modifier = Modifier.fillMaxWidth(),
            )

        EraseStop.OFFLINE, EraseStop.RESTORE_IN_PROGRESS -> Unit

        else ->
            PrimaryActionButton(
                text = stringResource(R.string.action_retry),
                onClick = { onAction(StorageAction.Retry) },
                variant = ActionVariant.TONAL,
                modifier = Modifier.fillMaxWidth(),
            )
    }
    if (reason != EraseStop.LOCAL_FAILED && reason != EraseStop.RESTORE_IN_PROGRESS) {
        InlineTextButton(
            text = stringResource(R.string.erase_device_only_action),
            onClick = { onAction(StorageAction.DeviceOnly) },
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@ThemePreviews
@Composable
private fun StorageScreenPreview() {
    PreviewTheme {
        StorageScreen(
            StorageUiState(
                usage = StorageUsage(2_400_000, 38_100_000, 4_800_000, 600_000, null),
                fromAndroid = true,
            ),
        ) {}
    }
}
