package io.github.yesidrobayo.shopledger.ui.destination

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.newFileName
import io.github.yesidrobayo.shopledger.domain.usecase.backup.PrepareSlotFilesUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant

/** El artículo de ayuda de los destinos, en la ayuda de la app. */
private const val HELP_ARTICLE = "help_destinations"

/**
 * `Preparar archivos en la nube` en la navegación. La política ya se aceptó: la navegación la
 * pregunta antes de mostrar esta pantalla.
 *
 * Cada `Crear el archivo` abre el selector del sistema para crear un documento nuevo, con el nombre
 * propuesto de la ranura que sigue. El documento que devuelve lo revisa la capa de datos.
 */
@Composable
fun SlotSetupRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            SlotSetupViewModel(
                setup = PrepareSlotFilesUseCase(dependencies.container.data.slotFilesSetup),
                computation = dependencies.container.dispatchers.io,
            )
        }
    val appName = stringResource(R.string.app_name)
    when (model) {
        ScreenModel.Opening -> SlotSetupScreen(SlotSetupUiState(), appName, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.DocumentSlotsSetup, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val cancelled = stringResource(R.string.slots_error_cancelled)
            val saveFailed = stringResource(R.string.state_error_save)
            val picker =
                rememberLauncherForActivityResult(CreateSlotDocument()) { uri ->
                    viewModel.onAction(SlotSetupAction.FilePicked(uri?.toString()))
                }
            LaunchedEffect(uiState.message) {
                val message = uiState.message ?: return@LaunchedEffect
                viewModel.onAction(SlotSetupAction.MessageShown)
                val text =
                    when (message) {
                        SlotSetupMessage.CANCELLED -> cancelled
                        SlotSetupMessage.SAVE_FAILED -> saveFailed
                    }
                messages.show(AppSnackbarVisuals(text, SnackbarVariant.INFO))
            }
            LaunchedEffect(uiState.target) {
                val target = uiState.target ?: return@LaunchedEffect
                viewModel.onAction(SlotSetupAction.TargetHandled)
                when (target) {
                    SlotSetupTarget.BACK -> navigator.back()
                    SlotSetupTarget.BACKUP -> navigator.returnToBackup()
                    SlotSetupTarget.WEBDAV_CONNECT -> navigator.open(AppNavKey.WebDavConnect())
                    SlotSetupTarget.LOCAL_COPIES -> navigator.open(AppNavKey.LocalCopies)
                    SlotSetupTarget.DESTINATION_CHOICE -> navigator.returnToDestinationChoice()
                    SlotSetupTarget.HELP -> navigator.open(AppNavKey.Help(HELP_ARTICLE))
                }
            }
            SlotSetupScreen(uiState, appName, onAction = { action ->
                when (action) {
                    SlotSetupAction.CreateFile -> {
                        viewModel.onAction(action)
                        uiState.next?.let { slot ->
                            // Sin una app que cree documentos no hay selector: es como cancelar.
                            try {
                                picker.launch(slot.newFileName)
                            } catch (_: ActivityNotFoundException) {
                                viewModel.onAction(SlotSetupAction.FilePicked(null))
                            }
                        }
                    }

                    else -> viewModel.onAction(action)
                }
            })
        }
    }
}

/**
 * Vuelve al respaldo: quita esta pantalla y las de elegir y conectar el destino. Si el respaldo
 * estaba debajo, queda a la vista; si no, se abre.
 */
private fun AppNavigator.returnToBackup() {
    while (current.isDestinationSetup() && back()) Unit
    open(AppNavKey.Backup)
}

/** Vuelve a elegir dónde guardar: a la pantalla de antes si era esa, o la abre. */
private fun AppNavigator.returnToDestinationChoice() {
    back()
    if (current !is AppNavKey.BackupDestinationChoice) open(AppNavKey.BackupDestinationChoice())
}

private fun AppNavKey.isDestinationSetup(): Boolean = this == AppNavKey.DocumentSlotsSetup ||
    this is AppNavKey.BackupDestinationChoice ||
    this is AppNavKey.WebDavConnect

/**
 * Crear un documento nuevo en el selector del sistema: `ACTION_CREATE_DOCUMENT`, abrible, de tipo
 * binario y con el nombre propuesto. El sistema concede un permiso que la app puede conservar.
 */
private class CreateSlotDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(SLOT_MIME_TYPE)
            .putExtra(Intent.EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }

    private companion object {
        const val SLOT_MIME_TYPE = "application/octet-stream"
    }
}
