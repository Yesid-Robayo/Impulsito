package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un campo de texto libre, como un nombre, una nota o un símbolo: el mismo contorno, forma y colores
 * que [MoneyField], con el texto en `bodyLarge` porque no es una cifra.
 *
 * Guarda el texto tal como se escribió: no recorta ni bloquea un largo, para no perder lo que la
 * persona escribió o pegó. El largo y la forma los revisa quien lo usa, que pasa [error] al salir
 * del campo o al intentar guardar, nunca mientras se escribe. Para saber cuándo sale, recibe
 * [onLeave].
 *
 * @param supportingText una ayuda debajo, como el largo permitido. Con un error, se ve el error.
 * @param onLeave se llama cuando el campo pierde el foco después de haberlo tenido.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    singleLine: Boolean = true,
    onLeave: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (focused && !it.isFocused) onLeave()
                    focused = it.isFocused
                },
        textStyle = MaterialTheme.typography.bodyLarge,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        isError = error != null,
        supportingText =
            when {
                error != null -> {
                    { FieldError(error) }
                }

                supportingText != null -> {
                    {
                        Text(
                            supportingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> null
            },
        keyboardOptions =
            KeyboardOptions(
                capitalization = capitalization,
                keyboardType = KeyboardType.Text,
                imeAction = imeAction,
            ),
        singleLine = singleLine,
        shape = MaterialTheme.shapes.medium,
        colors = appFieldColors(),
    )
}

@ThemePreviews
@Composable
private fun TextFieldsPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            AppTextField(s.LONG_NAME, {}, stringResource(R.string.form_name))
            AppTextField("", {}, stringResource(R.string.form_name))
            AppTextField(
                s.SHORT_NAME,
                {},
                stringResource(R.string.form_name),
                error = stringResource(R.string.state_error_read),
            )
        }
    }
}
