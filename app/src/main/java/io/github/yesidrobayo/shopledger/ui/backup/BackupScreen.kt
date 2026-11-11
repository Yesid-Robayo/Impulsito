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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupNotice
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.format.DataSizeFormatter
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRow
import io.github.yesidrobayo.shopledger.ui.components.BackupCopyRowData
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.backupToneContainer
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

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
            }
        }
    }
    if (wording != null && wording.overview.report.status != BackupStatus.NOT_CONNECTED) {
        item(key = DESTINATION) { Destination(uiState.destination, onAction) }
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

        BackupStatus.PERMISSION_LOST ->
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_reconnect),
                onClick = { onAction(BackupAction.ChooseDestination) },
                variant = ActionVariant.PRIMARY,
            )

        BackupStatus.POLICY_PENDING ->
            PrimaryActionButton(
                text = stringResource(R.string.backup_action_review_policy),
                onClick = { onAction(BackupAction.ReviewPolicy) },
                variant = ActionVariant.PRIMARY,
            )

        // El conflicto se decide en su propia hoja; aquí no hay copia que pedir.
        BackupStatus.CONFLICT -> Unit

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

/** `Dónde se guardan`: el usuario y el servidor, o la nube del teléfono, y cambiar el destino. */
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
            when (info) {
                is BackupDestinationInfo.WebDav -> {
                    Text(info.account ?: NO_VALUE_TEXT, style = MaterialTheme.typography.titleSmall)
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
            InlineTextButton(
                text = stringResource(R.string.backup_action_change_destination),
                onClick = { onAction(BackupAction.ChooseDestination) },
            )
        }
    }
}

/** `Copias en la nube` y su lista, con todos sus estados. Sin destino no aparece. */
private fun LazyListScope.copies(uiState: BackupUiState, onAction: (BackupAction) -> Unit) {
    val list = uiState.copies
    if (list is BackupCopyList.NotConnected) return
    if (uiState.wording?.overview?.report?.status == BackupStatus.NOT_CONNECTED) return
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
                    BackupCopyRow(copyRowData(copy, uiState.wording))
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

/** Los textos de una copia: `Hoy, 10:43`, el tamaño, el origen, los registros y sus etiquetas. */
@Composable
private fun copyRowData(copy: BackupCopySummary, wording: BackupWording?): BackupCopyRowData {
    val formats = LocalAppFormats.current
    val zone = wording?.zone
    val created = copy.createdAt
    val whenText =
        if (created == null || zone == null) {
            null
        } else {
            val today = wording.overview.computedAt.let { BusinessDate.of(it, zone) }
            val day = BusinessDate.of(created, zone)
            val time = formats.date.time(created, zone)
            when (day) {
                today -> stringResource(R.string.format_today_at, time)
                today.plusDays(-1) -> stringResource(R.string.format_yesterday_at, time)
                else -> formats.date.dayMonthTime(created, zone)
            }
        }
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
private const val COPIES_HEADER = "copiesHeader"
private const val COPIES_LOADING = "copiesLoading"
private const val COPIES_STATE = "copiesState"
