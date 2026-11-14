package io.github.yesidrobayo.shopledger.ui.localcopies

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.SecondDestinationUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.lock.rememberSensitiveCheck
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import kotlinx.coroutines.launch

/**
 * `Copias locales` en la navegación. `Guardar en un archivo` abre el selector del sistema para crear
 * el documento, con el nombre sugerido y sin pedir ningún permiso; la copia se escribe en el
 * documento que devuelve. Cancelar el selector no cambia nada.
 */
@Composable
fun LocalCopiesRoute(navigator: AppNavigator) {
    val appName = stringResource(R.string.app_name)
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
                second = SecondDestinationUseCase(container.data.secondDestination),
                appName = appName,
                photoFacts = { container.data.photoBackup.facts() },
                exportPhotos = { uri -> container.data.photoBackup.export(uri) },
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
            val photosSaved = stringResource(R.string.offsite_photos_saved)
            val folderFailed = stringResource(R.string.state_error_save)
            val obscured = stringResource(R.string.security_obscured_touch)
            // Con el bloqueo encendido, exportar pide de nuevo la verificación del sistema: el
            // archivo lleva todo el negocio.
            val sensitive = rememberSensitiveCheck()
            val scope = rememberCoroutineScope()
            val exportTitle = stringResource(R.string.offsite_save_file_action)
            val folders =
                rememberLauncherForActivityResult(LocalFolderPicker()) { uri ->
                    uri?.let { viewModel.onAction(LocalCopiesAction.FolderPicked(it.toString())) }
                }
            LifecycleResumeEffect(viewModel) {
                viewModel.onAction(LocalCopiesAction.Resumed)
                onPauseOrDispose {}
            }
            val picker =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(GZIP_TYPE),
                ) { uri ->
                    uri?.let { viewModel.onAction(LocalCopiesAction.FileCreated(it.toString())) }
                }
            // Con el cifrado activo la copia sale cifrada: otro tipo de archivo, y otra extensión.
            val encryptedPicker =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(ENCRYPTED_TYPE),
                ) { uri ->
                    uri?.let { viewModel.onAction(LocalCopiesAction.FileCreated(it.toString())) }
                }
            val photosPicker =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(ZIP_TYPE),
                ) { uri ->
                    uri?.let {
                        viewModel.onAction(LocalCopiesAction.PhotosFileCreated(it.toString()))
                    }
                }
            LaunchedEffect(uiState.message) {
                when (uiState.message) {
                    LocalCopiesMessage.SAVED -> {
                        viewModel.onAction(LocalCopiesAction.MessageShown)
                        messages.show(AppSnackbarVisuals(saved, SnackbarVariant.SUCCESS))
                    }

                    LocalCopiesMessage.FOLDER_FAILED -> {
                        viewModel.onAction(LocalCopiesAction.MessageShown)
                        messages.show(AppSnackbarVisuals(folderFailed, SnackbarVariant.ERROR))
                    }

                    LocalCopiesMessage.PHOTOS_SAVED -> {
                        viewModel.onAction(LocalCopiesAction.MessageShown)
                        messages.show(AppSnackbarVisuals(photosSaved, SnackbarVariant.SUCCESS))
                    }

                    LocalCopiesMessage.OBSCURED_TOUCH -> {
                        viewModel.onAction(LocalCopiesAction.MessageShown)
                        messages.show(AppSnackbarVisuals(obscured, SnackbarVariant.INFO))
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
                            scope.launch {
                                if (sensitive.confirm(exportTitle)) {
                                    // Sin una app que cree documentos no hay selector: es como
                                    // cancelar.
                                    try {
                                        if (uiState.encrypted == true) {
                                            encryptedPicker.launch(name)
                                        } else {
                                            picker.launch(name)
                                        }
                                    } catch (_: ActivityNotFoundException) {
                                        Unit
                                    }
                                }
                            }
                        }
                    }

                    LocalCopiesAction.ChooseFolder ->
                        // Sin una app que elija carpetas no hay selector: es como cancelar.
                        try {
                            folders.launch(LocalFolderPicker.documents())
                        } catch (_: ActivityNotFoundException) {
                            Unit
                        }

                    LocalCopiesAction.ExportPhotos ->
                        if (!uiState.photos.working) {
                            val name = viewModel.suggestedPhotosName()
                            // Las fotos son del negocio: con el bloqueo encendido, se pide de nuevo.
                            scope.launch {
                                if (sensitive.confirm(exportTitle)) {
                                    // Sin una app que cree documentos no hay selector.
                                    try {
                                        photosPicker.launch(name)
                                    } catch (_: ActivityNotFoundException) {
                                        Unit
                                    }
                                }
                            }
                        }

                    is LocalCopiesAction.FileCreated,
                    is LocalCopiesAction.PhotosFileCreated,
                    LocalCopiesAction.DismissPhotosFailure,
                    LocalCopiesAction.DismissResult,
                    LocalCopiesAction.MessageShown,
                    is LocalCopiesAction.FolderPicked,
                    is LocalCopiesAction.SetFollow,
                    LocalCopiesAction.AskRemove,
                    LocalCopiesAction.ConfirmRemove,
                    LocalCopiesAction.CloseRemove,
                    LocalCopiesAction.Resumed,
                    LocalCopiesAction.ObscuredTouch,
                    -> viewModel.onAction(action)
                }
            })
        }
    }
}

/** El tipo del archivo exportado: una base comprimida con gzip. */
private const val GZIP_TYPE = "application/gzip"

/** El tipo del ZIP de las fotos exportadas. */
private const val ZIP_TYPE = "application/zip"

/** El tipo de una copia cifrada, que no es gzip para nadie más que la app. */
private const val ENCRYPTED_TYPE = "application/octet-stream"
