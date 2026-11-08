package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Lo que hace una fila de ajustes, que decide lo que lleva a la derecha. */
sealed interface SettingsRowAction {
    /** Abre otra pantalla: toda la fila se toca y lleva una flecha. */
    data class Navigation(val onClick: () -> Unit) : SettingsRowAction

    /** Cambia un valor: toda la fila cambia el interruptor. */
    data class Toggle(val checked: Boolean, val onCheckedChange: (Boolean) -> Unit) :
        SettingsRowAction

    /** Solo muestra un valor, sin acción: un monto con `MoneyText` o un texto. */
    data class Value(val content: @Composable () -> Unit) : SettingsRowAction
}

/**
 * Una fila de ajustes: título y, si hace falta, una línea de valor debajo. Toda la fila es un solo
 * foco y un solo objetivo táctil: con un interruptor, TalkBack lee el título, «interruptor» y su
 * estado.
 *
 * @param warning lo que falta resolver en este ajuste, como `Sin confirmar`: va en una etiqueta de
 *   aviso con su icono, debajo del valor, para que no dependa del color.
 */
@Composable
fun SettingsRow(
    title: String,
    action: SettingsRowAction,
    modifier: Modifier = Modifier,
    summary: String? = null,
    warning: String? = null,
) {
    val spacing = AppTheme.spacing
    val interaction =
        when (action) {
            is SettingsRowAction.Navigation -> Modifier.clickable(
                role = Role.Button,
                onClick = action.onClick,
            )

            is SettingsRowAction.Toggle ->
                Modifier.toggleable(
                    value = action.checked,
                    role = Role.Switch,
                    onValueChange = action.onCheckedChange,
                )

            is SettingsRowAction.Value -> Modifier.semantics(mergeDescendants = true) {}
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .then(interaction)
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s16),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (warning != null) {
                StatusTag(warning, icon = R.drawable.ic_warning, tone = StatusTone.WARNING)
            }
        }
        when (action) {
            is SettingsRowAction.Navigation ->
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppTheme.sizes.icon),
                )

            is SettingsRowAction.Toggle -> AppSwitch(
                checked = action.checked,
                onCheckedChange = null,
            )

            is SettingsRowAction.Value -> action.content()
        }
    }
}

/**
 * El interruptor de la app, sin icono dentro: encendido, la pista en el color de acción; apagado, la
 * pista más oscura del papel con el pulgar en contorno. Se usa solo dentro de [SettingsRow], que le
 * da el área táctil y la lectura.
 *
 * @param onCheckedChange `null` cuando la fila entera lo cambia.
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors =
            SwitchDefaults.colors(
                checkedTrackColor = scheme.primary,
                checkedThumbColor = scheme.onPrimary,
                checkedBorderColor = scheme.primary,
                uncheckedTrackColor = scheme.surfaceContainerHighest,
                uncheckedThumbColor = scheme.outline,
                uncheckedBorderColor = scheme.outline,
            ),
    )
}

@ThemePreviews
@Composable
private fun SettingsRowPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column {
            SettingsRow(s.SETTING_TITLE, SettingsRowAction.Toggle(true) {})
            SettingsRow(
                s.SETTING_TITLE,
                SettingsRowAction.Toggle(false) {
                },
                summary = s.NOTICE_TEXT,
            )
            SettingsRow(s.TOOL_NAME, SettingsRowAction.Navigation {}, summary = s.TOOL_DETAIL)
            SettingsRow(
                s.LONG_NAME,
                SettingsRowAction.Navigation {},
                summary = s.TOOL_DETAIL,
                warning = stringResource(R.string.region_currency_unconfirmed),
            )
            SettingsRow(
                s.SHORT_NAME,
                SettingsRowAction.Value {
                    MoneyText(s.LONG_AMOUNT, MoneySize.ROW)
                },
            )
        }
    }
}
