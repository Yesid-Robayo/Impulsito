package io.github.yesidrobayo.shopledger.ui.update

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.update.ApkVerifier

/**
 * `Actualizaciones y verificar APK` en la navegación. `Elegir archivo` abre el selector del
 * sistema: solo se lee lo elegido, una vez, sin permiso persistente. La app nunca instala nada.
 *
 * Sin la base, la pantalla sigue, sin la versión ni la huella y sin verificar.
 */
@Composable
fun UpdatesRoute(navigator: AppNavigator) {
    val app = LocalContext.current.applicationContext
    val model =
        rememberAppViewModel { dependencies ->
            val verifier = ApkVerifier(app, dependencies.container.dispatchers.io)
            UpdatesViewModel(
                installedApp = { verifier.installed() },
                verifyApk = { uri -> verifier.verify(Uri.parse(uri)) },
            )
        }
    val viewModel = (model as? ScreenModel.Ready)?.viewModel
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            viewModel?.onAction(UpdatesAction.FileChosen(uri?.toString()))
        }
    val onAction: (UpdatesAction) -> Unit = { action ->
        when (action) {
            UpdatesAction.Back -> navigator.back()

            // Algunos gestores de archivos entregan un APK sin su tipo: se acepta también el
            // genérico, y lo que no sea un APK lo dice el resultado.
            UpdatesAction.ChooseFile -> if (viewModel != null) picker.launch(APK_TYPES)

            is UpdatesAction.FileChosen -> viewModel?.onAction(action)
        }
    }
    when (model) {
        ScreenModel.Opening -> UpdatesScreen(UpdatesUiState(), onAction)

        ScreenModel.Unavailable ->
            UpdatesScreen(UpdatesUiState(installedLoading = false), onAction)

        is ScreenModel.Ready -> {
            val uiState by model.viewModel.uiState.collectAsStateWithLifecycle()
            UpdatesScreen(uiState, onAction)
        }
    }
}

private val APK_TYPES =
    arrayOf("application/vnd.android.package-archive", "application/octet-stream")
