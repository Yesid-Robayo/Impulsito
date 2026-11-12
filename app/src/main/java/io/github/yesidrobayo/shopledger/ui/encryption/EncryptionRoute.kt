package io.github.yesidrobayo.shopledger.ui.encryption

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.EncryptionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.lock.SensitiveScreen
import io.github.yesidrobayo.shopledger.lock.rememberSensitiveCheck
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * `Cifrado de copias` en la navegación.
 *
 * - `Activar cifrado` pide antes la verificación del sistema si el bloqueo de la app la exige.
 * - Durante la activación la pantalla entera es sensible: sin capturas ni Recientes, sin
 *   superposiciones de otras apps y sin toques tapados. El botón atrás del sistema sale de la
 *   activación, y el código se descarta.
 * - `Guardar kit de recuperación` abre el selector del sistema para crear el PDF, con el nombre
 *   `recovery-kit-AAAAMMDD.pdf`, sin pedir permisos. Cancelar el selector no cambia nada.
 */
@Composable
fun EncryptionRoute(navigator: AppNavigator) {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val model =
        rememberAppViewModel { dependencies ->
            val container = dependencies.container
            val kit = RecoveryKitWriter(context)
            EncryptionViewModel(
                port = EncryptionUseCase(container.data.encryption),
                writeKit = { uri, groups, date ->
                    kit.write(uri, groups, formats.date.longDate(date))
                },
                today = {
                    val now = container.clock.now()
                    val calendar = BusinessCalendarSource(dependencies.ports.settings).current()
                    val zone = (calendar as? BusinessCalendarResult.Ready)?.calendar?.zone
                    // Sin zona del negocio, que solo falta antes de configurar la región, va el día
                    // en UTC: la fecha del kit solo orienta.
                    zone?.let { BusinessDate.of(now, it) }
                        ?: BusinessDate(now.toLocalDateTime(TimeZone.UTC).date)
                },
                computation = container.dispatchers.io,
            )
        }
    when (model) {
        ScreenModel.Opening -> EncryptionScreen(EncryptionUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.BackupEncryption, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val messages = LocalAppMessages.current
            val obscured = stringResource(R.string.security_obscured_touch)
            val sensitive = rememberSensitiveCheck()
            val scope = rememberCoroutineScope()
            val activateTitle = stringResource(R.string.encryption_activate_action)
            val picker =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(PDF_TYPE),
                ) { uri ->
                    if (uri == null) {
                        viewModel.kitCancelled()
                    } else {
                        viewModel.onAction(EncryptionAction.KitFileCreated(uri.toString()))
                    }
                }
            val inActivation = uiState.step != null
            if (inActivation) {
                SensitiveScreen {
                    viewModel.onAction(EncryptionAction.ObscuredTouch)
                    messages.show(AppSnackbarVisuals(obscured, SnackbarVariant.INFO))
                }
                BackHandler { viewModel.onAction(EncryptionAction.Exit) }
            }
            EncryptionScreen(uiState, onAction = { action ->
                when (action) {
                    EncryptionAction.Back -> navigator.back()

                    EncryptionAction.Start ->
                        scope.launch {
                            if (sensitive.confirm(
                                    activateTitle,
                                )
                            ) {
                                viewModel.onAction(EncryptionAction.Start)
                            }
                        }

                    EncryptionAction.SaveKit -> {
                        viewModel.onAction(EncryptionAction.SaveKit)
                        // Sin una app que cree documentos no hay selector: queda el papel.
                        val name = viewModel.kitFileName()
                        try {
                            if (name == null) viewModel.kitCancelled() else picker.launch(name)
                        } catch (_: ActivityNotFoundException) {
                            viewModel.kitCancelled()
                        }
                    }

                    EncryptionAction.ObscuredTouch ->
                        messages.show(AppSnackbarVisuals(obscured, SnackbarVariant.INFO))

                    is EncryptionAction.Accept,
                    EncryptionAction.ContinueRisk,
                    is EncryptionAction.KitFileCreated,
                    EncryptionAction.OnPaper,
                    EncryptionAction.ContinueCode,
                    is EncryptionAction.Typed,
                    EncryptionAction.CheckCode,
                    EncryptionAction.Retry,
                    EncryptionAction.Finish,
                    EncryptionAction.Exit,
                    -> viewModel.onAction(action)
                }
            })
        }
    }
}

/** El tipo del kit: un PDF de una página. */
private const val PDF_TYPE = "application/pdf"
