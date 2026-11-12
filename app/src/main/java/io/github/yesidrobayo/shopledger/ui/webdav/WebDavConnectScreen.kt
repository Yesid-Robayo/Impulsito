package io.github.yesidrobayo.shopledger.ui.webdav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavAddressProblem
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestFailure
import io.github.yesidrobayo.shopledger.domain.model.backup.WebDavTestStep
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PasswordField
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * `Conectar el servicio`: de dónde salen los datos, las sugerencias, la dirección, el usuario y la
 * contraseña, los avisos de la contraseña y del cifrado, y `Probar y conectar` fijo abajo.
 *
 * - Probando, el botón queda en `Trabajando` con `Probando la conexión`, los siete pasos van debajo
 *   con su estado y se puede cancelar.
 * - Un usuario o una contraseña que no sirven se dicen en el campo; lo demás, en una `NoticeCard`
 *   con el paso que falló. `Reintentar` solo cuando no hubo respuesta: nada se reintenta solo.
 * - Columna centrada de 600 dp; en una ventana ancha, las sugerencias a la izquierda y los campos a
 *   la derecha.
 *
 * Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun WebDavConnectScreen(
    uiState: WebDavConnectUiState,
    onAction: (WebDavConnectAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialogs(uiState, onAction)
    val spacing = AppTheme.spacing
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AppTopBar(
                title = stringResource(R.string.webdav_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(WebDavConnectAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                val expanded = maxWidth >= EXPANDED_WIDTH && uiState.suggestions.isNotEmpty()
                val scroll = rememberScrollState()
                if (expanded) {
                    Row(
                        Modifier
                            .widthIn(max = AppTheme.sizes.maxContentWidth * 2)
                            .fillMaxWidth()
                            .verticalScroll(scroll),
                        horizontalArrangement = Arrangement.spacedBy(spacing.s8),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Intro()
                            Suggestions(uiState, onAction)
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = spacing.s16)
                                .padding(top = spacing.s8, bottom = spacing.s24),
                            verticalArrangement = Arrangement.spacedBy(spacing.s12),
                        ) { Form(uiState, onAction) }
                    }
                } else {
                    Column(
                        Modifier
                            .widthIn(max = AppTheme.sizes.maxContentWidth)
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .padding(bottom = spacing.s24),
                    ) {
                        Intro()
                        Suggestions(uiState, onAction)
                        Column(
                            Modifier.padding(horizontal = spacing.s16).padding(top = spacing.s12),
                            verticalArrangement = Arrangement.spacedBy(spacing.s12),
                        ) { Form(uiState, onAction) }
                    }
                }
            }
            BottomBar(uiState, onAction)
        }
    }
}

@Composable
private fun Intro() {
    Text(
        stringResource(R.string.webdav_intro),
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier.padding(horizontal = AppTheme.spacing.s16).padding(top = AppTheme.spacing.s8),
    )
}

/** `Sugerencias`, solo si la lista fija tiene alguna, con su nota y una fila por servicio. */
@Composable
private fun Suggestions(uiState: WebDavConnectUiState, onAction: (WebDavConnectAction) -> Unit) {
    if (uiState.suggestions.isEmpty()) return
    Column {
        SectionHeader(stringResource(R.string.webdav_suggestions_title))
        Text(
            stringResource(R.string.webdav_suggestions_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AppTheme.spacing.s16),
        )
        for (suggestion in uiState.suggestions) {
            SettingsRow(
                title = suggestion.name,
                action =
                    SettingsRowAction.Navigation {
                        onAction(WebDavConnectAction.PickSuggestion(suggestion.key))
                    },
            )
        }
    }
}

/** Los campos, los avisos y los pasos de la prueba. */
@Composable
private fun ColumnScope.Form(
    uiState: WebDavConnectUiState,
    onAction: (WebDavConnectAction) -> Unit,
) {
    val editable = uiState.phase == WebDavPhase.EDITING
    val user = remember { FocusRequester() }
    LaunchedEffect(uiState.focusUser) {
        if (uiState.focusUser > 0) user.requestFocus()
    }
    AppTextField(
        value = uiState.url,
        onValueChange = { if (editable) onAction(WebDavConnectAction.UrlChange(it)) },
        label = stringResource(R.string.webdav_field_url),
        error = uiState.urlError?.let { urlErrorText(it) },
        supportingText = stringResource(R.string.webdav_field_url_help),
        capitalization = KeyboardCapitalization.None,
        keyboardType = KeyboardType.Uri,
        autoCorrect = false,
    )
    AppTextField(
        value = uiState.username,
        onValueChange = { if (editable) onAction(WebDavConnectAction.UserChange(it)) },
        label = stringResource(R.string.webdav_field_user),
        modifier = Modifier.focusRequester(user),
        capitalization = KeyboardCapitalization.None,
        autoCorrect = false,
    )
    PasswordField(
        value = uiState.password,
        onValueChange = { if (editable) onAction(WebDavConnectAction.PasswordChange(it)) },
        label = stringResource(R.string.webdav_field_password),
        visible = uiState.passwordVisible,
        onToggleVisible = { onAction(WebDavConnectAction.TogglePassword) },
        showLabel = stringResource(R.string.webdav_password_show_action),
        hideLabel = stringResource(R.string.webdav_password_hide_action),
        error =
            if (uiState.passwordRejected) stringResource(R.string.webdav_error_auth) else null,
    )
    uiState.notice?.let { notice ->
        // Con el teclado abierto, el aviso aparece debajo de lo que se ve: se trae a la vista.
        val requester = remember { BringIntoViewRequester() }
        LaunchedEffect(notice) { requester.bringIntoView() }
        Box(Modifier.bringIntoViewRequester(requester)) { Notice(notice, onAction) }
    }
    if (uiState.steps.isNotEmpty()) Steps(uiState.steps)
    val suggestion = uiState.suggestion
    NoticeCard(
        stringResource(
            if (suggestion?.usesAccountPassword == true) {
                R.string.webdav_password_account_warning
            } else {
                R.string.webdav_password_warning
            },
        ),
        NoticeVariant.WARNING,
    )
    if (suggestion?.declaresLimits == true) {
        NoticeCard(stringResource(R.string.webdav_suggestion_limits_warning), NoticeVariant.WARNING)
    }
    NoticeCard(
        stringResource(R.string.webdav_encrypt_offer),
        NoticeVariant.INFO,
        actionLabel = stringResource(R.string.onboarding_encrypt_row),
        onAction = { onAction(WebDavConnectAction.OpenEncryption) },
    )
}

@Composable
private fun urlErrorText(problem: WebDavAddressProblem): String = when (problem) {
    WebDavAddressProblem.NOT_HTTPS -> stringResource(R.string.webdav_error_http)
    WebDavAddressProblem.INVALID -> stringResource(R.string.webdav_error_url)
}

/** El aviso de un fallo, de la falta de internet o de un guardado que no quedó. */
@Composable
private fun Notice(notice: WebDavNotice, onAction: (WebDavConnectAction) -> Unit) {
    when (notice) {
        WebDavNotice.NoInternet ->
            NoticeCard(stringResource(R.string.webdav_error_no_internet), NoticeVariant.WARNING)

        WebDavNotice.SaveFailed ->
            NoticeCard(stringResource(R.string.state_error_save), NoticeVariant.ERROR)

        is WebDavNotice.Failed -> {
            val retry = notice.failure == WebDavTestFailure.UNREACHABLE
            NoticeCard(
                text = failureText(notice.step, notice.failure),
                variant = NoticeVariant.ERROR,
                actionLabel = if (retry) stringResource(R.string.action_retry) else null,
                onAction = { onAction(WebDavConnectAction.Connect) },
            )
        }
    }
}

@Composable
private fun failureText(step: WebDavTestStep, failure: WebDavTestFailure): String {
    val app = stringResource(R.string.app_name)
    return when (failure) {
        WebDavTestFailure.CREDENTIAL_REJECTED -> stringResource(R.string.webdav_error_auth)

        WebDavTestFailure.FORBIDDEN -> stringResource(R.string.webdav_error_forbidden)

        WebDavTestFailure.RATE_LIMITED -> stringResource(R.string.webdav_error_rate_limited)

        WebDavTestFailure.UNREACHABLE -> stringResource(R.string.webdav_error_unreachable)

        WebDavTestFailure.INSECURE -> stringResource(R.string.webdav_error_secure, app)

        WebDavTestFailure.NO_SPACE -> stringResource(R.string.webdav_error_space)

        WebDavTestFailure.BASE_URL -> stringResource(R.string.webdav_error_base_url)

        WebDavTestFailure.REDIRECT -> stringResource(R.string.webdav_error_redirect, app)

        WebDavTestFailure.UNSUPPORTED ->
            stringResource(R.string.webdav_error_unsupported, stepText(step))
    }
}

@Composable
private fun stepText(step: WebDavTestStep): String = stringResource(
    when (step) {
        WebDavTestStep.LIST -> R.string.webdav_step_list
        WebDavTestStep.CREATE_FOLDER -> R.string.webdav_step_create_folder
        WebDavTestStep.UPLOAD -> R.string.webdav_step_upload
        WebDavTestStep.MOVE -> R.string.webdav_step_move
        WebDavTestStep.NO_OVERWRITE -> R.string.webdav_step_no_overwrite
        WebDavTestStep.DOWNLOAD -> R.string.webdav_step_download
        WebDavTestStep.DELETE -> R.string.webdav_step_delete
    },
)

/**
 * Los siete pasos de la prueba, uno por fila, con su estado en icono y en texto para TalkBack:
 * hecho, en curso, falló o pendiente. El hecho va en el tono neutro: que la prueba pasara no es
 * todavía una copia guardada.
 */
@Composable
private fun Steps(steps: List<StepProgress>) {
    val scheme = MaterialTheme.colorScheme
    val done = stringResource(R.string.webdav_step_state_done)
    val running = stringResource(R.string.webdav_step_state_running)
    val failed = stringResource(R.string.webdav_step_state_failed)
    val pending = stringResource(R.string.webdav_step_state_pending)
    Column(Modifier.fillMaxWidth()) {
        for (progress in steps) {
            val state =
                when (progress.status) {
                    StepStatus.DONE -> done
                    StepStatus.RUNNING -> running
                    StepStatus.FAILED -> failed
                    StepStatus.PENDING -> pending
                }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.sizes.touchTarget)
                    .semantics(mergeDescendants = true) { stateDescription = state },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            ) {
                val iconSize = Modifier.size(AppTheme.sizes.icon)
                when (progress.status) {
                    StepStatus.RUNNING ->
                        CircularProgressIndicator(
                            iconSize.padding(AppTheme.spacing.s2),
                            color = scheme.primary,
                            strokeWidth = AppTheme.sizes.hairline * 2,
                        )

                    StepStatus.DONE ->
                        Icon(
                            painterResource(R.drawable.ic_check),
                            null,
                            iconSize,
                            tint = scheme.onSurfaceVariant,
                        )

                    StepStatus.FAILED ->
                        Icon(
                            painterResource(R.drawable.ic_error),
                            null,
                            iconSize,
                            tint = scheme.error,
                        )

                    StepStatus.PENDING ->
                        Icon(
                            painterResource(R.drawable.ic_radio_button_unchecked),
                            null,
                            iconSize,
                            tint = scheme.outline,
                        )
                }
                Text(
                    stepText(progress.step),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (progress.status == StepStatus.PENDING) {
                            scheme.onSurfaceVariant
                        } else {
                            scheme.onSurface
                        },
                )
            }
        }
    }
}

/** La barra fija: `Probar y conectar`, y `Cancelar` mientras la prueba corre. */
@Composable
private fun BottomBar(uiState: WebDavConnectUiState, onAction: (WebDavConnectAction) -> Unit) {
    val spacing = AppTheme.spacing
    Surface(Modifier.keepsMessagesAbove(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = spacing.s16, vertical = spacing.s12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxWidth()) {
                PrimaryActionButton(
                    text =
                        stringResource(
                            if (uiState.working) {
                                R.string.webdav_testing
                            } else {
                                R.string.webdav_connect_action
                            },
                        ),
                    onClick = { onAction(WebDavConnectAction.Connect) },
                    variant = ActionVariant.PRIMARY,
                    enabled = uiState.canConnect || uiState.working,
                    state = if (uiState.working) ActionState.WORKING else ActionState.IDLE,
                )
                if (uiState.phase == WebDavPhase.TESTING) {
                    InlineTextButton(
                        stringResource(R.string.action_cancel),
                        { onAction(WebDavConnectAction.Cancel) },
                        Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

@Composable
private fun Dialogs(uiState: WebDavConnectUiState, onAction: (WebDavConnectAction) -> Unit) {
    when (uiState.phase) {
        WebDavPhase.CONFIRM_HOST ->
            ConfirmDialog(
                title = stringResource(R.string.webdav_confirm_host_title),
                body = stringResource(R.string.webdav_confirm_host_body, uiState.host.orEmpty()),
                confirmLabel = stringResource(R.string.webdav_confirm_host_action),
                onConfirm = { onAction(WebDavConnectAction.ConfirmHost) },
                onDismiss = { onAction(WebDavConnectAction.DismissHost) },
                dismissLabel = stringResource(R.string.action_cancel),
            )

        WebDavPhase.CONFIRM_CHANGE ->
            ConfirmDialog(
                title = stringResource(R.string.destination_change_title),
                body =
                    stringResource(
                        R.string.destination_change_body,
                        uiState.changeFrom ?: NO_VALUE_TEXT,
                        stringResource(R.string.app_name),
                    ),
                confirmLabel = stringResource(R.string.destination_change_action),
                onConfirm = { onAction(WebDavConnectAction.ConfirmChange) },
                onDismiss = { onAction(WebDavConnectAction.DismissChange) },
                dismissLabel = stringResource(R.string.action_cancel),
                sensitive = true,
            )

        WebDavPhase.EDITING,
        WebDavPhase.TESTING,
        WebDavPhase.VERIFYING,
        WebDavPhase.SAVING,
        -> Unit
    }
}

// Desde aquí la ventana es Expanded: sugerencias a la izquierda y campos a la derecha.
private val EXPANDED_WIDTH = 840.dp
