package io.github.yesidrobayo.shopledger.ui.update

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.BuildConfig
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ChangePreferenceUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.update.ApkVerifier
import kotlin.time.Instant
import kotlinx.coroutines.flow.map

/**
 * `Actualizaciones y verificar APK` en la navegación.
 *
 * - `Buscar actualización` lee el aviso de versión de `update_manifest_url` y comprueba su firma
 *   con `update_manifest_public_key`, los dos dentro de la app. Sin alguno de los dos, la búsqueda
 *   dice que no se pudo leer el aviso, y nada se bloquea. Es la única consulta de red fuera del
 *   servicio WebDAV, y solo corre al tocar el botón.
 * - `Elegir archivo` abre el selector del sistema: solo se lee lo elegido, una vez, sin permiso
 *   persistente. La app nunca instala nada.
 *
 * Sin la base, la pantalla sigue, sin la versión, sin la búsqueda y sin verificar.
 */
@Composable
fun UpdatesRoute(navigator: AppNavigator) {
    val app = LocalContext.current.applicationContext
    val messages = LocalAppMessages.current
    val notSaved = stringResource(R.string.state_error_save)
    val manifestUrl = stringResource(R.string.update_manifest_url)
    val publicKey = stringResource(R.string.update_manifest_public_key)
    val model =
        rememberAppViewModel { dependencies ->
            val container = dependencies.container
            val settings = dependencies.ports.settings
            val verifier = ApkVerifier(app, container.dispatchers.io)
            val changePreference = ChangePreferenceUseCase(settings, container.clock)
            UpdatesViewModel(
                installedApp = { verifier.installed() },
                verifyApk = { uri -> verifier.verify(Uri.parse(uri)) },
                searchEnabled = settings.observe().map { it.preferences.updateCheckEnabled },
                setSearchEnabled = { enabled ->
                    changePreference(PreferenceChange.UpdateCheck(enabled)) ==
                        ChangePreferenceResult.Saved
                },
                checkForUpdate = {
                    container.data.checkForUpdate(manifestUrl, publicKey, builtAt())
                },
                lastSearchAt = { container.data.lastUpdateCheckAt() },
                zone =
                    BusinessCalendarSource(settings).observe().map { result ->
                        (result as? BusinessCalendarResult.Ready)?.calendar?.zone
                    },
                now = { container.clock.now() },
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

            UpdatesAction.Search,
            is UpdatesAction.SetSearchEnabled,
            UpdatesAction.MessageShown,
            is UpdatesAction.FileChosen,
            -> viewModel?.onAction(action)
        }
    }
    when (model) {
        ScreenModel.Opening -> UpdatesScreen(UpdatesUiState(), onAction)

        ScreenModel.Unavailable ->
            UpdatesScreen(UpdatesUiState(installedLoading = false), onAction)

        is ScreenModel.Ready -> {
            val ready = model.viewModel
            val uiState by ready.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(uiState.saveFailed) {
                if (uiState.saveFailed) {
                    ready.onAction(UpdatesAction.MessageShown)
                    messages.show(AppSnackbarVisuals(notSaved, SnackbarVariant.ERROR))
                }
            }
            UpdatesScreen(uiState, onAction)
        }
    }
}

/**
 * La fecha de compilación, la del último commit del código, o nula si se compiló sin Git: entonces
 * ningún aviso se descarta por su fecha.
 */
private fun builtAt(): Instant? =
    BuildConfig.SOURCE_DATE_MILLIS.takeIf { it > 0 }?.let(Instant::fromEpochMilliseconds)

private val APK_TYPES =
    arrayOf("application/vnd.android.package-archive", "application/octet-stream")
