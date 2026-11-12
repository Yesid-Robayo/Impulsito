package io.github.yesidrobayo.shopledger.ui.encryption

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.lock.rejectObscuredTouches
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CheckboxRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OnboardingStep
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.RecoveryCodeBlock
import io.github.yesidrobayo.shopledger.ui.components.RecoveryCodeField
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * `Cifrado de copias`: el estado del cifrado y, desde `Activar cifrado`, los cuatro pasos de la
 * activación. Va apagado por defecto, y nada de esto usa la red.
 *
 * - **Apagado:** qué protege, qué no y el riesgo real, con `Activar cifrado` fijo abajo. Si se
 *   apagó a propósito, dice que quedan copias cifradas y que activar de nuevo crea otro código.
 * - **Activo:** `Las copias salen cifradas`, sin acción principal: no pide nada mientras el teléfono
 *   funcione.
 * - **Detenido:** el código ya no abre en este teléfono. Las copias no suben, nunca en claro.
 * - **Activación:** aceptar el riesgo, ver el código y guardar el kit o anotarlo, reescribirlo
 *   entero y la prueba de ida y vuelta. Salir en cualquier paso deja el cifrado apagado.
 *
 * Es pura: recibe el estado y avisa cada acción. Quien la muestra pone `FLAG_SECURE` en los pasos.
 */
@Composable
fun EncryptionScreen(
    uiState: EncryptionUiState,
    onAction: (EncryptionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = uiState.step
    if (step == null) {
        StatusScreen(uiState.status, onAction, modifier)
        if (uiState.confirmChange) {
            ConfirmDialog(
                title = stringResource(R.string.encryption_change_confirm_title),
                body = stringResource(R.string.encryption_change_confirm_body),
                confirmLabel = stringResource(R.string.encryption_change_confirm_action),
                onConfirm = { onAction(EncryptionAction.ConfirmChange) },
                onDismiss = { onAction(EncryptionAction.CloseChange) },
                dismissLabel = stringResource(R.string.action_cancel),
                sensitive = true,
            )
        }
        return
    }
    // Los pasos dan su color de contenido: sin él, el texto sale con el de la ventana y en oscuro
    // no se lee.
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        when (step) {
            is ActivationStepUi.Risk -> RiskStep(step, onAction, Modifier)
            is ActivationStepUi.ShowCode -> CodeStep(step, onAction, Modifier)
            is ActivationStepUi.Retype -> RetypeStep(step, onAction, Modifier)
            is ActivationStepUi.Testing -> TestingStep(step, onAction, Modifier)
            is ActivationStepUi.Done -> DoneStep(step.changed, onAction, Modifier)
            is ActivationStepUi.TypeExisting -> TypeExistingStep(step, onAction, Modifier)
        }
    }
}

@Composable
private fun StatusScreen(
    status: EncryptionStatusUi,
    onAction: (EncryptionAction) -> Unit,
    modifier: Modifier,
) {
    val spacing = AppTheme.spacing
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.encryption_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(EncryptionAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
                titleStyle = MaterialTheme.typography.titleLarge,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = spacing.s16, vertical = spacing.s8),
                    verticalArrangement = Arrangement.spacedBy(spacing.s16),
                ) {
                    when (status) {
                        EncryptionStatusUi.Loading ->
                            item(key = LOADING) {
                                DelayedSkeleton {
                                    SkeletonBlock(
                                        Modifier.fillMaxWidth().height(AppTheme.sizes.touchTarget),
                                    )
                                }
                            }

                        is EncryptionStatusUi.Off -> offItems(status.wasOn)

                        EncryptionStatusUi.On -> {
                            item(key = ON) { OnBlock() }
                            item(key = CHANGE) {
                                SettingsRow(
                                    title = stringResource(R.string.encryption_change_code_action),
                                    action =
                                        SettingsRowAction.Navigation {
                                            onAction(EncryptionAction.AskChange)
                                        },
                                )
                            }
                        }

                        EncryptionStatusUi.KeyUnavailable -> {
                            item(key = STOPPED) { StoppedBlock() }
                            item(key = STOPPED_ACTIONS) { StoppedActions(onAction) }
                        }

                        EncryptionStatusUi.Unknown ->
                            item(key = UNKNOWN) {
                                NoticeCard(
                                    stringResource(R.string.state_error_read),
                                    NoticeVariant.ERROR,
                                )
                            }
                    }
                }
            }
            if (status is EncryptionStatusUi.Off) {
                HorizontalDivider(
                    thickness = AppTheme.sizes.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Box(
                    Modifier
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .keepsMessagesAbove()
                        .navigationBarsPadding()
                        .padding(spacing.s16),
                ) {
                    PrimaryActionButton(
                        text = stringResource(R.string.encryption_activate_action),
                        onClick = { onAction(EncryptionAction.Start) },
                        variant = ActionVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.offItems(wasOn: Boolean) {
    item(key = OFF_TITLE) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
            StatusTag(stringResource(R.string.encryption_status_off))
            Text(
                stringResource(R.string.encryption_off_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
    if (wasOn) {
        item(key = LEFTOVER) {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                Text(
                    stringResource(R.string.encryption_leftover_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                NoticeCard(stringResource(R.string.encryption_leftover_body), NoticeVariant.INFO)
            }
        }
    }
    item(key = PROTECTS) {
        TextBlock(
            stringResource(R.string.encryption_protects_title),
            stringResource(R.string.encryption_protects_body),
        )
    }
    item(key = NOT_PROTECTS) {
        TextBlock(
            stringResource(R.string.encryption_not_protects_title),
            stringResource(R.string.encryption_not_protects_body),
        )
    }
    item(key = RISK) {
        TextBlock(
            stringResource(R.string.encryption_risk_title),
            stringResource(
                R.string.legal_encryption_warning_body,
                stringResource(R.string.app_name),
            ),
        )
    }
    if (wasOn) {
        item(key = REACTIVATE) {
            Text(
                stringResource(R.string.encryption_reactivate_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics {
                heading()
            },
        )
        Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}

/** `Las copias salen cifradas`, sobre el contenedor del estado bueno del respaldo. */
@Composable
private fun OnBlock() {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.backupOkContainer, MaterialTheme.shapes.medium)
            .padding(AppTheme.spacing.s16),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_lock),
            contentDescription = null,
            tint = colors.onBackupOkContainer,
            modifier = Modifier.size(AppTheme.sizes.icon),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
        ) {
            StatusTag(stringResource(R.string.encryption_status_on), tone = StatusTone.PROFIT)
            Text(
                stringResource(R.string.encryption_on_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackupOkContainer,
            )
        }
    }
}

/** El código ya no abre en este teléfono: las copias están detenidas y nunca salen en claro. */
@Composable
private fun StoppedBlock() {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.backupErrorContainer, MaterialTheme.shapes.medium)
            .padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_error),
                contentDescription = null,
                tint = colors.onBackupErrorContainer,
                modifier = Modifier.size(AppTheme.sizes.icon),
            )
            Text(
                stringResource(R.string.encryption_key_unavailable_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackupErrorContainer,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            stringResource(R.string.encryption_key_unavailable_body),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackupErrorContainer,
        )
    }
}

/**
 * Las salidas con el código que no abre: escribirlo para volver a guardarlo, o crear otro, que no
 * necesita el anterior porque la base del teléfono está en claro.
 */
@Composable
private fun StoppedActions(onAction: (EncryptionAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        PrimaryActionButton(
            text = stringResource(R.string.backup_failure_encryption_key_unavailable_action),
            onClick = { onAction(EncryptionAction.TypeExistingCode) },
            variant = ActionVariant.TONAL,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryActionButton(
            text = stringResource(R.string.encryption_create_new_code_action),
            onClick = { onAction(EncryptionAction.CreateNewCode) },
            variant = ActionVariant.OUTLINED,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** `Escribir el código` con el cifrado detenido: el vigente, para guardarlo otra vez aquí. */
@Composable
private fun TypeExistingStep(
    step: ActivationStepUi.TypeExisting,
    onAction: (EncryptionAction) -> Unit,
    modifier: Modifier,
) {
    OnboardingStep(
        step = 1,
        totalSteps = 1,
        title = stringResource(R.string.recovery_type_code_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.recovery_check_action),
                onClick = { onAction(EncryptionAction.Reseal) },
                variant = ActionVariant.PRIMARY,
                state = if (step.working) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier.fillMaxWidth().keepsMessagesAbove(),
            )
        },
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.encryption_key_unavailable_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        RecoveryCodeField(
            groups = step.typed,
            onGroupsChange = { onAction(EncryptionAction.TypedExisting(it)) },
            label = stringResource(R.string.recovery_field_label),
            error =
                when (step.problem) {
                    null -> null
                    ResealProblem.MISMATCH -> stringResource(R.string.recovery_check_mismatch)
                    ResealProblem.SAVE -> stringResource(R.string.state_error_save)
                },
        )
        ExitLink(onAction)
    }
}

@Composable
private fun RiskStep(
    step: ActivationStepUi.Risk,
    onAction: (EncryptionAction) -> Unit,
    modifier: Modifier,
) {
    OnboardingStep(
        step = 1,
        totalSteps = STEPS,
        title = stringResource(R.string.encryption_step_before_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.action_continue),
                onClick = { onAction(EncryptionAction.ContinueRisk) },
                variant = ActionVariant.PRIMARY,
                enabled = step.accepted,
                modifier = Modifier.fillMaxWidth().keepsMessagesAbove(),
            )
        },
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.legal_encryption_warning_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(
                R.string.legal_encryption_warning_body,
                stringResource(R.string.app_name),
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        CheckboxRow(
            text = stringResource(R.string.legal_encryption_ack),
            checked = step.accepted,
            onCheckedChange = { onAction(EncryptionAction.Accept(it)) },
        )
        ExitLink(onAction)
    }
}

@Composable
private fun CodeStep(
    step: ActivationStepUi.ShowCode,
    onAction: (EncryptionAction) -> Unit,
    modifier: Modifier,
) {
    OnboardingStep(
        step = 2,
        totalSteps = STEPS,
        title = stringResource(R.string.recovery_code_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.action_continue),
                onClick = { onAction(EncryptionAction.ContinueCode) },
                variant = ActionVariant.PRIMARY,
                enabled = step.canContinue,
                modifier = Modifier.fillMaxWidth().keepsMessagesAbove(),
            )
        },
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.recovery_code_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        RecoveryCodeBlock(step.groups)
        Text(
            stringResource(R.string.recovery_code_no_copy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NoticeCard(stringResource(R.string.recovery_keep_apart_body), NoticeVariant.WARNING)
        if (step.kit == KitState.FAILED) {
            NoticeCard(stringResource(R.string.encryption_error_kit), NoticeVariant.ERROR)
        }
        PrimaryActionButton(
            text = stringResource(R.string.recovery_kit_save_action),
            onClick = { onAction(EncryptionAction.SaveKit) },
            variant = ActionVariant.TONAL,
            state = if (step.kit == KitState.SAVING) ActionState.WORKING else ActionState.IDLE,
            modifier = Modifier.fillMaxWidth().rejectObscuredTouches {
                onAction(EncryptionAction.ObscuredTouch)
            },
        )
        if (step.kit == KitState.SAVED) {
            StatusTag(
                stringResource(R.string.recovery_kit_saved),
                icon = R.drawable.ic_check,
                tone = StatusTone.PROFIT,
            )
        }
        InlineTextButton(
            text = stringResource(R.string.recovery_paper_action),
            onClick = { onAction(EncryptionAction.OnPaper) },
        )
        if (step.onPaper) {
            StatusTag(
                stringResource(R.string.recovery_paper_action),
                icon = R.drawable.ic_check,
                tone = StatusTone.PROFIT,
            )
        }
        ExitLink(onAction)
    }
}

@Composable
private fun RetypeStep(
    step: ActivationStepUi.Retype,
    onAction: (EncryptionAction) -> Unit,
    modifier: Modifier,
) {
    OnboardingStep(
        step = 3,
        totalSteps = STEPS,
        title = stringResource(R.string.recovery_type_code_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.recovery_check_action),
                onClick = { onAction(EncryptionAction.CheckCode) },
                variant = ActionVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth().keepsMessagesAbove(),
            )
        },
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.recovery_type_code_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        RecoveryCodeField(
            groups = step.typed,
            onGroupsChange = { onAction(EncryptionAction.Typed(it)) },
            label = stringResource(R.string.recovery_field_label),
            errorGroup = step.errorGroup,
            error = step.errorGroup?.let { stringResource(R.string.recovery_error_typo, it + 1) },
        )
        ExitLink(onAction)
    }
}

@Composable
private fun TestingStep(
    step: ActivationStepUi.Testing,
    onAction: (EncryptionAction) -> Unit,
    modifier: Modifier,
) {
    val failure = step.failure
    OnboardingStep(
        step = 4,
        totalSteps = STEPS,
        title = stringResource(R.string.encryption_step_test_title),
        action = {
            PrimaryActionButton(
                text = stringResource(
                    if (failure ==
                        null
                    ) {
                        R.string.encryption_step_test_title
                    } else {
                        R.string.action_retry
                    },
                ),
                onClick = { onAction(EncryptionAction.Retry) },
                variant = ActionVariant.PRIMARY,
                state = if (failure == null) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier.fillMaxWidth().keepsMessagesAbove(),
            )
        },
        modifier = modifier,
    ) {
        listOf(
            R.string.encryption_test_encrypting,
            R.string.encryption_test_opening,
            R.string.encryption_test_comparing,
        ).forEach { line ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (failure == null) {
                    CircularProgressIndicator(Modifier.size(AppTheme.sizes.iconInline))
                } else {
                    Icon(
                        painterResource(R.drawable.ic_error),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(AppTheme.sizes.iconInline),
                    )
                }
                Text(stringResource(line), style = MaterialTheme.typography.bodyLarge)
            }
        }
        when (failure) {
            null -> Unit

            ActivationFailure.ROUND_TRIP ->
                NoticeCard(stringResource(R.string.encryption_error_roundtrip), NoticeVariant.ERROR)

            ActivationFailure.SAVE ->
                NoticeCard(stringResource(R.string.encryption_error_save_code), NoticeVariant.ERROR)
        }
        if (failure != null) ExitLink(onAction)
    }
}

@Composable
private fun DoneStep(changed: Boolean, onAction: (EncryptionAction) -> Unit, modifier: Modifier) {
    OnboardingStep(
        step = STEPS,
        totalSteps = STEPS,
        title = stringResource(R.string.encryption_done_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.encryption_keep_plain_action),
                onClick = { onAction(EncryptionAction.Finish) },
                variant = ActionVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth().keepsMessagesAbove(),
            )
        },
        modifier = modifier,
    ) {
        OnBlock()
        if (changed) {
            // Un código nuevo no vuelve a cifrar las copias anteriores: siguen con el suyo.
            Text(
                stringResource(R.string.encryption_change_confirm_body),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                stringResource(R.string.encryption_old_copies_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.encryption_old_copies_body),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Salir de la activación: el código se descarta y el cifrado sigue apagado. */
@Composable
private fun ColumnScope.ExitLink(onAction: (EncryptionAction) -> Unit) {
    InlineTextButton(
        text = stringResource(R.string.action_cancel),
        onClick = { onAction(EncryptionAction.Exit) },
        modifier = Modifier.align(Alignment.Start),
    )
}

/** Los pasos de la activación. */
private const val STEPS = 4

private const val LOADING = "loading"
private const val ON = "on"
private const val STOPPED = "stopped"
private const val STOPPED_ACTIONS = "stopped_actions"
private const val CHANGE = "change"
private const val UNKNOWN = "unknown"
private const val OFF_TITLE = "off_title"
private const val LEFTOVER = "leftover"
private const val PROTECTS = "protects"
private const val NOT_PROTECTS = "not_protects"
private const val RISK = "risk"
private const val REACTIVATE = "reactivate"
