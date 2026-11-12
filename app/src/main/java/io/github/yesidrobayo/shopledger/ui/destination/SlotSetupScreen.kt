package io.github.yesidrobayo.shopledger.ui.destination

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupFile
import io.github.yesidrobayo.shopledger.domain.model.backup.newFileName
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.HelpLink
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SlotFileRow
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * `Preparar archivos en la nube`: los tres archivos donde la app escribe las copias, en la nube que
 * ya usa el teléfono, sabiendo lo que eso no hace.
 *
 * - Arriba, qué son los tres archivos; luego las tres filas, con el que sigue resaltado, y cómo se
 *   usa el selector.
 * - Si el último archivo no sirvió, un aviso con por qué y qué hacer.
 * - Avisos fijos: las fotos no van a estos archivos, y preparar no trae copias anteriores.
 * - Abajo, fija, una sola acción: `Crear el archivo`, o `Continuar` con los tres.
 * - Columna centrada de 600 dp en una ventana ancha.
 *
 * Es pura: recibe el estado y avisa cada acción.
 *
 * @param appName el nombre de la app, para `{app}`.
 */
@Composable
fun SlotSetupScreen(
    uiState: SlotSetupUiState,
    appName: String,
    onAction: (SlotSetupAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val provider = uiState.look?.providerLabel.orEmpty()
    val list = rememberLazyListState()
    // Un archivo que no sirvió se dice debajo de las filas: con letra grande quedaría fuera de la
    // vista, así que la lista baja hasta el aviso.
    LaunchedEffect(uiState.notice) {
        if (uiState.notice != null) list.animateScrollToItem(if (uiState.ready) 3 else 2)
    }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.slots_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(SlotSetupAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = {
                    HelpLink(stringResource(R.string.destination_title)) {
                        onAction(SlotSetupAction.Help)
                    }
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(horizontal = spacing.s16, vertical = spacing.s8),
                    verticalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    item(key = INTRO) {
                        Text(
                            stringResource(R.string.slots_intro, appName),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (uiState.ready) {
                        item(key = READY) { ReadyBlock(appName, provider) }
                    }
                    item(key = FILES) { Files(uiState) }
                    uiState.notice?.let { notice ->
                        item(key = NOTICE) { Rejection(notice, appName, provider, onAction) }
                    }
                    if (uiState.look?.otherDestination == true) {
                        item(key = OTHER_DESTINATION) {
                            NoticeCard(
                                stringResource(R.string.slots_other_destination_note, appName),
                                NoticeVariant.INFO,
                            )
                        }
                    }
                    if (uiState.look != null) {
                        item(key = HOW) { Guidance(provider) }
                    }
                    item(key = NOTES) { Notes(provider, onAction) }
                }
            }
            HorizontalDivider(
                thickness = AppTheme.sizes.hairline,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Box(
                Modifier
                    .widthIn(max = AppTheme.sizes.maxContentWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .keepsMessagesAbove()
                    .navigationBarsPadding()
                    .padding(spacing.s16),
            ) { MainAction(uiState, onAction) }
        }
    }
}

@Composable
private fun ReadyBlock(appName: String, provider: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            stringResource(R.string.slots_ready_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.slots_ready_body, appName, provider),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Las tres filas, o su esqueleto mientras se lee `slots.json`, solo si tarda. */
@Composable
private fun Files(uiState: SlotSetupUiState) {
    val look = uiState.look
    if (look == null) {
        DelayedSkeleton {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                repeat(BackupSlot.entries.size) {
                    SkeletonBlock(
                        Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRowSingle),
                    )
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        look.files.forEach { file ->
            SlotFileRow(
                title =
                    stringResource(
                        R.string.slots_file_row,
                        file.slot.ordinal + 1,
                        BackupSlot.entries.size,
                    ),
                fileName = file.fileName ?: file.slot.newFileName,
                statusText = stringResource(statusText(file)),
                exists = file.status != SlotFileStatus.MISSING,
                next = !uiState.ready && file.slot == look.next,
            )
        }
    }
}

private fun statusText(file: SlotSetupFile): Int = when (file.status) {
    SlotFileStatus.MISSING -> R.string.slots_file_pending
    SlotFileStatus.CREATED -> R.string.slots_file_created
    SlotFileStatus.READY -> R.string.slots_file_ready
}

/** Por qué no sirvió el archivo elegido, con su salida. */
@Composable
private fun Rejection(
    notice: SlotSetupNotice,
    appName: String,
    provider: String,
    onAction: (SlotSetupAction) -> Unit,
) {
    when (notice) {
        SlotSetupNotice.OutsideProviders ->
            NoticeCard(
                stringResource(R.string.slots_error_provider, appName, provider),
                NoticeVariant.WARNING,
                actionLabel = stringResource(R.string.slots_error_service_action),
                onAction = { onAction(SlotSetupAction.ConnectService) },
                secondaryLabel = stringResource(R.string.slots_error_local_action),
                onSecondary = { onAction(SlotSetupAction.GoToLocalCopies) },
            )

        SlotSetupNotice.NotWritable ->
            NoticeCard(
                stringResource(R.string.slots_error_not_writable, provider, appName),
                NoticeVariant.WARNING,
                actionLabel = stringResource(R.string.backup_action_other_service),
                onAction = { onAction(SlotSetupAction.ChooseOtherService) },
            )

        SlotSetupNotice.SizeUnknown ->
            NoticeCard(
                stringResource(R.string.slots_error_no_size, provider, appName),
                NoticeVariant.WARNING,
                actionLabel = stringResource(R.string.backup_action_other_service),
                onAction = { onAction(SlotSetupAction.ChooseOtherService) },
            )

        // Restaurar ese archivo llega con la pantalla de restaurar: hasta entonces, solo otro.
        SlotSetupNotice.NotEmpty ->
            NoticeCard(
                stringResource(R.string.slots_error_not_empty, appName),
                NoticeVariant.WARNING,
                actionLabel = stringResource(R.string.slots_other_file_action),
                onAction = { onAction(SlotSetupAction.CreateAnotherFile) },
            )

        is SlotSetupNotice.SameFile ->
            NoticeCard(
                stringResource(R.string.slots_error_same_file, notice.fileNumber),
                NoticeVariant.WARNING,
                actionLabel = stringResource(R.string.slots_other_file_action),
                onAction = { onAction(SlotSetupAction.CreateAnotherFile) },
            )
    }
}

/** Cómo se usa el selector, y qué hacer si esa nube no aparece. */
@Composable
private fun Guidance(provider: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            stringResource(R.string.slots_how, provider),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        Text(
            stringResource(R.string.slots_no_cloud_help, provider),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
    }
}

/**
 * Lo que preparar no hace: las fotos no van, y sin cifrado esa nube puede leer todo; y no trae
 * copias anteriores. Las copias todavía no se cifran en la app, así que la advertencia va siempre.
 * `Buscar un archivo` llega con la pantalla de restaurar: hasta entonces el aviso no lleva acción.
 */
@Composable
private fun Notes(provider: String, onAction: (SlotSetupAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
        NoticeCard(
            stringResource(R.string.slots_photos_note) + "\n" +
                stringResource(R.string.slots_plain_warning, provider),
            NoticeVariant.INFO,
            actionLabel = stringResource(R.string.offsite_export_photos_action),
            onAction = { onAction(SlotSetupAction.ExportPhotos) },
        )
        NoticeCard(stringResource(R.string.slots_no_restore_note), NoticeVariant.INFO)
    }
}

@Composable
private fun MainAction(uiState: SlotSetupUiState, onAction: (SlotSetupAction) -> Unit) {
    val look = uiState.look
    val create = look != null && look.next != null && !uiState.ready
    PrimaryActionButton(
        text = stringResource(if (look == null || create) CREATE else CONTINUE),
        onClick = {
            onAction(if (create) SlotSetupAction.CreateFile else SlotSetupAction.Continue)
        },
        variant = ActionVariant.PRIMARY,
        modifier = Modifier.fillMaxWidth(),
        enabled = look != null,
        state = if (uiState.working) ActionState.WORKING else ActionState.IDLE,
    )
}

private val CREATE = R.string.slots_create_action
private val CONTINUE = R.string.action_continue

private const val INTRO = "intro"
private const val READY = "ready"
private const val FILES = "files"
private const val NOTICE = "notice"
private const val OTHER_DESTINATION = "other-destination"
private const val HOW = "how"
private const val NOTES = "notes"
