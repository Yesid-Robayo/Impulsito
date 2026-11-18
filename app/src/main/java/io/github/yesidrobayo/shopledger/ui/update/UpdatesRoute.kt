package io.github.yesidrobayo.shopledger.ui.update

import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.github.yesidrobayo.shopledger.ui.help.HelpMarkdown
import io.github.yesidrobayo.shopledger.update.ApkUpdateFiles
import io.github.yesidrobayo.shopledger.update.ApkVerifier
import io.github.yesidrobayo.shopledger.update.AutomaticUpdateChecks
import java.io.IOException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * `Actualizaciones y verificar APK` en la navegación.
 *
 * - `Buscar actualización` lee el aviso de versión de `update_manifest_url` y comprueba su firma
 *   con `update_manifest_public_key`, los dos dentro de la app. Sin alguno de los dos, la búsqueda
 *   dice que no se pudo leer el aviso, y nada se bloquea. Corre al tocar el botón; la automática,
 *   una vez al día, la programa su interruptor.
 * - `Actualizar` baja el APK de la versión anunciada desde las descargas de la versión publicada,
 *   con la dirección que arma `update_apk_url_template` con el nombre de versión del aviso
 *   firmado: con la búsqueda, son las únicas consultas de red fuera del servicio WebDAV, y solo al
 *   tocar. Lo deja en `cache/apk-update/` y, ya comparado, lo abre el instalador de Android por
 *   el proveedor de archivos, solo para leer. Si Android todavía no permite instalar desde la app,
 *   `Permitir` abre su ajuste, y al volver a la pantalla sigue sola. Android pide confirmar.
 * - `Elegir archivo` abre el selector del sistema: solo se lee lo elegido, una vez, sin permiso
 *   persistente. Comprobar un archivo no lo instala.
 * - `Novedades` se lee de los recursos, en el idioma de la interfaz.
 *
 * Sin la base, la pantalla sigue, sin la versión, sin la búsqueda y sin verificar.
 *
 * Con [searchNow], desde el aviso de una versión nueva, busca al abrir.
 */
@Composable
fun UpdatesRoute(navigator: AppNavigator, searchNow: Boolean = false) {
    val app = LocalContext.current.applicationContext
    val messages = LocalAppMessages.current
    val notSaved = stringResource(R.string.state_error_save)
    val activity = LocalContext.current
    val resources = LocalResources.current
    val appName = stringResource(R.string.app_name)
    val manifestUrl = stringResource(R.string.update_manifest_url)
    val publicKey = stringResource(R.string.update_manifest_public_key)
    val apkUrlTemplate = stringResource(R.string.update_apk_url_template)
    val model =
        rememberAppViewModel { dependencies ->
            val container = dependencies.container
            val settings = dependencies.ports.settings
            val io = container.dispatchers.io
            val verifier = ApkVerifier(app, io)
            val changePreference = ChangePreferenceUseCase(settings, container.clock)
            UpdatesViewModel(
                installedApp = { verifier.installed() },
                verifyApk = { uri -> verifier.verify(Uri.parse(uri)) },
                searchEnabled = settings.observe().map { it.preferences.updateCheckEnabled },
                setSearchEnabled = { enabled ->
                    changePreference(PreferenceChange.UpdateCheck(enabled)) ==
                        ChangePreferenceResult.Saved
                },
                automatic = { container.data.automaticUpdateCheckEnabled() },
                setAutomatic = { enabled ->
                    val saved = container.data.setAutomaticUpdateCheck(enabled)
                    if (saved) {
                        container.updateCheckDelivery.sync(
                            settings.get().preferences.updateCheckEnabled,
                        )
                    }
                    saved
                },
                searchOnOpen = searchNow,
                checkForUpdate = {
                    container.data.checkForUpdate(
                        manifestUrl,
                        publicKey,
                        AutomaticUpdateChecks.builtAt(),
                    )
                },
                lastSearchAt = { container.data.lastUpdateCheckAt() },
                zone =
                    BusinessCalendarSource(settings).observe().map { result ->
                        (result as? BusinessCalendarResult.Ready)?.calendar?.zone
                    },
                now = { container.clock.now() },
                downloadApk = { versionName, onProgress ->
                    container.data.downloadUpdateApk(
                        urlTemplate = apkUrlTemplate,
                        versionName = versionName,
                        target = ApkUpdateFiles.file(app),
                        maxBytes = ApkVerifier.MAX_APK_BYTES,
                        onProgress = onProgress,
                    )
                },
                checkDownloadedApk = { sha256 ->
                    verifier.checkDownloaded(ApkUpdateFiles.file(app), sha256)
                },
                canInstallPackages = { app.packageManager.canRequestPackageInstalls() },
                discardDownload = { withContext(io) { ApkUpdateFiles.clear(app) } },
                leaveScope = container.applicationScope,
                whatsNew = {
                    withContext(io) {
                        readWhatsNew(resources)?.let { HelpMarkdown.parse(it, appName) }
                    }
                },
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

            // Sin esa pantalla de ajustes, no hay cómo pedirlo: el aviso sigue a la vista.
            UpdatesAction.AllowInstalls -> ApkUpdateFiles.openInstallPermission(activity)

            UpdatesAction.Search,
            is UpdatesAction.SetSearchEnabled,
            is UpdatesAction.SetAutomatic,
            UpdatesAction.MessageShown,
            is UpdatesAction.FileChosen,
            UpdatesAction.StartUpdate,
            UpdatesAction.CancelUpdate,
            UpdatesAction.Resumed,
            is UpdatesAction.InstallerOpened,
            UpdatesAction.OpenInstallerAgain,
            UpdatesAction.OpenWhatsNew,
            UpdatesAction.CloseWhatsNew,
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
            // Al volver del ajuste de Android, con el permiso o sin él.
            LifecycleResumeEffect(ready) {
                ready.onAction(UpdatesAction.Resumed)
                onPauseOrDispose {}
            }
            // El archivo ya pasó las comprobaciones: el instalador de Android pide confirmar.
            LaunchedEffect(uiState.install) {
                if (uiState.install == InstallUi.Opening) {
                    val opened = ApkUpdateFiles.openInstaller(activity)
                    ready.onAction(UpdatesAction.InstallerOpened(opened))
                }
            }
            UpdatesScreen(uiState, onAction)
        }
    }
}

/**
 * `Novedades` en el idioma de [resources], o nulo si no se pudo leer. Lee del APK: va fuera del
 * hilo principal.
 */
private fun readWhatsNew(resources: Resources): String? = try {
    resources.openRawResource(R.raw.whats_new).bufferedReader(Charsets.UTF_8).use { it.readText() }
} catch (_: Resources.NotFoundException) {
    null
} catch (_: IOException) {
    null
}

private val APK_TYPES =
    arrayOf("application/vnd.android.package-archive", "application/octet-stream")
