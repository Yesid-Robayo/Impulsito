package io.github.yesidrobayo.shopledger.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.format.DataSizeFormatter
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AdaptiveFieldRow
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRow
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRowData
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.backupToneContainer
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlin.time.Instant

/**
 * La pantalla del respaldo: tranquilidad con hechos. Cuándo se guardó lo último, qué falta, dónde
 * se guardan y qué copias hay.
 *
 * - El bloque de estado, con el fondo del contenedor de su tono, su icono, la frase y el detalle.
 *   Subiendo o restaurando, una barra indeterminada.
 * - Los avisos que ningún estado oculta: revisar en la nube, comprobación completa, espacio.
 * - `Respaldar ahora`, o el arreglo del estado: elegir dónde guardar o reconectar.
 * - `Dónde se guardan`: el usuario y el servidor con WebDAV, o el nombre de la nube del teléfono.
 * - `Copias en la nube`, con sus estados: cargando, sin internet, error con reintento y vacía.
 * - En una ventana ancha, el estado y el destino a la izquierda y las copias a la derecha.
 *
 * Es pura: recibe el estado y avisa cada acción. Los avisos breves los muestra la navegación.
 */
@Composable
fun BackupScreen(
    uiState: BackupUiState,
    onAction: (BackupAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.missingHelp) {
        MissingHelpSheet(uiState.wording?.providerLabel ?: NO_VALUE_TEXT) {
            onAction(BackupAction.CloseMissingHelp)
        }
    }
    uiState.wording?.overview?.meteredPolicy?.let { MeteredLayer(uiState.metered, it, onAction) }
    ReleaseLayer(uiState.release, onAction)
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= EXPANDED_WIDTH
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                AppTheme.spacing.s24
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.backup_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(BackupAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                    titleStyle = MaterialTheme.typography.titleLarge,
                )
                if (expanded) {
                    Row(
                        Modifier.weight(1f).padding(horizontal = AppTheme.spacing.s8),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
                    ) {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(bottom = bottom),
                        ) { overview(uiState, onAction) }
                        LazyColumn(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(bottom = bottom),
                        ) { copies(uiState, onAction) }
                    }
                } else {
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        LazyColumn(
                            Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize(),
                            contentPadding = PaddingValues(bottom = bottom),
                        ) {
                            overview(uiState, onAction)
                            copies(uiState, onAction)
                        }
                    }
                }
            }
            RestoreLayer(
                restore = uiState.restore,
                rowOf = { copyRowData(it, uiState.wording) },
                dateOf = { instantText(it, uiState.wording) },
                onAction = onAction,
                canRelease =
                    uiState.wording?.overview?.destinationKind == BackupDestinationKind.WEBDAV,
            )
            BackupLinkLayer(uiState, onAction)
        }
    }
}

/** El estado, los avisos, la acción y el destino. */
private fun LazyListScope.overview(uiState: BackupUiState, onAction: (BackupAction) -> Unit) {
    val wording = uiState.wording
    item(key = STATUS) {
        Column(
            Modifier.padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            if (wording == null) {
                DelayedSkeleton {
                    SkeletonBlock(
                        Modifier.fillMaxWidth().heightIn(
                            min =
                                AppTheme.spacing.s64 * 2,
                        ),
                    )
                }
            } else {
                StatusBlock(wording)
                Notices(wording)
                MainAction(wording, uiState.requesting, onAction)
                uiState.handoff?.takeIf { wording.slots }?.let { CloudCheck(it, wording, onAction) }
            }
        }
    }
    if (wording != null && wording.overview.report.status != BackupStatus.NOT_CONNECTED) {
        item(key = DESTINATION) { Destination(uiState.destination, onAction) }
    }
    // Solo con WebDAV: con ranuras, la red y lo que se sube los decide la app de esa nube.
    if (wording != null && wording.overview.report.status != BackupStatus.NOT_CONNECTED &&
        wording.overview.destinationKind == BackupDestinationKind.WEBDAV
    ) {
        item(key = METERED) {
            MeteredSettings(wording.overview.meteredPolicy, uiState.uploadedThisMonth, onAction)
        }
    }
}

/** `Copias con datos móviles`, con la opción elegida, y `Subido este mes`. */
@Composable
private fun MeteredSettings(
    policy: MeteredBackupPolicy,
    uploadedThisMonth: Long?,
    onAction: (BackupAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    val uploaded =
        uploadedThisMonth?.let {
            DataSizeFormatter(formats.date.formatLocale, formats.uiLocale).megabytes(it)
        } ?: NO_VALUE_TEXT
    Column {
        SectionHeader(stringResource(R.string.backup_metered_row))
        SettingsRow(
            title = stringResource(policy.label()),
            action = SettingsRowAction.Navigation { onAction(BackupAction.OpenMeteredPolicy) },
        )
        SettingsRow(
            title = stringResource(R.string.backup_uploaded_month_label),
            action =
                SettingsRowAction.Value {
                    Text(
                        uploaded,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                },
        )
    }
}

/** El nombre de cada política con datos móviles. */
internal fun MeteredBackupPolicy.label(): Int = when (this) {
    MeteredBackupPolicy.EVERY_60_MIN -> R.string.backup_metered_every_60
    MeteredBackupPolicy.EVERY_30_MIN -> R.string.backup_metered_every_30
    MeteredBackupPolicy.WIFI_ONLY -> R.string.backup_metered_wifi_only
}

/** La hoja con las tres opciones, o la advertencia de `Solo con Wi-Fi`. */
@Composable
private fun MeteredLayer(
    metered: MeteredUi,
    current: MeteredBackupPolicy,
    onAction: (BackupAction) -> Unit,
) {
    when (metered) {
        MeteredUi.Closed -> Unit

        MeteredUi.Choosing ->
            AppBottomSheet(
                onDismissRequest = { onAction(BackupAction.CloseMeteredPolicy) },
                title = stringResource(R.string.backup_metered_row),
            ) {
                MeteredOptions(current, onAction)
            }

        MeteredUi.ConfirmWifiOnly ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_wifi_only_title),
                body = stringResource(R.string.dialog_wifi_only_body),
                confirmLabel = stringResource(R.string.dialog_wifi_only_action),
                onConfirm = { onAction(BackupAction.ConfirmWifiOnly) },
                onDismiss = { onAction(BackupAction.CloseMeteredPolicy) },
                dismissLabel = stringResource(R.string.action_cancel),
            )
    }
}

/** Las tres opciones, con la marca de la elegida, que no depende del color. */
@Composable
internal fun MeteredOptions(current: MeteredBackupPolicy, onAction: (BackupAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            stringResource(R.string.backup_metered_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (policy in MeteredBackupPolicy.entries) {
            val selected = policy == current
            SettingsRow(
                title = stringResource(policy.label()),
                action =
                    SettingsRowAction.Value {
                        if (selected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(AppTheme.sizes.icon),
                            )
                        }
                    },
                modifier =
                    Modifier.selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onAction(BackupAction.PickMeteredPolicy(policy)) },
                    ),
            )
        }
    }
}

/** `¿Liberar esta copia?`. Mientras se libera, la pregunta sigue sin poder confirmarse de nuevo. */
@Composable
private fun ReleaseLayer(release: ReleaseUi, onAction: (BackupAction) -> Unit) {
    val working = release is ReleaseUi.Working
    when (release) {
        ReleaseUi.Idle, is ReleaseUi.Failed -> Unit

        is ReleaseUi.Confirm, is ReleaseUi.Working ->
            ConfirmDialog(
                title = stringResource(R.string.dialog_release_copy_title),
                body =
                    stringResource(
                        R.string.dialog_release_copy_body,
                        stringResource(R.string.app_name),
                    ),
                confirmLabel = stringResource(R.string.backup_action_release),
                onConfirm = { if (!working) onAction(BackupAction.ConfirmRelease) },
                onDismiss = { onAction(BackupAction.CloseRelease) },
                dismissLabel = stringResource(R.string.action_cancel),
            )
    }
}

/** El bloque del estado: el fondo de su tono, el icono, la frase grande y el detalle. */
@Composable
private fun StatusBlock(wording: BackupWording) {
    val status = wording.overview.report.status
    val (container, content) = backupToneContainer(status.tone())
    val busy = status == BackupStatus.UPLOADING || status == BackupStatus.RESTORING
    Column(
        Modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.large)
            .padding(AppTheme.spacing.s20)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        Icon(
            painter = painterResource(status.icon()),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(AppTheme.spacing.s32),
        )
        Text(backupTitle(wording), style = MaterialTheme.typography.headlineLarge, color = content)
        backupDetail(wording)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = content)
        }
        if (busy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = content,
                trackColor = container,
            )
        }
    }
}

/**
 * Los avisos aparte del estado. El de revisar en la nube no se repite si ya es el estado que gana:
 * cualquier otro estado lo deja a la vista.
 */
@Composable
private fun Notices(wording: BackupWording) {
    val report = wording.overview.report
    val app = stringResource(R.string.app_name)
    val provider = wording.providerLabel ?: NO_VALUE_TEXT
    for (notice in BackupNotice.entries.filter { it in report.notices }) {
        val text =
            when (notice) {
                BackupNotice.HUMAN_CHECK_DUE ->
                    if (report.status == BackupStatus.CHECK_DUE) {
                        null
                    } else {
                        stringResource(R.string.backup_state_check_due, provider)
                    }

                BackupNotice.FULL_READ_OVERDUE ->
                    wording.overview.lastFullReadAt?.let { since ->
                        val days = (wording.overview.computedAt - since).inWholeDays
                        stringResource(
                            R.string.backup_full_verify_late_notice,
                            days.toString(),
                            app,
                        )
                    }

                BackupNotice.LOW_DEVICE_STORAGE -> stringResource(R.string.backup_device_low)

                BackupNotice.COPY_NEAR_LIMIT ->
                    if (wording.slots) {
                        stringResource(R.string.backup_near_limit_slots, app)
                    } else {
                        stringResource(R.string.backup_near_limit, app)
                    }

                BackupNotice.SERVICE_NEARLY_FULL -> stringResource(R.string.backup_destination_low)
            }
        text?.let { NoticeCard(it, NoticeVariant.WARNING) }
    }
}

/** La acción del estado: elegir dónde guardar, reconectar o `Respaldar ahora`. */
@Composable
private fun MainAction(
    wording: BackupWording,
    requesting: Boolean,
    onAction: (BackupAction) -> Unit,
) {
    when (wording.overview.report.status) {
        BackupStatus.NOT_CONNECTED ->
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_choose_destination),
                onClick = { onAction(BackupAction.ChooseDestination) },
                variant = ActionVariant.PRIMARY,
            )

        // Con la sesión de la nube cerrada, los archivos siguen sirviendo: se inicia sesión allí.
        BackupStatus.PERMISSION_LOST ->
            if (wording.sessionRequired) {
                PrimaryActionButton(
                    text =
                        stringResource(
                            R.string.backup_check_open_action,
                            wording.providerLabel ?: NO_VALUE_TEXT,
                        ),
                    onClick = { onAction(BackupAction.SignInToProvider) },
                    variant = ActionVariant.PRIMARY,
                )
            } else {
                PrimaryActionButton(
                    text = stringResource(R.string.backup_action_reconnect),
                    onClick = { onAction(BackupAction.Reconnect) },
                    variant = ActionVariant.PRIMARY,
                )
            }

        BackupStatus.POLICY_PENDING ->
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_review_policy),
                onClick = { onAction(BackupAction.ReviewPolicy) },
                variant = ActionVariant.PRIMARY,
            )

        // El conflicto se decide en su propia hoja; aquí no hay copia que pedir.
        BackupStatus.CONFLICT ->
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_review),
                onClick = { onAction(BackupAction.ReviewConflict) },
                variant = ActionVariant.PRIMARY,
            )

        BackupStatus.UPLOADING, BackupStatus.RESTORING ->
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_now),
                onClick = {},
                variant = ActionVariant.TONAL,
                enabled = false,
            )

        BackupStatus.NOTHING_TO_BACK_UP,
        BackupStatus.UP_TO_DATE,
        BackupStatus.HANDED_OFF,
        BackupStatus.CHECK_DUE,
        BackupStatus.PENDING,
        BackupStatus.WAITING_FOR_NETWORK,
        BackupStatus.WAITING_FOR_WIFI,
        BackupStatus.BLOCKED_BY_DATA_SAVER,
        BackupStatus.STALE,
        BackupStatus.FAILED,
        ->
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_now),
                onClick = { onAction(BackupAction.BackUpNow) },
                variant = ActionVariant.TONAL,
                enabled = !requesting,
            )
    }
}

/**
 * `Revisar en la nube`, con cualquier estado de las ranuras: el archivo, su tamaño y la hora en que se
 * escribió, lo que hay que ver allí, la última revisión, y abrir la nube o anotar que se vio. Anotar
 * es lo que dijo la persona: ningún texto lo presenta como una comprobación.
 */
@Composable
private fun CloudCheck(
    handoff: HandoffCopy,
    wording: BackupWording,
    onAction: (BackupAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    val spacing = AppTheme.spacing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val provider = wording.providerLabel ?: NO_VALUE_TEXT
    val zone = wording.zone
    val time = zone?.let { formats.date.dayMonthTime(handoff.writtenAt, it) } ?: NO_VALUE_TEXT
    val size = DataSizeFormatter(
        formats.date.formatLocale,
        formats.uiLocale,
    ).megabytes(handoff.sizeBytes)
    val lastCheck = handoff.lastCheckAt
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        Text(
            stringResource(R.string.backup_check_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(
                R.string.backup_handed_off_detail,
                handoff.fileName ?: NO_VALUE_TEXT,
                size,
                time,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.backup_check_how),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        Text(
            if (lastCheck != null && zone != null) {
                stringResource(
                    R.string.backup_check_last,
                    formats.date.dayMonthTime(lastCheck, zone),
                )
            } else {
                stringResource(R.string.backup_check_never)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        val openLabel = stringResource(R.string.backup_check_open_action, provider)
        val seenLabel = stringResource(R.string.backup_check_seen_action, provider)
        // Lado a lado si caben; con letra grande o nombres largos, uno debajo del otro.
        AdaptiveFieldRow(
            firstLabel = openLabel,
            secondLabel = seenLabel,
            first = { modifier ->
                PrimaryActionButton(
                    text = openLabel,
                    onClick = { onAction(BackupAction.OpenProvider) },
                    variant = ActionVariant.TONAL,
                    modifier = modifier,
                )
            },
            second = { modifier ->
                PrimaryActionButton(
                    text = seenLabel,
                    onClick = { onAction(BackupAction.MarkSeen) },
                    variant = ActionVariant.TONAL,
                    modifier = modifier,
                )
            },
        )
        InlineTextButton(
            stringResource(R.string.backup_check_missing_action),
            { onAction(BackupAction.ShowMissingHelp) },
        )
    }
}

/** `Si no aparece con esa hora`: tres pasos. No anota nada, y la revisión sigue pendiente. */
@Composable
fun MissingHelpSheet(provider: String, onClose: () -> Unit) {
    AppBottomSheet(
        onDismissRequest = onClose,
        title = stringResource(R.string.backup_check_missing_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            for (step in listOf(
                stringResource(R.string.backup_check_missing_step_1, provider),
                stringResource(R.string.backup_check_missing_step_2),
                stringResource(R.string.backup_check_missing_step_3),
            )) {
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * `Dónde se guardan`: el usuario y el servidor, o la nube del teléfono, con `Desconectar` a su
 * lado, y cambiar el destino.
 */
@Composable
private fun Destination(info: BackupDestinationInfo?, onAction: (BackupAction) -> Unit) {
    val spacing = AppTheme.spacing
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        SectionHeader(stringResource(R.string.backup_destination_label))
        Column(
            Modifier.padding(horizontal = spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.s2),
                ) {
                    when (info) {
                        is BackupDestinationInfo.WebDav -> {
                            Text(
                                info.account ?: NO_VALUE_TEXT,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            info.host?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = muted)
                            }
                        }

                        is BackupDestinationInfo.Slots ->
                            Text(
                                stringResource(
                                    R.string.backup_destination_connected,
                                    info.providerLabel ?: NO_VALUE_TEXT,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )

                        null -> Unit
                    }
                }
                if (info != null) {
                    InlineTextButton(
                        text = stringResource(R.string.backup_action_disconnect),
                        onClick = { onAction(BackupAction.Disconnect) },
                    )
                }
            }
            InlineTextButton(
                text = stringResource(R.string.backup_action_change_destination),
                onClick = { onAction(BackupAction.ChooseDestination) },
            )
        }
    }
}

/** `Copias en la nube` y su lista, con todos sus estados. Sin destino no aparece. */
private fun LazyListScope.copies(uiState: BackupUiState, onAction: (BackupAction) -> Unit) {
    val stopped = uiState.restore as? RestoreUi.Stopped
    if (stopped != null) {
        item(key = RESTORE_NOTICE) {
            Column(Modifier.padding(horizontal = AppTheme.spacing.s16)) {
                RestoreStoppedNotice(stopped, onAction)
            }
        }
    }
    val list = uiState.copies
    val connected = list !is BackupCopyList.NotConnected &&
        uiState.wording?.overview?.report?.status != BackupStatus.NOT_CONNECTED
    if (connected) copyList(uiState, list, onAction)
    // Sin destino también: en un teléfono nuevo, un archivo es la forma de traer los datos.
    if (uiState.wording != null) {
        item(key = FIND_FILE) {
            Column(Modifier.padding(horizontal = AppTheme.spacing.s8)) {
                InlineTextButton(
                    stringResource(R.string.backup_action_find_file),
                    { onAction(BackupAction.FindFile) },
                )
            }
        }
    }
    // Sin destino también: una copia exportada es la protección de quien no eligió dónde guardar.
    if (uiState.wording != null) {
        item(key = EXPORT_COPY) {
            Column(Modifier.padding(horizontal = AppTheme.spacing.s8)) {
                InlineTextButton(
                    stringResource(R.string.backup_export_copy_action),
                    { onAction(BackupAction.OpenLocalCopies) },
                )
            }
        }
    }
    if (connected) item(key = DRILL) { Drill(uiState.drilling, onAction) }
    val wording = uiState.wording
    if (connected && wording != null) {
        item(key = PROTECTION) { Protection(wording, uiState.copiesEncrypted) }
    }
}

/**
 * `Qué tan protegidos están los datos`: lo que se puede perder según el destino y la política con
 * datos móviles, y qué expone compartir la carpeta de copias. La app no sabe si está compartida, y
 * la frase lo dice: ni WebDAV ni el selector del sistema lo informan.
 */
@Composable
private fun Protection(wording: BackupWording, encrypted: Boolean?) {
    val app = stringResource(R.string.app_name)
    val limits =
        if (wording.slots) {
            stringResource(R.string.backup_limits_slots, wording.providerLabel ?: NO_VALUE_TEXT)
        } else {
            stringResource(
                when (wording.overview.meteredPolicy) {
                    MeteredBackupPolicy.EVERY_60_MIN -> R.string.backup_limits_every_60_min
                    MeteredBackupPolicy.EVERY_30_MIN -> R.string.backup_limits_every_30_min
                    MeteredBackupPolicy.WIFI_ONLY -> R.string.backup_limits_wifi_only
                },
            )
        }
    Column {
        SectionHeader(stringResource(R.string.backup_limits_title))
        Column(
            Modifier.padding(horizontal = AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            Text(limits, style = MaterialTheme.typography.bodyMedium)
            encrypted?.let {
                Text(
                    stringResource(
                        if (it) {
                            R.string.backup_shared_notice_encrypted
                        } else {
                            R.string.backup_shared_notice
                        },
                        app,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `Copias en la nube`, con sus estados. */
private fun LazyListScope.copyList(
    uiState: BackupUiState,
    list: BackupCopyList?,
    onAction: (BackupAction) -> Unit,
) {
    item(key = COPIES_HEADER) { SectionHeader(stringResource(R.string.backup_copies_title)) }
    when (list) {
        null ->
            item(key = COPIES_LOADING) {
                DelayedSkeleton {
                    Column(
                        Modifier.padding(AppTheme.spacing.s16),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
                    ) {
                        repeat(SKELETON_ROWS) {
                            SkeletonBlock(
                                Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow),
                            )
                        }
                    }
                }
            }

        BackupCopyList.NoInternet ->
            item(key = COPIES_STATE) {
                InlineState(stringResource(R.string.backup_list_no_internet))
            }

        BackupCopyList.Failed ->
            item(key = COPIES_STATE) {
                InlineState(
                    stringResource(R.string.backup_list_error),
                    stringResource(R.string.action_retry),
                ) { onAction(BackupAction.RetryCopies) }
            }

        is BackupCopyList.Listed ->
            if (list.copies.isEmpty()) {
                item(key = COPIES_STATE) {
                    InlineState(stringResource(R.string.empty_backups_title))
                }
            } else {
                items(list.copies, key = { it.key }) { copy ->
                    BackupCopyRow(
                        copyRowData(copy, uiState.wording),
                        onClick =
                            if (copy.restorable) {
                                { onAction(BackupAction.OpenCopy(copy.key)) }
                            } else {
                                null
                            },
                    )
                }
            }

        BackupCopyList.NotConnected -> Unit
    }
}

@Composable
private fun InlineState(text: String, action: String? = null, onAction: () -> Unit = {}) {
    EmptyStateInline(
        title = text,
        modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        action = action?.let { label -> { InlineTextButton(label, onAction) } },
    )
}

/** Cuándo: `Hoy, 10:43`, `Ayer, 21:14` o la fecha, en la zona del negocio; nulo si no se sabe. */
@Composable
private fun instantOrNull(instant: Instant?, wording: BackupWording?): String? {
    val formats = LocalAppFormats.current
    val zone = wording?.zone
    if (instant == null || zone == null) return null
    val today = wording.overview.computedAt.let { BusinessDate.of(it, zone) }
    val day = BusinessDate.of(instant, zone)
    val time = formats.date.time(instant, zone)
    return when (day) {
        today -> stringResource(R.string.format_today_at, time)
        today.plusDays(-1) -> stringResource(R.string.format_yesterday_at, time)
        else -> formats.date.dayMonthTime(instant, zone)
    }
}

/** Como [instantOrNull], con un guion cuando no se sabe. */
@Composable
internal fun instantText(instant: Instant?, wording: BackupWording?): String =
    instantOrNull(instant, wording) ?: NO_VALUE_TEXT

/** Los textos de una copia: `Hoy, 10:43`, el tamaño, el origen, los registros y sus etiquetas. */
@Composable
internal fun copyRowData(copy: BackupCopySummary, wording: BackupWording?): BackupCopyRowData {
    val formats = LocalAppFormats.current
    val whenText = instantOrNull(copy.createdAt, wording)
    val slot = copy.slot
    val origin =
        when {
            slot != null -> stringResource(R.string.backup_slot_label, slot.name)
            copy.origin == CopyOrigin.THIS_PHONE -> stringResource(R.string.backup_device_this)
            else -> stringResource(R.string.backup_device_other)
        }
    val tags = buildList {
        if (copy.pinned) add(stringResource(R.string.backup_pinned_tag))
        if (copy.withDifferences) add(stringResource(R.string.backup_differences_tag))
        if (!copy.restorable) add(stringResource(R.string.backup_copy_not_restorable))
    }
    val records = copy.records?.let {
        val count = it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(R.plurals.backup_records_count, count, count)
    }
    return BackupCopyRowData(
        whenText = whenText,
        sizeText = DataSizeFormatter(
            formats.date.formatLocale,
            formats.uiLocale,
        ).megabytes(copy.sizeBytes),
        originText = origin,
        mostRecent = copy.newest,
        recordsText = records,
        tags = tags,
    )
}

// Desde 840 dp, el estado a la izquierda y las copias a la derecha.
private val EXPANDED_WIDTH = 840.dp

private const val SKELETON_ROWS = 3
private const val STATUS = "status"
private const val DESTINATION = "destination"
private const val METERED = "metered"
private const val COPIES_HEADER = "copiesHeader"
private const val COPIES_LOADING = "copiesLoading"
private const val COPIES_STATE = "copiesState"
private const val RESTORE_NOTICE = "restoreNotice"
private const val FIND_FILE = "findFile"
private const val PROTECTION = "protection"
private const val EXPORT_COPY = "exportCopy"
private const val DRILL = "drill"

/**
 * `Probar restauración`: el simulacro sobre una copia aparte. Mientras corre, una barra y qué está
 * haciendo, sin otro toque posible; nunca cambia los datos.
 */
@Composable
private fun Drill(drilling: Boolean, onAction: (BackupAction) -> Unit) {
    val spacing = AppTheme.spacing
    if (drilling) {
        Column(
            Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Text(
                stringResource(R.string.backup_drill_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    } else {
        Column(Modifier.padding(horizontal = spacing.s8)) {
            InlineTextButton(
                stringResource(R.string.backup_drill_action),
                { onAction(BackupAction.RunDrill) },
            )
        }
    }
}
