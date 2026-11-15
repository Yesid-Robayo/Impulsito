package io.github.yesidrobayo.shopledger.ui.encryption

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.backup.EncryptionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.lock.AuthOutcome
import io.github.yesidrobayo.shopledger.lock.AuthPurpose
import io.github.yesidrobayo.shopledger.lock.DeviceCredentialCheck
import io.github.yesidrobayo.shopledger.lock.LocalDeviceAuthenticator
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
 * - `Ver mi código` pide la verificación del teléfono siempre que tenga bloqueo de pantalla, aunque
 *   el bloqueo de la app esté apagado. Sin bloqueo de pantalla no hay con qué pedirla: la pantalla
 *   avisa y deja seguir, porque un código que no se puede volver a ver deja las copias cifradas sin
 *   salida. Mientras se ve, la pantalla entera es sensible, y atrás lo cierra.
 * - La hoja `Comprobar el código` también hace sensible la pantalla, porque lleva el código escrito.
 *   `Código perdido` lleva a `Ver mi código`, con la misma verificación, y una prueba correcta lo
 *   dice con un aviso.
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
                port =
                    EncryptionUseCase(container.data.encryption, container.data.auditRecorder),
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
                now = { container.clock.now() },
                dateOf = { instant ->
                    val calendar = BusinessCalendarSource(dependencies.ports.settings).current()
                    val zone = (calendar as? BusinessCalendarResult.Ready)?.calendar?.zone
                    // Sin zona del negocio, la fecha va en UTC: solo orienta.
                    zone?.let { BusinessDate.of(instant, it) }
                        ?: BusinessDate(instant.toLocalDateTime(TimeZone.UTC).date)
                },
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
            val changeTitle = stringResource(R.string.encryption_change_code_action)
            val newCodeTitle = stringResource(R.string.encryption_create_new_code_action)
            val viewTitle = stringResource(R.string.recovery_view_title)
            val deactivateTitle = stringResource(R.string.encryption_deactivate_action)
            val deactivated = stringResource(R.string.encryption_deactivated)
            val deactivateFailed = stringResource(R.string.encryption_deactivate_error)
            // Apagar pide la verificación del sistema siempre que el teléfono tenga bloqueo de
            // pantalla, aunque el bloqueo de la app esté apagado. No pide el código.
            val authenticator = LocalDeviceAuthenticator.current
            val deviceCheck = remember(authenticator) { DeviceCredentialCheck(authenticator) }
            LaunchedEffect(uiState.message) {
                val message = uiState.message ?: return@LaunchedEffect
                viewModel.onAction(EncryptionAction.MessageShown)
                when (message) {
                    EncryptionMessage.DEACTIVATED ->
                        messages.show(AppSnackbarVisuals(deactivated, SnackbarVariant.SUCCESS))

                    EncryptionMessage.DEACTIVATE_FAILED ->
                        messages.show(AppSnackbarVisuals(deactivateFailed, SnackbarVariant.ERROR))
                }
            }
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
            if (uiState.codeView != null || uiState.codeCheck != null) {
                SensitiveScreen {
                    viewModel.onAction(EncryptionAction.ObscuredTouch)
                    messages.show(AppSnackbarVisuals(obscured, SnackbarVariant.INFO))
                }
                BackHandler {
                    if (uiState.codeView != null) {
                        viewModel.onAction(EncryptionAction.CloseCode)
                    } else {
                        viewModel.onAction(EncryptionAction.LaterCheck)
                    }
                }
            }
            val checkOk = stringResource(R.string.recovery_check_ok)
            LaunchedEffect(uiState.codeCheckPassed) {
                if (uiState.codeCheckPassed) {
                    messages.show(AppSnackbarVisuals(checkOk, SnackbarVariant.SUCCESS))
                    viewModel.onAction(EncryptionAction.CheckPassedShown)
                }
            }
            // `Ver mi código` pide la verificación del teléfono siempre que tenga bloqueo de pantalla.
            val viewCode: () -> Unit = {
                scope.launch {
                    val outcome =
                        authenticator.authenticate(AuthPurpose.SENSITIVE, viewTitle, null)
                    when (outcome) {
                        AuthOutcome.Success ->
                            viewModel.onAction(EncryptionAction.ViewCodeAuthorized)

                        AuthOutcome.NoDeviceCredential ->
                            viewModel.onAction(EncryptionAction.ViewCodeWithoutScreenLock)

                        AuthOutcome.Canceled -> Unit

                        // El texto del sistema se muestra tal cual y nunca se registra.
                        is AuthOutcome.Failed ->
                            messages.show(
                                AppSnackbarVisuals(outcome.systemMessage, SnackbarVariant.INFO),
                            )
                    }
                }
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

                    // Cambiar el código o crear otro piden la verificación del sistema si el
                    // bloqueo de la app la exige, como activar.
                    EncryptionAction.ConfirmChange ->
                        scope.launch {
                            if (sensitive.confirm(changeTitle)) {
                                viewModel.onAction(EncryptionAction.ConfirmChange)
                            } else {
                                viewModel.onAction(EncryptionAction.CloseChange)
                            }
                        }

                    EncryptionAction.ConfirmDeactivate ->
                        scope.launch {
                            if (deviceCheck.allows(deactivateTitle)) {
                                viewModel.onAction(EncryptionAction.ConfirmDeactivate)
                            } else {
                                viewModel.onAction(EncryptionAction.CloseDeactivate)
                            }
                        }

                    EncryptionAction.CreateNewCode ->
                        scope.launch {
                            if (sensitive.confirm(newCodeTitle)) {
                                viewModel.onAction(EncryptionAction.CreateNewCode)
                            }
                        }

                    EncryptionAction.ViewCode -> viewCode()

                    EncryptionAction.LostCode -> {
                        viewModel.onAction(EncryptionAction.LostCode)
                        viewCode()
                    }

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
                    EncryptionAction.AskChange,
                    EncryptionAction.CloseChange,
                    EncryptionAction.TypeExistingCode,
                    is EncryptionAction.TypedExisting,
                    EncryptionAction.Reseal,
                    EncryptionAction.ViewCodeAuthorized,
                    EncryptionAction.ViewCodeWithoutScreenLock,
                    EncryptionAction.DismissViewWithoutScreenLock,
                    EncryptionAction.CloseCode,
                    EncryptionAction.TestCode,
                    is EncryptionAction.TypedCheck,
                    EncryptionAction.SubmitCheck,
                    EncryptionAction.LaterCheck,
                    EncryptionAction.CheckPassedShown,
                    EncryptionAction.AskDeactivate,
                    EncryptionAction.CloseDeactivate,
                    EncryptionAction.MessageShown,
                    -> viewModel.onAction(action)
                }
            })
        }
    }
}

/** El tipo del kit: un PDF de una página. */
private const val PDF_TYPE = "application/pdf"
