package io.github.yesidrobayo.shopledger.ui.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Las preguntas de la aceptación, encima de toda la navegación: la hoja `Política y términos`, el
 * diálogo `Cambió el uso de los datos` y los términos completos.
 */
@Composable
fun PolicyPromptHost(prompt: PolicyPrompt) {
    val state by prompt.state.collectAsStateWithLifecycle()
    PolicyQuestions(
        state = state,
        onAccept = prompt::accept,
        onLater = prompt::later,
        onOpenTerms = prompt::openTerms,
        onCloseTerms = prompt::closeTerms,
    )
}

/** Las preguntas de [state], puras: avisan cada acción. */
@Composable
fun PolicyQuestions(
    state: PolicyPromptState,
    onAccept: () -> Unit,
    onLater: () -> Unit,
    onOpenTerms: () -> Unit,
    onCloseTerms: () -> Unit,
) {
    when {
        state.terms -> TermsSheet(onCloseTerms)

        state.question == PolicyQuestion.ACCEPT_TO_CONNECT ->
            AcceptSheet(state, onAccept, onLater, onOpenTerms)

        state.question == PolicyQuestion.CHANGED -> ChangedDialog(onAccept, onLater, onOpenTerms)

        else -> Unit
    }
}

/**
 * La hoja `Política y términos`: para guardar copias en la nube hay que aceptarlas, y no aceptar
 * nunca bloquea los datos ni exportarlos. Cerrarla es no aceptar.
 */
@Composable
private fun AcceptSheet(
    state: PolicyPromptState,
    onAccept: () -> Unit,
    onLater: () -> Unit,
    onOpenTerms: () -> Unit,
) {
    AppBottomSheet(
        onDismissRequest = onLater,
        title = stringResource(R.string.legal_accept_title),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.legal_accept_action),
                onClick = onAccept,
                variant = ActionVariant.PRIMARY,
                state = if (state.saving) ActionState.WORKING else ActionState.IDLE,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            Text(
                stringResource(R.string.legal_accept_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.legal_accept_not_blocking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InlineTextButton(stringResource(R.string.legal_terms_title), onOpenTerms)
            if (state.notSaved) {
                NoticeCard(stringResource(R.string.state_error_save), NoticeVariant.ERROR)
            }
        }
    }
}

/**
 * `Cambió el uso de los datos`, con el resumen, `Aceptar el cambio`, `Leer el texto completo` y
 * `Ahora no`. Sin aceptar, las copias en la nube quedan en pausa y nada más cambia.
 */
@Composable
private fun ChangedDialog(onAccept: () -> Unit, onLater: () -> Unit, onOpenTerms: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.consent_change_title),
        body = stringResource(
            R.string.consent_change_body,
            stringResource(R.string.consent_change_summary),
        ),
        confirmLabel = stringResource(R.string.consent_accept_action),
        onConfirm = onAccept,
        onDismiss = onLater,
        dismissLabel = stringResource(R.string.consent_later_action),
        secondaryLabel = stringResource(R.string.consent_read_action),
        onSecondary = onOpenTerms,
    )
}

/** Los términos de uso completos, desde los recursos: se leen sin internet. */
@Composable
private fun TermsSheet(onClose: () -> Unit) {
    val app = stringResource(R.string.app_name)
    AppBottomSheet(onDismissRequest = onClose, title = stringResource(R.string.legal_terms_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
            for (paragraph in listOf(
                stringResource(R.string.legal_terms_what, app),
                stringResource(R.string.legal_terms_as_is, app),
                stringResource(R.string.legal_terms_responsibility),
                stringResource(R.string.legal_terms_no_substitute, app),
                stringResource(R.string.legal_terms_third_party),
                stringResource(R.string.legal_terms_recovery),
                stringResource(R.string.legal_terms_changes, app),
            )) {
                Text(paragraph, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Una pantalla que conecta un destino, detrás de la aceptación: sin la versión vigente aceptada no
 * se muestra, y la hoja pregunta. Si la persona no acepta, [onDeclined] vuelve atrás sin conectar.
 */
@Composable
fun PolicyGated(onDeclined: () -> Unit, content: @Composable () -> Unit) {
    val prompt = LocalPolicyPrompt.current
    val state by prompt.state.collectAsStateWithLifecycle()
    DisposableEffect(prompt) {
        prompt.enterGate()
        onDispose { prompt.leaveGate() }
    }
    LaunchedEffect(state.gate) {
        if (state.gate == PolicyGate.DECLINED) onDeclined()
    }
    when (state.gate) {
        PolicyGate.ALLOWED -> content()

        // Mientras pregunta, la pantalla queda vacía: nada de lo que conecta está a la mano.
        PolicyGate.CHECKING, PolicyGate.WAITING, PolicyGate.DECLINED, null ->
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier)
            }
    }
}

@ThemePreviews
@Composable
private fun AcceptSheetPreview() {
    PreviewTheme {
        Column {
            PolicyQuestions(
                PolicyPromptState(question = PolicyQuestion.ACCEPT_TO_CONNECT, notSaved = true),
                {},
                {},
                {},
                {},
            )
        }
    }
}
