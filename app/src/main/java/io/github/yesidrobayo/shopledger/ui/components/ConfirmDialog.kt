package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** El peso de una confirmación. */
enum class ConfirmVariant {
    /** Un cambio importante que se puede revertir con otro registro. */
    STANDARD,

    /** Algo que no se puede deshacer: el botón va en el color de error. */
    DESTRUCTIVE,
}

/**
 * Una confirmación, solo para algo irreversible o que cambia datos de forma importante: nunca para
 * registrar una venta ni para avisar de poco stock.
 *
 * El título es la pregunta con el verbo, el cuerpo dice la consecuencia real y el botón repite el
 * verbo: `Restaurar copia`, nunca `Aceptar` ni `Sí`.
 *
 * @param acknowledgement la frase de la confirmación reforzada, como «Entiendo que este teléfono tiene
 *   menos registros»: el botón se activa solo al marcarla. `null` en una confirmación común.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    variant: ConfirmVariant = ConfirmVariant.STANDARD,
    acknowledgement: String? = null,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        ConfirmDialogContent(
            title,
            body,
            confirmLabel,
            onConfirm,
            onDismiss,
            variant = variant,
            acknowledgement = acknowledgement,
        )
    }
}

/**
 * El cuerpo del diálogo, aparte para poder verlo sin abrir una ventana. El título y el cuerpo se
 * desplazan con letra grande; los botones quedan siempre a la vista.
 */
@Composable
fun ConfirmDialogContent(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ConfirmVariant = ConfirmVariant.STANDARD,
    acknowledgement: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = AppTheme.spacing
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
    ) {
        // En la ventana del diálogo el alto tiene tope, y el texto se desplaza sobre los botones. Sin
        // tope, como dentro de una lista, no hay nada que repartir.
        BoxWithConstraints {
            val bounded = constraints.hasBoundedHeight
            Column(Modifier.padding(spacing.s24)) {
                Column(
                    modifier =
                        if (bounded) {
                            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    verticalArrangement = Arrangement.spacedBy(spacing.s12),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (acknowledgement != null) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = spacing.s12)
                                .heightIn(min = AppTheme.sizes.touchTarget)
                                .toggleable(value = acknowledged, role = Role.Checkbox) {
                                    acknowledged =
                                        it
                                },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = acknowledged,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = scheme.error,
                                checkmarkColor = scheme.onError,
                            ),
                        )
                        Text(
                            text = acknowledgement,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = spacing.s12),
                        )
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.s16),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s8, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(spacing.s8),
                ) {
                    InlineTextButton(stringResource(R.string.action_cancel), onDismiss)
                    val enabled = acknowledgement == null || acknowledged
                    when (variant) {
                        ConfirmVariant.STANDARD -> InlineTextButton(confirmLabel, {
                            if (enabled) onConfirm()
                        })

                        ConfirmVariant.DESTRUCTIVE ->
                            Button(
                                onClick = onConfirm,
                                enabled = enabled,
                                modifier = Modifier.heightIn(min = AppTheme.sizes.touchTarget),
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.buttonColors(scheme.error, scheme.onError),
                                elevation = null,
                            ) {
                                Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
                            }
                    }
                }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun ConfirmDialogPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
        ) {
            ConfirmDialogContent(s.DIALOG_TITLE, s.DIALOG_BODY, s.DIALOG_ACTION, {}, {})
            ConfirmDialogContent(
                s.DIALOG_TITLE,
                s.DIALOG_BODY,
                s.DIALOG_ACTION,
                {},
                {},
                variant = ConfirmVariant.DESTRUCTIVE,
                acknowledgement = s.DIALOG_ACK,
            )
        }
    }
}
