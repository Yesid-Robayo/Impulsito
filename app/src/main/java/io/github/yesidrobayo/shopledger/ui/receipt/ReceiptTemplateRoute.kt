package io.github.yesidrobayo.shopledger.ui.receipt

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ImportReceiptLogoUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SaveReceiptTemplateUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.MarkUnsavedWork
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import kotlinx.coroutines.flow.map

/**
 * La plantilla del comprobante, abierta desde los ajustes.
 *
 * - El logo se elige con el selector de fotos del sistema, que no pide permisos; lo elegido se lee
 *   una sola vez, al recodificarlo.
 * - Guardar avisa con un mensaje y deja la pantalla abierta, para seguir viendo la vista previa.
 * - Con cambios sin guardar, atrás y cerrar preguntan antes de descartarlos.
 * - El nombre del negocio se cambia en los ajustes: tocarlo vuelve ahí.
 */
@Composable
fun ReceiptTemplateRoute(key: AppNavKey.ReceiptTemplate, navigator: AppNavigator) {
    val model =
        rememberAppViewModel(null) { dependencies ->
            val ports = dependencies.ports
            val save = SaveReceiptTemplateUseCase(
                ports.transactions,
                ports.settings,
                ports.mediaAssets,
                dependencies.container.clock,
            )
            val importLogo = ImportReceiptLogoUseCase(dependencies.container.data.imageImporter)
            ReceiptTemplateViewModel(
                observeSource = {
                    ports.settings.observe().map {
                        ReceiptTemplateSource(it.receipt, it.profile.businessName)
                    }
                },
                loadImage = { id -> ports.transactions.read { ports.mediaAssets.findImage(id) } },
                importLogo = importLogo::invoke,
                save = save::invoke,
            )
        }
    when (model) {
        ScreenModel.Opening ->
            ReceiptTemplateScreen(ReceiptTemplateUiState.Loading, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val editing = uiState as? ReceiptTemplateUiState.Editing
            val messages = LocalAppMessages.current
            val savedText = stringResource(R.string.receipt_saved)
            LaunchedEffect(editing?.saved) {
                if (editing?.saved != true) return@LaunchedEffect
                messages.show(AppSnackbarVisuals(savedText, SnackbarVariant.SUCCESS))
                viewModel.onAction(ReceiptTemplateAction.SavedShown)
            }
            LaunchedEffect(editing?.left) {
                if (editing?.left == true) navigator.back()
            }
            MarkUnsavedWork(key, editing?.changed == true)
            BackHandler(enabled = editing?.changed == true) {
                viewModel.onAction(ReceiptTemplateAction.Leave)
            }
            val picker =
                rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
                    if (it != null) {
                        viewModel.onAction(ReceiptTemplateAction.PickLogo(it.toString()))
                    }
                }
            ReceiptTemplateScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        // Los ajustes son la pantalla de abajo: volver es abrirlos.
                        ReceiptTemplateAction.OpenBusinessName ->
                            viewModel.onAction(ReceiptTemplateAction.Leave)

                        is ReceiptTemplateAction.DetailsChanged,
                        is ReceiptTemplateAction.FooterChanged,
                        is ReceiptTemplateAction.PickLogo,
                        ReceiptTemplateAction.RemoveLogo,
                        ReceiptTemplateAction.Save,
                        ReceiptTemplateAction.Leave,
                        ReceiptTemplateAction.ConfirmLeave,
                        ReceiptTemplateAction.DismissLeave,
                        ReceiptTemplateAction.Retry,
                        ReceiptTemplateAction.SavedShown,
                        -> viewModel.onAction(action)
                    }
                },
                onPickLogo = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
        }
    }
}
