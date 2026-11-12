package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un campo que no se escribe: muestra lo elegido y, al tocarlo, abre la hoja donde se elige, como la
 * categoría o la unidad de un producto. Tiene la forma, el contorno y los colores de [AppTextField],
 * para que el formulario se lea como una sola columna de campos, con una flecha que dice que abre
 * algo.
 *
 * Con [lockedReason] no se puede cambiar: se ve apagado y dice por qué debajo, porque un campo
 * bloqueado sin razón parece roto. Para TalkBack es un solo botón con su etiqueta y su valor.
 */
@Composable
fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lockedReason: String? = null,
) {
    val locked = lockedReason != null
    val scheme = MaterialTheme.colorScheme
    // Un solo foco para TalkBack: lee la etiqueta y lo elegido, lo anuncia como botón y lo abre con
    // el doble toque. El campo y la capa que recibe el toque no aparecen aparte para TalkBack: serían
    // dos focos más, uno de ellos sin nombre. Con el campo bloqueado, dice por qué.
    val spoken = stringResource(R.string.a11y_label_value, label, value)
    Box(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                if (locked) {
                    disabled()
                    lockedReason?.let { stateDescription = it }
                } else {
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                }
            },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth().semantics { hideFromAccessibility() },
            readOnly = true,
            enabled = !locked,
            textStyle = MaterialTheme.typography.bodyLarge,
            label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
            trailingIcon =
                if (locked) {
                    null
                } else {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            modifier = Modifier.size(AppTheme.sizes.icon),
                        )
                    }
                },
            supportingText =
                lockedReason?.let {
                    {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = appFieldColors().copy(
                disabledContainerColor = OutlinedTextFieldDefaults.colors().disabledContainerColor,
                disabledTextColor = scheme.onSurfaceVariant,
                disabledLabelColor = scheme.onSurfaceVariant,
                disabledIndicatorColor = scheme.outlineVariant,
                disabledSupportingTextColor = scheme.onSurfaceVariant,
            ),
        )
        if (!locked) {
            // El campo no recibe el toque: lo recibe esta capa, que lo abre como un botón.
            Box(
                Modifier
                    .matchParentSize()
                    .padding(top = AppTheme.spacing.s8)
                    .clearAndSetSemantics {}
                    .clickable(onClick = onClick),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PickerFieldPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            PickerField(stringResource(R.string.form_category), s.CATEGORY_A, {})
            PickerField(stringResource(R.string.form_category), s.LONG_NAME, {})
            PickerField(
                stringResource(R.string.term_unit),
                s.UNIT.symbol,
                {},
                lockedReason = stringResource(R.string.unit_base_locked),
            )
        }
    }
}
