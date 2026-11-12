package io.github.yesidrobayo.shopledger.ui.localcopies

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant

/**
 * `Copias locales` en la navegación. `Guardar en un archivo` abre el selector del sistema para crear
 * el documento, con el nombre sugerido y sin pedir ningún permiso; la copia se escribe en el
 * documento que devuelve. Cancelar el selector no cambia nada.
 */
@Composable
fun LocalCopiesRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val container = dependencies.container
            LocalCopiesViewModel(
                export = { uri -> container.data.fullCopyExport.export(uri) },
                encrypted = { container.data.backupSettings.copiesEncrypted() },
                zone = {
                    val current = BusinessCalendarSource(dependencies.ports.settings).current()
                    (current as? BusinessCalendarResult.Ready)?.calendar?.zone
                },
                now = container.clock::now,
                computation = container.dispatchers.io,
            )
        }
    when (model) {
        ScreenModel.Opening -> LocalCopiesScreen(LocalCopiesUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.LocalCopies, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val saved = stringResource(R.string.offsite_saved)
            val picker =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(GZIP_TYPE),
                ) { uri ->
                    uri?.let { viewModel.onAction(LocalCopiesAction.FileCreated(it.toString())) }
                }
            LaunchedEffect(uiState.message) {
                when (uiState.message) {
                    LocalCopiesMessage.SAVED -> {
                        viewModel.onAction(LocalCopiesAction.MessageShown)
                        messages.show(AppSnackbarVisuals(saved, SnackbarVariant.SUCCESS))
                    }

                    null -> Unit
                }
            }
            LocalCopiesScreen(uiState, onAction = { action ->
                when (action) {
                    LocalCopiesAction.Back -> navigator.back()

                    LocalCopiesAction.SaveToFile -> {
                        if (uiState.export !is ExportUi.Working) {
                            viewModel.onAction(LocalCopiesAction.DismissResult)
                            val name = viewModel.suggestedName()
                            // Sin una app que cree documentos no hay selector: es como cancelar.
                            try {
                                picker.launch(name)
                            } catch (_: ActivityNotFoundException) {
                                Unit
                            }
                        }
                    }

                    is LocalCopiesAction.FileCreated,
                    LocalCopiesAction.DismissResult,
                    LocalCopiesAction.MessageShown,
                    -> viewModel.onAction(action)
                }
            })
        }
    }
}

/** El tipo del archivo exportado: una base comprimida con gzip. */
private const val GZIP_TYPE = "application/gzip"
