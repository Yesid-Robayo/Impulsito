package io.github.yesidrobayo.shopledger.ui.localcopies

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.lock.HideOverlaysWhileShown
import io.github.yesidrobayo.shopledger.lock.rejectObscuredTouches
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * `Copias locales`: una copia completa fuera de la nube.
 *
 * - Arriba, para qué sirve; luego `Exportar una copia completa`, con su explicación y el aviso de si
 *   sale en claro o cifrada.
 * - El resultado de la última exportación, si no salió bien o no se podría restaurar.
 * - Abajo, fija, la acción principal: `Guardar en un archivo`, que mientras trabaja dice `Creando la
 *   copia` y no atiende toques.
 * - Columna centrada de 600 dp en una ventana ancha. Nada usa internet.
 *
 * Es pura: recibe el estado y avisa cada acción. Los avisos breves los muestra la navegación.
 */
@Composable
fun LocalCopiesScreen(
    uiState: LocalCopiesUiState,
    onAction: (LocalCopiesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    // Exportar saca todo el negocio: mientras se ve, ninguna otra app se dibuja encima.
    HideOverlaysWhileShown()
    if (uiState.confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.offsite_remove_confirm_title),
            body = stringResource(R.string.offsite_remove_confirm_body),
            confirmLabel = stringResource(R.string.offsite_remove_destination_action),
            onConfirm = { onAction(LocalCopiesAction.ConfirmRemove) },
            onDismiss = { onAction(LocalCopiesAction.CloseRemove) },
            variant = ConfirmVariant.DESTRUCTIVE,
            dismissLabel = stringResource(R.string.action_cancel),
            sensitive = true,
        )
    }
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.offsite_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(LocalCopiesAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = { ScreenHelpLink(HelpTopic.LOCAL_COPIES) },
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = spacing.s16, vertical = spacing.s8),
                    verticalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    item(key = INTRO) {
                        Text(
                            stringResource(R.string.offsite_intro),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    item(key = EXPORT) { ExportSection(uiState.encrypted) }
                    item(key = RESULT) { ExportResult(uiState.export, onAction) }
                    item(key = SECOND) { SecondSection(uiState, onAction) }
                    item(key = PHOTOS) { PhotosSection(uiState.photos, onAction) }
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
            ) {
                val working = uiState.export is ExportUi.Working
                PrimaryActionButton(
                    text =
                        stringResource(
                            if (working) {
                                R.string.offsite_exporting
                            } else {
                                R.string.offsite_save_file_action
                            },
                        ),
                    onClick = { onAction(LocalCopiesAction.SaveToFile) },
                    variant = ActionVariant.PRIMARY,
                    // Región viva: al empezar, TalkBack anuncia `Creando la copia` sin mover
                    // el foco.
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .rejectObscuredTouches { onAction(LocalCopiesAction.ObscuredTouch) },
                    state = if (working) ActionState.WORKING else ActionState.IDLE,
                )
            }
        }
    }
}

/** `Exportar una copia completa`: qué es, y si el archivo sale en claro o cifrado. */
@Composable
private fun ExportSection(encrypted: Boolean?) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        Text(
            stringResource(R.string.offsite_export_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = AppTheme.spacing.s8).semantics { heading() },
        )
        Text(
            stringResource(R.string.offsite_export_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (encrypted) {
            true ->
                NoticeCard(
                    stringResource(R.string.offsite_export_encrypted_info),
                    NoticeVariant.INFO,
                )

            false ->
                NoticeCard(
                    stringResource(R.string.offsite_export_plain_warning),
                    NoticeVariant.WARNING,
                )

            // Mientras se lee no se adelanta ningún aviso.
            null -> Unit
        }
    }
}

/**
 * El resultado que se queda a la vista: un fallo, con su arreglo, o una copia que se guardó pero no
 * se podría restaurar. Una copia guardada sin problema se dice con un aviso breve.
 */
@Composable
private fun ExportResult(export: ExportUi, onAction: (LocalCopiesAction) -> Unit) {
    val app = stringResource(R.string.app_name)
    when (export) {
        ExportUi.Idle, ExportUi.Working -> Unit

        ExportUi.SavedNotRestorable ->
            NoticeCard(
                stringResource(R.string.offsite_saved_not_restorable, app),
                NoticeVariant.WARNING,
                actionLabel = stringResource(R.string.action_close),
                onAction = { onAction(LocalCopiesAction.DismissResult) },
            )

        is ExportUi.Failed ->
            when (export.reason) {
                ExportFailure.NO_SPACE ->
                    NoticeCard(
                        stringResource(R.string.offsite_error_space),
                        NoticeVariant.ERROR,
                        actionLabel = stringResource(R.string.offsite_save_file_action),
                        onAction = { onAction(LocalCopiesAction.SaveToFile) },
                    )

                ExportFailure.DAMAGED ->
                    NoticeCard(
                        stringResource(R.string.offsite_error_damaged),
                        NoticeVariant.ERROR,
                        actionLabel = stringResource(R.string.action_close),
                        onAction = { onAction(LocalCopiesAction.DismissResult) },
                    )

                ExportFailure.OTHER ->
                    NoticeCard(
                        stringResource(R.string.offsite_error_export),
                        NoticeVariant.ERROR,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { onAction(LocalCopiesAction.SaveToFile) },
                    )
            }
    }
}

/**
 * `Segundo destino automático`: qué hace, la carpeta y su última copia, el problema de la última,
 * `Mantener al día esta carpeta`, y cambiar o quitar la carpeta. Un problema aquí nunca es un
 * fallo del respaldo: se dice solo en esta sección.
 */
@Composable
private fun SecondSection(uiState: LocalCopiesUiState, onAction: (LocalCopiesAction) -> Unit) {
    val app = stringResource(R.string.app_name)
    val choose = { onAction(LocalCopiesAction.ChooseFolder) }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        Text(
            stringResource(R.string.offsite_second_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = AppTheme.spacing.s16).semantics { heading() },
        )
        Text(
            stringResource(R.string.offsite_second_body, app),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (uiState.folderRejected) {
            NoticeCard(
                stringResource(R.string.offsite_second_folder_rejected),
                NoticeVariant.ERROR,
                actionLabel = stringResource(R.string.offsite_choose_folder_action),
                onAction = choose,
            )
        }
        when (val second = uiState.second) {
            // Se lee del teléfono: tras un instante, el esqueleto.
            null -> DelayedSkeleton {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }

            SecondDestinationView.None ->
                SettingsRow(
                    title = stringResource(R.string.offsite_choose_folder_action),
                    action = SettingsRowAction.Navigation(choose),
                )

            SecondDestinationView.Unreadable ->
                NoticeCard(
                    stringResource(R.string.offsite_permission_lost),
                    NoticeVariant.WARNING,
                    actionLabel = stringResource(R.string.offsite_choose_folder_action),
                    onAction = choose,
                )

            is SecondDestinationView.Connected -> ConnectedFolder(second, uiState, onAction)
        }
    }
}

/**
 * `Fotos`: nunca entran en la copia. `Exportar fotos` crea un ZIP aparte; sin fotos en el teléfono
 * no se ofrece. Un fallo se dice solo aquí: nunca es un fallo del respaldo.
 */
@Composable
private fun PhotosSection(photos: PhotosUi, onAction: (LocalCopiesAction) -> Unit) {
    val export = { onAction(LocalCopiesAction.ExportPhotos) }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        Text(
            stringResource(R.string.offsite_photos_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = AppTheme.spacing.s16).semantics { heading() },
        )
        Text(
            stringResource(R.string.offsite_photos_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (photos.exportable) {
            // Se lee del teléfono: tras un instante, el esqueleto.
            null -> DelayedSkeleton {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }

            0 -> Text(
                stringResource(R.string.offsite_no_photos),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> PrimaryActionButton(
                text = stringResource(R.string.offsite_export_photos_action),
                onClick = export,
                variant = ActionVariant.OUTLINED,
                state = if (photos.working) ActionState.WORKING else ActionState.IDLE,
            )
        }
        when (photos.failure) {
            PhotosFailure.KEY_MISSING ->
                NoticeCard(
                    stringResource(R.string.offsite_photos_key_missing),
                    NoticeVariant.WARNING,
                    actionLabel = stringResource(R.string.action_close),
                    onAction = { onAction(LocalCopiesAction.DismissPhotosFailure) },
                )

            PhotosFailure.OTHER ->
                NoticeCard(
                    stringResource(R.string.offsite_photos_failed),
                    NoticeVariant.ERROR,
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = export,
                )

            null -> Unit
        }
    }
}

@Composable
private fun ConnectedFolder(
    second: SecondDestinationView.Connected,
    uiState: LocalCopiesUiState,
    onAction: (LocalCopiesAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    val choose = { onAction(LocalCopiesAction.ChooseFolder) }
    // La carpeta y su última copia son un solo foco. Sin nombre, la raya se lee `Sin dato`.
    Column(
        Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        val folder = second.folderName
        val noValue = stringResource(R.string.a11y_no_value)
        val folderReading = stringResource(R.string.offsite_folder_value, folder ?: noValue)
        Text(
            stringResource(R.string.offsite_folder_value, folder ?: NO_VALUE_TEXT),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { contentDescription = folderReading },
        )
        val lastCopy = second.lastCopyAt
        val zone = uiState.zone
        if (lastCopy != null && zone != null) {
            Text(
                stringResource(
                    R.string.offsite_last_copy,
                    formats.date.dayMonthTime(lastCopy, zone),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    second.problem?.let { problem ->
        when (problem) {
            SecondDestinationProblem.FOLDER_MISSING ->
                NoticeCard(
                    stringResource(R.string.offsite_folder_missing),
                    NoticeVariant.WARNING,
                    actionLabel = stringResource(R.string.offsite_choose_folder_action),
                    onAction = choose,
                )

            SecondDestinationProblem.PERMISSION_LOST ->
                NoticeCard(
                    stringResource(R.string.offsite_permission_lost),
                    NoticeVariant.WARNING,
                    actionLabel = stringResource(R.string.offsite_choose_folder_action),
                    onAction = choose,
                )

            SecondDestinationProblem.NO_SPACE ->
                NoticeCard(stringResource(R.string.offsite_error_space), NoticeVariant.WARNING)

            SecondDestinationProblem.WRITE_FAILED ->
                NoticeCard(stringResource(R.string.offsite_write_failed), NoticeVariant.WARNING)

            SecondDestinationProblem.KEY_UNAVAILABLE ->
                NoticeCard(stringResource(R.string.offsite_key_unavailable), NoticeVariant.WARNING)

            SecondDestinationProblem.FATAL_SKIPPED ->
                NoticeCard(stringResource(R.string.offsite_fatal_skipped), NoticeVariant.WARNING)
        }
    }
    SettingsRow(
        title = stringResource(R.string.offsite_second_follow_row),
        action =
            SettingsRowAction.Toggle(second.followMainBackup) {
                onAction(LocalCopiesAction.SetFollow(it))
            },
        summary = stringResource(R.string.offsite_second_follow_help),
    )
    InlineTextButton(stringResource(R.string.offsite_change_folder_action), choose)
    InlineTextButton(
        stringResource(R.string.offsite_remove_destination_action),
        { onAction(LocalCopiesAction.AskRemove) },
    )
}

private const val INTRO = "intro"
private const val SECOND = "second"
private const val EXPORT = "export"
private const val RESULT = "result"
private const val PHOTOS = "photos"

@ThemePreviews
@Composable
private fun LocalCopiesPlainPreview() {
    PreviewTheme { LocalCopiesScreen(LocalCopiesUiState(encrypted = false), {}) }
}

@ThemePreviews
@Composable
private fun LocalCopiesWorkingPreview() {
    PreviewTheme {
        LocalCopiesScreen(LocalCopiesUiState(encrypted = true, export = ExportUi.Working), {})
    }
}

@ThemePreviews
@Composable
private fun LocalCopiesFailedPreview() {
    PreviewTheme {
        LocalCopiesScreen(
            LocalCopiesUiState(
                encrypted = false,
                export = ExportUi.Failed(ExportFailure.OTHER),
            ),
            {},
        )
    }
}
