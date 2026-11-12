package io.github.yesidrobayo.shopledger.ui.localcopies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
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
                    modifier = Modifier.fillMaxWidth(),
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

private const val INTRO = "intro"
private const val EXPORT = "export"
private const val RESULT = "result"

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
