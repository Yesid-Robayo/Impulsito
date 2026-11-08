package io.github.yesidrobayo.shopledger.harness

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.data.harness.RestoreAnswer
import io.github.yesidrobayo.shopledger.data.harness.RestoreQuestion
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** Lo que la persona puede hacer en el arnés. */
internal class HarnessCallbacks(
    val acceptPolicy: () -> Unit,
    val connect: (url: String, username: String, password: String) -> Unit,
    val prepareSlots: () -> Unit,
    val recordChanges: () -> Unit,
    val backupNow: () -> Unit,
    val pickRestoreFile: () -> Unit,
    val answer: (RestoreAnswer) -> Unit,
    val cancelRestore: () -> Unit,
    val restart: () -> Unit,
)

/**
 * La pantalla mínima del arnés del respaldo, solo en `debug`: el estado del respaldo arriba y una
 * tarjeta por acción. Los resultados se ven como códigos, sin datos de la cuenta ni del negocio.
 *
 * Los campos de la cuenta WebDAV viven solo en memoria: nunca pasan al estado guardado de la
 * actividad, y la contraseña se borra al tocar `Probar y conectar`.
 */
@Composable
internal fun BackupHarnessScreen(state: HarnessUiState, callbacks: HarnessCallbacks) {
    val spacing = AppTheme.spacing
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = AppTheme.sizes.maxContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.s24),
            verticalArrangement = Arrangement.spacedBy(spacing.s16),
        ) {
            AppTopBar(
                title = stringResource(R.string.harness_title),
                navigation = TopBarNavigation.NONE,
                onNavigate = {},
                navigationLabel = null,
            )
            Column(
                Modifier.padding(horizontal = spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s16),
            ) {
                Body(stringResource(R.string.harness_debug_notice))
                when (state) {
                    // Sin esqueleto: la base abre en un momento, y nada aquí espera a la red.
                    HarnessUiState.Loading -> Unit

                    HarnessUiState.Unavailable -> Body(stringResource(R.string.harness_unavailable))

                    is HarnessUiState.Ready -> ReadyContent(state, callbacks)
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(state: HarnessUiState.Ready, callbacks: HarnessCallbacks) {
    val busy = state.working != null
    fun actionState(action: HarnessAction) =
        if (state.working == action) ActionState.WORKING else ActionState.IDLE

    Section(R.string.harness_section_status) {
        StatusLines(state)
        state.outcome?.let {
            Body(stringResource(R.string.harness_result_line, it.action.name, it.code))
        }
        PrimaryActionButton(
            text = stringResource(R.string.backup_action_now),
            onClick = callbacks.backupNow,
            variant = ActionVariant.PRIMARY,
            enabled = !busy,
            state = actionState(HarnessAction.BACKUP_NOW),
        )
    }
    Section(R.string.harness_section_policy) {
        val none = stringResource(R.string.harness_none)
        Body(stringResource(R.string.harness_policy_line, state.policy?.name ?: none))
        PrimaryActionButton(
            text = stringResource(R.string.harness_accept_policy),
            onClick = callbacks.acceptPolicy,
            variant = ActionVariant.TONAL,
            enabled = !busy,
            state = actionState(HarnessAction.POLICY),
        )
    }
    WebDavSection(busy, actionState(HarnessAction.WEBDAV), callbacks.connect)
    Section(R.string.harness_section_slots) {
        Body(stringResource(R.string.harness_slots_hint))
        PrimaryActionButton(
            text = stringResource(R.string.harness_prepare_slots),
            onClick = callbacks.prepareSlots,
            variant = ActionVariant.TONAL,
            enabled = !busy,
            state = actionState(HarnessAction.SLOTS),
        )
    }
    Section(R.string.harness_section_changes) {
        val count = BackupHarnessViewModel.CHANGES_PER_TAP
        PrimaryActionButton(
            text = pluralStringResource(R.plurals.harness_record_changes, count, count),
            onClick = callbacks.recordChanges,
            variant = ActionVariant.TONAL,
            enabled = !busy,
            state = actionState(HarnessAction.CHANGES),
        )
    }
    RestoreSection(state.restore, busy, actionState(HarnessAction.RESTORE), callbacks)
}

@Composable
private fun StatusLines(state: HarnessUiState.Ready) {
    val none = stringResource(R.string.harness_none)
    val report = state.report
    Body(stringResource(R.string.harness_status_line, report.status.name))
    Body(stringResource(R.string.harness_pending_line, report.pendingChanges))
    val notices = report.notices.joinToString { it.name }.ifEmpty { none }
    Body(stringResource(R.string.harness_notices_line, notices))
    state.saved?.let { saved ->
        Body(
            stringResource(
                R.string.harness_link_line,
                saved.linkState,
                saved.destinationKind ?: none,
            ),
        )
        Body(
            stringResource(
                R.string.harness_sequence_line,
                saved.changeSeq,
                saved.verifiedSeq,
                saved.headGeneration,
            ),
        )
        Body(
            stringResource(
                R.string.harness_failure_line,
                saved.failureCode ?: none,
                saved.consecutiveFailures,
            ),
        )
    }
}

@Composable
private fun WebDavSection(
    busy: Boolean,
    connectState: ActionState,
    onConnect: (String, String, String) -> Unit,
) {
    // Solo en memoria, sin estado guardado: la contraseña nunca va a un Bundle.
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Section(R.string.harness_section_webdav) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.harness_webdav_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.harness_webdav_user)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.harness_webdav_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryActionButton(
            text = stringResource(R.string.harness_connect),
            onClick = {
                onConnect(url, username, password)
                password = ""
            },
            variant = ActionVariant.TONAL,
            enabled = !busy,
            state = connectState,
        )
    }
}

@Composable
private fun RestoreSection(
    phase: RestorePhase,
    busy: Boolean,
    restoreState: ActionState,
    callbacks: HarnessCallbacks,
) {
    Section(R.string.harness_section_restore) {
        when (phase) {
            RestorePhase.Idle ->
                PrimaryActionButton(
                    text = stringResource(R.string.harness_restore_pick),
                    onClick = callbacks.pickRestoreFile,
                    variant = ActionVariant.OUTLINED,
                    enabled = !busy,
                    state = restoreState,
                )

            is RestorePhase.Asking -> {
                Body(
                    stringResource(
                        R.string.harness_restore_question,
                        phase.question.name,
                        phase.count,
                    ),
                )
                val (label, answer) =
                    when (phase.question) {
                        RestoreQuestion.REPAIR ->
                            R.string.harness_restore_continue to
                                RestoreAnswer.Repair

                        RestoreQuestion.EMPTY_COPY ->
                            R.string.harness_restore_continue to RestoreAnswer.ReplaceWithEmptyCopy

                        RestoreQuestion.WITHOUT_SAFETY_COPY ->
                            R.string.harness_restore_continue to
                                RestoreAnswer.ContinueWithoutSafetyCopy

                        RestoreQuestion.DISCARD_SAFETY_COPY ->
                            R.string.harness_restore_discard_first to
                                RestoreAnswer.DiscardSafetyCopy(0)
                    }
                PrimaryActionButton(
                    text = stringResource(label),
                    onClick = { callbacks.answer(answer) },
                    variant = ActionVariant.OUTLINED,
                    enabled = !busy,
                    state = restoreState,
                )
                PrimaryActionButton(
                    text = stringResource(R.string.harness_restore_cancel),
                    onClick = callbacks.cancelRestore,
                    variant = ActionVariant.TEXT,
                    enabled = !busy,
                )
            }

            is RestorePhase.ReadyToRestart -> {
                Body(stringResource(R.string.harness_ready_line, phase.warnings))
                PrimaryActionButton(
                    text = stringResource(R.string.harness_restart),
                    onClick = callbacks.restart,
                    variant = ActionVariant.OUTLINED,
                )
            }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable ColumnScope.() -> Unit) {
    AppCard {
        SectionHeader(stringResource(title))
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12), content = content)
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
